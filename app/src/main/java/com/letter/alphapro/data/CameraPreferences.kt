package com.letter.alphapro.data

import android.content.Context
import android.content.SharedPreferences

class CameraPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alphapro_camera_prefs", Context.MODE_PRIVATE)

    var pairedDeviceAddress: String?
        get() = prefs.getString("paired_device_address", null)
        set(value) = prefs.edit().putString("paired_device_address", value).apply()

    var pairedDeviceName: String?
        get() = prefs.getString("paired_device_name", null)
        set(value) = prefs.edit().putString("paired_device_name", value).apply()

    var isGpsAutoSyncEnabled: Boolean
        get() = prefs.getBoolean("is_gps_auto_sync_enabled", true)
        set(value) = prefs.edit().putBoolean("is_gps_auto_sync_enabled", value).apply()

    var lastWifiSsid: String?
        get() = prefs.getString("last_wifi_ssid", null)
        set(value) = prefs.edit().putString("last_wifi_ssid", value).apply()

    var lastWifiPassword: String?
        get() = prefs.getString("last_wifi_password", null)
        set(value) = prefs.edit().putString("last_wifi_password", value).apply()

    fun clearPairedDevice() {
        prefs.edit()
            .remove("paired_device_address")
            .remove("paired_device_name")
            .apply()
    }
}
