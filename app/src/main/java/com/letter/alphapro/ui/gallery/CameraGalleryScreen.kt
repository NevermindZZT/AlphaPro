package com.letter.alphapro.ui.gallery

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.ptp.PtpImageMetadata
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
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedHandles = remember { mutableStateListOf<Long>() }
    var showExportSheet by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf(0) } // 0: 全部, 1: HEIF, 2: RAW

    val isWifiConnected = uiState.wifiState is CameraWifiState.Connected
    val isWifiConnecting = uiState.wifiState is CameraWifiState.Connecting

    val filteredPhotos = remember(filterType, uiState.mediaItems) {
        when (filterType) {
            1 -> uiState.mediaItems.filter { it.isHeif }
            2 -> uiState.mediaItems.filter { it.isRaw }
            else -> uiState.mediaItems
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
                            text = "相机存储卡浏览",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isWifiConnected) "已载入 " + uiState.mediaItems.size.toString() + " 项媒体" else "Wi-Fi 未连接",
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
                            if (!isMultiSelectMode) selectedHandles.clear()
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
            if (isMultiSelectMode && selectedHandles.isNotEmpty()) {
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
                                text = "已选中 " + selectedHandles.size.toString() + " 项",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val hasHeif = uiState.mediaItems.any { it.handle in selectedHandles && it.isHeif }
                            Text(
                                text = if (hasHeif) "包含 10-bit HEIF 图像" else "RAW / JPEG 图像",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val selectedPhotos = uiState.mediaItems.filter { it.handle in selectedHandles }
                                selectedPhotos.forEach { photo ->
                                    viewModel.exportPhoto(photo, transcodeToJpeg = false)
                                }
                                isMultiSelectMode = false
                                selectedHandles.clear()
                            }) {
                                Text("原图直出")
                            }
                            Button(onClick = { showExportSheet = true }) {
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
                            text = "浏览机内照片并导出 10-bit HEIF 需要高速 Wi-Fi 通道。点击下方按钮即可由蓝牙自动协商免密直连相机 Wi-Fi。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Button(
                            onClick = { viewModel.connectCameraWifi() },
                            enabled = !isWifiConnecting,
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
            } else if (filteredPhotos.isEmpty()) {
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
                            label = { Text("全部 (" + uiState.mediaItems.size.toString() + ")") }
                        )
                        FilterChip(
                            selected = filterType == 1,
                            onClick = { filterType = 1 },
                            label = { Text("10-bit HEIF (" + uiState.mediaItems.count { it.isHeif }.toString() + ")") }
                        )
                        FilterChip(
                            selected = filterType == 2,
                            onClick = { filterType = 2 },
                            label = { Text("RAW (" + uiState.mediaItems.count { it.isRaw }.toString() + ")") }
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPhotos, key = { it.handle }) { photo ->
                            val isSelected = photo.handle in selectedHandles
                            val progress = uiState.downloadProgressMap[photo.filename]

                            PhotoGridCell(
                                photo = photo,
                                isSelected = isSelected,
                                isSelectMode = isMultiSelectMode,
                                downloadProgress = progress,
                                onClick = {
                                    if (isMultiSelectMode) {
                                        if (isSelected) selectedHandles.remove(photo.handle) else selectedHandles.add(photo.handle)
                                    } else {
                                        // 单击导出该照片
                                        viewModel.exportPhoto(photo, transcodeToJpeg = photo.isHeif)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (showExportSheet) {
                val selectedPhotos = uiState.mediaItems.filter { it.handle in selectedHandles }
                val heifCount = selectedPhotos.count { it.isHeif }
                HeifExportBottomSheet(
                    selectedCount = selectedHandles.size,
                    heifCount = heifCount,
                    onDismissRequest = { showExportSheet = false },
                    onStartExport = { mode, quality ->
                        showExportSheet = false
                        isMultiSelectMode = false
                        val transcode = mode == HeifExportMode.TRANSCODE_JPEG
                        selectedPhotos.forEach { photo ->
                            viewModel.exportPhoto(photo, transcodeToJpeg = transcode, jpegQuality = quality)
                        }
                        selectedHandles.clear()
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoGridCell(
    photo: PtpImageMetadata,
    isSelected: Boolean,
    isSelectMode: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(36.dp)
                )
            }

            Surface(
                modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
                shape = RoundedCornerShape(4.dp),
                color = when {
                    photo.isHeif -> AlphaOrangePrimary
                    photo.isRaw -> AlphaBlueSecondary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = when {
                        photo.isHeif -> "10b HIF"
                        photo.isRaw -> "RAW"
                        else -> "JPG"
                    },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            if (isSelectMode) {
                Box(modifier = Modifier.padding(4.dp).align(Alignment.TopEnd)) {
                    Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                }
            }

            if (downloadProgress != null && downloadProgress < 1f) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = photo.filename,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                        color = Color.White
                    )
                    val sizeMb = String.format("%.1fM", photo.fileSize / (1024.0 * 1024.0))
                    Text(
                        text = sizeMb,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
