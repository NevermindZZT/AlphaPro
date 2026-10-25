package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.delay

class SonyRemoteShutterController(
    private val gattManager: SonyBleGattManager
) {
    suspend fun halfPressAfDown(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
    }

    suspend fun halfPressAfUp(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_UP)
    }

    suspend fun takePhoto(): Boolean {
        // Shutter Down -> Hold briefly -> Shutter Up
        val down = gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
        if (!down) return false
        delay(120)
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
    }

    suspend fun toggleMovieRecording(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_MOVIE_REC_TOGGLE)
    }

    suspend fun startBulb(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
    }

    suspend fun stopBulb(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
    }

    suspend fun executeIntervalometer(
        intervalSeconds: Int,
        totalShots: Int,
        onShotTaken: (current: Int, total: Int) -> Unit
    ) {
        for (i in 1..totalShots) {
            takePhoto()
            onShotTaken(i, totalShots)
            if (i < totalShots) {
                delay(intervalSeconds * 1000L)
            }
        }
    }
}
