package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import com.letter.alphapro.core.ptp.CameraPhotoDate
import com.letter.alphapro.core.ptp.toCaptureGroups
import com.letter.alphapro.core.ptp.toDateSections
import com.letter.alphapro.viewmodel.CameraViewModel
import com.letter.alphapro.wifi.CameraWifiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraGalleryScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val errors by viewModel.thumbnailErrors.collectAsState()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var collapsedDates by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selected = remember { mutableStateListOf<String>() }
    var exportSheet by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val connected = state.wifiState is CameraWifiState.Connected
    val connecting = state.wifiState is CameraWifiState.Connecting
    val groups = remember(state.mediaItems) { state.mediaItems.toCaptureGroups() }
    val filtered = remember(groups, filter) { groups.filter { filter == 0 || (filter == 1 && it.hasHeif) || (filter == 2 && it.hasRaw) } }
    val sections = remember(filtered) { filtered.toDateSections() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("相机存储卡浏览", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (connected) "已载入 "+groups.size+" 组照片（"+state.mediaItems.size+" 个文件）" else "Wi-Fi 未连接",
                        maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                if (connected) {
                    AlphaIconButton(onClick = { viewModel.loadCameraMedia() }, enabled = !state.isMediaLoading) { Icon(Icons.Rounded.Refresh, "刷新") }
                    AlphaIconButton(onClick = { selecting = !selecting; if (!selecting) selected.clear() }) {
                        Icon(if (selecting) Icons.Rounded.Close else Icons.Rounded.Checklist, "多选")
                    }
                }
            })
        },
        bottomBar = {
            if (selecting && selected.isNotEmpty()) {
                Surface(tonalElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选择 "+selected.size+" 组照片", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlphaOutlinedButton(onClick = {
                                groups.filter { it.id in selected }.flatMap { it.files }.forEach { viewModel.exportPhoto(it, false) }
                                selected.clear(); selecting = false
                            }, modifier = Modifier.weight(1f)) { Text("原图直出") }
                            AlphaButton(onClick = { exportSheet = true }, modifier = Modifier.weight(1f)) { Text("智能导出") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (connected) {
                // Filters are outside the filtered-content branch, including zero-result/loading states.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf("全部 ("+groups.size+")", "HEIF ("+groups.count { it.hasHeif }+")", "RAW ("+groups.count { it.hasRaw }+")")
                    labels.forEachIndexed { index, label ->
                        AlphaFilterChip(selected = filter == index, onClick = { filter = index },
                            label = { Text(label, maxLines = 1, softWrap = false) })
                    }
                }
            }
            when {
                !connected -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("尚未连接相机 Wi-Fi", style = MaterialTheme.typography.titleMedium)
                        AlphaButton(onClick = viewModel::connectCameraWifi, size = AlphaButtonSize.Large,
                            enabled = !connecting && state.bleState is SonyBleConnectionState.Ready) {
                            Text(if (connecting) "正在连接…" else "一键直连相机 Wi-Fi")
                        }
                    }
                }
                state.isMediaLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(40.dp))
                        Text(if (groups.isEmpty()) "存储卡内暂无照片" else "没有符合当前筛选的照片")
                        if (filter != 0) AlphaTextButton(onClick = { filter = 0 }) { Text("显示全部照片") }
                    }
                }
                else -> LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEach { section ->
                        val collapsed = section.key in collapsedDates
                        item(key = "date:"+section.key, span = { GridItemSpan(maxLineSpan) }) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    collapsedDates = if (collapsed) collapsedDates - section.key else collapsedDates + section.key
                                }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (section.key == CameraPhotoDate.UNKNOWN_KEY) "日期未知" else section.title,
                                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(section.captures.size.toString()+" 张", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                        if (collapsed) "展开日期分组" else "折叠日期分组")
                                }
                            }
                        }
                        if (!collapsed) items(section.captures, key = { it.id }) { group ->
                            val file = group.visualFile
                            PhotoGroupGridCell(group, thumbnails[file.handle], errors[file.handle], selecting,
                                group.id in selected, state.downloadProgressMap[file.filename],
                                onRequest = { viewModel.requestThumbnail(file) },
                                onRelease = { viewModel.releaseThumbnailRequest(file.handle) },
                                onRetry = { viewModel.retryThumbnail(file) },
                                onClick = {
                                    if (selecting) { if (group.id in selected) selected.remove(group.id) else selected.add(group.id) }
                                    else viewModel.openPhoto(group)
                                })
                        }
                    }
                }
            }
        }
    }
    if (exportSheet) {
        val chosen = groups.filter { it.id in selected }
        HeifExportBottomSheet(chosen.size, chosen.count { it.hasHeif }, { exportSheet = false }) { mode, quality ->
            exportSheet = false; selecting = false
            chosen.forEach { viewModel.exportPhoto(it.visualFile, mode == HeifExportMode.TRANSCODE_JPEG && it.hasHeif, quality) }
            selected.clear()
        }
    }
}

@Composable
private fun PhotoGroupGridCell(
    group: CameraCaptureGroup, thumbnail: Bitmap?, error: String?, selecting: Boolean, selected: Boolean,
    progress: Float?, onRequest: () -> Unit, onRelease: () -> Unit, onRetry: () -> Unit, onClick: () -> Unit
) {
    LaunchedEffect(group.visualFile.handle, thumbnail == null, error) {
        if (thumbnail == null && error == null) onRequest()
    }
    DisposableEffect(group.visualFile.handle) { onDispose { onRelease() } }
    Surface(Modifier.aspectRatio(1f).clickable(onClick = onClick), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.fillMaxSize()) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), group.baseName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else {
                    Icon(Icons.Rounded.BrokenImage, error, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.outline)
                    AlphaTextButton(onClick = onRetry) { Text("重试缩略图", style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (!selecting) Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary) {
                Text(group.badgeLabel, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), maxLines = 1)
            }
            if (selecting) AlphaFilledIconButton(onClick, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "选择照片", Modifier.size(AlphaButtonDefaults.IconSize))
            }
            Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = Color.Black.copy(alpha = 0.55f)) {
                Text(group.baseName, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
            }
            if (progress != null && progress < 1f) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, Modifier.size(32.dp))
                }
            }
        }
    }
}
