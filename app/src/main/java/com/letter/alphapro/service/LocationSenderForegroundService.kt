package com.letter.alphapro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.letter.alphapro.AlphaProApplication
import com.letter.alphapro.R
import com.letter.alphapro.data.AppLanguage
import com.letter.alphapro.core.bluetooth.SonyBleConnectionState
import com.letter.alphapro.core.bluetooth.SonyBleGattManager
import com.letter.alphapro.core.location.AdaptiveLocationEngine
import com.letter.alphapro.core.location.GpxTrackLogger
import kotlinx.coroutines.*
import java.io.File

class LocationSenderForegroundService : Service() {
    private fun text(@StringRes id: Int, vararg args: Any): String =
        AppLanguage.getString(this, id, *args)

    companion object {
        const val CHANNEL_ID = "alphapro_location_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var gattManager: SonyBleGattManager
    private lateinit var locationEngine: AdaptiveLocationEngine
    private lateinit var gpxLogger: GpxTrackLogger

    private val languageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::gattManager.isInitialized && activeJob != null) refreshNotification()
        }
    }
    private var activeJob: Job? = null
    private var lastSentCoordinate: Pair<Double, Double>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val app = AlphaProApplication.instance
        gattManager = app.gattManager
        locationEngine = app.locationEngine
        gpxLogger = app.gpxLogger
        ContextCompat.registerReceiver(this, languageReceiver, IntentFilter(AppLanguage.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(text(R.string.runtime_notification_maintaining_connection), text(R.string.runtime_notification_waiting_handshake))
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
            // 1. Observe connection state for notifications
            launch {
                gattManager.connectionState.collect { state ->
                    refreshNotification(state)
                }
            }

            // 2. Collect location updates from engine
            launch {
                locationEngine.getFilteredLocationUpdates().collect { coord ->
                    gpxLogger.recordPoint(coord)
                    lastSentCoordinate = Pair(coord.latitude, coord.longitude)
                    if (gattManager.connectionState.value is SonyBleConnectionState.Ready) {
                        gattManager.sendLocation(coord.latitude, coord.longitude)
                    }
                }
            }

            // 3. MANDATORY 5-second periodic heartbeat loop!
            // Sony cameras have a 10s watchdog timer: if no location packet is received within ~10s,
            // the camera switches back to the prohibited GPS icon!
            launch {
                var lastGpxFlushTime = System.currentTimeMillis()
                while (isActive) {
                    delay(5000)
                    lastSentCoordinate?.let { (lat, lng) ->
                        if (gattManager.connectionState.value is SonyBleConnectionState.Ready) {
                            gattManager.sendLocation(lat, lng)
                        }
                    }

                    // Flush GPX track to disk every 60 seconds
                    if (System.currentTimeMillis() - lastGpxFlushTime > 60_000L) {
                        lastGpxFlushTime = System.currentTimeMillis()
                        gpxLogger.flush()
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::gattManager.isInitialized && activeJob != null) refreshNotification()
    }

    private fun refreshNotification(state: SonyBleConnectionState = gattManager.connectionState.value) {
        createNotificationChannel()
        val status = when (state) {
            is SonyBleConnectionState.Ready -> text(R.string.runtime_notification_connected, state.deviceName)
            is SonyBleConnectionState.Pairing -> text(R.string.runtime_notification_pairing)
            is SonyBleConnectionState.Connecting -> text(R.string.runtime_notification_connecting)
            is SonyBleConnectionState.DiscoveringServices -> text(R.string.runtime_notification_discovering_services)
            is SonyBleConnectionState.Handshaking -> text(R.string.runtime_notification_handshaking)
            is SonyBleConnectionState.Disconnected -> text(R.string.runtime_notification_disconnected)
            is SonyBleConnectionState.Error -> text(R.string.runtime_notification_error, state.message)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text(R.string.runtime_notification_title), status))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(languageReceiver) } catch (_: IllegalArgumentException) {}
        gpxLogger.flush()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                text(R.string.runtime_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = text(R.string.runtime_notification_channel_description)
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
