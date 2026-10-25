package com.letter.alphapro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.core.bluetooth.DiscoveredCamera
import com.letter.alphapro.ui.theme.AlphaOrangePrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanDialog(
    discoveredCameras: List<DiscoveredCamera>,
    isScanning: Boolean,
    onDismissRequest: () -> Unit,
    onSelectCamera: (DiscoveredCamera) -> Unit,
    onRefreshScan: (sonyOnly: Boolean) -> Unit
) {
    var sonyOnlyFilter by remember { mutableStateOf(true) }
    var connectingAddress by remember { mutableStateOf<String?>(null) }

    val displayedList = remember(discoveredCameras, sonyOnlyFilter) {
        if (sonyOnlyFilter) {
            discoveredCameras.filter { it.isSonyCamera }
        } else {
            discoveredCameras
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Rounded.BluetoothSearching,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索索尼微单",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { onRefreshScan(sonyOnlyFilter) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新搜索")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "请确保索尼相机已开机，并在相机菜单中开启 [智能手机连接] 或 [蓝牙遥控] 保持配对就绪状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Filter Row: Sony Camera vs All Devices
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = sonyOnlyFilter,
                        onClick = {
                            sonyOnlyFilter = true
                            onRefreshScan(true)
                        },
                        label = { Text("仅索尼相机", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = if (sonyOnlyFilter) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = !sonyOnlyFilter,
                        onClick = {
                            sonyOnlyFilter = false
                            onRefreshScan(false)
                        },
                        label = { Text("全部蓝牙设备", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = if (!sonyOnlyFilter) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (displayedList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = if (isScanning) "正在搜索附近的索尼相机..." else "未发现索尼相机",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "若相机已配对过其他手机，请在机身菜单重置蓝牙配对",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(displayedList, key = { it.address }) { camera ->
                            val isConnectingThis = connectingAddress == camera.address

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (camera.isSonyCamera) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        connectingAddress = camera.address
                                        onSelectCamera(camera)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (camera.isSonyCamera) AlphaOrangePrimary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (camera.isSonyCamera) Icons.Rounded.CameraAlt else Icons.Rounded.Bluetooth,
                                                contentDescription = null,
                                                tint = if (camera.isSonyCamera) AlphaOrangePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = camera.name,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                            )
                                            if (camera.isSonyCamera) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = AlphaOrangePrimary
                                                ) {
                                                    Text(
                                                        text = "Alpha",
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = camera.address,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            connectingAddress = camera.address
                                            onSelectCamera(camera)
                                        },
                                        enabled = !isConnectingThis,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        if (isConnectingThis) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text(
                                                text = "连接",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    )
}
