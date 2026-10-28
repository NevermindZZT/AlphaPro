package com.letter.alphapro.ui.hub

import com.letter.alphapro.ui.components.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.ui.components.DeviceScanDialog
import com.letter.alphapro.ui.theme.CameraActiveGreen
import com.letter.alphapro.ui.theme.CameraWarningAmber
import com.letter.alphapro.viewmodel.CameraViewModel
import com.letter.alphapro.wifi.CameraWifiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraHubScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val discoveredCameras by viewModel.discoveredCameras.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    var showScanDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AlphaPro",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "索尼相机专业伴侣",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    AlphaIconButton(onClick = {
                        showScanDialog = true
                        viewModel.startScan(sonyOnly = true)
                    }) {
                        Icon(Icons.Rounded.BluetoothSearching, contentDescription = "搜索配对相机")
                    }
                    AlphaIconButton(onClick = {
                        val intent = android.content.Intent(context, com.letter.alphapro.ui.settings.SettingsActivity::class.java)
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Dynamic Camera Device Status Hero Card
            DynamicCameraHeroCard(
                pairedName = uiState.pairedName,
                pairedAddress = uiState.pairedAddress,
                bleState = uiState.bleState,
                onPairNewDevice = {
                    showScanDialog = true
                    viewModel.startScan(sonyOnly = true)
                },
                onDisconnect = { viewModel.disconnectCamera() },
                onReconnect = { viewModel.reconnectCamera() },
                onForgetDevice = { viewModel.forgetDevice() }
            )

            Text(
                text = "控制中心与状态",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Bento Grid Row 1: GPS & Wi-Fi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BentoGpsCard(
                    modifier = Modifier.weight(1f),
                    enabled = uiState.isGpsSyncActive,
                    isConnected = uiState.bleState is SonyBleConnectionState.Ready,
                    onToggle = { viewModel.toggleGpsSync(it) }
                )
                BentoWifiCard(
                    modifier = Modifier.weight(1f),
                    wifiState = uiState.wifiState,
                    isBleConnected = uiState.bleState is SonyBleConnectionState.Ready,
                    onConnect = { viewModel.connectCameraWifi() },
                    onDisconnect = { viewModel.disconnectCameraWifi() }
                )
            }

            // Bento Grid Row 2: Time Sync & FTP Server
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BentoTimeSyncCard(
                    modifier = Modifier.weight(1f),
                    isBleConnected = uiState.bleState is SonyBleConnectionState.Ready,
                    onSync = { viewModel.syncCameraTime() }
                )
                BentoFtpCard(
                    modifier = Modifier.weight(1f),
                    enabled = uiState.isFtpServerRunning,
                    localIp = uiState.localFtpIp,
                    onToggle = { viewModel.toggleFtpServer(it) }
                )
            }

            // Quick Media Glance Card
            RecentCaptureCard(
                mediaItemsCount = uiState.mediaItems.size,
                latestItemName = uiState.mediaItems.firstOrNull()?.filename,
                latestItemFormat = if (uiState.mediaItems.firstOrNull()?.isHeif == true) "10-bit HEIF" else "RAW/JPEG"
            )
        }

        if (showScanDialog) {
            DeviceScanDialog(
                discoveredCameras = discoveredCameras,
                isScanning = isScanning,
                onDismissRequest = {
                    showScanDialog = false
                    viewModel.stopScan()
                },
                onSelectCamera = { camera ->
                    showScanDialog = false
                    viewModel.connectCamera(camera.address, camera.name)
                },
                onRefreshScan = { sonyOnly -> viewModel.startScan(sonyOnly) }
            )
        }
    }
}

@Composable
private fun DynamicCameraHeroCard(
    pairedName: String?,
    pairedAddress: String?,
    bleState: SonyBleConnectionState,
    onPairNewDevice: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onForgetDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (bleState) {
                        is SonyBleConnectionState.Ready -> CameraActiveGreen
                        is SonyBleConnectionState.Pairing, is SonyBleConnectionState.Connecting, is SonyBleConnectionState.Handshaking -> CameraWarningAmber
                        else -> MaterialTheme.colorScheme.outline
                    }
                    val statusText = when (bleState) {
                        is SonyBleConnectionState.Ready -> "蓝牙已联机 · 正在打卡"
                        is SonyBleConnectionState.Pairing -> "正在配对..."
                        is SonyBleConnectionState.Connecting -> "正在连接..."
                        is SonyBleConnectionState.Handshaking -> "鉴权校准中..."
                        is SonyBleConnectionState.DiscoveringServices -> "发现服务中..."
                        is SonyBleConnectionState.Disconnected -> if (pairedAddress != null) "相机休眠 · 待命重连" else "未配对相机"
                        is SonyBleConnectionState.Error -> "连接异常"
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (pairedAddress != null) {
                    val isConnected = bleState is SonyBleConnectionState.Ready
                    val isConnecting = bleState is SonyBleConnectionState.Connecting ||
                                       bleState is SonyBleConnectionState.DiscoveringServices ||
                                       bleState is SonyBleConnectionState.Handshaking ||
                                       bleState is SonyBleConnectionState.Pairing

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isConnected) {
                            AlphaOutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier,
                                size = AlphaButtonSize.Compact
                            ) {
                                Text("断开连接")
                            }
                        } else if (!isConnecting) {
                            AlphaButton(
                                onClick = onReconnect,
                                modifier = Modifier,
                                size = AlphaButtonSize.Compact
                            ) {
                                Icon(Icons.Rounded.BluetoothConnected, contentDescription = null, modifier = Modifier.size(AlphaButtonSize.Compact.iconSize))
                                Spacer(modifier = Modifier.width(AlphaButtonSize.Compact.iconSpacing))
                                Text("重新连接")
                            }
                        } else {
                            AlphaOutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier,
                                size = AlphaButtonSize.Compact
                            ) {
                                Text("取消")
                            }
                        }

                        AlphaIconButton(onClick = onForgetDevice) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "解除配对",
                                modifier = Modifier.size(AlphaButtonDefaults.IconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pairedName ?: "尚未配对索尼相机",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (pairedAddress != null) "MAC: " + pairedAddress else "支持 A7M4, A7R5, A6700, A7C2, ZV-E1 等机型",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (pairedAddress == null) {
                Spacer(modifier = Modifier.height(16.dp))
                AlphaButton(
                    onClick = onPairNewDevice,
                    modifier = Modifier.fillMaxWidth(),
                    size = AlphaButtonSize.Large
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AlphaButtonDefaults.IconSize))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("搜索并配对索尼相机")
                }
            }
        }
    }
}

@Composable
private fun BentoGpsCard(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    isConnected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = modifier.height(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled && isConnected) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.MyLocation,
                    contentDescription = null,
                    tint = if (enabled && isConnected) CameraActiveGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }

            Column {
                Text(
                    text = "后台 GPS 永续同步",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled && isConnected) "活跃打卡中 (每5秒)" else if (enabled) "服务待命 (等待相机开机)" else "已关闭打卡",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BentoWifiCard(
    modifier: Modifier = Modifier,
    wifiState: CameraWifiState,
    isBleConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = wifiState is CameraWifiState.Connected
    val isConnecting = wifiState is CameraWifiState.Connecting

    Card(
        modifier = modifier.height(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = if (isConnected) "PTP在线" else "Wi-Fi直连",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Column {
                Text(
                    text = if (isConnected) "相机 Wi-Fi 已连" else "相机 Wi-Fi 直连",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                AlphaTonalButton(
                    onClick = if (isConnected) onDisconnect else onConnect,
                    enabled = !isConnecting && (isConnected || isBleConnected),
                    modifier = Modifier.fillMaxWidth(),
                    size = AlphaButtonSize.Compact
                ) {
                    if (isConnecting) {
                        AlphaButtonProgress(size = AlphaButtonSize.Compact)
                    } else {
                        Text(
                            text = if (isConnected) "断开 Wi-Fi" else "一键秒连"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BentoTimeSyncCard(
    modifier: Modifier = Modifier,
    isBleConnected: Boolean,
    onSync: () -> Unit
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                AlphaIconButton(onClick = onSync, enabled = isBleConnected) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "同步",
                        modifier = Modifier.size(AlphaButtonDefaults.IconSize)
                    )
                }
            }
            Column {
                Text(
                    text = "自动时钟校准",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (isBleConnected) "点击同步机身时钟" else "请先连接相机蓝牙",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BentoFtpCard(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    localIp: String?,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }
            Column {
                Text(
                    text = "FTP 边拍边传",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled) (localIp ?: "127.0.0.1") + ":2121" else "端口 2121 待命",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentCaptureCard(
    mediaItemsCount: Int,
    latestItemName: String?,
    latestItemFormat: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = latestItemName ?: "存储卡照片预览",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (mediaItemsCount > 0) "已载入 $mediaItemsCount 张照片 · $latestItemFormat" else "请连接相机 Wi-Fi 载入照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
