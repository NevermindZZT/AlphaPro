package com.letter.alphapro.data

import kotlinx.coroutines.flow.asStateFlow

/** Only explicit guide actions may complete a first-run session. No timers or lifecycle writes. */
enum class OnboardingExitReason { SKIP, CONFIRMED_SKIP, FINISH, CLOSE_REPLAY, BACK }
data class OnboardingExit(val reason: OnboardingExitReason, val page: Int)

class OnboardingSession(
    completedRevision: Int,
    private val persistCompletion: (Int) -> Unit
) {
    private val _visible = kotlinx.coroutines.flow.MutableStateFlow(completedRevision < CURRENT_REVISION)
    val visible = _visible.asStateFlow()

    /** Returns true exactly once, after an accepted explicit action has been persisted. */
    fun requestExit(exit: OnboardingExit): Boolean {
        if (!_visible.value || exit.page !in 0 until PAGE_COUNT) return false
        val accepted = exit.reason == OnboardingExitReason.CONFIRMED_SKIP ||
            (exit.reason == OnboardingExitReason.FINISH && exit.page == PAGE_COUNT - 1)
        if (!accepted) return false
        persistCompletion(CURRENT_REVISION)
        _visible.value = false
        return true
    }

    companion object {
        // Revision 3 requires a separate skip confirmation instead of O4's single callback.
        const val CURRENT_REVISION = 3
        const val PAGE_COUNT = 4
    }
}
