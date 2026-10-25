package com.letter.alphapro.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface SonyBleConnectionState {
    data object Disconnected : SonyBleConnectionState
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
    private var targetDeviceAddress: String? = null

    // Serialized GATT command queue (ensures only 1 GATT call in-flight)
    private val gattMutex = Mutex()
    private val operationResultChannel = Channel<Boolean>(Channel.CONFLATED)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = SonyBleConnectionState.DiscoveringServices
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = SonyBleConnectionState.Disconnected
                    cleanupGatt()
                    // Schedule auto-reconnect backoff if device was previously paired
                    targetDeviceAddress?.let { address ->
                        scope.launch {
                            delay(3000)
                            connect(address)
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch {
                    performSonyHandshake(gatt)
                }
            } else {
                _connectionState.value = SonyBleConnectionState.Error("Service discovery failed: status $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            operationResultChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            operationResultChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    fun connect(address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            _connectionState.value = SonyBleConnectionState.Error("Invalid MAC address: $address")
            return
        }

        targetDeviceAddress = address
        _connectionState.value = SonyBleConnectionState.Connecting

        cleanupGatt()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        targetDeviceAddress = null
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

    private suspend fun performSonyHandshake(gatt: BluetoothGatt) {
        _connectionState.value = SonyBleConnectionState.Handshaking

        // 1. Write 0x01 to DD30 (Unlock command 1)
        val success1 = writeCharacteristic(
            SonyBleConstants.SERVICE_LOCATION_UUID,
            SonyBleConstants.CHAR_GPS_UNLOCK_COMMAND_30_UUID,
            SonyBleConstants.CMD_ACTIVATE_GPS
        )

        if (!success1) {
            // Some models don't have DD30, continue to DD31
        }

        // 2. Write 0x01 to DD31 (Unlock command 2)
        writeCharacteristic(
            SonyBleConstants.SERVICE_LOCATION_UUID,
            SonyBleConstants.CHAR_GPS_UNLOCK_COMMAND_31_UUID,
            SonyBleConstants.CMD_ACTIVATE_GPS
        )

        // 3. Millisecond time sync on CC13
        val timeSyncPacket = SonyTimeSyncPacketBuilder.build()
        writeCharacteristic(
            SonyBleConstants.SERVICE_CONTROL_UUID,
            SonyBleConstants.CHAR_TIME_SYNC_UUID,
            timeSyncPacket
        )

        // Lower connection priority to balanced to save camera battery
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

        val deviceName = gatt.device.name ?: "Sony Camera"
        _connectionState.value = SonyBleConnectionState.Ready(deviceName, gatt.device.address)
    }

    suspend fun sendLocation(latitude: Double, longitude: Double): Boolean {
        if (_connectionState.value !is SonyBleConnectionState.Ready) return false
        val packet = SonyLocationPacketBuilder.build(latitude, longitude)
        return writeCharacteristic(
            SonyBleConstants.SERVICE_LOCATION_UUID,
            SonyBleConstants.CHAR_LOCATION_DATA_UUID,
            packet
        )
    }

    suspend fun sendTimeSync(): Boolean {
        if (_connectionState.value !is SonyBleConnectionState.Ready) return false
        val packet = SonyTimeSyncPacketBuilder.build()
        return writeCharacteristic(
            SonyBleConstants.SERVICE_CONTROL_UUID,
            SonyBleConstants.CHAR_TIME_SYNC_UUID,
            packet
        )
    }

    suspend fun readWifiCredentials(): SonyWifiCredentials? = gattMutex.withLock {
        val gatt = bluetoothGatt ?: return@withLock null
        val service = gatt.getService(SonyBleConstants.SERVICE_CONTROL_UUID) ?: return@withLock null

        // 1. Turn Wi-Fi ON on camera
        writeCharacteristicInternal(service, SonyBleConstants.CHAR_WIFI_POWER_UUID, SonyBleConstants.CMD_WIFI_POWER_ON)

        // 2. Read SSID
        val ssidChar = service.getCharacteristic(SonyBleConstants.CHAR_WIFI_SSID_UUID) ?: return@withLock null
        if (!gatt.readCharacteristic(ssidChar)) return@withLock null
        val readSsidSuccess = withTimeoutOrNull(2000) { operationResultChannel.receive() } ?: false
        if (!readSsidSuccess) return@withLock null
        val ssid = ssidChar.value?.decodeToString()?.trim('\u0000', ' ') ?: return@withLock null

        // 3. Read Password
        val pwdChar = service.getCharacteristic(SonyBleConstants.CHAR_WIFI_PASSWORD_UUID) ?: return@withLock null
        if (!gatt.readCharacteristic(pwdChar)) return@withLock null
        val readPwdSuccess = withTimeoutOrNull(2000) { operationResultChannel.receive() } ?: false
        if (!readPwdSuccess) return@withLock null
        val password = pwdChar.value?.decodeToString()?.trim('\u0000', ' ') ?: return@withLock null

        SonyWifiCredentials(ssid = ssid, password = password)
    }

    suspend fun sendRemoteCommand(command: ByteArray): Boolean {
        return writeCharacteristic(
            SonyBleConstants.SERVICE_REMOTE_UUID,
            SonyBleConstants.CHAR_REMOTE_COMMAND_UUID,
            command
        )
    }

    private suspend fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray
    ): Boolean = gattMutex.withLock {
        val gatt = bluetoothGatt ?: return@withLock false
        val service = gatt.getService(serviceUuid) ?: return@withLock false
        writeCharacteristicInternal(service, charUuid, data)
    }

    @Suppress("DEPRECATION")
    private suspend fun writeCharacteristicInternal(
        service: BluetoothGattService,
        charUuid: UUID,
        data: ByteArray
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = service.getCharacteristic(charUuid) ?: return false

        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val initiated = gatt.writeCharacteristic(char)
        if (!initiated) return false

        return withTimeoutOrNull(2500) {
            operationResultChannel.receive()
        } ?: false
    }
}
