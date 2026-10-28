package com.letter.alphapro.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun cleanTestPreferences() {
        context.getSharedPreferences("alphapro_camera_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun legacyO1O2CompletionDoesNotSuppressRepairedFirstRun() {
        context.getSharedPreferences("alphapro_camera_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_onboarding_completed", true).commit()
        assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
    }

    @Test fun confirmedCompletionPersistsAcrossPreferenceInstances() {
        val first = CameraPreferences(context)
        val session = OnboardingSession(first.completedOnboardingRevision, { first.completedOnboardingRevision = it })
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(OnboardingSession.CURRENT_REVISION, CameraPreferences(context).completedOnboardingRevision)
        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(OnboardingSession.CURRENT_REVISION, CameraPreferences(context).completedOnboardingRevision)
    }

    @Test fun repeatedRawSkipCallbacksNeverWritePreferencesOrRequestMain() {
        val prefs = CameraPreferences(context)
        var writes = 0
        val session = OnboardingSession(prefs.completedOnboardingRevision, {
            writes++
            prefs.completedOnboardingRevision = it
        })
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        repeat(100) {
            for (page in 0 until OnboardingSession.PAGE_COUNT) {
                val exit = OnboardingExit(OnboardingExitReason.SKIP, page)
                assertFalse(session.requestExit(exit))
                assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(exit))
            }
        }
        assertTrue(session.visible.value)
        assertEquals(0, writes)
        assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
    }

    @Test fun cancelledOrUnrequestedConfirmationNeverWritesPreferences() {
        val prefs = CameraPreferences(context)
        val session = OnboardingSession(prefs.completedOnboardingRevision, { prefs.completedOnboardingRevision = it })
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 1)))
        flow.cancelSkip()
        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertTrue(session.visible.value)
        assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
    }

    @Test fun revisionTwoStillRequiresFirstRunWithoutAutomaticPreferenceRewrite() {
        CameraPreferences(context).completedOnboardingRevision = 2
        val prefs = CameraPreferences(context)
        var writes = 0
        val session = OnboardingSession(prefs.completedOnboardingRevision, {
            writes++
            prefs.completedOnboardingRevision = it
        })

        assertTrue(OnboardingActivityFlow.requiresFirstRun(prefs.completedOnboardingRevision))
        assertTrue(session.visible.value)
        assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        assertEquals(0, writes)
        assertEquals(2, CameraPreferences(context).completedOnboardingRevision)
    }

    @Test fun replayIncludingConfirmedSkipNeverWritesPreferences() {
        for (reason in OnboardingExitReason.values()) {
            val prefs = CameraPreferences(context)
            var writes = 0
            val session = OnboardingSession(prefs.completedOnboardingRevision, {
                writes++
                prefs.completedOnboardingRevision = it
            })
            val flow = OnboardingActivityFlow(OnboardingEntry.REPLAY, session)

            assertEquals(OnboardingDestination.CLOSE, flow.onExit(OnboardingExit(reason, 0)))
            assertEquals(0, writes)
            assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
        }
    }

    @Test fun backOrReplayCloseDoesNotWritePreferences() {
        val prefs = CameraPreferences(context)
        val session = OnboardingSession(prefs.completedOnboardingRevision, { prefs.completedOnboardingRevision = it })
        session.requestExit(OnboardingExit(OnboardingExitReason.BACK, 0))
        session.requestExit(OnboardingExit(OnboardingExitReason.CLOSE_REPLAY, 0))
        assertEquals(0, CameraPreferences(context).completedOnboardingRevision)
    }
}
