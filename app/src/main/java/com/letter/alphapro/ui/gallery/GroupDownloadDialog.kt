package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.letter.alphapro.R
import java.text.NumberFormat
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
    defaultTranscodeHeif: Boolean = true,
    onDismissRequest: () -> Unit,
    onConfirmDownload: (target: GroupDownloadTarget, transcodeHeifToJpeg: Boolean) -> Unit
) {
    var selectedTarget by remember { mutableStateOf(GroupDownloadTarget.VISUAL_ONLY) }
    var transcodeHeif by remember { mutableStateOf(defaultTranscodeHeif && com.letter.alphapro.core.heif.HeifDecoder.isAvailable) }

    val visualName = stringResource(if (group.hasHeif) R.string.gallery_format_heif_10bit else R.string.gallery_format_jpeg)
    val visualSizeMb = gallerySizeLabel(group.visualFile.fileSize / (1024.0 * 1024.0))
    val rawSizeMb = gallerySizeLabel((group.rawFile?.fileSize ?: 0) / (1024.0 * 1024.0))
    val totalSizeMb = gallerySizeLabel(group.totalSizeMb)
    val locale = LocalConfiguration.current.locales[0]
    val quality = remember(locale) { NumberFormat.getPercentInstance(locale).format(0.95) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .widthIn(max = 440.dp),
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
                text = stringResource(R.string.gallery_download_format_title, group.baseName),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.gallery_download_format_description, visualName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Option 1: Visual only (JPG / HIF)
                DownloadTargetCard(
                    title = stringResource(R.string.gallery_download_visual_only, visualName),
                    subtitle = stringResource(R.string.gallery_download_visual_description, visualSizeMb),
                    badge = stringResource(R.string.gallery_badge_share),
                    icon = Icons.Rounded.Image,
                    selected = selectedTarget == GroupDownloadTarget.VISUAL_ONLY,
                    onClick = { selectedTarget = GroupDownloadTarget.VISUAL_ONLY }
                )

                // Option 2: RAW only
                DownloadTargetCard(
                    title = stringResource(R.string.gallery_download_raw_only),
                    subtitle = stringResource(R.string.gallery_download_raw_description, rawSizeMb),
                    badge = stringResource(R.string.gallery_badge_edit),
                    icon = Icons.Rounded.RawOn,
                    selected = selectedTarget == GroupDownloadTarget.RAW_ONLY,
                    onClick = { selectedTarget = GroupDownloadTarget.RAW_ONLY }
                )

                // Option 3: Both RAW + Visual
                DownloadTargetCard(
                    title = stringResource(R.string.gallery_download_both, visualName),
                    subtitle = stringResource(R.string.gallery_download_both_description, totalSizeMb),
                    badge = stringResource(R.string.gallery_badge_save_all),
                    icon = Icons.Rounded.FolderZip,
                    selected = selectedTarget == GroupDownloadTarget.BOTH,
                    onClick = { selectedTarget = GroupDownloadTarget.BOTH }
                )

                // If HEIF is included in selection, show transcode toggle
                if (group.hasHeif && selectedTarget != GroupDownloadTarget.RAW_ONLY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.gallery_transcode_quality, quality),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.gallery_transcode_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
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
            AlphaButton(
                onClick = { onConfirmDownload(selectedTarget, transcodeHeif) }
            ) {
                Text(stringResource(R.string.gallery_start_download))
            }
        },
        dismissButton = {
            AlphaTextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.gallery_cancel))
            }
        }
    )
}

/** Format display values with the app configuration, not the device default locale. */
@Composable
internal fun gallerySizeLabel(sizeMb: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    return stringResource(R.string.gallery_size_mb, numberFormat.format(sizeMb))
}

@OptIn(ExperimentalMaterial3Api::class)
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    modifier = Modifier.size(20.dp),
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                ),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
