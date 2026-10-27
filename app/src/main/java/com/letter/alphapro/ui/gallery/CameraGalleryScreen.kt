package com.letter.alphapro.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import com.letter.alphapro.core.ptp.PtpImageMetadata
import com.letter.alphapro.core.ptp.toCaptureGroups
import com.letter.alphapro.ui.theme.AlphaBlueSecondary
import com.letter.alphapro.ui.theme.AlphaOrangePrimary
import com.letter.alphapro.viewmodel.CameraViewModel
import com.letter.alphapro.wifi.CameraWifiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraGalleryScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedGroupIds = remember { mutableStateListOf<String>() }
    var filterType by remember { mutableStateOf(0) } // 0: 全部, 1: HEIF, 2: RAW

    // Viewer and Dialog states
    var viewingGroup by remember { mutableStateOf<CameraCaptureGroup?>(null) }
    var downloadingGroup by remember { mutableStateOf<CameraCaptureGroup?>(null) }
    var showBatchExportSheet by remember { mutableStateOf(false) }

    val isWifiConnected = uiState.wifiState is CameraWifiState.Connected
    val isWifiConnecting = uiState.wifiState is CameraWifiState.Connecting

    // Group RAW + HIF/JPEG by base capture name
    val captureGroups = remember(uiState.mediaItems) {
        uiState.mediaItems.toCaptureGroups()
    }

    val filteredGroups = remember(filterType, captureGroups) {
        when (filterType) {
            1 -> captureGroups.filter { it.hasHeif }
            2 -> captureGroups.filter { it.hasRaw }
            else -> captureGroups
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "相机存储卡浏览",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isWifiConnected) "已载入 " + captureGroups.size.toString() + " 组照片 (" + uiState.mediaItems.size.toString() + " 个文件)" else "Wi-Fi 未连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isWifiConnected) {
                        IconButton(onClick = { viewModel.loadCameraMedia() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) selectedGroupIds.clear()
                        }) {
                            Icon(
                                imageVector = if (isMultiSelectMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                                contentDescription = "多选"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isMultiSelectMode && selectedGroupIds.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "已选中 " + selectedGroupIds.size.toString() + " 组照片",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val selectedGroups = captureGroups.filter { it.baseName in selectedGroupIds }
                            val dualCount = selectedGroups.count { it.isDualFormat }
                            Text(
                                text = if (dualCount > 0) "包含 " + dualCount.toString() + " 组 RAW+渲染图" else "单格式图像",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val selected = captureGroups.filter { it.baseName in selectedGroupIds }
                                selected.forEach { group ->
                                    group.files.forEach { file ->
                                        viewModel.exportPhoto(file, transcodeToJpeg = false)
                                    }
                                }
                                isMultiSelectMode = false
                                selectedGroupIds.clear()
                            }) {
                                Text("原图直出")
                            }
                            Button(onClick = { showBatchExportSheet = true }) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("智能导出")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isWifiConnected) {
                // Not connected prompt
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.WifiOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = "尚未连接相机 Wi-Fi",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "浏览机内照片并导出 10-bit HEIF / RAW 需要高速 Wi-Fi 通道。点击下方按钮即可由蓝牙免密直连相机 Wi-Fi。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Button(
                            onClick = { viewModel.connectCameraWifi() },
                            enabled = !isWifiConnecting && uiState.bleState is com.letter.alphapro.core.bluetooth.SonyBleConnectionState.Ready,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isWifiConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在免密协商连接...")
                            } else {
                                Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("一键直连相机 Wi-Fi")
                            }
                        }
                    }
                }
            } else if (uiState.isMediaLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "正在通过 PTP/IP 读取存储卡照片...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (filteredGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "相机存储卡内无匹配照片", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Filter Chips
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterType == 0,
                            onClick = { filterType = 0 },
                            label = { Text("全部 (" + captureGroups.size.toString() + ")") }
                        )
                        FilterChip(
                            selected = filterType == 1,
                            onClick = { filterType = 1 },
                            label = { Text("包含 HEIF (" + captureGroups.count { it.hasHeif }.toString() + ")") }
                        )
                        FilterChip(
                            selected = filterType == 2,
                            onClick = { filterType = 2 },
                            label = { Text("包含 RAW (" + captureGroups.count { it.hasRaw }.toString() + ")") }
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredGroups, key = { it.baseName }) { group ->
                            val isSelected = group.baseName in selectedGroupIds
                            val visualHandle = group.visualFile.handle
                            val progress = uiState.downloadProgressMap[group.visualFile.filename]
                                ?: (group.rawFile?.let { uiState.downloadProgressMap[it.filename] })

                            PhotoGroupGridCell(
                                group = group,
                                thumbnail = thumbnails[visualHandle],
                                isSelected = isSelected,
                                isSelectMode = isMultiSelectMode,
                                downloadProgress = progress,
                                onRequestThumbnail = { viewModel.requestThumbnail(group.visualFile) },
                                onClick = {
                                    if (isMultiSelectMode) {
                                        if (isSelected) selectedGroupIds.remove(group.baseName) else selectedGroupIds.add(group.baseName)
                                    } else {
                                        // 点击只查看大图，不触发下载！
                                        viewingGroup = group
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Full Screen Photo Detail Viewer Dialog
            viewingGroup?.let { group ->
                PhotoDetailViewerDialog(
                    group = group,
                    thumbnail = thumbnails[group.visualFile.handle],
                    onDismissRequest = { viewingGroup = null },
                    onDownloadClick = { targetGroup ->
                        viewingGroup = null
                        if (targetGroup.isDualFormat) {
                            downloadingGroup = targetGroup
                        } else {
                            if (targetGroup.hasHeif) {
                                showBatchExportSheet = true
                                selectedGroupIds.clear()
                                selectedGroupIds.add(targetGroup.baseName)
                            } else {
                                viewModel.exportPhoto(targetGroup.visualFile, transcodeToJpeg = false)
                            }
                        }
                    }
                )
            }

            // Dual Format Download Choice Dialog (RAW / HIF / Both)
            downloadingGroup?.let { group ->
                GroupDownloadDialog(
                    group = group,
                    onDismissRequest = { downloadingGroup = null },
                    onConfirmDownload = { target, transcodeHeif ->
                        downloadingGroup = null
                        viewModel.exportCaptureGroup(group, target, transcodeHeifToJpeg = transcodeHeif)
                    }
                )
            }

            // Batch / HEIF Export Bottom Sheet
            if (showBatchExportSheet) {
                val selectedGroups = captureGroups.filter { it.baseName in selectedGroupIds }
                val heifCount = selectedGroups.count { it.hasHeif }
                HeifExportBottomSheet(
                    selectedCount = selectedGroups.size,
                    heifCount = heifCount,
                    onDismissRequest = { showBatchExportSheet = false },
                    onStartExport = { mode, quality ->
                        showBatchExportSheet = false
                        isMultiSelectMode = false
                        val transcode = mode == HeifExportMode.TRANSCODE_JPEG
                        selectedGroups.forEach { group ->
                            if (group.isDualFormat) {
                                // Default export visual image with requested transcode
                                viewModel.exportPhoto(group.visualFile, transcodeToJpeg = transcode && group.hasHeif, jpegQuality = quality)
                            } else {
                                viewModel.exportPhoto(group.visualFile, transcodeToJpeg = transcode && group.hasHeif, jpegQuality = quality)
                            }
                        }
                        selectedGroupIds.clear()
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoGroupGridCell(
    group: CameraCaptureGroup,
    thumbnail: android.graphics.Bitmap?,
    isSelected: Boolean,
    isSelectMode: Boolean,
    downloadProgress: Float?,
    onRequestThumbnail: () -> Unit,
    onClick: () -> Unit
) {
    LaunchedEffect(group.visualFile.handle) {
        if (thumbnail == null) {
            onRequestThumbnail()
        }
    }

    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = group.baseName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Top Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        group.hasRaw && group.hasHeif -> AlphaOrangePrimary
                        group.hasRaw -> AlphaBlueSecondary
                        group.hasHeif -> AlphaOrangePrimary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = group.badgeLabel,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                if (isSelectMode) {
                    FilledIconButton(
                        onClick = onClick,
                        modifier = Modifier.size(24.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }

            // Bottom Filename Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Text(
                    text = group.baseName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color.White
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            // Download Progress overlay
            if (downloadProgress != null && downloadProgress < 1f) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = (downloadProgress * 100).toInt().toString() + "%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
