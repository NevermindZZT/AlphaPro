package com.letter.alphapro.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.LinkedHashMap

data class DiscoveredCamera(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isSonyCamera: Boolean,
    val firstDiscoveredTime: Long = System.currentTimeMillis()
)

@SuppressLint("MissingPermission")
class SonyBleScanner(private val context: Context, private val messageContext: () -> Context = { context }) {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter
    }

    fun startScan(sonyOnly: Boolean = false): Flow<List<DiscoveredCamera>> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        // LinkedHashMap preserves stable insertion order
        val discoveredMap = LinkedHashMap<String, DiscoveredCamera>()
        var lastEmitTime = 0L

        fun emitStableList() {
            // Stable order: Sony cameras first, ordered by when they were discovered (never jump on RSSI)
            val sortedList = discoveredMap.values
                .filter { if (sonyOnly) it.isSonyCamera else true }
                .sortedWith(
                    compareByDescending<DiscoveredCamera> { it.isSonyCamera }
                        .thenBy { it.firstDiscoveredTime }
                )
            trySend(sortedList)
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName
                val address = device.address

                // Check for Sony camera service UUIDs (Location DD00, Control CC00, Remote FF00)
                val isSonyService = result.scanRecord?.serviceUuids?.any {
                    it.uuid == SonyBleConstants.SERVICE_LOCATION_UUID ||
                    it.uuid == SonyBleConstants.SERVICE_CONTROL_UUID ||
                    it.uuid == SonyBleConstants.SERVICE_REMOTE_UUID
                } == true

                // Check for Sony camera model name prefixes
                val isSonyName = name != null && (
                    name.startsWith("ILCE", ignoreCase = true) ||
                    name.startsWith("ZV", ignoreCase = true) ||
                    name.startsWith("ILME", ignoreCase = true) ||
                    name.startsWith("DSC", ignoreCase = true) ||
                    name.contains("Sony", ignoreCase = true) ||
                    name.contains("Alpha", ignoreCase = true) ||
                    name.contains("α", ignoreCase = true)
                )

                // Check for Sony Corporation IEEE Company ID (301 = 0x012D)
                val isSonyManufacturer = result.scanRecord?.manufacturerSpecificData?.get(301) != null

                val isSony = isSonyService || isSonyName || isSonyManufacturer

                // Skip unnamed non-Sony noise
                if (!isSony && name == null) return

                val isNewDevice = !discoveredMap.containsKey(address)
                val existing = discoveredMap[address]

                val displayName = name ?: messageContext().getString(
                    if (isSony) R.string.ble_camera_fallback else R.string.ble_device_fallback, address.takeLast(5))

                discoveredMap[address] = DiscoveredCamera(
                    device = device,
                    name = displayName,
                    address = address,
                    rssi = result.rssi,
                    isSonyCamera = isSony,
                    firstDiscoveredTime = existing?.firstDiscoveredTime ?: System.currentTimeMillis()
                )

                val now = System.currentTimeMillis()
                // Emit immediately when a new device is found; throttle updates for existing devices to prevent UI jitter
                if (isNewDevice || now - lastEmitTime > 800) {
                    lastEmitTime = now
                    emitStableList()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (_: Exception) {
            close()
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {}
        }
    }
}
