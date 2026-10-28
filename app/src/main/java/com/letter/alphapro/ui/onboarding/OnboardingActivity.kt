package com.letter.alphapro.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
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
import com.letter.alphapro.data.OnboardingTrace
import com.letter.alphapro.ui.theme.AlphaProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Own window/lifecycle; no camera ViewModel, main navigation, permissions or automatic timer. */
class OnboardingActivity : AppCompatActivity() {
    private val preferences by lazy { CameraPreferences(this) }
    private val trace by lazy { OnboardingTrace(this) }
    private var entry = OnboardingEntry.FIRST_RUN
    private lateinit var flow: OnboardingActivityFlow
    private var saving by mutableStateOf(false)
    private var skipConfirmationPage by mutableStateOf<Int?>(null)
    private var navigating = false
    private var lastActivityTouch: Long? = null
    private var lastActivityKey: Long? = null

    override fun attachBaseContext(newBase: Context) {
        AppLanguage.initialize(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = parseEntry(savedInstanceState?.getString(ENTRY) ?: intent.getStringExtra(ENTRY))
        val revision = preferences.completedOnboardingRevision
        trace.record("O5 guide_create entry=$entry restored=${savedInstanceState != null} revision=$revision ${accessibilityState()}")
        if (entry == OnboardingEntry.FIRST_RUN && !OnboardingActivityFlow.requiresFirstRun(revision)) {
            // Recover if the process died after a successful save but before the Main handoff.
            trace.record("guide_recover_saved_completion")
            openMainAndFinish()
            return
        }
        val session = OnboardingSession(revision,
            persistCompletion = { preferences.completedOnboardingRevision = it }, trace = trace::record)
        flow = OnboardingActivityFlow(entry, session, trace::record)
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
                    actionsEnabled = !saving,
                    onUiAction = { action -> trace.record("ui_screen_action $action ${inputAges()} ${accessibilityState()}") }
                )
                skipConfirmationPage?.let { page ->
                    OnboardingSkipConfirmation(
                        enabled = !saving,
                        onConfirm = {
                            trace.record("ui_skip_confirm_button page=$page ${inputAges()}")
                            handleExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page))
                        },
                        onCancel = ::cancelSkipConfirmation,
                        onTrace = trace::record
                    )
                }
            }
        }
    }

    private fun handleExit(exit: OnboardingExit) {
        trace.record("ui_exit_sync entry=$entry reason=${exit.reason} page=${exit.page} saving=$saving navigating=$navigating main_thread=${Looper.myLooper() == Looper.getMainLooper()} ${inputAges()} ${accessibilityState()}")
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
                        trace.record("guide_close_without_main entry=$entry reason=${exit.reason}")
                        finish()
                    }
                    OnboardingDestination.MAIN -> openMainAndFinish()
                }
            } catch (_: IllegalStateException) {
                trace.record("guide_completion_save_failed")
                Toast.makeText(this@OnboardingActivity, R.string.onboarding_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    private fun cancelSkipConfirmation() {
        if (saving || navigating) return
        trace.record("ui_skip_cancel page=$skipConfirmationPage ${inputAges()}")
        flow.cancelSkip()
        skipConfirmationPage = null
    }

    private fun inputAges(): String {
        val now = SystemClock.elapsedRealtime()
        return "activity_touch_age_ms=${lastActivityTouch?.let { now - it } ?: "none"} " +
            "activity_key_age_ms=${lastActivityKey?.let { now - it } ?: "none"}"
    }

    private fun accessibilityState(): String {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return "accessibility_enabled=${manager?.isEnabled} touch_exploration=${manager?.isTouchExplorationEnabled}"
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastActivityTouch = SystemClock.elapsedRealtime()
            val tool = if (event.pointerCount > 0) event.getToolType(0) else -1
            trace.record("input_activity_touch action=${event.actionMasked} source=${event.source} flags=${event.flags} tool=$tool")
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            lastActivityKey = SystemClock.elapsedRealtime()
            trace.record("input_activity_key action=${event.action} code=${event.keyCode} source=${event.source} repeat=${event.repeatCount}")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        trace.record("input_window_focus focused=$hasFocus")
    }

    private fun openMainAndFinish() {
        if (navigating) return
        navigating = true
        trace.record("guide_handoff_main entry=$entry")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requested = parseEntry(intent.getStringExtra(ENTRY))
        trace.record("guide_new_intent current=$entry requested=$requested")
        if (requested == OnboardingEntry.FIRST_RUN && entry == OnboardingEntry.REPLAY &&
            OnboardingActivityFlow.requiresFirstRun(preferences.completedOnboardingRevision)) {
            // CLEAR_TOP matches the component, not its entry extra. Replace rather than
            // turn a required first run into a replay that could expose the old Settings.
            trace.record("guide_replace_replay_with_first_run")
            startActivity(Intent(this, OnboardingActivity::class.java)
                .putExtra(ENTRY, OnboardingEntry.FIRST_RUN.name))
            finish()
        }
        // Same-entry duplicates preserve mode, screen composition and saveable pager state.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ENTRY, entry.name)
        trace.record("guide_save_state entry=$entry")
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        trace.record("guide_resume entry=$entry")
    }

    override fun onPause() {
        trace.record("guide_pause entry=$entry")
        super.onPause()
    }

    override fun onStop() {
        trace.record("guide_stop entry=$entry finishing=$isFinishing")
        super.onStop()
    }

    override fun finish() {
        trace.record("guide_finish entry=$entry navigating=$navigating")
        super.finish()
    }

    override fun onDestroy() {
        trace.record("guide_destroy entry=$entry finishing=$isFinishing changing=$isChangingConfigurations")
        super.onDestroy()
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
