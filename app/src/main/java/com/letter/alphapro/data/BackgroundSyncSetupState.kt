package com.letter.alphapro.data

enum class LocationSetupStep { FOREGROUND_PERMISSION, BACKGROUND_PERMISSION, READY }

enum class LocationSetupAction { REQUEST_FOREGROUND, REQUEST_BACKGROUND, OPEN_APP_SETTINGS }

data class BackgroundSyncSetupState(
    val apiLevel: Int,
    val hasForegroundLocation: Boolean,
    val hasBackgroundLocation: Boolean,
    val batteryExempt: Boolean?
) {
    val locationStep: LocationSetupStep
        get() = when {
            !hasForegroundLocation -> LocationSetupStep.FOREGROUND_PERMISSION
            apiLevel >= 29 && !hasBackgroundLocation -> LocationSetupStep.BACKGROUND_PERMISSION
            else -> LocationSetupStep.READY
        }

    val locationReady: Boolean
        get() = locationStep == LocationSetupStep.READY

    val locationAction: LocationSetupAction
        get() = when (locationStep) {
            LocationSetupStep.FOREGROUND_PERMISSION -> LocationSetupAction.REQUEST_FOREGROUND
            LocationSetupStep.BACKGROUND_PERMISSION -> if (apiLevel == 29) {
                LocationSetupAction.REQUEST_BACKGROUND
            } else {
                LocationSetupAction.OPEN_APP_SETTINGS
            }
            LocationSetupStep.READY -> LocationSetupAction.OPEN_APP_SETTINGS
        }

    val batteryReady: Boolean
        get() = batteryExempt == true

    val complete: Boolean
        get() = locationReady && batteryReady
}
