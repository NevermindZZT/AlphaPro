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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.data.AppThemeMode
import com.letter.alphapro.data.DefaultHeifPolicy
import com.letter.alphapro.data.GpsInterval
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
                        text = "设置与偏好",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "返回"
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub Repo", GITHUB_REPO_URL))
                }
            )

            // 2. Appearance & Theme Settings Group
            SettingsSectionHeader(title = "外观与主题", icon = Icons.Rounded.Palette)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "主题显示模式",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AppThemeMode.entries.size
                                )
                            ) {
                                Text(mode.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingsSwitchRow(
                        title = "Material You 动态色彩",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "根据系统壁纸自动提取莫奈色盘；关闭后使用 AlphaPro 经典索尼极光橙" else "需要 Android 12 以上系统支持",
                        icon = Icons.Rounded.ColorLens,
                        checked = dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = { viewModel.setDynamicColor(it) }
                    )
                }
            }

            // 3. GPS & Track Settings Group
            SettingsSectionHeader(title = "GPS 定位与航迹打卡", icon = Icons.Rounded.MyLocation)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SettingsSwitchRow(
                        title = "自动 GPS 定位同步",
                        subtitle = "蓝牙联机后自动将手机高精度经纬度与海拔写入索尼微单机身",
                        icon = Icons.Rounded.GpsFixed,
                        checked = uiState.isGpsSyncActive,
                        onCheckedChange = { viewModel.toggleGpsSync(it) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "定位心跳发送频率",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "向相机发送 GPS 坐标心跳包的周期。推荐使用 5 秒，防止相机看门狗超时亮起禁止图标。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        GpsInterval.entries.forEachIndexed { index, interval ->
                            SegmentedButton(
                                selected = gpsInterval == interval,
                                onClick = { viewModel.setGpsInterval(interval) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = GpsInterval.entries.size
                                )
                            ) {
                                Text(interval.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SettingsSwitchRow(
                        title = "后台 GPX 轨迹记录",
                        subtitle = "在联机期间自动将拍摄外景路线保存为标准 .gpx 文件，便于导入 Lightroom 或 Google Earth",
                        icon = Icons.Rounded.Route,
                        checked = isGpxLogging,
                        onCheckedChange = { viewModel.setGpxLogging(it) }
                    )

                    if (isGpxLogging) {
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
                                        text = "公开路径: 内部存储 / Documents / AlphaPro / Tracks /",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "文件管理直接可见 · 今日已记录 " + viewModel.getGpxPointCount().toString() + " 点 (每分钟自动保存)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
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
                                                    context.startActivity(Intent.createChooser(shareIntent, "导出 / 分享 GPX 轨迹"))
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("导出 / 分享今日轨迹", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Gallery & 10-bit HEIF Export Settings Group
            SettingsSectionHeader(title = "相册与 10-bit HEIF 导出", icon = Icons.Rounded.PhotoLibrary)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "10-bit HEIF 默认导出策略",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "解决官方 Creators' App 在 Android 端无法导出 10-bit HEIF (HIF) 的核心问题。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        DefaultHeifPolicy.entries.forEachIndexed { index, policy ->
                            SegmentedButton(
                                selected = heifPolicy == policy,
                                onClick = { viewModel.setHeifPolicy(policy) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = DefaultHeifPolicy.entries.size
                                )
                            ) {
                                Text(policy.label, style = MaterialTheme.typography.labelSmall)
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
                                text = "JPEG 转码压缩品质",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "当前品质: $jpegQuality% (推荐 95%，近无损色彩同时保留所有相机 EXIF 参数)",
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
            SettingsSectionHeader(title = "连接与自动化", icon = Icons.Rounded.BluetoothSearching)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SettingsSwitchRow(
                        title = "相机开机系统级自动唤醒",
                        subtitle = "利用 CompanionDeviceManager 在场感知。相机开机发射蓝牙信标时，系统自动拉起前台服务同步 GPS",
                        icon = Icons.Rounded.PowerSettingsNew,
                        checked = autoWake,
                        onCheckedChange = { viewModel.setAutoWake(it) }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前已配对相机",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (uiState.pairedAddress != null) (uiState.pairedName ?: "索尼微单") + " · " + uiState.pairedAddress else "尚未配对任何相机机身",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (uiState.pairedAddress != null) {
                            OutlinedButton(
                                onClick = { showClearDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("清除记忆", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // 6. About & Open Source Credits
            SettingsSectionHeader(title = "关于项目与开源致谢", icon = Icons.Rounded.Info)
            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "AlphaPro 致力于为索尼全画幅与 APS-C 微单摄影师提供纯粹、现代、开源的 Android 端伴侣工具。旨在根除官方 Creators' App 蓝牙定位频繁掉线、拒绝导出 10-bit HEIF 等长期痛点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "开源社区致谢：",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "特别感谢 AlphaGPS、sony_geotagging 以及 Freemote 社区对索尼微单蓝牙协议与 PTP/IP 架构的逆向研究与探索。",
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
                                text = "开放源码协议：Apache License 2.0",
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
            title = { Text("解除相机配对记忆？") },
            text = { Text("解除后应用将不再自动连接该相机，切换新机身或重新配对时可执行此操作。") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        viewModel.forgetDevice()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认解除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
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
                                text = "v$version",
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
                        text = "索尼微单开源伴侣 · 专为摄影师打造",
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
                            text = "GitHub 开源项目仓库",
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

                    IconButton(onClick = onOpenRepo) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = "在浏览器中查看",
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
