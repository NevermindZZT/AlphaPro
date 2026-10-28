package com.letter.alphapro.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
) {
    override fun toString(): String = "SonyWifiCredentials(redacted)"
}

@SuppressLint("MissingPermission")
class SonyBleGattManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val messageContext: () -> Context = { context }
) {
    private val _connectionState = MutableStateFlow<SonyBleConnectionState>(SonyBleConnectionState.Disconnected)
    val connectionState: StateFlow<SonyBleConnectionState> = _connectionState.asStateFlow()
    private val _remoteFeedback = MutableStateFlow(SonyRemoteFeedback())
    val remoteFeedback: StateFlow<SonyRemoteFeedback> = _remoteFeedback.asStateFlow()

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    var targetDeviceAddress: String? = null
        private set
    private var locationConfig = LocationDataConfig(shouldSendTimeZoneAndDst = true)
    private var lastKnownLocation: Pair<Double, Double>? = null

    private val operationLane = GattOperationLane()
    private val wifiSetupMutex = Mutex()
    @Volatile private var negotiatedMtu = 23
    private var bondReceiver: BroadcastReceiver? = null
    private var autoReconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private val diagnosticLock = Any()
    private val diagnosticLines = mutableListOf<String>()
    @Volatile private var diagnosticActive = false
    private var diagnosticStarted = 0L
    private val _wifiDiagnostic = MutableStateFlow("")
    val wifiDiagnostic: StateFlow<String> = _wifiDiagnostic.asStateFlow()

    private fun traceWifi(message: String) {
        synchronized(diagnosticLock) {
            if (!diagnosticActive) return
            val line = "+" + (SystemClock.elapsedRealtime() - diagnosticStarted) + "ms " + message
            diagnosticLines.add(line)
            if (diagnosticLines.size > 100) diagnosticLines.removeAt(0)
            _wifiDiagnostic.value = diagnosticLines.joinToString("\n")
            // Only stage/UUID/status/counts. Never log attribute payload, SSID, password or address.
            android.util.Log.d("AlphaProWifi", line)
        }
    }

    private fun attributeKey(char: BluetoothGattCharacteristic) = "${char.uuid}:${char.instanceId}"
    private fun descriptorKey(desc: BluetoothGattDescriptor) = attributeKey(desc.characteristic) + ":" + desc.uuid

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        operationLane.activate(gatt)
                        negotiatedMtu = 23
                        _connectionState.value = SonyBleConnectionState.DiscoveringServices
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        scope.launch {
                            delay(500)
                            if (gatt === bluetoothGatt && !gatt.discoverServices()) {
                                retireGatt(gatt)
                            }
                        }
                    } else retireGatt(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> retireGatt(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { retireGatt(gatt); return }
            handshakeJob?.cancel()
            handshakeJob = scope.launch {
                delay(300)
                if (gatt === bluetoothGatt) {
                    try { performSonyHandshake(gatt) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { retireGatt(gatt) }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            operationLane.complete(gatt, GattOperationKind.MTU, "mtu", status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            operationLane.complete(gatt, GattOperationKind.WRITE, attributeKey(characteristic), status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            operationLane.complete(gatt, GattOperationKind.READ, attributeKey(characteristic), status, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // API 33+ dispatches the value-bearing overload. Do not deliver a duplicate legacy event.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                operationLane.complete(gatt, GattOperationKind.READ, attributeKey(characteristic), status, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            acceptRemoteFeedback(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                acceptRemoteFeedback(gatt, characteristic, characteristic.value ?: byteArrayOf())
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            operationLane.complete(gatt, GattOperationKind.DESCRIPTOR, descriptorKey(descriptor), status)
        }
    }

    private fun acceptRemoteFeedback(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (gatt !== bluetoothGatt || characteristic.uuid != SonyBleConstants.CHAR_REMOTE_STATUS_UUID) return
        SonyRemoteFeedbackParser.update(_remoteFeedback.value, value)?.let { _remoteFeedback.value = it }
    }

    /** Opaque identity for a remote gesture; never continue it on a replacement connection. */
    fun remoteSessionToken(): Any? = bluetoothGatt?.takeIf {
        _connectionState.value is SonyBleConnectionState.Ready && operationLane.isActive(it)
    }

    /** Only retire the exact session that failed; an old callback cannot close its replacement. */
    private fun retireGatt(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) return
        traceWifi("BLE session closed; waiting for reconnect")
        cleanupGatt()
        _connectionState.value = SonyBleConnectionState.Disconnected
        scheduleAutoReconnect()
    }

    fun connect(address: String) {
        scope.launch { connectInternal(address) }
    }

    private fun connectInternal(address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            _connectionState.value = SonyBleConnectionState.Error(messageContext().getString(R.string.ble_invalid_address, address))
            return
        }

        if (targetDeviceAddress.equals(address, ignoreCase = true) &&
            _connectionState.value is SonyBleConnectionState.Pairing) return

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
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
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
                                _connectionState.value = SonyBleConnectionState.Error(messageContext().getString(R.string.ble_pairing_failed))
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
        scope.launch { disconnectInternal() }
    }

    private fun disconnectInternal() {
        targetDeviceAddress = null
        autoReconnectJob?.cancel()
        unregisterBondReceiver()
        bluetoothGatt?.disconnect()
        cleanupGatt()
        _connectionState.value = SonyBleConnectionState.Disconnected
    }

    private fun cleanupGatt() {
        val old = bluetoothGatt
        bluetoothGatt = null
        _remoteFeedback.value = SonyRemoteFeedback()
        handshakeJob?.cancel()
        old?.let {
            operationLane.close(it)
            try { it.close() } catch (_: Exception) {}
        }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == uuid }
    }

    private suspend fun performSonyHandshake(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) return
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

        if (gatt !== bluetoothGatt || !operationLane.isActive(gatt)) return
        val deviceName = gatt.device.name ?: messageContext().getString(R.string.ble_camera_name)
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

    suspend fun readWifiCredentials(): SonyWifiCredentials? {
        if (!wifiSetupMutex.tryLock()) return null
        synchronized(diagnosticLock) {
            diagnosticStarted = SystemClock.elapsedRealtime()
            diagnosticLines.clear()
            diagnosticActive = true
            _wifiDiagnostic.value = ""
        }
        try {
            val gatt = bluetoothGatt ?: throw SonyWifiSetupException("Bluetooth is disconnected")
            if (_connectionState.value !is SonyBleConnectionState.Ready) {
                throw SonyWifiSetupException("Bluetooth handshake is not complete")
            }
            traceWifi("Starting Wi-Fi credential read; BLE MTU=" + negotiatedMtu)
            val port = object : SonyWifiBootstrapPort {
                override fun isReady() = gatt === bluetoothGatt && operationLane.isActive(gatt) &&
                    _connectionState.value is SonyBleConnectionState.Ready
                override fun hasCharacteristic(uuid: UUID) = findCharacteristic(gatt, uuid) != null
                override suspend fun prepareMtu() {
                    if (negotiatedMtu >= 67) return
                    val result = executeGatt(gatt, GattOperationKind.MTU, "mtu") { gatt.requestMtu(517) }
                    traceWifi("MTU result status=" + result.status + " mtu=" + negotiatedMtu)
                }
                override suspend fun subscribe(uuid: UUID): Boolean {
                    val char = findCharacteristic(gatt, uuid) ?: return false
                    return subscribeToNotifications(char)
                }
                override suspend fun read(uuid: UUID): ByteArray? {
                    val char = findCharacteristic(gatt, uuid) ?: throw SonyWifiSetupException("Characteristic is no longer available")
                    val result = executeGatt(gatt, GattOperationKind.READ, attributeKey(char)) { gatt.readCharacteristic(char) }
                    if (result.status == 5 || result.status == 15) {
                        throw SonyWifiSetupException(uuid.toString().take(8) + ": authentication/encryption failed status=" + result.status)
                    }
                    return if (result.status == BluetoothGatt.GATT_SUCCESS) result.value else null
                }
                override suspend fun write(uuid: UUID, value: ByteArray): Boolean {
                    val char = findCharacteristic(gatt, uuid) ?: return false
                    return writeCharacteristic(char, value)
                }
            }
            return SonyWifiBootstrap(port, ::traceWifi).prepare()
        } catch (cancelled: CancellationException) {
            traceWifi("Wi-Fi setup cancelled")
            throw cancelled
        } catch (error: Exception) {
            val detail = when (error) {
                is SonyWifiSetupException, is GattOperationException -> error.message ?: "BLE operation failed"
                is SecurityException -> "Bluetooth permission is missing"
                else -> "Internal error " + error.javaClass.simpleName
            }
            traceWifi("Failure: " + detail)
            return null
        } finally {
            diagnosticActive = false
            wifiSetupMutex.unlock()
        }
    }

    suspend fun sendRemoteCommand(command: ByteArray, expectedSession: Any? = null): Boolean {
        if (_connectionState.value !is SonyBleConnectionState.Ready) return false
        val gatt = bluetoothGatt ?: return false
        if (expectedSession != null && gatt !== expectedSession) return false
        val char = findCharacteristic(gatt, SonyBleConstants.CHAR_REMOTE_COMMAND_UUID) ?: return false
        return writeCharacteristic(char, command, preferNoResponse = true, settleAfterInitiation = true)
    }

    private suspend fun executeGatt(
        gatt: BluetoothGatt,
        kind: GattOperationKind,
        attribute: String,
        settleAfterInitiation: Boolean = false,
        initiate: () -> Boolean
    ): GattOperationResult {
        val started = SystemClock.elapsedRealtime()
        val diagnostic = !attribute.startsWith(SonyBleConstants.CHAR_LOCATION_DATA_UUID.toString())
        if (diagnostic) traceWifi("GATT " + kind + " " + attribute + " start mtu=" + negotiatedMtu)
        try {
            val result = operationLane.execute(gatt, kind, attribute, settleAfterInitiation = settleAfterInitiation, initiate = initiate)
            if (diagnostic) traceWifi("GATT " + kind + " " + attribute + " status=" + result.status +
                " bytes=" + (result.value?.size ?: 0) + " elapsed=" + (SystemClock.elapsedRealtime() - started))
            return result
        } catch (error: Exception) {
            if (diagnostic) traceWifi("GATT " + kind + " " + attribute + " " +
                (if (error is GattOperationException) error.message else error.javaClass.simpleName))
            // No transaction ID exists in ATT callbacks. Do not reuse a timed-out/cancelled lane:
            // a late completion could otherwise be mistaken for the next read of the same UUID.
            if (!operationLane.isActive(gatt)) {
                withContext(NonCancellable + Dispatchers.Main.immediate) { retireGatt(gatt) }
            }
            throw error
        }
    }

    private fun currentGattFor(char: BluetoothGattCharacteristic): BluetoothGatt? {
        val gatt = bluetoothGatt ?: return null
        return gatt.takeIf { findCharacteristic(it, char.uuid) === char }
    }

    private suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        preferNoResponse: Boolean = false,
        settleAfterInitiation: Boolean = false
    ): Boolean {
        val gatt = currentGattFor(characteristic) ?: return false
        val writeType = if (preferNoResponse &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return try {
            executeGatt(gatt, GattOperationKind.WRITE, attributeKey(characteristic), settleAfterInitiation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            }.status == BluetoothGatt.GATT_SUCCESS
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: GattOperationException) { false }
    }

    private suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray? {
        val gatt = currentGattFor(characteristic) ?: return null
        return try {
            val result = executeGatt(gatt, GattOperationKind.READ, attributeKey(characteristic)) {
                gatt.readCharacteristic(characteristic)
            }
            if (result.status == BluetoothGatt.GATT_SUCCESS) result.value else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: GattOperationException) { null }
    }

    private suspend fun subscribeToNotifications(characteristic: BluetoothGattCharacteristic): Boolean {
        val gatt = currentGattFor(characteristic) ?: return false
        val descriptor = characteristic.getDescriptor(SonyBleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return false
        val value = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> return false
        }
        return try {
            executeGatt(gatt, GattOperationKind.DESCRIPTOR, descriptorKey(descriptor)) {
                if (!gatt.setCharacteristicNotification(characteristic, true)) false
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }.status == BluetoothGatt.GATT_SUCCESS
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: GattOperationException) { false }
    }
}
