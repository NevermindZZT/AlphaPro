package com.letter.alphapro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.core.bluetooth.SonyBleGattManager
import com.letter.alphapro.core.location.AdaptiveLocationEngine
import com.letter.alphapro.core.location.FusedLocationSource
import com.letter.alphapro.core.location.GpxTrackLogger
import kotlinx.coroutines.*
import java.io.File

class LocationSenderForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "alphapro_location_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var gattManager: SonyBleGattManager
    private lateinit var locationEngine: AdaptiveLocationEngine
    private lateinit var gpxLogger: GpxTrackLogger

    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        gattManager = SonyBleGattManager(applicationContext, serviceScope)
        val locationSource = FusedLocationSource(applicationContext)
        locationEngine = AdaptiveLocationEngine(locationSource)
        val gpxDir = File(getExternalFilesDir(null), "tracks").apply { mkdirs() }
        gpxLogger = GpxTrackLogger(gpxDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("AlphaPro 正在守护索尼相机连接", "等待相机蓝牙握手...")
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address != null) {
            gattManager.connect(address)
        }

        startLocationTransmissionLoop()

        return START_STICKY
    }

    private fun startLocationTransmissionLoop() {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            launch {
                gattManager.connectionState.collect { state ->
                    val statusText = when (state) {
                        is SonyBleConnectionState.Ready -> "已联机 ${state.deviceName} · 正在同步 GPS"
                        is SonyBleConnectionState.Connecting -> "正在连接相机..."
                        is SonyBleConnectionState.DiscoveringServices -> "正在发现服务..."
                        is SonyBleConnectionState.Handshaking -> "正在进行鉴权与时钟校准..."
                        is SonyBleConnectionState.Disconnected -> "相机已休眠，待命重连中"
                        is SonyBleConnectionState.Error -> "错误: ${state.message}"
                    }
                    val notification = buildNotification("AlphaPro 索尼相机伴侣", statusText)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }

            launch {
                locationEngine.getFilteredLocationUpdates().collect { coord ->
                    gpxLogger.recordPoint(coord)
                    if (gattManager.connectionState.value is SonyBleConnectionState.Ready) {
                        gattManager.sendLocation(coord.latitude, coord.longitude)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        gattManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "相机定位与保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台向索尼微单持续发送 GPS 坐标，防止相机休眠断连"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }
}
