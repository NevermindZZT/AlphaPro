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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.letter.alphapro.R
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.ui.components.*
import com.letter.alphapro.viewmodel.CameraViewModel
import com.letter.alphapro.viewmodel.RemoteOperationMode
import com.letter.alphapro.viewmodel.RemoteOperationPhase

private enum class RemoteTopMode(val label: Int) {
    STANDARD(R.string.remote_product_mode_standard),
    TIMED(R.string.remote_product_mode_timed)
}

private enum class TimedShootingMode(val label: Int, val operation: RemoteOperationMode) {
    BULB(R.string.remote_product_bulb_timed_title, RemoteOperationMode.BULB),
    INTERVAL(R.string.remote_product_interval_title, RemoteOperationMode.INTERVAL)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteShutterScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val camera by viewModel.uiState.collectAsState()
    val remote by viewModel.remoteShooting.collectAsState()
    var topMode by rememberSaveable { mutableStateOf(RemoteTopMode.STANDARD) }
    var timedMode by rememberSaveable { mutableStateOf(TimedShootingMode.BULB) }
    var bulbSeconds by rememberSaveable { mutableIntStateOf(30) }
    var intervalSeconds by rememberSaveable { mutableIntStateOf(5) }
    var intervalCount by rememberSaveable { mutableIntStateOf(10) }
    val ready = camera.bleState is SonyBleConnectionState.Ready
    val unlocked = !remote.isBusy && !remote.releaseUnconfirmed
    val canStart = ready && unlocked

    val activeTopMode = if (remote.isLongRunning) {
        if (remote.mode in listOf(RemoteOperationMode.BULB, RemoteOperationMode.INTERVAL)) RemoteTopMode.TIMED
        else RemoteTopMode.STANDARD
    } else topMode

    val activeTimedMode = if (remote.isLongRunning && remote.mode in listOf(RemoteOperationMode.BULB, RemoteOperationMode.INTERVAL)) {
        if (remote.mode == RemoteOperationMode.BULB) TimedShootingMode.BULB else TimedShootingMode.INTERVAL
    } else timedMode

    val ownOperation = remote.isLongRunning && when (activeTopMode) {
        RemoteTopMode.STANDARD -> remote.mode == RemoteOperationMode.BURST
        RemoteTopMode.TIMED -> remote.mode == activeTimedMode.operation
    }
    val stopping = ownOperation && remote.phase == RemoteOperationPhase.STOPPING
    val ownHold = remote.isBusy && remote.manualHold
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
                RemoteTopMode.entries.forEachIndexed { index, choice ->
                    AlphaSegmentedButton(selected = activeTopMode == choice, onClick = { topMode = choice },
                        index = index, count = RemoteTopMode.entries.size, enabled = unlocked) { Text(stringResource(choice.label)) }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val isStandard = activeTopMode == RemoteTopMode.STANDARD
                val restingContentHeight = if (isStandard) 380.dp else 500.dp
                val availableHeight = maxHeight + padding.calculateBottomPadding()
                val topInset = if (isStandard) ((availableHeight - restingContentHeight) / 2)
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
                            if (isStandard) {
                                Text(
                                    stringResource(R.string.remote_product_standard_hint),
                                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val label = stringResource(when {
                                    stopping -> R.string.remote_product_stopping_short
                                    ownHold -> R.string.remote_product_release_to_stop
                                    else -> R.string.remote_product_shutter_standard
                                })
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    AlphaHoldShutterButton(
                                        onPress = viewModel::pressShutter,
                                        onRelease = viewModel::releaseRemotePress,
                                        onStop = viewModel::stopRemoteShooting,
                                        enabled = holdEnabled,
                                        active = ownHold,
                                        actionLabel = label,
                                        stateLabel = label,
                                        onTap = viewModel::takePhoto
                                    ) {
                                        Icon(Icons.Rounded.Camera, null, Modifier.size(64.dp))
                                    }
                                }
                                if (ownHold || stopping) {
                                    Text(
                                        stringResource(if (stopping) R.string.remote_product_stopping_short else R.string.remote_product_release_to_stop),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                AlphaSingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    TimedShootingMode.entries.forEachIndexed { index, choice ->
                                        AlphaSegmentedButton(
                                            selected = activeTimedMode == choice,
                                            onClick = { timedMode = choice },
                                            index = index,
                                            count = TimedShootingMode.entries.size,
                                            enabled = unlocked
                                        ) {
                                            Text(stringResource(choice.label))
                                        }
                                    }
                                }
                                Text(
                                    stringResource(if (activeTimedMode == TimedShootingMode.BULB) R.string.remote_product_bulb_hint else R.string.remote_product_interval_hint),
                                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (activeTimedMode == TimedShootingMode.BULB) {
                                    AlphaParameterSlider(
                                        label = stringResource(R.string.remote_product_duration), value = bulbSeconds,
                                        onValueChange = { bulbSeconds = it }, valueRange = 1..3600,
                                        valueLabel = stringResource(R.string.remote_product_seconds, bulbSeconds),
                                        enabled = unlocked, logarithmic = true
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AlphaParameterSlider(
                                            label = stringResource(R.string.remote_product_interval_seconds), value = intervalSeconds,
                                            onValueChange = { intervalSeconds = it }, valueRange = 1..3600,
                                            valueLabel = stringResource(R.string.remote_product_seconds, intervalSeconds),
                                            enabled = unlocked, logarithmic = true
                                        )
                                        AlphaParameterSlider(
                                            label = stringResource(R.string.remote_product_count), value = intervalCount,
                                            onValueChange = { intervalCount = it }, valueRange = 1..999,
                                            valueLabel = stringResource(R.string.remote_product_count_value, intervalCount),
                                            enabled = unlocked
                                        )
                                    }
                                }
                                val timedLabel = stringResource(when {
                                    stopping -> R.string.remote_product_stopping_short
                                    ownOperation -> R.string.remote_product_stop
                                    activeTimedMode == TimedShootingMode.BULB -> R.string.remote_product_start_timer
                                    else -> R.string.remote_product_start_interval
                                })
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    AlphaShutterButton(
                                        onClick = {
                                            when {
                                                ownOperation -> viewModel.stopRemoteShooting()
                                                activeTimedMode == TimedShootingMode.BULB -> viewModel.startTimedBulb(bulbSeconds)
                                                else -> viewModel.startIntervalometer(intervalSeconds, intervalCount)
                                            }
                                        },
                                        enabled = if (ownOperation) !stopping else canStart,
                                        active = ownOperation
                                    ) {
                                        Icon(Icons.Rounded.Camera, null, Modifier.size(64.dp))
                                    }
                                }
                                if (!ownOperation && !stopping) {
                                    Text(
                                        timedLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (ownOperation || (remote.isBusy && remote.mode == activeTimedMode.operation && (remote.elapsedSeconds > 0 || remote.submittedTriggers > 0))) {
                                    Column(
                                        Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(stringResource(R.string.remote_product_clock, remote.elapsedSeconds / 60, remote.elapsedSeconds % 60),
                                            style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                                        if (stopping) Text(stringResource(R.string.remote_modes_stopping), style = MaterialTheme.typography.bodySmall)
                                        if (activeTimedMode == TimedShootingMode.BULB) Text(
                                            stringResource(R.string.remote_product_remaining, remote.remainingSeconds), style = MaterialTheme.typography.bodySmall)
                                        if (activeTimedMode == TimedShootingMode.INTERVAL) {
                                            Text(stringResource(R.string.remote_product_progress, remote.submittedTriggers, remote.totalTriggers))
                                            if (ownOperation && !stopping) Text(stringResource(R.string.remote_product_next, remote.nextTriggerSeconds),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlphaTonalButton(
                                    onClick = viewModel::toggleAfLock,
                                    enabled = canStart,
                                    size = AlphaButtonSize.Large,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(AlphaButtonSize.Large.iconSize))
                                    Spacer(Modifier.width(AlphaButtonSize.Large.iconSpacing))
                                    Text(
                                        stringResource(if (remote.afRequested) R.string.remote_product_release_af else R.string.control_af_half_press),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                AlphaTonalButton(
                                    onClick = { viewModel.toggleMovieRecording() },
                                    enabled = canStart,
                                    size = AlphaButtonSize.Large,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Videocam, null, Modifier.size(AlphaButtonSize.Large.iconSize))
                                    Spacer(Modifier.width(AlphaButtonSize.Large.iconSpacing))
                                    Text(
                                        stringResource(when (remote.recording) {
                                            true -> R.string.remote_modes_movie_stop
                                            false -> R.string.remote_modes_movie_start
                                            null -> R.string.control_video_toggle
                                        }),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
                    if (activeTopMode == RemoteTopMode.TIMED) RemoteNoticeCard {
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
