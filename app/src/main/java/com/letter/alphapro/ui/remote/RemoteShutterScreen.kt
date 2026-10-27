package com.letter.alphapro.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.ui.theme.CameraActiveGreen
import com.letter.alphapro.ui.theme.CameraRecordingRed
import com.letter.alphapro.viewmodel.CameraViewModel

enum class RemoteShootingMode(val label: String) {
    SINGLE("单张"),
    CONTINUOUS("连拍"),
    BULB("B门长曝光"),
    INTERVAL("延时摄影")
}

@OptIn(ExperimentalMaterial3Api::class)
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
                            text = "无线蓝牙快门",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isBleReady) (uiState.pairedName ?: "索尼微单") + " · BLE 遥控就绪" else "蓝牙未连接",
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
                            text = if (isBleReady) "已就绪" else "未联机",
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
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "相机设置要求：请确认相机菜单 [网络] -> [Bluetooth] -> [Bluetooth遥控] 已设置为 [开]。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Mode Selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RemoteShootingMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RemoteShootingMode.entries.size)
                    ) {
                        Text(text = mode.label, style = MaterialTheme.typography.labelMedium)
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
                    // Tactile Shutter Button with gesture detection
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(
                                6.dp,
                                if (isBleReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                            .clickable(enabled = isBleReady) {
                                viewModel.takePhoto()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.Camera,
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                                tint = if (isBleReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (!isBleReady) "请先连接相机" else "按下拍照",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Secondary Controls: AF Lock & Video REC
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilledTonalButton(
                            onClick = {
                                isAfPressed = !isAfPressed
                                if (isAfPressed) viewModel.halfPressAfDown() else viewModel.halfPressAfUp()
                            },
                            enabled = isBleReady,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isAfPressed) "AF 锁定中" else "半按 AF")
                        }

                        Button(
                            onClick = {
                                isRecordingVideo = !isRecordingVideo
                                viewModel.toggleMovieRecording()
                            },
                            enabled = isBleReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecordingVideo) CameraRecordingRed else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (isRecordingVideo) Color.White else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecordingVideo) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                                contentDescription = null,
                                tint = if (isRecordingVideo) Color.White else CameraRecordingRed
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRecordingVideo) "录像中 (REC)" else "录像开关")
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
                            Text("B门长曝光控制器", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("曝光持续时间", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = uiState.bulbElapsedSeconds.toString() + " 秒",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.toggleBulb() },
                                enabled = isBleReady,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (uiState.isBulbActive) "结束长曝光" else "锁定开始长曝光")
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
                            Text("高级延时连拍 (Intervalometer)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("拍摄间隔: " + intervalSeconds.toString() + " 秒", style = MaterialTheme.typography.bodyMedium)
                                Text("计划张数: " + totalIntervalShots.toString() + " 张", style = MaterialTheme.typography.bodyMedium)
                            }
                            Slider(
                                value = intervalSeconds.toFloat(),
                                onValueChange = { intervalSeconds = it.toInt() },
                                valueRange = 1f..30f,
                                steps = 29
                            )
                            if (uiState.activeIntervalRemaining != null) {
                                Text(
                                    text = "正在执行延时连拍... 剩余 " + uiState.activeIntervalRemaining.toString() + " 张",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                )
                            }
                            Button(
                                onClick = { viewModel.startIntervalometer(intervalSeconds, totalIntervalShots) },
                                enabled = isBleReady && uiState.activeIntervalRemaining == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (uiState.activeIntervalRemaining != null) "延时序列进行中" else "启动延时拍摄序列")
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
