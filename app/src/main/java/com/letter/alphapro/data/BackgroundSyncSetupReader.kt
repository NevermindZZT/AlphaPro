package com.letter.alphapro.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Always query Android, not an app preference or an Activity result code. */
object BackgroundSyncSetupReader {
    fun hasForegroundLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun read(context: Context): BackgroundSyncSetupState = BackgroundSyncSetupState(
        apiLevel = Build.VERSION.SDK_INT,
        hasForegroundLocation = hasForegroundLocation(context),
        hasBackgroundLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        batteryExempt = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    )

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
