package com.letter.alphapro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.letter.alphapro.R
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppThemeMode(@StringRes val labelRes: Int) {
    FOLLOW_SYSTEM(R.string.pref_theme_system),
    LIGHT(R.string.pref_theme_light),
    DARK(R.string.pref_theme_dark)
}

enum class GpsInterval(val seconds: Int, @StringRes val labelRes: Int) {
    FAST(3, R.string.pref_gps_fast),
    BALANCED(5, R.string.pref_gps_balanced),
    POWER_SAVING(10, R.string.pref_gps_saving)
}

enum class DefaultHeifPolicy(@StringRes val labelRes: Int) {
    TRANSCODE_JPEG(R.string.pref_heif_jpeg),
    ORIGINAL_HIF(R.string.pref_heif_original),
    ALWAYS_ASK(R.string.pref_heif_ask)
}

object AppThemeState {
    private var initialized = false
    val themeMode = MutableStateFlow(AppThemeMode.FOLLOW_SYSTEM)
    val dynamicColor = MutableStateFlow(true)

    fun init(prefs: CameraPreferences) {
        if (!initialized) {
            themeMode.value = prefs.themeMode
            dynamicColor.value = prefs.isDynamicColorEnabled
            initialized = true
        }
    }

    fun setThemeMode(prefs: CameraPreferences, mode: AppThemeMode) {
        prefs.themeMode = mode
        themeMode.value = mode
    }

    fun setDynamicColor(prefs: CameraPreferences, enabled: Boolean) {
        prefs.isDynamicColorEnabled = enabled
        dynamicColor.value = enabled
    }
}

class CameraPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alphapro_camera_prefs", Context.MODE_PRIVATE)

    init {
        AppThemeState.init(this)
    }

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

    // Theme preferences
    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString("theme_mode", AppThemeMode.FOLLOW_SYSTEM.name)
            return runCatching { AppThemeMode.valueOf(name ?: "") }.getOrDefault(AppThemeMode.FOLLOW_SYSTEM)
        }
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()

    var isDynamicColorEnabled: Boolean
        get() = prefs.getBoolean("dynamic_color_enabled", true)
        set(value) = prefs.edit().putBoolean("dynamic_color_enabled", value).apply()

    // GPS preferences
    var gpsInterval: GpsInterval
        get() {
            val sec = prefs.getInt("gps_interval_sec", GpsInterval.BALANCED.seconds)
            return GpsInterval.entries.find { it.seconds == sec } ?: GpsInterval.BALANCED
        }
        set(value) = prefs.edit().putInt("gps_interval_sec", value.seconds).apply()

    var isGpxTrackLoggingEnabled: Boolean
        get() = prefs.getBoolean("gpx_track_logging_enabled", true)
        set(value) = prefs.edit().putBoolean("gpx_track_logging_enabled", value).apply()

    // Gallery & Export preferences
    var defaultHeifPolicy: DefaultHeifPolicy
        get() {
            val name = prefs.getString("default_heif_policy", DefaultHeifPolicy.TRANSCODE_JPEG.name)
            return runCatching { DefaultHeifPolicy.valueOf(name ?: "") }.getOrDefault(DefaultHeifPolicy.TRANSCODE_JPEG)
        }
        set(value) = prefs.edit().putString("default_heif_policy", value.name).apply()

    var defaultJpegQuality: Int
        get() = prefs.getInt("default_jpeg_quality", 95)
        set(value) = prefs.edit().putInt("default_jpeg_quality", value).apply()

    // Automation preferences
    var autoWakeOnCameraPowerOn: Boolean
        get() = prefs.getBoolean("auto_wake_on_camera_power_on", true)
        set(value) = prefs.edit().putBoolean("auto_wake_on_camera_power_on", value).apply()

    // A revisioned key intentionally ignores O1/O2's potentially accidental completion flag.
    var completedOnboardingRevision: Int
        get() = prefs.getInt("onboarding_completed_revision", 0)
        set(value) {
            check(prefs.edit().putInt("onboarding_completed_revision", value).commit()) {
                "Unable to save onboarding completion"
            }
        }

    fun clearPairedDevice() {
        prefs.edit()
            .remove("paired_device_address")
            .remove("paired_device_name")
            .apply()
    }
}
