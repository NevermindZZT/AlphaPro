package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.letter.alphapro.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
    val selected = viewModel.selectedGroupIds
    var exportSheet by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val connected = state.wifiState is CameraWifiState.Connected
    val connecting = state.wifiState is CameraWifiState.Connecting
    val groups = remember(state.mediaItems) { state.mediaItems.toCaptureGroups() }
    val filtered = remember(groups, filter) { groups.filter { filter == 0 || (filter == 1 && it.hasHeif) || (filter == 2 && it.hasRaw) } }
    val sections = remember(filtered) { filtered.toDateSections() }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.gallery_title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (connected) pluralStringResource(R.plurals.gallery_loaded_groups, groups.size, groups.size,
                        pluralStringResource(R.plurals.gallery_file_count, state.mediaItems.size, state.mediaItems.size)) else stringResource(R.string.gallery_wifi_disconnected),
                        maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                if (connected) {
                    AlphaIconButton(onClick = { viewModel.loadCameraMedia() }, enabled = !state.isMediaLoading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.gallery_refresh)) }
                    AlphaIconButton(onClick = { selecting = !selecting; if (!selecting) selected.clear() }) {
                        Icon(if (selecting) Icons.Rounded.Close else Icons.Rounded.Checklist, stringResource(if (selecting) R.string.gallery_exit_selection else R.string.gallery_select_multiple))
                    }
                }
            })
        },
        bottomBar = {
            if (selecting && selected.isNotEmpty()) {
                Surface(tonalElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pluralStringResource(R.plurals.gallery_selected_groups, selected.size, selected.size), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlphaOutlinedButton(onClick = {
                                groups.filter { it.id in selected }.flatMap { it.files }.forEach { viewModel.exportPhoto(it, false) }
                                selected.clear(); selecting = false
                            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.gallery_export_originals)) }
                            AlphaButton(onClick = { exportSheet = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.gallery_smart_export)) }
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
                    val labels = listOf(
                        stringResource(R.string.gallery_filter_all, groups.size),
                        stringResource(R.string.gallery_filter_heif, groups.count { it.hasHeif }),
                        stringResource(R.string.gallery_filter_raw, groups.count { it.hasRaw })
                    )
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
                        Text(stringResource(R.string.gallery_camera_wifi_not_connected), style = MaterialTheme.typography.titleMedium)
                        AlphaButton(onClick = viewModel::connectCameraWifi, size = AlphaButtonSize.Large,
                            enabled = !connecting && state.bleState is SonyBleConnectionState.Ready) {
                            Text(stringResource(if (connecting) R.string.gallery_connecting else R.string.gallery_connect_wifi))
                        }
                    }
                }
                state.isMediaLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(40.dp))
                        Text(stringResource(if (groups.isEmpty()) R.string.gallery_empty_card else R.string.gallery_empty_filter))
                        if (filter != 0) AlphaTextButton(onClick = { filter = 0 }) { Text(stringResource(R.string.gallery_show_all)) }
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
                                    Text(if (section.key == CameraPhotoDate.UNKNOWN_KEY) stringResource(R.string.gallery_unknown_date)
                                        else LocalDate.parse(section.key).format(dateFormatter),
                                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(pluralStringResource(R.plurals.gallery_photo_count, section.captures.size, section.captures.size), style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                        stringResource(if (collapsed) R.string.gallery_expand_date else R.string.gallery_collapse_date))
                                }
                            }
                        }
                        if (!collapsed) items(section.captures, key = { it.id }) { group ->
                            val file = group.visualFile
                            val isMultiSelect = selecting || selected.isNotEmpty()
                            PhotoGroupGridCell(group, thumbnails[file.handle], errors[file.handle]?.asString(), isMultiSelect,
                                group.id in selected, state.downloadProgressMap[file.filename],
                                onRequest = { viewModel.requestThumbnail(file, group.rawFile) },
                                onRelease = { viewModel.releaseThumbnailRequest(file.handle) },
                                onRetry = { viewModel.retryThumbnail(file, group.rawFile) },
                                onClick = {
                                    if (isMultiSelect) {
                                        if (group.id in selected) selected.remove(group.id) else selected.add(group.id)
                                        if (selected.isEmpty()) selecting = false
                                    } else {
                                        viewModel.openPhoto(group, filtered)
                                    }
                                },
                                onLongClick = {
                                    selecting = true
                                    if (group.id !in selected) selected.add(group.id)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGroupGridCell(
    group: CameraCaptureGroup, thumbnail: Bitmap?, error: String?, selecting: Boolean, selected: Boolean,
    progress: Float?, onRequest: () -> Unit, onRelease: () -> Unit, onRetry: () -> Unit, onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    var showError by remember(group.id, error) { mutableStateOf(false) }
    if (showError && error != null) AlertDialog(
        onDismissRequest = { showError = false },
        title = { Text(stringResource(R.string.gallery_thumbnail_error_title, group.baseName)) },
        text = {
            SelectionContainer {
                Text(error, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            AlphaTextButton(onClick = { showError = false }) { Text(stringResource(R.string.gallery_close)) }
        }
    )
    LaunchedEffect(group.visualFile.handle, thumbnail == null, error) {
        if (thumbnail == null && error == null) onRequest()
    }
    DisposableEffect(group.visualFile.handle) { onDispose { onRelease() } }
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(Modifier.fillMaxSize()) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), stringResource(R.string.gallery_photo_description, group.baseName), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else {
                    AlphaIconButton(onClick = { showError = true }) {
                        Icon(Icons.Rounded.BrokenImage, stringResource(R.string.gallery_thumbnail_error_details),
                            Modifier.size(28.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    AlphaTextButton(onClick = onRetry) { Text(stringResource(R.string.gallery_retry_thumbnail), style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (!selecting) Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary) {
                Text(group.badgeLabel, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), maxLines = 1)
            }
            if (selecting) AlphaFilledIconButton(onClick, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, stringResource(if (selected) R.string.gallery_deselect_photo else R.string.gallery_select_photo, group.baseName), Modifier.size(AlphaButtonDefaults.IconSize))
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
