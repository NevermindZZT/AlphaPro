package com.letter.alphapro.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.letter.alphapro.ui.gallery.CameraGalleryScreen
import com.letter.alphapro.ui.hub.CameraHubScreen
import com.letter.alphapro.ui.remote.RemoteShutterScreen
import com.letter.alphapro.ui.tether.TetherScreen
import com.letter.alphapro.viewmodel.CameraViewModel

enum class AlphaProDestination(
    val title: String,
    val icon: ImageVector,
) {
    HUB("仪表盘", Icons.Rounded.Camera),
    GALLERY("相机相册", Icons.Rounded.PhotoLibrary),
    TETHER("边拍边传", Icons.Rounded.Sync),
    REMOTE("无线快门", Icons.Rounded.RadioButtonChecked),
}

@Composable
fun AlphaProApp(viewModel: CameraViewModel = viewModel()) {
    var currentDestination by remember { mutableStateOf(AlphaProDestination.HUB) }
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userNotice) {
        uiState.userNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AlphaProDestination.entries.forEach { destination ->
                    val selected = currentDestination == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentDestination = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title
                            )
                        },
                        label = { Text(destination.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        when (currentDestination) {
            AlphaProDestination.HUB -> CameraHubScreen(viewModel = viewModel, modifier = modifier)
            AlphaProDestination.GALLERY -> CameraGalleryScreen(viewModel = viewModel, modifier = modifier)
            AlphaProDestination.TETHER -> TetherScreen(viewModel = viewModel, modifier = modifier)
            AlphaProDestination.REMOTE -> RemoteShutterScreen(viewModel = viewModel, modifier = modifier)
        }
    }
}
