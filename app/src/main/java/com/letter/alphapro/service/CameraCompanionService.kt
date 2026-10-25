package com.letter.alphapro.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build

@SuppressLint("MissingPermission")
class CameraCompanionService : CompanionDeviceService() {

    private fun startLocationForegroundService(address: String?) {
        val intent = Intent(this, LocationSenderForegroundService::class.java).apply {
            putExtra(LocationSenderForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startLocationForegroundService(address)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val address = associationInfo.deviceMacAddress?.toString()
            startLocationForegroundService(address)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        // Camera went to sleep or shut down
    }
}
