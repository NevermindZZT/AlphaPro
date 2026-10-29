package com.letter.alphapro.data

/** Entry is fixed for an Activity instance, including across recreation/new intents. */
enum class OnboardingEntry { FIRST_RUN, REPLAY }
enum class OnboardingDestination { STAY, CONFIRM_SKIP, CLOSE, MAIN }

class OnboardingActivityFlow(
    private val entry: OnboardingEntry,
    private val session: OnboardingSession
) {
    private var terminal = false
    private var pendingSkipPage: Int? = null

    fun onExit(exit: OnboardingExit): OnboardingDestination {
        if (terminal) return OnboardingDestination.STAY
        if (entry == OnboardingEntry.REPLAY) {
            terminal = true
            return OnboardingDestination.CLOSE
        }
        if (exit.reason == OnboardingExitReason.SKIP) {
            if (exit.page !in 0 until OnboardingSession.PAGE_COUNT) return OnboardingDestination.STAY
            pendingSkipPage = exit.page
            return OnboardingDestination.CONFIRM_SKIP
        }
        if (exit.reason == OnboardingExitReason.CONFIRMED_SKIP) {
            if (pendingSkipPage != exit.page) return OnboardingDestination.STAY
            // Keep the pending request if saving fails, so the visible confirmation can retry.
        } else if (pendingSkipPage != null) {
            return OnboardingDestination.STAY
        }
        if (exit.reason == OnboardingExitReason.BACK) {
            terminal = true
            return OnboardingDestination.CLOSE
        }
        if (session.requestExit(exit)) {
            pendingSkipPage = null
            terminal = true
            return OnboardingDestination.MAIN
        }
        return OnboardingDestination.STAY
    }

    fun cancelSkip() {
        pendingSkipPage = null
    }

    companion object {
        fun requiresFirstRun(completedRevision: Int): Boolean =
            completedRevision < OnboardingSession.CURRENT_REVISION
    }
}
