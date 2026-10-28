package com.letter.alphapro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.letter.alphapro.data.AppThemeMode
import com.letter.alphapro.data.AppThemeState
import com.letter.alphapro.data.CameraPreferences
import com.letter.alphapro.data.OnboardingActivityFlow
import com.letter.alphapro.data.OnboardingTrace
import com.letter.alphapro.ui.navigation.AlphaProApp
import com.letter.alphapro.ui.onboarding.OnboardingActivity
import com.letter.alphapro.ui.theme.AlphaProTheme
import com.letter.alphapro.viewmodel.CameraViewModel

class MainActivity : AppCompatActivity() {
    private val viewModel: CameraViewModel by viewModels()
    private val preferences by lazy { CameraPreferences(this) }
    private val onboardingTrace by lazy { OnboardingTrace(this) }
    private var routingToGuide = false
    private var permissionsInFlight = false
    private var permissionsResolved by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onboardingTrace.record("main_permissions_returned granted=${result.values.count { it }} total=${result.size}")
        permissionsInFlight = false
        permissionsResolved = true
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        com.letter.alphapro.data.AppLanguage.initialize(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onboardingTrace.record("O5 main_create restored=${savedInstanceState != null} revision=${preferences.completedOnboardingRevision}")
        // This must precede setContent, permission requests and ANY camera ViewModel access.
        if (routeToGuideIfRequired()) return

        enableEdgeToEdge()
        permissionsInFlight = savedInstanceState?.getBoolean(PERMISSIONS_PENDING) == true
        if (!permissionsInFlight) requestRequiredPermissions()
        setContent {
            val themeMode by AppThemeState.themeMode.collectAsState()
            val dynamicColor by AppThemeState.dynamicColor.collectAsState()
            val isDark = when (themeMode) {
                AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            if (permissionsResolved) {
                val viewingPhoto by viewModel.viewingPhoto.collectAsState()
                AlphaProTheme(darkTheme = isDark, dynamicColor = dynamicColor,
                    photoViewerVisible = viewingPhoto != null) {
                    AlphaProApp(viewModel = viewModel)
                }
            } else {
                AlphaProTheme(darkTheme = isDark, dynamicColor = dynamicColor) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onboardingTrace.record("main_new_intent")
        if (!routeToGuideIfRequired() && !permissionsInFlight) requestRequiredPermissions()
    }

    private fun routeToGuideIfRequired(): Boolean {
        // Intent extras or saved navigation state are never completion authority.
        if (!OnboardingActivityFlow.requiresFirstRun(preferences.completedOnboardingRevision)) return false
        if (!routingToGuide) {
            routingToGuide = true
            onboardingTrace.record("main_route_to_independent_guide")
            startActivity(OnboardingActivity.firstRunIntent(this))
            finish()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        onboardingTrace.record("main_resume routing=$routingToGuide")
    }

    override fun onPause() {
        onboardingTrace.record("main_pause")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSIONS_PENDING, permissionsInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        onboardingTrace.record("main_destroy finishing=$isFinishing changing=$isChangingConfigurations")
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        if (permissionsInFlight || routingToGuide) return
        onboardingTrace.record("main_permissions_requested")
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsInFlight = true
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            permissionsResolved = true
        }
    }

    private companion object { const val PERMISSIONS_PENDING = "permissions_pending" }
}
