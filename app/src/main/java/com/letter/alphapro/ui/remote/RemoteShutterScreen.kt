package com.letter.alphapro.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.letter.alphapro.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.ui.components.AlphaButton
import com.letter.alphapro.ui.components.AlphaButtonDefaults
import com.letter.alphapro.ui.components.AlphaSegmentedButton
import com.letter.alphapro.ui.components.AlphaShutterButton
import com.letter.alphapro.ui.components.AlphaSingleChoiceSegmentedButtonRow
import com.letter.alphapro.ui.components.AlphaTonalButton
import com.letter.alphapro.ui.theme.CameraActiveGreen
import com.letter.alphapro.viewmodel.CameraViewModel

enum class RemoteShootingMode(val labelRes: Int) {
    SINGLE(R.string.control_mode_single),
    CONTINUOUS(R.string.control_mode_continuous),
    BULB(R.string.control_mode_bulb),
    INTERVAL(R.string.control_mode_interval)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteShutterScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedMode by remember { mutableStateOf(RemoteShootingMode.SINGLE) }
    var isAfPressed by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }

    var intervalSeconds by remember { mutableStateOf(5) }
    var totalIntervalShots by remember { mutableStateOf(120) }

    val isBleReady = uiState.bleState is SonyBleConnectionState.Ready

    LaunchedEffect(isBleReady) {
        if (isBleReady) {
            // Send wake-up probe to wake camera remote receiver
            viewModel.halfPressAfUp()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.control_remote_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isBleReady) stringResource(R.string.control_remote_ready_camera, uiState.pairedName ?: stringResource(R.string.control_sony_camera)) else stringResource(R.string.control_bluetooth_disconnected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isBleReady) CameraActiveGreen else MaterialTheme.colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBleReady) stringResource(R.string.control_ready) else stringResource(R.string.control_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Sony Camera Remote Control Setup Hint Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AlphaButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.control_remote_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Mode Selector
            AlphaSingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RemoteShootingMode.entries.forEachIndexed { index, mode ->
                    AlphaSegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        index = index,
                        count = RemoteShootingMode.entries.size
                    ) {
                        Text(text = stringResource(mode.labelRes))
                    }
                }
            }

            // Big Shutter Area
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Shared shutter styling; keep a single photo callback.
                    AlphaShutterButton(
                        onClick = { viewModel.takePhoto() },
                        enabled = isBleReady
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.Camera,
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                                tint = LocalContentColor.current
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (!isBleReady) stringResource(R.string.control_connect_camera_first) else stringResource(R.string.control_take_photo),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Secondary Controls: AF Lock & Video REC
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AlphaTonalButton(
                            onClick = {
                                isAfPressed = !isAfPressed
                                if (isAfPressed) viewModel.halfPressAfDown() else viewModel.halfPressAfUp()
                            },
                            enabled = isBleReady
                        ) {
                            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(AlphaButtonDefaults.IconSize))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isAfPressed) stringResource(R.string.control_af_locked) else stringResource(R.string.control_af_half_press))
                        }

                        AlphaButton(
                            onClick = {
                                isRecordingVideo = !isRecordingVideo
                                viewModel.toggleMovieRecording()
                            },
                            enabled = isBleReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecordingVideo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (isRecordingVideo) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                imageVector = if (isRecordingVideo) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                                contentDescription = null,
                                tint = if (!isBleReady) LocalContentColor.current else if (isRecordingVideo) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(AlphaButtonDefaults.IconSize)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRecordingVideo) stringResource(R.string.control_video_recording) else stringResource(R.string.control_video_toggle))
                        }
                    }
                }
            }

            // Mode Specific: Bulb or Intervalometer
            when (selectedMode) {
                RemoteShootingMode.BULB -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(stringResource(R.string.control_bulb_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.control_exposure_duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = pluralStringResource(R.plurals.control_seconds_elapsed, uiState.bulbElapsedSeconds, uiState.bulbElapsedSeconds),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            AlphaButton(
                                onClick = { viewModel.toggleBulb() },
                                enabled = isBleReady,
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (uiState.isBulbActive) AlphaButtonDefaults.destructiveColors() else ButtonDefaults.buttonColors()
                            ) {
                                Text(if (uiState.isBulbActive) stringResource(R.string.control_bulb_stop) else stringResource(R.string.control_bulb_start))
                            }
                        }
                    }
                }
                RemoteShootingMode.INTERVAL -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.control_interval_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pluralStringResource(R.plurals.control_interval_seconds, intervalSeconds, intervalSeconds), style = MaterialTheme.typography.bodyMedium)
                                Text(pluralStringResource(R.plurals.control_planned_shots, totalIntervalShots, totalIntervalShots), style = MaterialTheme.typography.bodyMedium)
                            }
                            Slider(
                                value = intervalSeconds.toFloat(),
                                onValueChange = { intervalSeconds = it.toInt() },
                                valueRange = 1f..30f,
                                steps = 29
                            )
                            if (uiState.activeIntervalRemaining != null) {
                                Text(
                                    text = pluralStringResource(R.plurals.control_interval_remaining, uiState.activeIntervalRemaining ?: 0, uiState.activeIntervalRemaining ?: 0),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                )
                            }
                            AlphaButton(
                                onClick = { viewModel.startIntervalometer(intervalSeconds, totalIntervalShots) },
                                enabled = isBleReady && uiState.activeIntervalRemaining == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (uiState.activeIntervalRemaining != null) stringResource(R.string.control_interval_running) else stringResource(R.string.control_interval_start))
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
