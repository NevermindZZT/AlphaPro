package com.letter.alphapro.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class HeifExportMode {
    ORIGINAL_HIF,
    TRANSCODE_JPEG,
    ULTRA_HDR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeifExportBottomSheet(
    selectedCount: Int,
    heifCount: Int,
    onDismissRequest: () -> Unit,
    onStartExport: (HeifExportMode, Int) -> Unit
) {
    var selectedMode by remember { mutableStateOf(HeifExportMode.TRANSCODE_JPEG) }
    var jpegQuality by remember { mutableStateOf(95f) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "HEIF 导出策略选择",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "已选 " + selectedCount.toString() + " 项，其中包含 " + heifCount.toString() + " 张 10-bit 4:2:2 HEIF 照片。由于官方 Creators' App 无法在 Android 导出 HEIF，AlphaPro 提供多核软解与原图双轨支持。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Option 1: Transcode to high quality JPEG
            ExportOptionCard(
                icon = Icons.Rounded.Transform,
                title = "转码为高质量 JPEG (.jpg)",
                subtitle = "使用 libde265 软解，色调映射转换，完整迁移 EXIF (光圈/快门/GPS)，兼容所有社交与系统相册",
                badge = "推荐分享",
                selected = selectedMode == HeifExportMode.TRANSCODE_JPEG,
                onClick = { selectedMode = HeifExportMode.TRANSCODE_JPEG }
            ) {
                if (selectedMode == HeifExportMode.TRANSCODE_JPEG) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("压缩品质", style = MaterialTheme.typography.labelMedium)
                            Text(jpegQuality.toInt().toString() + "% (极佳保真度)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = jpegQuality,
                            onValueChange = { jpegQuality = it },
                            valueRange = 80f..100f,
                            steps = 3
                        )
                    }
                }
            }

            // Option 2: Original HIF file
            ExportOptionCard(
                icon = Icons.Rounded.RawOn,
                title = "原始 HEIF 文件直出 (.hif)",
                subtitle = "保持未压缩的 10-bit 色深与动态范围，直接写入媒体库，适合在 Lightroom Mobile 中精细修图",
                badge = "专业后期",
                selected = selectedMode == HeifExportMode.ORIGINAL_HIF,
                onClick = { selectedMode = HeifExportMode.ORIGINAL_HIF }
            )

            // Option 3: Ultra HDR (Android 14+)
            ExportOptionCard(
                icon = Icons.Rounded.HdrOn,
                title = "Android 14+ Ultra HDR 格式",
                subtitle = "提取 HLG 高动态范围信息并合成 HDR Gainmap 增益图，在 HDR 手机屏幕上呈现超高动态高光",
                badge = "前沿体验",
                selected = selectedMode == HeifExportMode.ULTRA_HDR,
                onClick = { selectedMode = HeifExportMode.ULTRA_HDR }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { onStartExport(selectedMode, jpegQuality.toInt()) },
                    modifier = Modifier.weight(1f)
                ) { Text("开始高速导出") }
            }
        }
    }
}

@Composable
private fun ExportOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(imageVector = icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(text = badge, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
            Box(modifier = Modifier.padding(start = 48.dp)) { content() }
        }
    }
}