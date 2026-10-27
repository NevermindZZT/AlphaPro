package com.letter.alphapro.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.letter.alphapro.AlphaProApplication
import com.letter.alphapro.core.bluetooth.*
import com.letter.alphapro.core.ftp.EmbeddedFtpServer
import com.letter.alphapro.core.ftp.TetherIncomingPhoto
import com.letter.alphapro.core.heif.HeifTranscoder
import com.letter.alphapro.core.ptp.PtpImageMetadata
import com.letter.alphapro.core.ptp.PtpIpClient
import com.letter.alphapro.core.ptp.PtpPhotoDownloader
import com.letter.alphapro.data.CameraPreferences
import com.letter.alphapro.service.LocationSenderForegroundService
import com.letter.alphapro.wifi.CameraWifiSpecifierConnector
import com.letter.alphapro.wifi.CameraWifiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

data class CameraUiState(
    val pairedAddress: String? = null,
    val pairedName: String? = null,
    val bleState: SonyBleConnectionState = SonyBleConnectionState.Disconnected,
    val wifiState: CameraWifiState = CameraWifiState.Idle,
    val isGpsSyncActive: Boolean = true,
    val lastGpsSyncTime: Long? = null,
    val lastLocationAccuracy: Float? = null,
    val isFtpServerRunning: Boolean = false,
    val localFtpIp: String? = null,
    val mediaItems: List<PtpImageMetadata> = emptyList(),
    val isMediaLoading: Boolean = false,
    val latestTetherPhoto: TetherIncomingPhoto? = null,
    val tetherPhotos: List<TetherIncomingPhoto> = emptyList(),
    val downloadProgressMap: Map<String, Float> = emptyMap(),
    val activeIntervalRemaining: Int? = null,
    val bulbElapsedSeconds: Int = 0,
    val isBulbActive: Boolean = false,
    val userNotice: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = CameraPreferences(application)
    val gattManager = (application as AlphaProApplication).gattManager
    val shutterController = SonyRemoteShutterController(gattManager)
    val wifiConnector = CameraWifiSpecifierConnector(application)
    val ptpClient = PtpIpClient()
    val ptpDownloader = PtpPhotoDownloader(application, ptpClient)
    val heifTranscoder = HeifTranscoder()
    private val bleScanner = SonyBleScanner(application)

    private val ftpStorageDir = File(application.getExternalFilesDir(null), "tether_ftp").apply { mkdirs() }
    val ftpServer = EmbeddedFtpServer(ftpStorageDir, 2121, viewModelScope)

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null
    private var bulbJob: Job? = null
    private var intervalJob: Job? = null

    val thumbnailCache = mutableMapOf<Long, android.graphics.Bitmap>()
    private val _thumbnailsFlow = MutableStateFlow<Map<Long, android.graphics.Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, android.graphics.Bitmap>> = _thumbnailsFlow.asStateFlow()

    fun requestThumbnail(metadata: PtpImageMetadata) {
        if (thumbnailCache.containsKey(metadata.handle)) return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = ptpClient.getThumbnail(metadata)
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    thumbnailCache[metadata.handle] = bitmap
                    _thumbnailsFlow.update { it + (metadata.handle to bitmap) }
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(
        CameraUiState(
            pairedAddress = prefs.pairedDeviceAddress,
            pairedName = prefs.pairedDeviceName,
            isGpsSyncActive = prefs.isGpsAutoSyncEnabled,
            localFtpIp = getLocalIpAddress()
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        // Collect BLE connection state
        viewModelScope.launch {
            gattManager.connectionState.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        bleState = state,
                        pairedName = if (state is SonyBleConnectionState.Ready) state.deviceName else current.pairedName
                    )
                }
            }
        }

        // Collect FTP tether incoming photos
        viewModelScope.launch {
            ftpServer.incomingPhotos.collect { incoming ->
                _uiState.update { current ->
                    current.copy(
                        latestTetherPhoto = incoming,
                        tetherPhotos = listOf(incoming) + current.tetherPhotos
                    )
                }
            }
        }

        // Auto-reconnect to last paired camera on launch
        prefs.pairedDeviceAddress?.let { address ->
            connectCamera(address, prefs.pairedDeviceName ?: "Sony Camera")
        }
    }

    fun startScan(sonyOnly: Boolean = false) {
        scanJob?.cancel()
        _isScanning.value = true
        _discoveredCameras.value = emptyList()
        scanJob = viewModelScope.launch {
            bleScanner.startScan(sonyOnly).collect { cameras ->
                _discoveredCameras.value = cameras
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun connectCamera(address: String, name: String) {
        prefs.pairedDeviceAddress = address
        prefs.pairedDeviceName = name
        _uiState.update { it.copy(pairedAddress = address, pairedName = name) }
        stopScan()

        // Register companion device presence observation so Android OS auto-wakes app when camera turns on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cdm = getApplication<Application>().getSystemService(android.companion.CompanionDeviceManager::class.java)
                cdm?.startObservingDevicePresence(address)
            } catch (_: Exception) {}
        }

        gattManager.connect(address)

        if (prefs.isGpsAutoSyncEnabled) {
            startLocationForegroundService(address)
        }
    }

    fun disconnectCamera() {
        gattManager.disconnect()
        stopLocationForegroundService()
        _uiState.update { it.copy(bleState = SonyBleConnectionState.Disconnected) }
    }

    fun reconnectCamera() {
        val address = prefs.pairedDeviceAddress ?: return
        val name = prefs.pairedDeviceName ?: "Sony Camera"
        connectCamera(address, name)
    }

    fun forgetDevice() {
        val address = prefs.pairedDeviceAddress
        if (address != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cdm = getApplication<Application>().getSystemService(android.companion.CompanionDeviceManager::class.java)
                cdm?.stopObservingDevicePresence(address)
            } catch (_: Exception) {}
        }
        disconnectCamera()
        prefs.clearPairedDevice()
        _uiState.update { it.copy(pairedAddress = null, pairedName = null) }
    }

    fun toggleGpsSync(enabled: Boolean) {
        prefs.isGpsAutoSyncEnabled = enabled
        _uiState.update { it.copy(isGpsSyncActive = enabled) }

        if (enabled) {
            val address = prefs.pairedDeviceAddress
            if (address != null) {
                startLocationForegroundService(address)
            }
        } else {
            stopLocationForegroundService()
        }
    }

    fun syncCameraTime() {
        viewModelScope.launch {
            val success = gattManager.sendTimeSync()
            _uiState.update {
                it.copy(userNotice = if (success) "相机时钟校准成功 (已与手机精确同步)" else "校准失败，请确认蓝牙已连接")
            }
        }
    }

    fun connectCameraWifi() {
        viewModelScope.launch {
            _uiState.update { it.copy(userNotice = "正在通过蓝牙获取相机 Wi-Fi 凭证...") }
            var credentials = gattManager.readWifiCredentials()

            // If BLE read timed out or failed, fall back to previously saved credentials
            if (credentials == null && !prefs.lastWifiSsid.isNullOrEmpty() && !prefs.lastWifiPassword.isNullOrEmpty()) {
                credentials = SonyWifiCredentials(prefs.lastWifiSsid!!, prefs.lastWifiPassword!!)
            }

            if (credentials == null) {
                _uiState.update { it.copy(userNotice = "无法获取相机 Wi-Fi 凭证，请确认相机蓝牙已连接并在菜单中开启网络连接") }
                return@launch
            }

            prefs.lastWifiSsid = credentials.ssid
            prefs.lastWifiPassword = credentials.password

            _uiState.update { it.copy(userNotice = "正在直连相机 Wi-Fi: " + credentials.ssid) }

            wifiConnector.connectCameraWifi(credentials.ssid, credentials.password)
                .collect { wifiState ->
                    _uiState.update { it.copy(wifiState = wifiState) }
                    if (wifiState is CameraWifiState.Connected) {
                        _uiState.update { it.copy(userNotice = "Wi-Fi 直连成功，正在读取机内照片...") }
                        loadCameraMedia(wifiState.gatewayIp, wifiState.network)
                    }
                }
        }
    }

    fun disconnectCameraWifi() {
        viewModelScope.launch {
            wifiConnector.release()
            ptpClient.disconnect()
            _uiState.update {
                it.copy(
                    wifiState = CameraWifiState.Idle,
                    mediaItems = emptyList(),
                    userNotice = "Wi-Fi 已断开"
                )
            }
        }
    }

    fun loadCameraMedia(
        gatewayIp: String = "192.168.122.1",
        network: android.net.Network? = (_uiState.value.wifiState as? CameraWifiState.Connected)?.network
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMediaLoading = true) }
            try {
                ptpClient.connect(cameraHost = gatewayIp, network = network)
                val photos = ptpClient.loadMediaList()
                _uiState.update {
                    it.copy(
                        mediaItems = photos.reversed(), // Latest first
                        isMediaLoading = false,
                        userNotice = if (photos.isNotEmpty()) "已载入 " + photos.size.toString() + " 张机内照片" else "存储卡内暂无照片"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isMediaLoading = false,
                        userNotice = "读取相机存储卡失败: " + (e.message ?: "网络超时")
                    )
                }
            }
        }
    }

    fun exportPhoto(
        metadata: PtpImageMetadata,
        transcodeToJpeg: Boolean,
        jpegQuality: Int = 95
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(userNotice = "开始导出 " + metadata.filename) }
            try {
                if (transcodeToJpeg && metadata.isHeif) {
                    val tempHeif = ptpDownloader.downloadToTempFile(metadata)
                    val targetJpeg = File(tempHeif.parentFile, metadata.filename.substringBeforeLast(".") + ".jpg")
                    heifTranscoder.transcodeHeifToJpeg(tempHeif, targetJpeg, jpegQuality)
                    tempHeif.delete()
                    _uiState.update { it.copy(userNotice = metadata.filename + " 转码导出完成 (95% JPEG)") }
                } else {
                    ptpDownloader.downloadToMediaStore(metadata).collect { progress ->
                        _uiState.update { current ->
                            val map = current.downloadProgressMap.toMutableMap()
                            map[metadata.filename] = progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat()
                            current.copy(
                                downloadProgressMap = map,
                                userNotice = if (progress.isComplete) metadata.filename + " 导出完成" else "正在导出 " + metadata.filename
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(userNotice = "导出失败: " + (e.message ?: "未知异常")) }
            }
        }
    }

    fun exportCaptureGroup(
        group: com.letter.alphapro.core.ptp.CameraCaptureGroup,
        target: com.letter.alphapro.ui.gallery.GroupDownloadTarget,
        transcodeHeifToJpeg: Boolean = true,
        jpegQuality: Int = 95
    ) {
        when (target) {
            com.letter.alphapro.ui.gallery.GroupDownloadTarget.VISUAL_ONLY -> {
                exportPhoto(group.visualFile, transcodeToJpeg = transcodeHeifToJpeg && group.visualFile.isHeif, jpegQuality = jpegQuality)
            }
            com.letter.alphapro.ui.gallery.GroupDownloadTarget.RAW_ONLY -> {
                group.rawFile?.let { raw ->
                    exportPhoto(raw, transcodeToJpeg = false)
                } ?: run {
                    exportPhoto(group.visualFile, transcodeToJpeg = false)
                }
            }
            com.letter.alphapro.ui.gallery.GroupDownloadTarget.BOTH -> {
                exportPhoto(group.visualFile, transcodeToJpeg = transcodeHeifToJpeg && group.visualFile.isHeif, jpegQuality = jpegQuality)
                group.rawFile?.let { raw ->
                    exportPhoto(raw, transcodeToJpeg = false)
                }
            }
        }
    }

    fun toggleFtpServer(enabled: Boolean) {
        if (enabled) {
            ftpServer.start()
            val ip = getLocalIpAddress()
            _uiState.update { it.copy(isFtpServerRunning = true, localFtpIp = ip, userNotice = "FTP 边拍边传已启动: " + (ip ?: "0.0.0.0") + ":2121") }
        } else {
            ftpServer.stop()
            _uiState.update { it.copy(isFtpServerRunning = false, userNotice = "FTP 服务已停止") }
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(userNotice = null) }
    }

    // Remote Shutter Operations
    fun halfPressAfDown() = viewModelScope.launch { shutterController.halfPressAfDown() }
    fun halfPressAfUp() = viewModelScope.launch { shutterController.halfPressAfUp() }
    fun takePhoto() = viewModelScope.launch { shutterController.takePhoto() }
    fun toggleMovieRecording() = viewModelScope.launch { shutterController.toggleMovieRecording() }

    fun toggleBulb() {
        if (_uiState.value.isBulbActive) {
            bulbJob?.cancel()
            viewModelScope.launch { shutterController.stopBulb() }
            _uiState.update { it.copy(isBulbActive = false) }
        } else {
            _uiState.update { it.copy(isBulbActive = true, bulbElapsedSeconds = 0) }
            bulbJob = viewModelScope.launch {
                shutterController.startBulb()
                while (isActive) {
                    delay(1000)
                    _uiState.update { it.copy(bulbElapsedSeconds = it.bulbElapsedSeconds + 1) }
                }
            }
        }
    }

    fun startIntervalometer(intervalSec: Int, totalShots: Int) {
        intervalJob?.cancel()
        intervalJob = viewModelScope.launch {
            shutterController.executeIntervalometer(intervalSec, totalShots) { current, total ->
                _uiState.update { it.copy(activeIntervalRemaining = total - current) }
            }
            _uiState.update { it.copy(activeIntervalRemaining = null, userNotice = "延时拍摄序列完成") }
        }
    }

    private fun startLocationForegroundService(address: String) {
        val app = getApplication<Application>()
        val intent = Intent(app, LocationSenderForegroundService::class.java).apply {
            putExtra(LocationSenderForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    private fun stopLocationForegroundService() {
        val app = getApplication<Application>()
        val intent = Intent(app, LocationSenderForegroundService::class.java)
        app.stopService(intent)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // Unified App Settings StateFlows
    private val _themeMode = MutableStateFlow(prefs.themeMode)
    val themeMode: StateFlow<com.letter.alphapro.data.AppThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.isDynamicColorEnabled)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _gpsInterval = MutableStateFlow(prefs.gpsInterval)
    val gpsInterval: StateFlow<com.letter.alphapro.data.GpsInterval> = _gpsInterval.asStateFlow()

    private val _isGpxLogging = MutableStateFlow(prefs.isGpxTrackLoggingEnabled)
    val isGpxLogging: StateFlow<Boolean> = _isGpxLogging.asStateFlow()

    private val _heifPolicy = MutableStateFlow(prefs.defaultHeifPolicy)
    val heifPolicy: StateFlow<com.letter.alphapro.data.DefaultHeifPolicy> = _heifPolicy.asStateFlow()

    private val _jpegQuality = MutableStateFlow(prefs.defaultJpegQuality)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _autoWake = MutableStateFlow(prefs.autoWakeOnCameraPowerOn)
    val autoWake: StateFlow<Boolean> = _autoWake.asStateFlow()

    fun setThemeMode(mode: com.letter.alphapro.data.AppThemeMode) {
        prefs.themeMode = mode
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.isDynamicColorEnabled = enabled
        _dynamicColor.value = enabled
    }

    fun setGpsInterval(interval: com.letter.alphapro.data.GpsInterval) {
        prefs.gpsInterval = interval
        _gpsInterval.value = interval
    }

    fun setGpxLogging(enabled: Boolean) {
        prefs.isGpxTrackLoggingEnabled = enabled
        _isGpxLogging.value = enabled
    }

    fun setHeifPolicy(policy: com.letter.alphapro.data.DefaultHeifPolicy) {
        prefs.defaultHeifPolicy = policy
        _heifPolicy.value = policy
    }

    fun setJpegQuality(quality: Int) {
        prefs.defaultJpegQuality = quality
        _jpegQuality.value = quality
    }

    fun setAutoWake(enabled: Boolean) {
        prefs.autoWakeOnCameraPowerOn = enabled
        _autoWake.value = enabled
        val address = prefs.pairedDeviceAddress
        if (address != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cdm = getApplication<Application>().getSystemService(android.companion.CompanionDeviceManager::class.java)
                if (enabled) {
                    cdm?.startObservingDevicePresence(address)
                } else {
                    cdm?.stopObservingDevicePresence(address)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        ftpServer.stop()
        wifiConnector.release()
        stopScan()
    }
}
