package com.letter.alphapro.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.letter.alphapro.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import com.letter.alphapro.ui.gallery.*
import com.letter.alphapro.ui.hub.CameraHubScreen
import com.letter.alphapro.ui.remote.RemoteShutterScreen
import com.letter.alphapro.ui.tether.TetherScreen
import com.letter.alphapro.viewmodel.CameraViewModel

enum class AlphaProDestination(@StringRes val titleRes: Int, val icon: ImageVector) {
    HUB(R.string.nav_dashboard, Icons.Rounded.Camera),
    GALLERY(R.string.nav_gallery, Icons.Rounded.PhotoLibrary),
    TETHER(R.string.nav_tether, Icons.Rounded.Sync),
    REMOTE(R.string.nav_remote, Icons.Rounded.RadioButtonChecked),
}

@Composable
fun AlphaProApp(viewModel: CameraViewModel = viewModel()) {
    var currentDestination by rememberSaveable { mutableStateOf(AlphaProDestination.HUB) }
    val uiState by viewModel.uiState.collectAsState()
    val viewingPhoto by viewModel.viewingPhoto.collectAsState()
    val preview by viewModel.photoPreview.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var downloadGroup by remember { mutableStateOf<CameraCaptureGroup?>(null) }
    var heifGroup by remember { mutableStateOf<CameraCaptureGroup?>(null) }

    LaunchedEffect(uiState.userNotice) {
        uiState.userNotice?.let { snackbarHostState.showSnackbar(it); viewModel.clearNotice() }
    }
    uiState.wifiFailureDiagnostic?.let {
        com.letter.alphapro.ui.components.WifiDiagnosticDialog(it, viewModel::dismissWifiDiagnostic)
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (viewingPhoto != null) Modifier.clearAndSetSemantics {} else Modifier,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    AlphaProDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination == destination,
                            onClick = { currentDestination = destination },
                            icon = { Icon(destination.icon, stringResource(destination.titleRes)) },
                            label = { Text(stringResource(destination.titleRes)) }
                        )
                    }
                }
            }
        ) { padding ->
            val modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
            when (currentDestination) {
                AlphaProDestination.HUB -> CameraHubScreen(viewModel, modifier)
                AlphaProDestination.GALLERY -> CameraGalleryScreen(viewModel, modifier)
                AlphaProDestination.TETHER -> TetherScreen(viewModel, modifier)
                AlphaProDestination.REMOTE -> RemoteShutterScreen(viewModel, modifier)
            }
        }
        // Outside the main Scaffold: no bottom-tab padding or floating-window measurement.
        viewingPhoto?.let { group ->
            BackHandler(onBack = viewModel::closePhoto)
            PhotoDetailViewerScreen(
                group = group,
                thumbnail = thumbnails[group.visualFile.handle],
                preview = preview,
                onRetry = { viewModel.requestPhotoPreview(group.visualFile) },
                onDismissRequest = viewModel::closePhoto,
                onDownloadClick = {
                    viewModel.closePhoto()
                    when {
                        it.isDualFormat -> downloadGroup = it
                        it.hasHeif -> heifGroup = it
                        else -> viewModel.exportPhoto(it.visualFile, false)
                    }
                }
            )
        }
    }
    downloadGroup?.let { group ->
        GroupDownloadDialog(group, { downloadGroup = null }) { target, transcode ->
            downloadGroup = null
            viewModel.exportCaptureGroup(group, target, transcode)
        }
    }
    heifGroup?.let { group ->
        HeifExportBottomSheet(1, 1, { heifGroup = null }) { mode, quality ->
            heifGroup = null
            viewModel.exportPhoto(group.visualFile, mode == HeifExportMode.TRANSCODE_JPEG, quality)
        }
    }
}
