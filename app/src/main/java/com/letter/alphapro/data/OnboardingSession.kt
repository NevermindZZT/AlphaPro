package com.letter.alphapro.data

import kotlinx.coroutines.flow.asStateFlow

/** Only explicit guide actions may complete a first-run session. No timers or lifecycle writes. */
enum class OnboardingExitReason { SKIP, CONFIRMED_SKIP, FINISH, CLOSE_REPLAY, BACK }
data class OnboardingExit(val reason: OnboardingExitReason, val page: Int)

class OnboardingSession(
    completedRevision: Int,
    private val persistCompletion: (Int) -> Unit,
    private val trace: (String) -> Unit = {}
) {
    private val _visible = kotlinx.coroutines.flow.MutableStateFlow(completedRevision < CURRENT_REVISION)
    val visible = _visible.asStateFlow()

    init {
        trace("session_created revision=$completedRevision visible=${_visible.value}")
    }

    /** Returns true exactly once, after an accepted explicit action has been persisted. */
    fun requestExit(exit: OnboardingExit): Boolean {
        trace("exit_requested reason=${exit.reason} page=${exit.page} visible=${_visible.value}")
        if (!_visible.value || exit.page !in 0 until PAGE_COUNT) return false
        val accepted = exit.reason == OnboardingExitReason.CONFIRMED_SKIP ||
            (exit.reason == OnboardingExitReason.FINISH && exit.page == PAGE_COUNT - 1)
        if (!accepted) {
            trace("exit_ignored")
            return false
        }
        persistCompletion(CURRENT_REVISION)
        _visible.value = false
        trace("completion_saved revision=$CURRENT_REVISION reason=${exit.reason}")
        return true
    }

    companion object {
        // Revision 3 requires a separate skip confirmation instead of O4's single callback.
        const val CURRENT_REVISION = 3
        const val PAGE_COUNT = 4
    }
}
