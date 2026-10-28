package com.letter.alphapro.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.letter.alphapro.R
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.ui.components.*
import com.letter.alphapro.viewmodel.CameraViewModel
import com.letter.alphapro.viewmodel.RemoteOperationMode
import com.letter.alphapro.viewmodel.RemoteOperationPhase

private enum class RemoteShootingMode(val label: Int, val operation: RemoteOperationMode) {
    SINGLE(R.string.remote_product_single, RemoteOperationMode.SINGLE),
    BURST(R.string.remote_product_burst, RemoteOperationMode.BURST),
    BULB(R.string.remote_product_bulb, RemoteOperationMode.BULB),
    INTERVAL(R.string.remote_product_interval, RemoteOperationMode.INTERVAL)
}
private enum class BulbControl { HOLD, TIMER }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteShutterScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val camera by viewModel.uiState.collectAsState()
    val remote by viewModel.remoteShooting.collectAsState()
    var selected by rememberSaveable { mutableStateOf(RemoteShootingMode.SINGLE) }
    var bulbControl by rememberSaveable { mutableStateOf(BulbControl.HOLD) }
    var bulbSeconds by rememberSaveable { mutableIntStateOf(30) }
    var intervalSeconds by rememberSaveable { mutableIntStateOf(5) }
    var intervalCount by rememberSaveable { mutableIntStateOf(10) }
    val ready = camera.bleState is SonyBleConnectionState.Ready
    val unlocked = !remote.isBusy && !remote.releaseUnconfirmed
    val canStart = ready && unlocked
    val mode = if (remote.isLongRunning) RemoteShootingMode.entries.firstOrNull { it.operation == remote.mode } ?: selected else selected
    val ownOperation = remote.isLongRunning && remote.mode == mode.operation
    val stopping = ownOperation && remote.phase == RemoteOperationPhase.STOPPING
    val manualBulb = if (ownOperation && mode == RemoteShootingMode.BULB) remote.manualHold else bulbControl == BulbControl.HOLD
    val ownHold = ownOperation && remote.manualHold
    val holdEnabled = ready && !remote.releaseUnconfirmed && (canStart || (ownHold && !stopping))

    BackHandler(enabled = remote.isLongRunning || remote.releaseUnconfirmed) {
        if (remote.isLongRunning) viewModel.stopRemoteShooting() else viewModel.notifyRemoteExitBlocked()
    }
    val owner = LocalLifecycleOwner.current
    val latestLeave by rememberUpdatedState(viewModel::onRemoteScreenLeft)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latestLeave() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); latestLeave() }
    }
    val view = LocalView.current
    if (remote.isLongRunning) DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.control_remote_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(if (ready) R.string.remote_product_connected else R.string.control_bluetooth_disconnected),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        },
        bottomBar = {
            if (remote.isLongRunning || remote.releaseUnconfirmed) Surface(tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (remote.isLongRunning) AlphaButton(onClick = viewModel::stopRemoteShooting,
                        modifier = Modifier.fillMaxWidth(), enabled = !stopping, size = AlphaButtonSize.Large,
                        colors = AlphaButtonDefaults.destructiveColors()) {
                        Text(stringResource(if (stopping) R.string.remote_modes_stopping else R.string.remote_product_stop))
                    } else AlphaTonalButton(onClick = viewModel::acknowledgeRemoteWarning, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.remote_modes_checked_camera))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AlphaSingleChoiceSegmentedButtonRow(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
            ) {
                RemoteShootingMode.entries.forEachIndexed { index, choice ->
                    AlphaSegmentedButton(selected = mode == choice, onClick = { selected = choice },
                        index = index, count = RemoteShootingMode.entries.size, enabled = unlocked) { Text(stringResource(choice.label)) }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Balance sparse modes without stretching the card or shutter. This inset
                // depends only on viewport/mode, NEVER live status: a held shutter must
                // not move under the finger when its timer or feedback first appears.
                val sparse = mode == RemoteShootingMode.SINGLE || mode == RemoteShootingMode.BURST
                val restingContentHeight = if (mode == RemoteShootingMode.SINGLE) 360.dp else 480.dp
                // Include the pinned action bar's space: its appearance must not
                // change the resting inset while the user is holding the shutter.
                val availableHeight = maxHeight + padding.calculateBottomPadding()
                val topInset = if (sparse) ((availableHeight - restingContentHeight) / 2)
                    .coerceIn(16.dp, 80.dp) else 16.dp
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(top = topInset, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(when (mode) {
                                    RemoteShootingMode.SINGLE -> R.string.remote_product_single_hint
                                    RemoteShootingMode.BURST -> R.string.remote_product_burst_hint
                                    RemoteShootingMode.BULB -> R.string.remote_product_bulb_hint
                                    RemoteShootingMode.INTERVAL -> R.string.remote_product_interval_hint
                                }),
                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (mode == RemoteShootingMode.BULB) {
                                AlphaSingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    AlphaSegmentedButton(selected = manualBulb, onClick = { bulbControl = BulbControl.HOLD },
                                        index = 0, count = 2, enabled = unlocked) { Text(stringResource(R.string.remote_product_hold_mode)) }
                                    AlphaSegmentedButton(selected = !manualBulb, onClick = { bulbControl = BulbControl.TIMER },
                                        index = 1, count = 2, enabled = unlocked) { Text(stringResource(R.string.remote_product_timer_mode)) }
                                }
                                if (!manualBulb) AlphaParameterSlider(
                                    label = stringResource(R.string.remote_product_duration), value = bulbSeconds,
                                    onValueChange = { bulbSeconds = it }, valueRange = 1..3600,
                                    valueLabel = stringResource(R.string.remote_product_seconds, bulbSeconds), enabled = unlocked, logarithmic = true)
                            }
                            if (mode == RemoteShootingMode.INTERVAL) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AlphaParameterSlider(label = stringResource(R.string.remote_product_interval_seconds), value = intervalSeconds,
                                        onValueChange = { intervalSeconds = it }, valueRange = 1..3600,
                                        valueLabel = stringResource(R.string.remote_product_seconds, intervalSeconds), enabled = unlocked, logarithmic = true)
                                    AlphaParameterSlider(label = stringResource(R.string.remote_product_count), value = intervalCount,
                                        onValueChange = { intervalCount = it }, valueRange = 1..999,
                                        valueLabel = stringResource(R.string.remote_product_count_value, intervalCount), enabled = unlocked)
                                }
                            }
                            val manual = mode == RemoteShootingMode.BURST || (mode == RemoteShootingMode.BULB && manualBulb)
                            val label = stringResource(when {
                                stopping -> R.string.remote_product_stopping_short
                                mode == RemoteShootingMode.SINGLE -> R.string.control_take_photo
                                manual && ownHold -> R.string.remote_product_release_to_stop
                                manual && mode == RemoteShootingMode.BURST -> R.string.remote_product_hold_burst
                                manual -> R.string.remote_product_hold_bulb
                                ownOperation -> R.string.remote_product_stop
                                mode == RemoteShootingMode.BULB -> R.string.remote_product_start_timer
                                else -> R.string.remote_product_start_interval
                            })
                            // Keep one stable interactive subtree; status is below the
                            // shutter so it cannot displace the held target on touch-down.
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                key(mode, manual) {
                                    if (manual) AlphaHoldShutterButton(
                                        onPress = { if (mode == RemoteShootingMode.BURST) viewModel.pressBurst() else viewModel.pressBulb() },
                                        onRelease = viewModel::releaseRemotePress, onStop = viewModel::stopRemoteShooting,
                                        enabled = holdEnabled, active = ownHold, actionLabel = label, stateLabel = label) {
                                        Icon(Icons.Rounded.Camera, null, Modifier.size(54.dp))
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    } else AlphaShutterButton(onClick = {
                                        when {
                                            ownOperation -> viewModel.stopRemoteShooting()
                                            mode == RemoteShootingMode.SINGLE -> viewModel.takePhoto()
                                            mode == RemoteShootingMode.BULB -> viewModel.startTimedBulb(bulbSeconds)
                                            else -> viewModel.startIntervalometer(intervalSeconds, intervalCount)
                                        }
                                    }, enabled = if (ownOperation) !stopping else canStart, active = ownOperation) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Rounded.Camera, null, Modifier.size(54.dp))
                                            Text(label, style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                            if (ownOperation || remote.mode == mode.operation && (remote.elapsedSeconds > 0 || remote.submittedTriggers > 0)) {
                                Column(
                                    Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(stringResource(R.string.remote_product_clock, remote.elapsedSeconds / 60, remote.elapsedSeconds % 60),
                                        style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                                    if (stopping) Text(stringResource(R.string.remote_modes_stopping),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (!remote.manualHold && mode == RemoteShootingMode.BULB) Text(
                                        stringResource(R.string.remote_product_remaining, remote.remainingSeconds), style = MaterialTheme.typography.bodySmall)
                                    if (mode == RemoteShootingMode.INTERVAL) {
                                        Text(stringResource(R.string.remote_product_progress, remote.submittedTriggers, remote.totalTriggers))
                                        if (ownOperation && !stopping) Text(stringResource(R.string.remote_product_next, remote.nextTriggerSeconds),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            FlowRow(
                                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AlphaTonalButton(onClick = viewModel::toggleAfLock, enabled = canStart, size = AlphaButtonSize.Compact) {
                                    Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(AlphaButtonSize.Compact.iconSize))
                                    Spacer(Modifier.width(AlphaButtonSize.Compact.iconSpacing))
                                    Text(stringResource(if (remote.afRequested) R.string.remote_product_release_af else R.string.control_af_half_press))
                                }
                                AlphaTonalButton(onClick = { viewModel.toggleMovieRecording() }, enabled = canStart, size = AlphaButtonSize.Compact) {
                                    Icon(Icons.Rounded.Videocam, null, Modifier.size(AlphaButtonSize.Compact.iconSize))
                                    Spacer(Modifier.width(AlphaButtonSize.Compact.iconSpacing))
                                    Text(stringResource(when (remote.recording) {
                                        true -> R.string.remote_modes_movie_stop
                                        false -> R.string.remote_modes_movie_start
                                        null -> R.string.control_video_toggle
                                    }))
                                }
                            }
                            if (remote.cameraFocusAcquired == true) Text(stringResource(R.string.remote_product_focused),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Real errors are never folded into general help or hidden by disclosure.
                    if (remote.error != null || remote.releaseUnconfirmed) RemoteNoticeCard(isError = true) {
                        remote.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium) }
                        if (remote.releaseUnconfirmed) Text(stringResource(R.string.remote_modes_release_warning),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (mode != RemoteShootingMode.SINGLE) RemoteNoticeCard {
                        Text(stringResource(R.string.remote_product_foreground), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.remote_product_foreground_caution), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Supporting guidance has less visual weight than capture; errors remain explicit. */
@Composable
private fun RemoteNoticeCard(isError: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}
