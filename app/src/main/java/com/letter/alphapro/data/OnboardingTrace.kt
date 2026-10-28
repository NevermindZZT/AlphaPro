package com.letter.alphapro.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.letter.alphapro.BuildConfig

/** Local, bounded diagnostics: event metadata/call paths only, never user text or camera data. */
class OnboardingTrace(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alphapro_onboarding_trace", Context.MODE_PRIVATE)

    fun record(event: String) {
        synchronized(lock) {
            val stack = Throwable().stackTrace
            val origin = when {
                // Capture BEFORE coroutine dispatch; retain framework frames to distinguish
                // pointer/key/accessibility actions from a composition or programmatic call.
                event.startsWith("ui_") -> stack.take(48)
                event.contains("exit_requested") || event.startsWith("guide_finish") ->
                    stack.filter { it.className.startsWith("com.letter.alphapro.") }.take(6)
                else -> emptyList()
            }.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            val line = ("${SystemClock.elapsedRealtime()} $event" +
                if (origin.isEmpty()) "" else " caller=$origin").take(12_000)
            val lines = (prefs.getString("events", "").orEmpty().lineSequence()
                .filter { it.isNotBlank() }.toList() + line).takeLast(80).toMutableList()
            var chars = lines.sumOf { it.length + 1 }
            while (chars > 64_000 && lines.size > 1) chars -= lines.removeAt(0).length + 1
            prefs.edit().putString("events", lines.joinToString("\n")).apply()
            Log.i("AlphaProOnboarding", line)
        }
    }

    fun report(): String = synchronized(lock) {
        "AlphaPro ${BuildConfig.VERSION_NAME} onboarding-O5 / API ${Build.VERSION.SDK_INT}\n" +
            prefs.getString("events", "").orEmpty()
    }

    private companion object { val lock = Any() }
}
