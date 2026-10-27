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
        // Complete Sony two-stage shutter sequence:
        // 1. Half-press down (locks focus)
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
        delay(150)
        // 2. Full-press down (triggers exposure while half-press is held!)
        val down = gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
        delay(150)
        // 3. Release full-press
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
        delay(50)
        // 4. Release half-press
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_UP)
        return down
    }

    suspend fun toggleMovieRecording(): Boolean {
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_MOVIE_REC_DOWN)
        delay(150)
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_MOVIE_REC_UP)
    }

    suspend fun startBulb(): Boolean {
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
        delay(100)
        return gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
    }

    suspend fun stopBulb(): Boolean {
        gattManager.sendRemoteCommand(SonyBleConstants.CMD_FULL_SHUTTER_UP)
        delay(50)
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
