package com.letter.alphapro.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.letter.alphapro.MainActivity
import com.letter.alphapro.R
import com.letter.alphapro.data.AppLanguage
import com.letter.alphapro.data.AppThemeMode
import com.letter.alphapro.data.AppThemeState
import com.letter.alphapro.data.CameraPreferences
import com.letter.alphapro.data.OnboardingActivityFlow
import com.letter.alphapro.data.OnboardingDestination
import com.letter.alphapro.data.OnboardingEntry
import com.letter.alphapro.data.OnboardingExit
import com.letter.alphapro.data.OnboardingExitReason
import com.letter.alphapro.data.OnboardingSession
import com.letter.alphapro.ui.theme.AlphaProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Own window/lifecycle; no camera ViewModel, main navigation, permissions or automatic timer. */
class OnboardingActivity : AppCompatActivity() {
    private val preferences by lazy { CameraPreferences(this) }
    private var entry = OnboardingEntry.FIRST_RUN
    private lateinit var flow: OnboardingActivityFlow
    private var saving by mutableStateOf(false)
    private var skipConfirmationPage by mutableStateOf<Int?>(null)
    private var navigating = false

    override fun attachBaseContext(newBase: Context) {
        AppLanguage.initialize(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = parseEntry(savedInstanceState?.getString(ENTRY) ?: intent.getStringExtra(ENTRY))
        val revision = preferences.completedOnboardingRevision
        if (entry == OnboardingEntry.FIRST_RUN && !OnboardingActivityFlow.requiresFirstRun(revision)) {
            // Recover if the process died after a successful save but before the Main handoff.
            openMainAndFinish()
            return
        }
        val session = OnboardingSession(revision,
            persistCompletion = { preferences.completedOnboardingRevision = it })
        flow = OnboardingActivityFlow(entry, session)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppThemeState.themeMode.collectAsState()
            val dynamicColor by AppThemeState.dynamicColor.collectAsState()
            val dark = when (themeMode) {
                AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            AlphaProTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                OnboardingScreen(
                    onExit = ::handleExit,
                    isReplay = entry == OnboardingEntry.REPLAY,
                    actionsEnabled = !saving
                )
                skipConfirmationPage?.let { page ->
                    OnboardingSkipConfirmation(
                        enabled = !saving,
                        onConfirm = {
                            handleExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page))
                        },
                        onCancel = ::cancelSkipConfirmation
                    )
                }
            }
        }
    }

    private fun handleExit(exit: OnboardingExit) {
        if (saving || navigating || isFinishing) return
        saving = true
        lifecycleScope.launch {
            try {
                // Completion is checked and durably written before another Activity is started.
                val destination = withContext(Dispatchers.IO) { flow.onExit(exit) }
                when (destination) {
                    OnboardingDestination.STAY -> Unit
                    OnboardingDestination.CONFIRM_SKIP -> {
                        skipConfirmationPage = exit.page
                    }
                    OnboardingDestination.CLOSE -> {
                        navigating = true
                        finish()
                    }
                    OnboardingDestination.MAIN -> openMainAndFinish()
                }
            } catch (_: IllegalStateException) {
                Toast.makeText(this@OnboardingActivity, R.string.onboarding_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    private fun cancelSkipConfirmation() {
        if (saving || navigating) return
        flow.cancelSkip()
        skipConfirmationPage = null
    }

    private fun openMainAndFinish() {
        if (navigating) return
        navigating = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requested = parseEntry(intent.getStringExtra(ENTRY))
        if (requested == OnboardingEntry.FIRST_RUN && entry == OnboardingEntry.REPLAY &&
            OnboardingActivityFlow.requiresFirstRun(preferences.completedOnboardingRevision)) {
            // CLEAR_TOP matches the component, not its entry extra. Replace rather than
            // turn a required first run into a replay that could expose the old Settings.
            startActivity(Intent(this, OnboardingActivity::class.java)
                .putExtra(ENTRY, OnboardingEntry.FIRST_RUN.name))
            finish()
        }
        // Same-entry duplicates preserve mode, screen composition and saveable pager state.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ENTRY, entry.name)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val ENTRY = "onboarding_entry"
        private fun parseEntry(value: String?): OnboardingEntry =
            OnboardingEntry.entries.firstOrNull { it.name == value } ?: OnboardingEntry.FIRST_RUN

        fun firstRunIntent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
            .putExtra(ENTRY, OnboardingEntry.FIRST_RUN.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        // Ordinary same-task launch: do not clear Main/Settings or reuse a first-run host.
        fun replayIntent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
            .putExtra(ENTRY, OnboardingEntry.REPLAY.name)
    }
}
