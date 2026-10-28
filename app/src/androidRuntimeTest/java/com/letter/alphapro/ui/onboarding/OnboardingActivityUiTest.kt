package com.letter.alphapro.ui.onboarding

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letter.alphapro.data.OnboardingActivityFlow
import com.letter.alphapro.data.OnboardingDestination
import com.letter.alphapro.data.OnboardingEntry
import com.letter.alphapro.data.OnboardingExit
import com.letter.alphapro.data.OnboardingExitReason
import com.letter.alphapro.data.OnboardingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Opt-in screen-to-production-flow integration, not a replacement Activity implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingActivityUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun useManualAnimationClock() {
        // The real screen contains infinite animations: never wait for animation quiescence.
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun idleAnimationsAndRecompositionKeepTheCurrentPageWithoutRequestingAnExit() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)
        val exits = mutableListOf<OnboardingExit>()
        val destinations = mutableListOf<OnboardingDestination>()
        val hostVersion = mutableIntStateOf(0)
        var observedVersion = -1
        compose.setContent {
            val version = hostVersion.intValue
            SideEffect { observedVersion = version }
            MaterialTheme {
                OnboardingScreen(onExit = { exit ->
                    exits += exit
                    destinations += flow.onExit(exit)
                })
            }
        }
        advanceAnimations(12_000)
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        advanceAnimations()
        compose.onNodeWithText("Previous").assertIsDisplayed()

        compose.runOnIdle { hostVersion.intValue++ }
        advanceAnimations(12_000)

        compose.onNodeWithText("Previous").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, observedVersion)
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
            assertTrue(exits.isEmpty())
            assertTrue(destinations.isEmpty())
        }
    }

    @Test
    fun explicitRawSkipRequestsConfirmationAndRepeatedClicksNeverPersist() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)
        val exits = mutableListOf<OnboardingExit>()
        val destinations = mutableListOf<OnboardingDestination>()
        showScreen(flow, exits, destinations)
        advanceAnimations()

        compose.onNodeWithText("Later").assertIsDisplayed().performClick()
        advanceAnimations()
        compose.runOnIdle {
            assertEquals(listOf(OnboardingExit(OnboardingExitReason.SKIP, 0)), exits)
            assertEquals(listOf(OnboardingDestination.CONFIRM_SKIP), destinations)
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
        }

        // This fixture only connects the real screen callback to the production flow.
        // It does not render the Activity's dialog or claim to verify Activity navigation.
        repeat(3) {
            compose.onNodeWithText("Later").performClick()
            advanceAnimations()
        }
        compose.runOnIdle {
            assertEquals(List(4) { OnboardingExit(OnboardingExitReason.SKIP, 0) }, exits)
            assertEquals(List(4) { OnboardingDestination.CONFIRM_SKIP }, destinations)
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun nextAcrossAllPagesDoesNotCompleteUntilExplicitGetStarted() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)
        val exits = mutableListOf<OnboardingExit>()
        val destinations = mutableListOf<OnboardingDestination>()
        showScreen(flow, exits, destinations)
        advanceAnimations()

        repeat(OnboardingSession.PAGE_COUNT - 1) {
            compose.onNodeWithText("Next").performClick()
            advanceAnimations()
            compose.runOnIdle {
                assertTrue(session.visible.value)
                assertTrue(writes.isEmpty())
                assertTrue(exits.isEmpty())
                assertTrue(destinations.isEmpty())
            }
        }
        compose.onNodeWithText("Later").assertDoesNotExist()
        compose.onNodeWithText("Get Started").assertIsDisplayed().performClick()
        advanceAnimations()

        compose.runOnIdle {
            assertEquals(
                listOf(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)),
                exits
            )
            assertEquals(listOf(OnboardingDestination.MAIN), destinations)
            assertFalse(session.visible.value)
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        }
    }

    @Test
    fun incompleteSessionReplayCloseRequestsCloseWithoutPersisting() {
        assertReplayCloseDoesNotPersist(completedRevision = 0)
    }

    @Test
    fun completedSessionReplayCloseRequestsCloseWithoutPersisting() {
        assertReplayCloseDoesNotPersist(completedRevision = OnboardingSession.CURRENT_REVISION)
    }

    @Test
    fun replayDoneRequestsCloseEvenForAnIncompleteSession() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.REPLAY, session)
        val exits = mutableListOf<OnboardingExit>()
        val destinations = mutableListOf<OnboardingDestination>()
        showScreen(flow, exits, destinations, isReplay = true)
        advanceAnimations()

        repeat(OnboardingSession.PAGE_COUNT - 1) {
            compose.onNodeWithText("Next").performClick()
            advanceAnimations()
        }
        compose.runOnIdle {
            assertTrue(writes.isEmpty())
            assertTrue(exits.isEmpty())
        }
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        advanceAnimations()

        compose.runOnIdle {
            assertEquals(
                listOf(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)),
                exits
            )
            assertEquals(listOf(OnboardingDestination.CLOSE), destinations)
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
        }
    }

    private fun assertReplayCloseDoesNotPersist(completedRevision: Int) {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(completedRevision, writes::add)
        val wasVisible = session.visible.value
        val flow = OnboardingActivityFlow(OnboardingEntry.REPLAY, session)
        val exits = mutableListOf<OnboardingExit>()
        val destinations = mutableListOf<OnboardingDestination>()
        showScreen(flow, exits, destinations, isReplay = true)
        advanceAnimations(12_000)
        compose.onNodeWithText("Later").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        advanceAnimations()

        compose.runOnIdle {
            assertEquals(listOf(OnboardingExit(OnboardingExitReason.CLOSE_REPLAY, 0)), exits)
            assertEquals(listOf(OnboardingDestination.CLOSE), destinations)
            assertEquals(wasVisible, session.visible.value)
            assertTrue(writes.isEmpty())
        }
    }

    private fun showScreen(
        flow: OnboardingActivityFlow,
        exits: MutableList<OnboardingExit>,
        destinations: MutableList<OnboardingDestination>,
        isReplay: Boolean = false
    ) {
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(
                    isReplay = isReplay,
                    onExit = { exit ->
                        exits += exit
                        destinations += flow.onExit(exit)
                    }
                )
            }
        }
    }

    private fun advanceAnimations(milliseconds: Long = 1_000) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }
}
