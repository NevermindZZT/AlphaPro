package com.letter.alphapro.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface SonyBleConnectionState {
    data object Disconnected : SonyBleConnectionState
    data object Pairing : SonyBleConnectionState
    data object Connecting : SonyBleConnectionState
    data object DiscoveringServices : SonyBleConnectionState
    data object Handshaking : SonyBleConnectionState
    data class Ready(val deviceName: String, val address: String) : SonyBleConnectionState
    data class Error(val message: String) : SonyBleConnectionState
}

data class SonyWifiCredentials(
    val ssid: String,
    val password: String,
    val bssid: String? = null
)

@SuppressLint("MissingPermission")
class SonyBleGattManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _connectionState = MutableStateFlow<SonyBleConnectionState>(SonyBleConnectionState.Disconnected)
    val connectionState: StateFlow<SonyBleConnectionState> = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    var targetDeviceAddress: String? = null
        private set
    private var locationConfig = LocationDataConfig(shouldSendTimeZoneAndDst = true)
    private var lastKnownLocation: Pair<Double, Double>? = null

    private val gattMutex = Mutex()
    private var pendingWriteDeferred: CompletableDeferred<Boolean>? = null
    private var pendingReadDeferred: CompletableDeferred<ByteArray?>? = null
    private var pendingDescriptorDeferred: CompletableDeferred<Boolean>? = null

    private var bondReceiver: BroadcastReceiver? = null
    private var autoReconnectJob: Job? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = SonyBleConnectionState.DiscoveringServices
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        scope.launch {
                            delay(500) // Settle link parameters before discovery
                            gatt.discoverServices()
                        }
                    } else {
                        _connectionState.value = SonyBleConnectionState.Error("连接失败: status $status")
                        disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = SonyBleConnectionState.Disconnected
                    cleanupGatt()
                    scheduleAutoReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch {
                    delay(300)
                    performSonyHandshake(gatt)
                }
            } else {
                _connectionState.value = SonyBleConnectionState.Error("服务发现失败: status $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val deferred = pendingWriteDeferred
            pendingWriteDeferred = null
            deferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val bytes = if (status == BluetoothGatt.GATT_SUCCESS) value else null
            val deferred = pendingReadDeferred
            pendingReadDeferred = null
            deferred?.complete(bytes)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val bytes = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
            val deferred = pendingReadDeferred
            pendingReadDeferred = null
            deferred?.complete(bytes)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val deferred = pendingDescriptorDeferred
            pendingDescriptorDeferred = null
            deferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {}

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {}
    }

    fun connect(address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            _connectionState.value = SonyBleConnectionState.Error("无效蓝牙地址: $address")
            return
        }

        // Idempotency check: If already connected or connecting to this device, do NOT tear it down!
        if (targetDeviceAddress.equals(address, ignoreCase = true) &&
            bluetoothGatt != null &&
            (_connectionState.value is SonyBleConnectionState.Ready ||
             _connectionState.value is SonyBleConnectionState.Connecting ||
             _connectionState.value is SonyBleConnectionState.DiscoveringServices ||
             _connectionState.value is SonyBleConnectionState.Handshaking)) {
            return
        }

        targetDeviceAddress = address
        autoReconnectJob?.cancel()

        // Check bond state: Sony cameras require OS-level Bluetooth Bonding
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _connectionState.value = SonyBleConnectionState.Pairing
            registerBondReceiver(address) {
                connectGattInternal(device)
            }
            val bondInitiated = device.createBond()
            if (!bondInitiated && device.bondState == BluetoothDevice.BOND_BONDED) {
                connectGattInternal(device)
            }
            return
        }

        connectGattInternal(device)
    }

    private fun connectGattInternal(device: BluetoothDevice) {
        unregisterBondReceiver()
        _connectionState.value = SonyBleConnectionState.Connecting
        cleanupGatt()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleAutoReconnect() {
        val address = targetDeviceAddress ?: return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            delay(2500)
            if (targetDeviceAddress == address && _connectionState.value is SonyBleConnectionState.Disconnected) {
                connect(address)
            }
        }
    }

    private fun registerBondReceiver(address: String, onBonded: () -> Unit) {
        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (dev?.address.equals(address, ignoreCase = true)) {
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> onBonded()
                            BluetoothDevice.BOND_NONE -> {
                                _connectionState.value = SonyBleConnectionState.Error("蓝牙配对失败或已被取消")
                            }
                        }
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            bondReceiver = null
        }
    }

    fun disconnect() {
        targetDeviceAddress = null
        autoReconnectJob?.cancel()
        unregisterBondReceiver()
        bluetoothGatt?.disconnect()
        cleanupGatt()
        _connectionState.value = SonyBleConnectionState.Disconnected
    }

    private fun cleanupGatt() {
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == uuid }
    }

    private suspend fun performSonyHandshake(gatt: BluetoothGatt) {
        _connectionState.value = SonyBleConnectionState.Handshaking

        // 1. Subscribe to DD01 (CHARACTERISTIC_LOCATION_ENABLED_IN_CAMERA)
        val dd01 = findCharacteristic(gatt, SonyBleConstants.CHAR_CAMERA_LOCATION_ENABLED_UUID)
        if (dd01 != null) {
            subscribeToNotifications(dd01)
        }

        // 2. Read DD21 (Config read) to determine if timezone/DST bytes are required (95 vs 91 bytes)
        val dd21 = findCharacteristic(gatt, SonyBleConstants.CHAR_LOCATION_READ_UUID)
        if (dd21 != null) {
            val configBytes = readCharacteristic(dd21)
            if (configBytes != null) {
                val hasTimeZone = LocationDataConfig.hasTimeZoneDstFlag(configBytes)
                locationConfig = LocationDataConfig(shouldSendTimeZoneAndDst = hasTimeZone)
            }
        }

        // 3. Write 0x01 to DD30 (Unlock command 1) if present
        val dd30 = findCharacteristic(gatt, SonyBleConstants.CHAR_GPS_UNLOCK_COMMAND_30_UUID)
        if (dd30 != null) {
            writeCharacteristic(dd30, SonyBleConstants.CMD_ACTIVATE_GPS)
        }

        // 4. Write 0x01 to DD31 (Lock command 2) if present
        val dd31 = findCharacteristic(gatt, SonyBleConstants.CHAR_GPS_UNLOCK_COMMAND_31_UUID)
        if (dd31 != null) {
            writeCharacteristic(dd31, SonyBleConstants.CMD_ACTIVATE_GPS)
        }

        // 5. Millisecond time sync on CC13
        val cc13 = findCharacteristic(gatt, SonyBleConstants.CHAR_TIME_SYNC_UUID)
        if (cc13 != null) {
            val timeSyncPacket = SonyTimeSyncPacketBuilder.build()
            writeCharacteristic(cc13, timeSyncPacket)
        }

        // 6. Subscribe to FF02 (Remote status notifications) if available
        val ff02 = findCharacteristic(gatt, SonyBleConstants.CHAR_REMOTE_STATUS_UUID)
        if (ff02 != null) {
            subscribeToNotifications(ff02)
        }

        // 7. Probe remote control readiness with PROBE_COMMAND (0x01, 0x06) on FF01
        val ff01 = findCharacteristic(gatt, SonyBleConstants.CHAR_REMOTE_COMMAND_UUID)
        if (ff01 != null) {
            writeCharacteristic(ff01, SonyBleConstants.CMD_HALF_SHUTTER_UP)
        }

        // Lower connection priority to balanced to conserve battery
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

        val deviceName = gatt.device.name ?: "Sony Camera"
        _connectionState.value = SonyBleConnectionState.Ready(deviceName, gatt.device.address)

        // 8. Immediately send location if already cached!
        lastKnownLocation?.let { (lat, lng) ->
            sendLocation(lat, lng)
        }
    }

    suspend fun sendLocation(latitude: Double, longitude: Double): Boolean {
        lastKnownLocation = Pair(latitude, longitude)
        if (_connectionState.value !is SonyBleConnectionState.Ready) return false
        val gatt = bluetoothGatt ?: return false
        val dd11 = findCharacteristic(gatt, SonyBleConstants.CHAR_LOCATION_DATA_UUID) ?: return false

        val packet = SonyLocationPacketBuilder.build(
            latitude = latitude,
            longitude = longitude,
            config = locationConfig
        )
        return writeCharacteristic(dd11, packet)
    }

    suspend fun sendTimeSync(): Boolean {
        if (_connectionState.value !is SonyBleConnectionState.Ready) return false
        val gatt = bluetoothGatt ?: return false
        val cc13 = findCharacteristic(gatt, SonyBleConstants.CHAR_TIME_SYNC_UUID) ?: return false

        val packet = SonyTimeSyncPacketBuilder.build()
        return writeCharacteristic(cc13, packet)
    }

    suspend fun readWifiCredentials(): SonyWifiCredentials? = gattMutex.withLock {
        val gatt = bluetoothGatt ?: return@withLock null
        val wifiPower = findCharacteristic(gatt, SonyBleConstants.CHAR_WIFI_POWER_UUID) ?: return@withLock null
        val wifiSsid = findCharacteristic(gatt, SonyBleConstants.CHAR_WIFI_SSID_UUID) ?: return@withLock null
        val wifiPwd = findCharacteristic(gatt, SonyBleConstants.CHAR_WIFI_PASSWORD_UUID) ?: return@withLock null

        // 1. Send Wi-Fi ON command to camera (write 0x01 to CC08)
        writeCharacteristicInternal(wifiPower, SonyBleConstants.CMD_WIFI_POWER_ON)

        // 2. Poll camera status CC09 until Wi-Fi state == 2 (Wi-Fi ON)
        val statusChar = findCharacteristic(gatt, SonyBleConstants.CHAR_CAMERA_STATUS_UUID)
        if (statusChar != null) {
            var waitCount = 0
            while (waitCount++ < 25) {
                val statusBytes = readCharacteristicInternal(statusChar)
                if (statusBytes != null) {
                    val status = SonyWifiDataParser.parseWifiStatus(statusBytes)
                    if (status?.first == 2) break
                    if (status?.first == 0 && status.second in 2..5) {
                        return@withLock null
                    }
                }
                delay(500)
            }
        } else {
            delay(1500)
        }

        // 3. Poll SSID characteristic (CC06) and parse prefixed ASCII
        var ssid: String? = null
        var pollCount = 0
        while (ssid == null && pollCount++ < 25) {
            val ssidBytes = readCharacteristicInternal(wifiSsid)
            if (ssidBytes != null) {
                ssid = SonyWifiDataParser.parsePrefixedAscii(ssidBytes)
            }
            if (ssid == null) delay(500)
        }
        if (ssid == null) return@withLock null

        // 4. Poll Password characteristic (CC07) and parse prefixed ASCII
        var password: String? = null
        pollCount = 0
        while (password == null && pollCount++ < 25) {
            val pwdBytes = readCharacteristicInternal(wifiPwd)
            if (pwdBytes != null) {
                password = SonyWifiDataParser.parsePrefixedAscii(pwdBytes)
            }
            if (password == null) delay(500)
        }
        if (password == null) return@withLock null

        // 5. Read BSSID (CC0C) if present
        val bssidChar = findCharacteristic(gatt, SonyBleConstants.CHAR_WIFI_BSSID_UUID)
        val bssidBytes = bssidChar?.let { readCharacteristicInternal(it) }
        val bssid = bssidBytes?.let { SonyWifiDataParser.parseAscii(it) }

        SonyWifiCredentials(ssid = ssid.trim(), password = password.trim(), bssid = bssid?.trim())
    }

    suspend fun sendRemoteCommand(command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = findCharacteristic(gatt, SonyBleConstants.CHAR_REMOTE_COMMAND_UUID) ?: return false
        return writeCharacteristic(char, command)
    }

    private suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean = gattMutex.withLock {
        writeCharacteristicInternal(characteristic, data)
    }

    private suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): ByteArray? = gattMutex.withLock {
        readCharacteristicInternal(characteristic)
    }

    private suspend fun subscribeToNotifications(
        characteristic: BluetoothGattCharacteristic
    ): Boolean = gattMutex.withLock {
        val gatt = bluetoothGatt ?: return@withLock false
        val descriptor = characteristic.getDescriptor(SonyBleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return@withLock false

        gatt.setCharacteristicNotification(characteristic, true)
        writeDescriptorInternal(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private suspend fun writeDescriptorInternal(
        desc: BluetoothGattDescriptor,
        data: ByteArray
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pendingDescriptorDeferred = deferred

        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeDescriptor(desc, data)
            res == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = data
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
        if (!initiated) {
            pendingDescriptorDeferred = null
            return false
        }

        return withTimeoutOrNull(2500) {
            deferred.await()
        } ?: false
    }

    private suspend fun writeCharacteristicInternal(
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pendingWriteDeferred = deferred

        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeCharacteristic(char, data, writeType)
            res == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
        if (!initiated) {
            pendingWriteDeferred = null
            return false
        }

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            pendingWriteDeferred = null
            return true
        }

        return withTimeoutOrNull(2500) {
            deferred.await()
        } ?: false
    }

    private suspend fun readCharacteristicInternal(
        char: BluetoothGattCharacteristic
    ): ByteArray? {
        val gatt = bluetoothGatt ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        pendingReadDeferred = deferred

        val initiated = gatt.readCharacteristic(char)
        if (!initiated) {
            pendingReadDeferred = null
            return null
        }

        return withTimeoutOrNull(2500) {
            deferred.await()
        }
    }
}
