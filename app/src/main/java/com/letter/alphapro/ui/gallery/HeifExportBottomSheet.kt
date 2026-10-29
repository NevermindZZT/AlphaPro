package com.letter.alphapro.ui.gallery

import com.letter.alphapro.ui.components.*
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.letter.alphapro.R
import java.text.NumberFormat

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
    val decoderAvailable = remember { com.letter.alphapro.core.heif.HeifDecoder.isAvailable }
    var selectedMode by remember { mutableStateOf(if (decoderAvailable) HeifExportMode.TRANSCODE_JPEG else HeifExportMode.ORIGINAL_HIF) }
    var jpegQuality by remember { mutableStateOf(95f) }
    val locale = LocalConfiguration.current.locales[0]
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }

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
                text = stringResource(R.string.gallery_heif_export_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = stringResource(R.string.gallery_heif_export_description,
                    pluralStringResource(R.plurals.gallery_selected_items, selectedCount, selectedCount),
                    pluralStringResource(R.plurals.gallery_heif_photos, heifCount, heifCount)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Option 1: Transcode to high quality JPEG
            ExportOptionCard(
                icon = Icons.Rounded.Transform,
                title = stringResource(R.string.gallery_export_jpeg_title),
                subtitle = stringResource(if (decoderAvailable) R.string.gallery_export_jpeg_description else R.string.runtime_heif_decoder_unavailable),
                badge = stringResource(R.string.gallery_badge_share),
                selected = selectedMode == HeifExportMode.TRANSCODE_JPEG,
                enabled = decoderAvailable,
                onClick = { selectedMode = HeifExportMode.TRANSCODE_JPEG }
            ) {
                if (selectedMode == HeifExportMode.TRANSCODE_JPEG) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.gallery_compression_quality), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.gallery_quality_value, percentFormat.format(jpegQuality.toInt() / 100.0)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                title = stringResource(R.string.gallery_export_heif_title),
                subtitle = stringResource(R.string.gallery_export_heif_description),
                badge = stringResource(R.string.gallery_badge_edit),
                selected = selectedMode == HeifExportMode.ORIGINAL_HIF,
                onClick = { selectedMode = HeifExportMode.ORIGINAL_HIF }
            )

            // Option 3: Ultra HDR (Android 14+)
            ExportOptionCard(
                icon = Icons.Rounded.HdrOn,
                title = stringResource(R.string.gallery_export_hdr_title),
                subtitle = stringResource(R.string.gallery_export_hdr_description),
                badge = stringResource(R.string.gallery_badge_experimental),
                selected = selectedMode == HeifExportMode.ULTRA_HDR,
                onClick = { selectedMode = HeifExportMode.ULTRA_HDR }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AlphaOutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.gallery_cancel)) }
                AlphaButton(
                    onClick = { onStartExport(selectedMode, jpegQuality.toInt()) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.gallery_start_export)) }
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
    enabled: Boolean = true,
    content: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick, enabled = enabled)
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
