package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import com.letter.alphapro.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min

/** A full-size Activity-root destination, never a Dialog or nested Scaffold. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoDetailViewerScreen(
    photos: List<CameraCaptureGroup>,
    initialIndex: Int,
    preview: PhotoPreviewState,
    selectedIds: SnapshotStateList<String>,
    onToggleSelect: (CameraCaptureGroup) -> Unit,
    onIndexChange: (Int) -> Unit,
    getThumbnail: (Long) -> Bitmap?,
    onRetry: () -> Unit,
    onDismissRequest: () -> Unit,
    onDownloadClick: (CameraCaptureGroup) -> Unit,
    onBatchDownloadClick: (List<CameraCaptureGroup>) -> Unit
) {
    if (photos.isEmpty()) return
    val paneLabel = stringResource(R.string.gallery_viewer_title)
    val safeInitial = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { photos.size })

    // initialIndex is an entry seed, not a second controller. Feeding VM echoes back into
    // scrollToPage while a drag/fling is active cancels or reverses the user's swipe.
    val latestOnIndexChange by rememberUpdatedState(onIndexChange)
    LaunchedEffect(pagerState) {
        // The host already selected/loaded the entry page before opening this screen.
        var lastReportedPage = pagerState.settledPage
        snapshotFlow {
            pagerState.settledPage.takeUnless { pagerState.isScrollInProgress }
        }.filterNotNull().distinctUntilChanged().collect { page ->
            if (page != lastReportedPage) {
                lastReportedPage = page
                latestOnIndexChange(page)
            }
        }
    }

    val settledIndex = pagerState.settledPage.coerceIn(0, photos.lastIndex)
    val currentGroup = photos[settledIndex]
    // Each page owns its viewport and transform. Offscreen page measurement and preview
    // replacement must not restart another page's in-flight pointer coroutine.
    val transforms = remember { mutableMapOf<String, PhotoViewerTransformState>() }
    val activeTransform = transforms.getOrPut(currentGroup.id) { PhotoViewerTransformState() }
    LaunchedEffect(currentGroup.id) { activeTransform.reset() }
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val captureDate = currentGroup.captureDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).format(dateFormatter) }
    val sizeLabel = gallerySizeLabel(currentGroup.totalSizeMb)

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .semantics { paneTitle = paneLabel }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {})
            }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val metadataLimit = minOf(144.dp, maxHeight * 0.25f)
            Column(Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.gallery_viewer_photo_title, currentGroup.baseName, settledIndex + 1, photos.size),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = Color.White, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (activeTransform.isZoomed) {
                        AlphaIconButton(onClick = activeTransform::reset) {
                            Icon(Icons.Rounded.ZoomOutMap, stringResource(R.string.gallery_reset_zoom), tint = Color.White)
                        }
                    }
                    val isSelected = currentGroup.id in selectedIds
                    AlphaIconButton(onClick = { onToggleSelect(currentGroup) }) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = stringResource(if (isSelected) R.string.gallery_deselect_photo else R.string.gallery_select_photo, currentGroup.baseName),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    AlphaIconButton(onClick = onDismissRequest) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.gallery_close), tint = Color.White)
                    }
                }

                // Middle: HorizontalPager for smooth swiping between photos
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("gallery_viewer_pager"),
                    key = { photos[it].id },
                    userScrollEnabled = !activeTransform.isZoomed && !activeTransform.ownsGesture
                ) { pageIndex ->
                    val pageGroup = photos[pageIndex]
                    val pageHandle = pageGroup.visualFile.handle
                    val isCurrent = pageIndex == settledIndex
                    val currentPreview = if (isCurrent) preview.takeIf { it.handle == pageHandle } else null
                    val large = currentPreview?.bitmap
                    val thumbnail = getThumbnail(pageHandle)
                    val bitmap = large ?: thumbnail
                    val transform = transforms.getOrPut(pageGroup.id) { PhotoViewerTransformState() }
                    // Preview decoding and layout can change during a gesture. Read the latest
                    // bitmap/viewport without cancelling the coroutine or capturing isCurrent.
                    val latestBitmap by rememberUpdatedState(bitmap)
                    val visibleOffset = transform.clamp(transform.offset, transform.scale, bitmap)

                    Box(
                        Modifier.fillMaxSize().clipToBounds()
                            .onSizeChanged { transform.viewport = it }
                            .pointerInput(pageGroup.id, transform) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val policy = PhotoViewerGesturePolicy()
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val pressed = event.changes.count { it.pressed }
                                            if (policy.shouldConsume(pressed, transform.isZoomed)) {
                                                transform.ownsGesture = true
                                                if (latestBitmap != null && pressed > 0) {
                                                    val centroid = event.calculateCentroid(useCurrent = false)
                                                    // Pointer-up/transition events can have no shared centroid.
                                                    // Check before arithmetic: Unspecified.x itself throws.
                                                    if (centroid.isSpecified) {
                                                        val zoom = event.calculateZoom()
                                                        val next = (transform.scale * zoom).coerceIn(1f, 5f)
                                                        val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                                                        if (focus.x.isFinite() && focus.y.isFinite()) {
                                                            transform.offset = transform.clamp(
                                                                (transform.clamp(transform.offset, transform.scale, latestBitmap) - focus) *
                                                                    (next / transform.scale) + focus + event.calculatePan(),
                                                                next, latestBitmap
                                                            )
                                                        }
                                                        transform.scale = next
                                                    }
                                                }
                                                // Initial pass wins before the pager's Main pass. Never
                                                // consume a fit-scale one-finger sequence, even at slop.
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        transform.ownsGesture = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                remember(bitmap) { bitmap.asImageBitmap() },
                                stringResource(R.string.gallery_photo_description, pageGroup.baseName),
                                Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = transform.scale
                                    scaleY = transform.scale
                                    translationX = visibleOffset.x
                                    translationY = visibleOffset.y
                                },
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                stringResource(if (currentPreview?.isLoading == true) R.string.gallery_fetching_preview else R.string.gallery_no_preview),
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                // Bottom metadata and actions
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentPreview = preview.takeIf { it.handle == currentGroup.visualFile.handle }
                    val large = currentPreview?.bitmap
                    val thumb = getThumbnail(currentGroup.visualFile.handle)
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = metadataLimit)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            when {
                                large != null -> currentPreview?.sourceLabel?.asString() ?: stringResource(R.string.gallery_camera_preview_dimensions, large.width, large.height)
                                thumb != null -> stringResource(R.string.gallery_thumbnail_dimensions, thumb.width, thumb.height)
                                else -> stringResource(R.string.gallery_preview_not_loaded)
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentPreview?.isLoading == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Text(stringResource(R.string.gallery_loading), Modifier.padding(start = 8.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (currentPreview?.error != null || (currentPreview?.isLoading != true && large == null)) {
                            Text(currentPreview?.error?.asString() ?: stringResource(R.string.gallery_preview_unavailable), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                            AlphaTextButton(
                                onClick = onRetry, enabled = currentPreview?.isLoading != true,
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.38f))
                            ) { Text(stringResource(R.string.gallery_retry_preview)) }
                        }
                        Text(
                            if (captureDate != null) stringResource(R.string.gallery_format_date, currentGroup.badgeLabel, captureDate) else currentGroup.badgeLabel,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(if (currentGroup.isDualFormat) R.string.gallery_dual_size else R.string.gallery_size, sizeLabel),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Download Actions
                    val selectedCount = selectedIds.size
                    if (selectedCount > 0) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlphaOutlinedButton(
                                onClick = { onDownloadClick(currentGroup) },
                                modifier = Modifier.weight(1f),
                                size = AlphaButtonSize.Large,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(AlphaButtonDefaults.IconSize))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gallery_download_current), maxLines = 1)
                            }
                            AlphaButton(
                                onClick = {
                                    val chosen = photos.filter { it.id in selectedIds }
                                    onBatchDownloadClick(chosen)
                                },
                                modifier = Modifier.weight(1.3f),
                                size = AlphaButtonSize.Large
                            ) {
                                Icon(Icons.Rounded.DownloadDone, null, Modifier.size(AlphaButtonDefaults.IconSize))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gallery_download_selected, selectedCount), maxLines = 1)
                            }
                        }
                    } else {
                        AlphaButton(
                            onClick = { onDownloadClick(currentGroup) },
                            modifier = Modifier.fillMaxWidth(),
                            size = AlphaButtonSize.Large
                        ) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(AlphaButtonDefaults.IconSize))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (currentGroup.isDualFormat) R.string.gallery_download_export else R.string.gallery_export_photo))
                        }
                    }
                }
            }
        }
    }
}

@Stable
private class PhotoViewerTransformState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)
    var ownsGesture by mutableStateOf(false)
    val isZoomed: Boolean get() = scale > 1f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun clamp(value: Offset, zoom: Float, bitmap: Bitmap?): Offset {
        if (bitmap == null || viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val x = ((bitmap.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((bitmap.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
}
