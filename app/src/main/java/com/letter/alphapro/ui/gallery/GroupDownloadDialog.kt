package com.letter.alphapro.ui.gallery

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
import com.letter.alphapro.core.ptp.CameraCaptureGroup

enum class GroupDownloadTarget {
    VISUAL_ONLY, // JPG or HIF
    RAW_ONLY,    // ARW
    BOTH         // RAW + Visual
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDownloadDialog(
    group: CameraCaptureGroup,
    onDismissRequest: () -> Unit,
    onConfirmDownload: (target: GroupDownloadTarget, transcodeHeifToJpeg: Boolean) -> Unit
) {
    var selectedTarget by remember { mutableStateOf(GroupDownloadTarget.VISUAL_ONLY) }
    var transcodeHeif by remember { mutableStateOf(true) }

    val visualName = if (group.hasHeif) "10-bit HEIF" else "JPEG"
    val visualSizeMb = String.format("%.1f MB", group.visualFile.fileSize / (1024.0 * 1024.0))
    val rawSizeMb = String.format("%.1f MB", (group.rawFile?.fileSize ?: 0) / (1024.0 * 1024.0))
    val totalSizeMb = String.format("%.1f MB", group.totalSizeMb)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "下载格式选择 (${group.baseName})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "检测到该照片包含 RAW 与 $visualName 双格式，请选择导出内容：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Option 1: Visual only (JPG / HIF)
                DownloadTargetCard(
                    title = "仅下载 $visualName",
                    subtitle = "体积约 $visualSizeMb · 适合社交发图与快速浏览",
                    badge = "推荐分享",
                    icon = Icons.Rounded.Image,
                    selected = selectedTarget == GroupDownloadTarget.VISUAL_ONLY,
                    onClick = { selectedTarget = GroupDownloadTarget.VISUAL_ONLY }
                )

                // Option 2: RAW only
                DownloadTargetCard(
                    title = "仅下载 RAW 原片 (.ARW)",
                    subtitle = "体积约 $rawSizeMb · 适合在电脑/平板进行精细后期调色",
                    badge = "专业后期",
                    icon = Icons.Rounded.RawOn,
                    selected = selectedTarget == GroupDownloadTarget.RAW_ONLY,
                    onClick = { selectedTarget = GroupDownloadTarget.RAW_ONLY }
                )

                // Option 3: Both RAW + Visual
                DownloadTargetCard(
                    title = "同时下载 RAW + $visualName",
                    subtitle = "总大小约 $totalSizeMb · 完整双格式归档到手机相册",
                    badge = "全部保存",
                    icon = Icons.Rounded.FolderZip,
                    selected = selectedTarget == GroupDownloadTarget.BOTH,
                    onClick = { selectedTarget = GroupDownloadTarget.BOTH }
                )

                // If HEIF is included in selection, show transcode toggle
                if (group.hasHeif && selectedTarget != GroupDownloadTarget.RAW_ONLY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "转码为通用 JPEG (95%品质)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "保留全部 EXIF 拍摄参数并兼容所有相册",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = transcodeHeif,
                                onCheckedChange = { transcodeHeif = it }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmDownload(selectedTarget, transcodeHeif) }
            ) {
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DownloadTargetCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
