package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import kotlin.math.min

/** A full-size Activity-root destination, never a Dialog or nested Scaffold. */
@Composable
fun PhotoDetailViewerScreen(
    group: CameraCaptureGroup,
    thumbnail: Bitmap?,
    preview: PhotoPreviewState,
    onRetry: () -> Unit,
    onDismissRequest: () -> Unit,
    onDownloadClick: (CameraCaptureGroup) -> Unit
) {
    val handle = group.visualFile.handle
    val current = preview.takeIf { it.handle == handle }
    val large = current?.bitmap
    val bitmap = large ?: thumbnail
    var scale by remember(handle) { mutableFloatStateOf(1f) }
    var offset by remember(handle) { mutableStateOf(Offset.Zero) }
    var viewport by remember(handle) { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .semantics { paneTitle = "照片查看器" }
            .pointerInput(Unit) {
                // Standard nested gesture handling: block background taps without consuming
                // motion events that belong to child buttons or the pinch/drag viewport.
                detectTapGestures(onTap = {})
            }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val metadataLimit = minOf(144.dp, maxHeight * 0.25f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(group.baseName, Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = Color.White, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (scale > 1f) {
                        AlphaIconButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                            Icon(Icons.Rounded.ZoomOutMap, "还原缩放", tint = Color.White)
                        }
                    }
                    AlphaIconButton(onClick = onDismissRequest) {
                        Icon(Icons.Rounded.Close, "关闭", tint = Color.White)
                    }
                }
                val fit = if (bitmap != null && viewport.width > 0 && viewport.height > 0) {
                    min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
                } else 0f
                val fittedWidth = (bitmap?.width ?: 0) * fit
                val fittedHeight = (bitmap?.height ?: 0) * fit
                fun clamp(value: Offset, zoom: Float): Offset {
                    val x = ((fittedWidth * zoom - viewport.width) / 2).coerceAtLeast(0f)
                    val y = ((fittedHeight * zoom - viewport.height) / 2).coerceAtLeast(0f)
                    return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
                }
                val visibleOffset = clamp(offset, scale)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()
                    .onSizeChanged { viewport = it }
                    .pointerInput(handle, bitmap, viewport) {
                        if (bitmap != null) detectTransformGestures { centroid, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((clamp(offset, scale) - focus) * (next / scale) + focus + pan, next)
                            scale = next
                        }
                    }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(remember(bitmap) { bitmap.asImageBitmap() }, group.baseName,
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = visibleOffset.x; translationY = visibleOffset.y
                            }, contentScale = ContentScale.Fit)
                    } else Text(if (current?.isLoading == true) "正在获取预览…" else "暂无可用预览",
                        color = Color.White.copy(alpha = 0.75f))
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Only metadata scrolls. The export button is always outside this scroller.
                    Column(Modifier.fillMaxWidth().heightIn(max = metadataLimit)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when {
                            large != null -> current?.sourceLabel ?: "相机预览 · "+large.width+" × "+large.height
                            thumbnail != null -> "低分辨率缩略图 · "+thumbnail.width+" × "+thumbnail.height
                            else -> "尚未加载预览"
                        }, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        if (current?.isLoading == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Text("正在载入…", Modifier.padding(start = 8.dp), color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (current?.error != null || (current?.isLoading != true && large == null)) {
                            Text(current?.error ?: "相机预览不可用", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                            AlphaTextButton(onClick = onRetry, enabled = current?.isLoading != true,
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.38f))) { Text("重试预览") }
                        }
                        Text(group.badgeLabel + group.captureDate.takeIf { it.isNotBlank() }?.let { " · "+it }.orEmpty(),
                            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text((if (group.isDualFormat) "双格式总大小: " else "大小: ")+String.format("%.1f MB", group.totalSizeMb),
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                    AlphaButton(onClick = { onDownloadClick(group) }, Modifier.fillMaxWidth(), size = AlphaButtonSize.Large) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(AlphaButtonDefaults.IconSize))
                        Spacer(Modifier.width(8.dp))
                        Text(if (group.isDualFormat) "下载 / 导出" else "导出照片")
                    }
                }
            }
        }
    }
}
