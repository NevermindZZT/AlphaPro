package com.letter.alphapro.viewmodel

import com.letter.alphapro.ui.components.LocalizedText

enum class RemoteOperationMode { SINGLE, BURST, BULB, INTERVAL, MOVIE, AF }
enum class RemoteOperationPhase { IDLE, STARTING, RUNNING, STOPPING }

data class RemoteShootingUiState(
    val mode: RemoteOperationMode? = null,
    val phase: RemoteOperationPhase = RemoteOperationPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val submittedTriggers: Int = 0,
    val totalTriggers: Int = 0,
    val nextTriggerSeconds: Int = 0,
    val afRequested: Boolean = false,
    val recording: Boolean? = null,
    val cameraShutterActive: Boolean? = null,
    val cameraFocusAcquired: Boolean? = null,
    val error: LocalizedText? = null,
    val manualHold: Boolean = false,
    val shutterPressAccepted: Boolean = false,
    val releaseUnconfirmed: Boolean = false
) {
    val isBusy: Boolean get() = phase != RemoteOperationPhase.IDLE
    val isLongRunning: Boolean get() = isBusy && mode in listOf(RemoteOperationMode.BURST, RemoteOperationMode.BULB, RemoteOperationMode.INTERVAL)
}
