package com.letter.alphapro.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.R
import com.letter.alphapro.data.AppThemeMode
import com.letter.alphapro.data.DefaultHeifPolicy
import com.letter.alphapro.data.GpsInterval
import com.letter.alphapro.ui.components.AlphaButton
import com.letter.alphapro.ui.components.AlphaButtonDefaults
import com.letter.alphapro.ui.components.AlphaIconButton
import com.letter.alphapro.ui.components.AlphaOutlinedButton
import com.letter.alphapro.ui.components.AlphaSegmentedButton
import com.letter.alphapro.ui.components.AlphaSingleChoiceSegmentedButtonRow
import com.letter.alphapro.ui.components.AlphaTextButton
import com.letter.alphapro.ui.theme.AlphaOrangePrimary
import com.letter.alphapro.viewmodel.CameraViewModel

private const val GITHUB_REPO_URL = "https://github.com/NevermindZZT/AlphaPro"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val gpsInterval by viewModel.gpsInterval.collectAsState()
    val isGpxLogging by viewModel.isGpxLogging.collectAsState()
    val heifPolicy by viewModel.heifPolicy.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val autoWake by viewModel.autoWake.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    AlphaIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Project Hero Card & Version Display
            ProjectHeroCard(
                version = com.letter.alphapro.BuildConfig.VERSION_NAME,
                repoUrl = GITHUB_REPO_URL,
                onOpenRepo = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                    context.startActivity(intent)
                },
                onCopyRepo = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_repo_clip_label), GITHUB_REPO_URL))
                }
            )

            AppLanguageSettings()

            // 2. Appearance & Theme Settings Group
            SettingsSectionHeader(title = stringResource(R.string.settings_appearance), icon = Icons.Rounded.Palette)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme_mode),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    AlphaSingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            AlphaSegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                index = index,
                                count = AppThemeMode.entries.size
                            ) {
                                Text(stringResource(mode.labelRes))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stringResource(R.string.settings_dynamic_color_description) else stringResource(R.string.settings_dynamic_color_requirement),
                        icon = Icons.Rounded.ColorLens,
                        checked = dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = { viewModel.setDynamicColor(it) }
                    )
                }
            }

            // 3. GPS & Track Settings Group
            SettingsSectionHeader(title = stringResource(R.string.settings_gps_tracks), icon = Icons.Rounded.MyLocation)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gps_sync),
                        subtitle = stringResource(R.string.settings_gps_sync_description),
                        icon = Icons.Rounded.GpsFixed,
                        checked = uiState.isGpsSyncActive,
                        onCheckedChange = { viewModel.toggleGpsSync(it) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.settings_gps_interval),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(R.string.settings_gps_interval_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AlphaSingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        GpsInterval.entries.forEachIndexed { index, interval ->
                            AlphaSegmentedButton(
                                selected = gpsInterval == interval,
                                onClick = { viewModel.setGpsInterval(interval) },
                                index = index,
                                count = GpsInterval.entries.size
                            ) {
                                Text(stringResource(interval.labelRes))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gpx_logging),
                        subtitle = stringResource(R.string.settings_gpx_logging_description),
                        icon = Icons.Rounded.Route,
                        checked = isGpxLogging,
                        onCheckedChange = { viewModel.setGpxLogging(it) }
                    )

                    if (isGpxLogging) {
                        val gpxPointCount = viewModel.getGpxPointCount()
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.settings_gpx_path),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = pluralStringResource(R.plurals.settings_gpx_point_count, gpxPointCount, gpxPointCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    AlphaButton(
                                        onClick = {
                                            val gpxFile = viewModel.flushAndGetGpxFile()
                                            if (gpxFile != null && gpxFile.exists()) {
                                                try {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        context.packageName + ".fileprovider",
                                                        gpxFile
                                                    )
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/gpx+xml"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.settings_gpx_share_chooser)))
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_gpx_share_today))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Gallery & 10-bit HEIF Export Settings Group
            SettingsSectionHeader(title = stringResource(R.string.settings_gallery_heif), icon = Icons.Rounded.PhotoLibrary)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_heif_policy),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(R.string.settings_heif_policy_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AlphaSingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        DefaultHeifPolicy.entries.forEachIndexed { index, policy ->
                            AlphaSegmentedButton(
                                selected = heifPolicy == policy,
                                onClick = { viewModel.setHeifPolicy(policy) },
                                index = index,
                                count = DefaultHeifPolicy.entries.size
                            ) {
                                Text(stringResource(policy.labelRes))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_jpeg_quality),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.settings_jpeg_quality_description, jpegQuality),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Slider(
                        value = jpegQuality.toFloat(),
                        onValueChange = { viewModel.setJpegQuality(it.toInt()) },
                        valueRange = 80f..100f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 5. Connectivity & Device Management
            SettingsSectionHeader(title = stringResource(R.string.settings_connectivity), icon = Icons.Rounded.BluetoothSearching)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_wake),
                        subtitle = stringResource(R.string.settings_auto_wake_description),
                        icon = Icons.Rounded.PowerSettingsNew,
                        checked = autoWake,
                        onCheckedChange = { viewModel.setAutoWake(it) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_paired_camera),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (uiState.pairedAddress != null) stringResource(
                                    R.string.settings_paired_camera_details,
                                    uiState.pairedName ?: stringResource(R.string.settings_camera_fallback),
                                    uiState.pairedAddress.orEmpty()
                                ) else stringResource(R.string.settings_no_paired_camera),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (uiState.pairedAddress != null) {
                            AlphaOutlinedButton(
                                onClick = { showClearDialog = true },
                                colors = AlphaButtonDefaults.outlinedDestructiveColors()
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_forget_camera))
                            }
                        }
                    }
                }
            }

            // 6. About & Open Source Credits
            SettingsSectionHeader(title = stringResource(R.string.settings_about), icon = Icons.Rounded.Info)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_project_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.settings_credits_title),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(R.string.settings_credits_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.settings_license),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_description)) },
            confirmButton = {
                AlphaButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.forgetDevice()
                    },
                    colors = AlphaButtonDefaults.destructiveColors()
                ) {
                    Text(stringResource(R.string.settings_forget_confirm))
                }
            },
            dismissButton = {
                AlphaTextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProjectHeroCard(
    version: String,
    repoUrl: String,
    onOpenRepo: () -> Unit,
    onCopyRepo: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AlphaOrangePrimary,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Camera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AlphaPro",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.settings_version, version),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_project_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // GitHub Repository Banner Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenRepo)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_repo_title),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = repoUrl,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    AlphaIconButton(onClick = onOpenRepo) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.settings_open_browser),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
