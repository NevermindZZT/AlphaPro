package com.letter.alphapro.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.letter.alphapro.AlphaProApplication
import com.letter.alphapro.R
import com.letter.alphapro.data.AppLanguage
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
    private fun text(@StringRes id: Int, vararg args: Any): String =
        AppLanguage.getString(getApplication<Application>(), id, *args)

    val prefs = CameraPreferences(application)
    val gattManager = (application as AlphaProApplication).gattManager
    val shutterController = SonyRemoteShutterController(gattManager, clockMillis = { android.os.SystemClock.elapsedRealtime() })
    val wifiConnector = CameraWifiSpecifierConnector(application)
    val ptpClient = PtpIpClient()
    val ptpDownloader = PtpPhotoDownloader(application, ptpClient)
    val heifTranscoder = HeifTranscoder()
    private val bleScanner = SonyBleScanner(application, messageContext = { AppLanguage.localizedContext(application) })

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
    private var remoteJob: Job? = null
    private var remoteStartedAt = 0L
    private var remoteNextAt = 0L
    private data class RemotePress(val token: Long, val signal: CompletableDeferred<Unit>)
    private var activeRemotePress: RemotePress? = null
    private var remotePressCounter = 0L
    private var manualAfSession: Any? = null
    private val _remoteShooting = MutableStateFlow(RemoteShootingUiState())
    val remoteShooting = _remoteShooting.asStateFlow()

    private val photoLoader = com.letter.alphapro.ui.gallery.CameraPhotoLoader(ptpClient, viewModelScope)
    val thumbnails = photoLoader.thumbnails
    val photoPreview = photoLoader.preview
    val thumbnailErrors = photoLoader.thumbnailErrors
    private val _viewingPhoto = MutableStateFlow<com.letter.alphapro.core.ptp.CameraCaptureGroup?>(null)
    val viewingPhoto = _viewingPhoto.asStateFlow()
    fun openPhoto(group: com.letter.alphapro.core.ptp.CameraCaptureGroup) {
        _viewingPhoto.value = group
        photoLoader.showPreview(group.visualFile)
    }
    fun closePhoto() {
        _viewingPhoto.value = null
        photoLoader.clearPreview()
    }
    fun retryThumbnail(metadata: PtpImageMetadata) = photoLoader.retryThumbnail(metadata)
    fun releaseThumbnailRequest(handle: Long) = photoLoader.releaseThumbnailRequest(handle)
    fun requestThumbnail(metadata: PtpImageMetadata) = photoLoader.requestThumbnail(metadata)
    fun requestPhotoPreview(metadata: PtpImageMetadata) = photoLoader.showPreview(metadata)
    fun clearPhotoPreview() = photoLoader.clearPreview()

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
                if (state !is SonyBleConnectionState.Ready) {
                    remoteJob?.cancel()
                    _remoteShooting.update { it.copy(afRequested = false, recording = null, cameraShutterActive = null, cameraFocusAcquired = null) }
                }
                _uiState.update { current ->
                    current.copy(
                        bleState = state,
                        pairedName = if (state is SonyBleConnectionState.Ready) state.deviceName else current.pairedName
                    )
                }
            }
        }

        viewModelScope.launch {
            gattManager.remoteFeedback.collect { feedback ->
                _remoteShooting.update { it.copy(recording = feedback.recording,
                    cameraShutterActive = feedback.shutterActive, cameraFocusAcquired = feedback.focusAcquired) }
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
            connectCamera(address, prefs.pairedDeviceName ?: text(R.string.runtime_camera_name_fallback))
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
        val name = prefs.pairedDeviceName ?: text(R.string.runtime_camera_name_fallback)
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
                it.copy(userNotice = if (success) text(R.string.runtime_time_sync_success) else text(R.string.runtime_time_sync_failed))
            }
        }
    }

    fun connectCameraWifi() {
        // Set busy before launching or reading credentials, so repeated taps cannot queue bootstraps.
        if (wifiJob?.isActive == true || _uiState.value.wifiState is CameraWifiState.Connecting) return
        if (gattManager.connectionState.value !is SonyBleConnectionState.Ready) {
            _uiState.update { it.copy(userNotice = text(R.string.runtime_wifi_wait_for_bluetooth)) }
            return
        }
        val wifiManager = getApplication<Application>().getSystemService(WifiManager::class.java)
        if (wifiManager?.isWifiEnabled == false) {
            _uiState.update { it.copy(userNotice = text(R.string.runtime_wifi_enable_phone)) }
            return
        }
        _uiState.update { it.copy(wifiState = CameraWifiState.Connecting, wifiFailureDiagnostic = null,
            userNotice = text(R.string.runtime_wifi_reading_credentials)) }
        wifiJob = viewModelScope.launch {
            fun fail(message: String) {
                _uiState.update { it.copy(wifiState = CameraWifiState.Error(message), userNotice = message,
                    wifiFailureDiagnostic = text(R.string.runtime_wifi_diagnostic, com.letter.alphapro.BuildConfig.VERSION_NAME,
                        gattManager.wifiDiagnostic.value, message)) }
            }
            try {
                val credentials = gattManager.readWifiCredentials()
                if (credentials == null) {
                    fail(text(R.string.runtime_wifi_credentials_failed))
                    return@launch
                }
                // Do not copy passwords to the system clipboard automatically.
                prefs.lastWifiSsid = credentials.ssid
                prefs.lastWifiPassword = credentials.password
                _uiState.update { it.copy(userNotice = text(R.string.runtime_wifi_awaiting_confirmation)) }
                wifiConnector.connectCameraWifi(credentials.ssid, credentials.password)
                    .collect { state ->
                        val previous = _uiState.value.wifiState
                        _uiState.update { it.copy(wifiState = state) }
                        when (state) {
                            is CameraWifiState.Connected -> {
                                if (previous !is CameraWifiState.Connected || previous.network != state.network ||
                                    previous.gatewayIp != state.gatewayIp) {
                                    _uiState.update { it.copy(userNotice = text(R.string.runtime_wifi_connected_loading)) }
                                    loadCameraMedia(state.gatewayIp, state.network)
                                }
                            }
                            is CameraWifiState.Error -> fail(text(R.string.runtime_wifi_connection_failed, state.message))
                            else -> Unit
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(text(R.string.runtime_wifi_process_failed, error.javaClass.simpleName))
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
            closePhoto()
            photoLoader.reset()
            ptpClient.disconnect()
            _uiState.update { it.copy(wifiState = CameraWifiState.Idle, mediaItems = emptyList(), userNotice = text(R.string.runtime_wifi_disconnected)) }
        }
    }

    fun loadCameraMedia(
        gatewayIp: String = "192.168.122.1",
        network: android.net.Network? = (_uiState.value.wifiState as? CameraWifiState.Connected)?.network
    ) {
        viewModelScope.launch {
            closePhoto()
            photoLoader.reset()
            _uiState.update { it.copy(isMediaLoading = true) }
            try {
                ptpClient.connect(cameraHost = gatewayIp, network = network)
                val photos = ptpClient.loadMediaList()
                _uiState.update {
                    it.copy(
                        mediaItems = photos.reversed(), // Latest first
                        isMediaLoading = false,
                        userNotice = if (photos.isNotEmpty()) AppLanguage.localizedContext(getApplication<Application>()).resources
                            .getQuantityString(R.plurals.runtime_photos_loaded, photos.size, photos.size) else text(R.string.runtime_no_photos)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isMediaLoading = false,
                        userNotice = text(R.string.runtime_media_load_failed, e.message ?: text(R.string.runtime_network_timeout))
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
            _uiState.update { it.copy(userNotice = text(R.string.runtime_export_starting, metadata.filename)) }
            try {
                if (transcodeToJpeg && metadata.isHeif) {
                    val tempHeif = ptpDownloader.downloadToTempFile(metadata)
                    val targetJpeg = File(tempHeif.parentFile, metadata.filename.substringBeforeLast(".") + ".jpg")
                    heifTranscoder.transcodeHeifToJpeg(tempHeif, targetJpeg, jpegQuality)
                    tempHeif.delete()
                    _uiState.update { it.copy(userNotice = text(R.string.runtime_export_transcoded, metadata.filename, jpegQuality)) }
                } else {
                    ptpDownloader.downloadToMediaStore(metadata).collect { progress ->
                        _uiState.update { current ->
                            val map = current.downloadProgressMap.toMutableMap()
                            map[metadata.filename] = progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat()
                            current.copy(
                                downloadProgressMap = map,
                                userNotice = if (progress.isComplete) text(R.string.runtime_export_complete, metadata.filename) else text(R.string.runtime_export_progress, metadata.filename)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(userNotice = text(R.string.runtime_export_failed, e.message ?: text(R.string.runtime_unknown_error))) }
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
            _uiState.update { it.copy(isFtpServerRunning = true, localFtpIp = ip, userNotice = text(R.string.runtime_ftp_started, ip ?: "0.0.0.0", 2121)) }
        } else {
            ftpServer.stop()
            _uiState.update { it.copy(isFtpServerRunning = false, userNotice = text(R.string.runtime_ftp_stopped)) }
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(userNotice = null) }
    }

    // Remote ownership is separate from the GPS/GATT lane. Transport ACK != saved image.
    private fun launchRemote(
        mode: RemoteOperationMode, durationSeconds: Int = 0, total: Int = 0, manualHold: Boolean = false,
        action: suspend () -> Boolean
    ): Boolean {
        if (_remoteShooting.value.isBusy || remoteJob?.isActive == true || _remoteShooting.value.releaseUnconfirmed) return false
        val session = gattManager.remoteSessionToken()
        if (session == null) {
            _remoteShooting.update { it.copy(error = com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_not_connected)) }
            return false
        }
        remoteStartedAt = 0L; remoteNextAt = 0L
        _remoteShooting.update { it.copy(mode = mode, phase = RemoteOperationPhase.STARTING,
            elapsedSeconds = 0, remainingSeconds = durationSeconds, submittedTriggers = 0,
            totalTriggers = total, nextTriggerSeconds = 0, manualHold = manualHold, shutterPressAccepted = false, error = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            var accepted = false
            var cancelled = false
            val timer = launch {
                while (isActive) {
                    delay(200)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (remoteStartedAt > 0L) {
                        val elapsed = ((now - remoteStartedAt) / 1000).toInt().coerceAtLeast(0)
                        val next = if (remoteNextAt > now) ((remoteNextAt - now + 999) / 1000).toInt() else 0
                        _remoteShooting.update { it.copy(elapsedSeconds = elapsed,
                            remainingSeconds = (durationSeconds - elapsed).coerceAtLeast(0), nextTriggerSeconds = next) }
                    }
                }
            }
            try {
                if (gattManager.remoteSessionToken() !== session) throw CancellationException("Remote session replaced")
                accepted = action()
                if (!accepted) _remoteShooting.update { it.copy(error = com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_command_failed)) }
            } catch (cancel: CancellationException) {
                cancelled = true
                throw cancel
            } catch (_: Exception) {
                _remoteShooting.update { it.copy(error = com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_command_failed)) }
            } finally {
                timer.cancel()
                withContext(NonCancellable) {
                    _remoteShooting.update { it.copy(phase = RemoteOperationPhase.STOPPING) }
                    val needsCleanup = cancelled || !accepted
                    val released = if (needsCleanup) shutterController.releaseAll(expectedSession = session) else true
                    val uncertain = _remoteShooting.value.releaseUnconfirmed || (needsCleanup && !released)
                    if (uncertain) _uiState.update { it.copy(userNotice = text(
                        if (mode == RemoteOperationMode.BULB) R.string.remote_runtime_bulb_unknown else R.string.remote_runtime_release_unknown)) }
                    val captureMode = mode in listOf(RemoteOperationMode.SINGLE, RemoteOperationMode.BURST, RemoteOperationMode.BULB, RemoteOperationMode.INTERVAL)
                    val finalElapsed = if (remoteStartedAt > 0L) ((android.os.SystemClock.elapsedRealtime() - remoteStartedAt) / 1000).toInt().coerceAtLeast(0) else 0
                    if (captureMode || needsCleanup) manualAfSession = null
                    _remoteShooting.update { it.copy(phase = RemoteOperationPhase.IDLE, nextTriggerSeconds = 0,
                        elapsedSeconds = finalElapsed, remainingSeconds = (durationSeconds - finalElapsed).coerceAtLeast(0),
                        afRequested = if (captureMode || needsCleanup) false else it.afRequested,
                        releaseUnconfirmed = uncertain,
                        error = if (uncertain && it.error == null) com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_release_unknown) else it.error) }
                    if (remoteJob === owner) remoteJob = null
                }
            }
        }
        remoteJob = job
        job.start()
        return true
    }

    private fun markRemoteRunning() {
        remoteStartedAt = android.os.SystemClock.elapsedRealtime()
        _remoteShooting.update { it.copy(phase = if (it.phase == RemoteOperationPhase.STOPPING) it.phase else RemoteOperationPhase.RUNNING, shutterPressAccepted = true) }
    }

    fun takePhoto() = launchRemote(RemoteOperationMode.SINGLE) {
        shutterController.takePhoto()
    }

    fun toggleAfLock() {
        if (_remoteShooting.value.afRequested) halfPressAfUp() else halfPressAfDown()
    }
    fun halfPressAfDown() = launchRemote(RemoteOperationMode.AF) {
        val session = gattManager.remoteSessionToken()
        val result = shutterController.halfPressAfDown()
        if (result) { manualAfSession = session; _remoteShooting.update { it.copy(afRequested = true) } }
        result
    }
    fun halfPressAfUp() = launchRemote(RemoteOperationMode.AF) {
        val result = shutterController.halfPressAfUp()
        if (result) { manualAfSession = null; _remoteShooting.update { it.copy(afRequested = false) } }
        result
    }
    fun toggleMovieRecording() = launchRemote(RemoteOperationMode.MOVIE) {
        // Keep the verified toggle pulse. Only FF02 feedback changes recording UI state.
        shutterController.toggleMovieRecording()
    }

    /** Returns a per-gesture ownership token; a stale finger-up cannot stop a newer operation. */
    fun pressBurst(): Long? = beginRemotePress(RemoteOperationMode.BURST)
    fun pressBulb(): Long? = beginRemotePress(RemoteOperationMode.BULB)

    private fun beginRemotePress(mode: RemoteOperationMode): Long? {
        if (_remoteShooting.value.isBusy || remoteJob?.isActive == true || _remoteShooting.value.releaseUnconfirmed) return null
        val press = RemotePress(++remotePressCounter, CompletableDeferred())
        activeRemotePress = press
        val initialSession = gattManager.remoteSessionToken()
        val baseline = gattManager.remoteFeedback.value.shutterSequence
        val started = launchRemote(mode, manualHold = true) {
            try {
                val waitForRelease: suspend () -> Unit = {
                    // Internal lost-input protection, not a user-configured burst duration.
                    withTimeout(3_600_000L) { press.signal.await() }
                }
                if (mode == RemoteOperationMode.BURST) {
                    shutterController.holdShutterUntilReleased(
                        releaseRequested = { press.signal.isCompleted }, awaitRelease = waitForRelease,
                        onHeld = ::markRemoteRunning)
                } else {
                    shutterController.runBulbFromPress(
                        releaseRequested = { press.signal.isCompleted }, awaitRelease = waitForRelease,
                        isExposureActive = { freshShutterActivity(initialSession, baseline) },
                        onStarted = ::markRemoteRunning,
                        onStopping = { _remoteShooting.update { it.copy(phase = RemoteOperationPhase.STOPPING) } },
                        onUncertain = ::bulbUncertain)
                }
            } finally {
                if (activeRemotePress?.token == press.token) activeRemotePress = null
            }
        }
        if (!started && activeRemotePress?.token == press.token) activeRemotePress = null
        return press.token.takeIf { started }
    }

    fun releaseRemotePress(token: Long) {
        val press = activeRemotePress?.takeIf { it.token == token } ?: return
        press.signal.complete(Unit)
        // Before full-press acceptance, cancel queue waiting rather than firing after finger-up.
        if (!_remoteShooting.value.shutterPressAccepted) stopRemoteShooting()
    }

    private fun freshShutterActivity(session: Any?, baseline: Long): Boolean? =
        gattManager.remoteFeedback.value.takeIf {
            gattManager.remoteSessionToken() === session && it.shutterSequence > baseline
        }?.shutterActive

    private fun bulbUncertain() {
        _remoteShooting.update { it.copy(releaseUnconfirmed = true,
            error = com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_bulb_unknown)) }
    }

    fun startTimedBulb(seconds: Int) {
        if (seconds !in 1..3600) { remoteInvalidConfig(); return }
        val initialSession = gattManager.remoteSessionToken()
        val baseline = gattManager.remoteFeedback.value.shutterSequence
        launchRemote(RemoteOperationMode.BULB, seconds) {
            shutterController.runBulbFromPress(
                durationMillis = seconds * 1000L,
                isExposureActive = { freshShutterActivity(initialSession, baseline) },
                onStarted = ::markRemoteRunning,
                onStopping = { _remoteShooting.update { it.copy(phase = RemoteOperationPhase.STOPPING) } },
                onUncertain = ::bulbUncertain)
        }
    }

    fun startIntervalometer(intervalSec: Int, totalShots: Int) {
        if (intervalSec !in 1..3600 || totalShots !in 1..999) { remoteInvalidConfig(); return }
        launchRemote(RemoteOperationMode.INTERVAL, total = totalShots) {
            markRemoteRunning()
            shutterController.runIntervalometer(intervalSec, totalShots,
                onTriggerSubmitted = { current, total -> _remoteShooting.update { it.copy(submittedTriggers = current, totalTriggers = total) } },
                onNextTriggerAt = { remoteNextAt = it })
        }
    }

    private fun remoteInvalidConfig() {
        _remoteShooting.update { it.copy(error = com.letter.alphapro.ui.components.LocalizedText(R.string.remote_runtime_invalid_config)) }
    }

    fun stopRemoteShooting() {
        val current = remoteJob
        if (current != null && _remoteShooting.value.isBusy) {
            _remoteShooting.update { it.copy(phase = RemoteOperationPhase.STOPPING) }
            activeRemotePress?.signal?.complete(Unit)
            current.cancel()
        }
    }

    fun onRemoteScreenLeft() {
        val state = _remoteShooting.value
        if (state.isLongRunning || (state.isBusy && state.mode == RemoteOperationMode.AF)) stopRemoteShooting()
        else if (!state.isBusy && state.afRequested) halfPressAfUp()
    }

    fun notifyRemoteExitBlocked() {
        _uiState.update { it.copy(userNotice = text(R.string.remote_runtime_exit_blocked)) }
    }

    fun acknowledgeRemoteWarning() {
        if (!_remoteShooting.value.isBusy) _remoteShooting.update { it.copy(error = null, releaseUnconfirmed = false) }
    }

    private fun startLocationForegroundService(address: String) {
        val app = getApplication<Application>()
        if (!com.letter.alphapro.data.BackgroundSyncSetupReader.hasForegroundLocation(app)) {
            _uiState.update { it.copy(userNotice = text(R.string.background_sync_location_required)) }
            return
        }
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
        val finishing = remoteJob
        val afSession = manualAfSession
        if (finishing != null || afSession != null) {
            val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            cleanupScope.launch {
                try {
                    finishing?.cancelAndJoin()
                    if (afSession != null) shutterController.releaseAll(expectedSession = afSession)
                } finally { cleanupScope.cancel() }
            }
        }
        super.onCleared()
        ftpServer.stop()
        wifiConnector.release()
        stopScan()
    }
}
