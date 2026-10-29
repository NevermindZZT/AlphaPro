package com.letter.alphapro.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.letter.alphapro.R
import com.letter.alphapro.data.BackgroundSyncSetupReader
import com.letter.alphapro.data.LocationSetupAction
import com.letter.alphapro.data.LocationSetupStep

/** Explicit user actions only. Re-read Android on return; never infer a grant from RESULT_OK. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundSyncSetupCard(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(context) { mutableStateOf(BackgroundSyncSetupReader.read(context)) }
    var busy by remember { mutableStateOf(false) }
    val refresh = {
        state = BackgroundSyncSetupReader.read(context)
        busy = false
    }
    val latestRefresh by rememberUpdatedState(refresh)
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        latestRefresh()
    }
    val foregroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        latestRefresh()
    }
    val backgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        latestRefresh()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null))

    fun unavailable() {
        busy = false
        Toast.makeText(context, R.string.background_sync_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    fun openSystem(candidates: List<Intent>) {
        if (!enabled || busy) return
        busy = true
        for (intent in candidates) {
            try {
                systemSettings.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some vendor Settings apps omit the standard activity; try the next public action.
            } catch (_: SecurityException) {
                // Restricted device/profile: do not claim the setting was changed.
            }
        }
        unavailable()
    }

    val alwaysLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.backgroundPermissionOptionLabel.toString()
    } else stringResource(R.string.background_sync_always_label)
    val locationHint = when {
        state.locationStep == LocationSetupStep.FOREGROUND_PERMISSION ->
            stringResource(R.string.background_sync_location_foreground_hint)
        state.apiLevel < 29 -> stringResource(R.string.background_sync_location_legacy_hint)
        state.locationReady -> stringResource(R.string.background_sync_location_ready_hint)
        else -> stringResource(R.string.background_sync_location_background_hint, alwaysLabel)
    }
    val locationStatus = stringResource(when (state.locationStep) {
        LocationSetupStep.FOREGROUND_PERMISSION -> R.string.background_sync_location_missing_foreground
        LocationSetupStep.BACKGROUND_PERMISSION -> R.string.background_sync_location_missing_background
        LocationSetupStep.READY -> R.string.background_sync_location_ready
    })
    val batteryStatus = stringResource(when (state.batteryExempt) {
        true -> R.string.background_sync_battery_ready
        false -> R.string.background_sync_battery_missing
        null -> R.string.background_sync_battery_unknown
    })

    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.background_sync_setup_title),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.background_sync_setup_intro),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SetupDescription(Icons.Rounded.LocationOn, stringResource(R.string.background_sync_location_title),
                locationHint, locationStatus, state.locationReady)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AlphaTonalButton(enabled = enabled && !busy, size = AlphaButtonSize.Compact, onClick = {
                    if (enabled && !busy) {
                        // A grant may have been revoked while this page was in memory.
                        val current = BackgroundSyncSetupReader.read(context)
                        state = current
                        when (current.locationAction) {
                            LocationSetupAction.REQUEST_FOREGROUND -> {
                                busy = true
                                try {
                                    foregroundLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION))
                                } catch (_: SecurityException) { unavailable() }
                            }
                            LocationSetupAction.REQUEST_BACKGROUND -> {
                                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                    busy = true
                                    try {
                                        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    } catch (_: SecurityException) { unavailable() }
                                } else openSystem(listOf(appInfo(), Intent(Settings.ACTION_APPLICATION_SETTINGS)))
                            }
                            LocationSetupAction.OPEN_APP_SETTINGS ->
                                openSystem(listOf(appInfo(), Intent(Settings.ACTION_APPLICATION_SETTINGS)))
                        }
                    }
                }) {
                    Text(stringResource(when (state.locationStep) {
                        LocationSetupStep.FOREGROUND_PERMISSION -> R.string.background_sync_location_grant
                        LocationSetupStep.BACKGROUND_PERMISSION -> if (state.locationAction == LocationSetupAction.OPEN_APP_SETTINGS)
                            R.string.background_sync_location_settings else R.string.background_sync_location_always
                        LocationSetupStep.READY -> R.string.background_sync_location_manage
                    }))
                }
                if (state.locationAction != LocationSetupAction.OPEN_APP_SETTINGS) {
                    // Also reachable after a permanent denial, without repeatedly launching a dialog.
                    AlphaTextButton(enabled = enabled && !busy, size = AlphaButtonSize.Compact,
                        onClick = { openSystem(listOf(appInfo(), Intent(Settings.ACTION_APPLICATION_SETTINGS))) }) {
                        Text(stringResource(R.string.background_sync_location_manage))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SetupDescription(Icons.Rounded.BatteryChargingFull, stringResource(R.string.background_sync_battery_title),
                stringResource(R.string.background_sync_battery_hint), batteryStatus, state.batteryReady)
            AlphaTonalButton(enabled = enabled && !busy, size = AlphaButtonSize.Compact, onClick = {
                if (enabled && !busy) {
                    val current = BackgroundSyncSetupReader.read(context)
                    state = current
                    openSystem(listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appInfo()))
                }
            }) {
                Text(stringResource(if (state.batteryReady) R.string.background_sync_battery_manage
                    else R.string.background_sync_battery_allow))
            }
            Text(stringResource(R.string.background_sync_setup_caution),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupDescription(icon: ImageVector, title: String, hint: String, status: String, ready: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ready) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
