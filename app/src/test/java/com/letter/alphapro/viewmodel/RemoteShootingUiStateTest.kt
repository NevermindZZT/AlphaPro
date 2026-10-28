package com.letter.alphapro.viewmodel

import org.junit.Assert.*
import org.junit.Test

class RemoteShootingUiStateTest {
    @Test fun connectionDoesNotInventExposureOrRecordingFeedback() {
        val state = RemoteShootingUiState()
        assertFalse(state.isBusy); assertFalse(state.isLongRunning)
        assertNull(state.recording); assertNull(state.cameraShutterActive); assertNull(state.cameraFocusAcquired)
        assertEquals(0, state.submittedTriggers)
    }
    @Test fun startingRunningAndStoppingKeepAdvancedOwnershipVisible() {
        for (mode in listOf(RemoteOperationMode.BURST, RemoteOperationMode.BULB, RemoteOperationMode.INTERVAL)) {
            for (phase in listOf(RemoteOperationPhase.STARTING, RemoteOperationPhase.RUNNING, RemoteOperationPhase.STOPPING)) {
                val state = RemoteShootingUiState(mode = mode, phase = phase)
                assertTrue(state.isBusy); assertTrue(state.isLongRunning)
            }
        }
    }
    @Test fun completedCountersAreNotAnActiveOrCameraConfirmedExposure() {
        val state = RemoteShootingUiState(mode = RemoteOperationMode.INTERVAL, submittedTriggers = 5, totalTriggers = 5)
        assertFalse(state.isBusy); assertFalse(state.isLongRunning); assertNull(state.cameraShutterActive)
    }
    @Test fun uncertainReleaseDoesNotPretendAnOperationIsStillSending() {
        val state = RemoteShootingUiState(releaseUnconfirmed = true)
        assertFalse(state.isBusy); assertTrue(state.releaseUnconfirmed)
    }
}
