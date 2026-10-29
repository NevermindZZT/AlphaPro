package com.letter.alphapro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSyncSetupStateTest {
    private val apiLevels = listOf(26, 28, 29, 30, 36, 37)

    @Test fun missingForegroundTakesPriorityEvenWithStaleBackgroundPermission() {
        for (apiLevel in apiLevels) {
            for (background in listOf(false, true)) {
                assertPolicy(apiLevel, false, background,
                    LocationSetupStep.FOREGROUND_PERMISSION, LocationSetupAction.REQUEST_FOREGROUND)
            }
        }
    }

    @Test fun beforeApi29ForegroundAloneMakesLocationReady() {
        for (apiLevel in listOf(26, 28)) {
            for (background in listOf(false, true)) {
                assertPolicy(apiLevel, true, background,
                    LocationSetupStep.READY, LocationSetupAction.OPEN_APP_SETTINGS)
            }
        }
    }

    @Test fun api29RequestsMissingBackgroundPermissionDirectly() {
        assertPolicy(29, true, false,
            LocationSetupStep.BACKGROUND_PERMISSION, LocationSetupAction.REQUEST_BACKGROUND)
    }

    @Test fun api30AndLaterUseSettingsForMissingBackgroundPermission() {
        for (apiLevel in listOf(30, 36, 37)) {
            assertPolicy(apiLevel, true, false,
                LocationSetupStep.BACKGROUND_PERMISSION, LocationSetupAction.OPEN_APP_SETTINGS)
        }
    }

    @Test fun grantedLocationPermissionsKeepSettingsAvailableForManagement() {
        for (apiLevel in listOf(29, 30, 36, 37)) {
            assertPolicy(apiLevel, true, true,
                LocationSetupStep.READY, LocationSetupAction.OPEN_APP_SETTINGS)
        }
    }

    @Test fun revokedOrUnknownGrantsDoNotRetainCompletedState() {
        val ready = BackgroundSyncSetupState(36, true, true, true)
        assertState(ready, LocationSetupStep.READY, LocationSetupAction.OPEN_APP_SETTINGS)
        assertState(ready.copy(hasForegroundLocation = false),
            LocationSetupStep.FOREGROUND_PERMISSION, LocationSetupAction.REQUEST_FOREGROUND)
        assertState(ready.copy(hasBackgroundLocation = false),
            LocationSetupStep.BACKGROUND_PERMISSION, LocationSetupAction.OPEN_APP_SETTINGS)
        assertState(ready.copy(batteryExempt = false),
            LocationSetupStep.READY, LocationSetupAction.OPEN_APP_SETTINGS)
        assertState(ready.copy(batteryExempt = null),
            LocationSetupStep.READY, LocationSetupAction.OPEN_APP_SETTINGS)
    }

    private fun assertPolicy(
        apiLevel: Int,
        foreground: Boolean,
        background: Boolean,
        step: LocationSetupStep,
        action: LocationSetupAction
    ) {
        for (battery in listOf(true, false, null)) {
            assertState(BackgroundSyncSetupState(apiLevel, foreground, background, battery), step, action)
        }
    }

    private fun assertState(
        state: BackgroundSyncSetupState,
        step: LocationSetupStep,
        action: LocationSetupAction
    ) {
        val locationReady = step == LocationSetupStep.READY
        val batteryReady = state.batteryExempt == true
        assertEquals(state.toString(), step, state.locationStep)
        assertEquals(state.toString(), locationReady, state.locationReady)
        assertEquals(state.toString(), action, state.locationAction)
        assertEquals(state.toString(), batteryReady, state.batteryReady)
        assertEquals(state.toString(), locationReady && batteryReady, state.complete)
    }
}
