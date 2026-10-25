package com.letter.alphapro.ui.tether

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.letter.alphapro.core.ftp.TetherIncomingPhoto
import com.letter.alphapro.ui.theme.CameraActiveGreen
import com.letter.alphapro.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TetherScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPhoto by remember { mutableStateOf<TetherIncomingPhoto?>(null) }
    var isLutActive by remember { mutableStateOf(false) }

    val activeDisplayPhoto = selectedPhoto ?: uiState.latestTetherPhoto

    Scaffold(
        modifier = modifier,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "无线边拍边传 (FTP Tether)",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (uiState.isFtpServerRunning) "服务监听中 · 端口 2121" else "服务未启动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Switch(
                        checked = uiState.isFtpServerRunning,
                        onCheckedChange = { viewModel.toggleFtpServer(it) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // FTP Hotspot & Setup Banner
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isFtpServerRunning) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Rounded.WifiTethering,
                            contentDescription = null,
                            tint = if (uiState.isFtpServerRunning) CameraActiveGreen else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            val ip = uiState.localFtpIp ?: "127.0.0.1"
                            Text(
                                text = if (uiState.isFtpServerRunning) "本机 FTP: " + ip + ":2121" else "请开启右上角开关启动服务",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "相机设置: [网络] -> [FTP传输] -> [FTP服务器1] 填入上述IP",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Main Photo Inspection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (activeDisplayPhoto != null) {
                        AsyncImage(
                            model = activeDisplayPhoto.file,
                            contentDescription = activeDisplayPhoto.filename,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Floating Tool Bar
                        Surface(
                            modifier = Modifier
                                .padding(12.dp)
                                .align(Alignment.TopEnd),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.6f)
                        ) {
                            Row(modifier = Modifier.padding(4.dp)) {
                                IconButton(onClick = { isLutActive = !isLutActive }) {
                                    Icon(
                                        imageVector = Icons.Rounded.ColorLens,
                                        contentDescription = "LUT",
                                        tint = if (isLutActive) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                }
                            }
                        }

                        // Info Bar Overlay
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                            color = Color.Black.copy(alpha = 0.75f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = activeDisplayPhoto.filename,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                                val sizeMb = String.format("%.1f MB", activeDisplayPhoto.fileSize / (1024.0 * 1024.0))
                                Text(
                                    text = sizeMb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                            }
                        }
                    } else {
                        // Empty Waiting State
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.DarkGray,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = if (uiState.isFtpServerRunning) "等待相机拍摄传输照片..." else "请先开启上方 FTP 边拍边传服务",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "在相机上按下快门，原图即刻在 1~2 秒内自动推送至手机检焦",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // Filmstrip History
            if (uiState.tetherPhotos.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "底片回传流",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "已接收 " + uiState.tetherPhotos.size.toString() + " 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.tetherPhotos, key = { it.filename }) { item ->
                            val isSelected = item == activeDisplayPhoto
                            Surface(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedPhoto = item },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = if (isSelected) ButtonDefaults.outlinedButtonBorder else null
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = item.file,
                                        contentDescription = item.filename,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(Color.Black.copy(alpha = 0.6f))
                                    ) {
                                        Text(
                                            text = item.filename.takeLast(8),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
