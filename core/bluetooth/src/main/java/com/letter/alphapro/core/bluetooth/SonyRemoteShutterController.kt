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
        // Sony Shutter Sequence: Half-press AF -> Full-press Shutter -> Full release -> Half release
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
        delay(150)
        val fullDown = gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
        delay(120)
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
        delay(60)
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_UP)
        return fullDown
    }

    suspend fun toggleMovieRecording(): Boolean {
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_MOVIE_REC_TOGGLE)
    }

    suspend fun startBulb(): Boolean {
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
        delay(100)
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
    }

    suspend fun stopBulb(): Boolean {
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
        delay(60)
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_UP)
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
