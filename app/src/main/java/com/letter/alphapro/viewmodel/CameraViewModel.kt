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
    val wifiFailureDiagnostic: String? = null,
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
    val gpxLogger = (application as AlphaProApplication).gpxLogger

    fun getGpxTrackDirectoryPath(): String {
        return gpxLogger.storageDir.absolutePath
    }

    fun getGpxPointCount(): Int {
        return gpxLogger.pointCount
    }

    fun flushAndGetGpxFile(): File? {
        return gpxLogger.flush() ?: gpxLogger.getLatestTrackFile()
    }

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var wifiJob: Job? = null
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
        // Set busy before launching or reading credentials, so repeated taps cannot queue bootstraps.
        if (wifiJob?.isActive == true || _uiState.value.wifiState is CameraWifiState.Connecting) return
        if (gattManager.connectionState.value !is SonyBleConnectionState.Ready) {
            _uiState.update { it.copy(userNotice = "请等待相机蓝牙完成握手后再连接 Wi-Fi") }
            return
        }
        val wifiManager = getApplication<Application>().getSystemService(WifiManager::class.java)
        if (wifiManager?.isWifiEnabled == false) {
            _uiState.update { it.copy(userNotice = "请先开启手机 Wi-Fi") }
            return
        }
        _uiState.update { it.copy(wifiState = CameraWifiState.Connecting, wifiFailureDiagnostic = null,
            userNotice = "正在通过蓝牙读取热点信息…") }
        wifiJob = viewModelScope.launch {
            fun fail(message: String) {
                _uiState.update { it.copy(wifiState = CameraWifiState.Error(message), userNotice = message,
                    wifiFailureDiagnostic = "Wi-Fi 诊断 W2 · " + com.letter.alphapro.BuildConfig.VERSION_NAME +
                        "\n" + gattManager.wifiDiagnostic.value + "\n" + message) }
            }
            try {
                val credentials = gattManager.readWifiCredentials()
                if (credentials == null) {
                    fail("相机热点信息读取失败，请查看诊断详情")
                    return@launch
                }
                // Do not copy passwords to the system clipboard automatically.
                prefs.lastWifiSsid = credentials.ssid
                prefs.lastWifiPassword = credentials.password
                _uiState.update { it.copy(userNotice = "热点信息已读取，等待系统 Wi-Fi 连接确认…") }
                wifiConnector.connectCameraWifi(credentials.ssid, credentials.password)
                    .collect { state ->
                        val previous = _uiState.value.wifiState
                        _uiState.update { it.copy(wifiState = state) }
                        when (state) {
                            is CameraWifiState.Connected -> {
                                if (previous !is CameraWifiState.Connected || previous.network != state.network ||
                                    previous.gatewayIp != state.gatewayIp) {
                                    _uiState.update { it.copy(userNotice = "Wi-Fi 已连接，正在读取照片…") }
                                    loadCameraMedia(state.gatewayIp, state.network)
                                }
                            }
                            is CameraWifiState.Error -> fail("热点信息已读取；" + state.message)
                            else -> Unit
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("Wi-Fi 流程异常：" + error.javaClass.simpleName)
            } finally {
                if (_uiState.value.wifiState is CameraWifiState.Connecting) {
                    _uiState.update { it.copy(wifiState = CameraWifiState.Idle) }
                }
            }
        }
    }

    fun dismissWifiDiagnostic() {
        _uiState.update { it.copy(wifiFailureDiagnostic = null) }
    }

    fun disconnectCameraWifi() {
        viewModelScope.launch {
            wifiJob?.cancelAndJoin()
            wifiJob = null
            wifiConnector.release()
            ptpClient.disconnect()
            _uiState.update { it.copy(wifiState = CameraWifiState.Idle, mediaItems = emptyList(), userNotice = "Wi-Fi 已断开") }
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
    val themeMode: StateFlow<com.letter.alphapro.data.AppThemeMode> = com.letter.alphapro.data.AppThemeState.themeMode.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = com.letter.alphapro.data.AppThemeState.dynamicColor.asStateFlow()

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
        com.letter.alphapro.data.AppThemeState.setThemeMode(prefs, mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        com.letter.alphapro.data.AppThemeState.setDynamicColor(prefs, enabled)
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