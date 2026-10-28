package com.letter.alphapro.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.letter.alphapro.data.AppThemeMode
import com.letter.alphapro.ui.theme.AlphaProTheme
import com.letter.alphapro.viewmodel.CameraViewModel

class SettingsActivity : AppCompatActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        com.letter.alphapro.data.AppLanguage.initialize(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferences = com.letter.alphapro.data.CameraPreferences(this)
        if (com.letter.alphapro.data.OnboardingActivityFlow.requiresFirstRun(preferences.completedOnboardingRevision)) {
            com.letter.alphapro.data.OnboardingTrace(this).record("settings_route_required_first_run")
            startActivity(com.letter.alphapro.ui.onboarding.OnboardingActivity.firstRunIntent(this))
            finish()
            return
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val dynamicColor by viewModel.dynamicColor.collectAsState()

            val isDark = when (themeMode) {
                AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            AlphaProTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColor
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}
