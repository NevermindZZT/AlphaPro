package com.letter.alphapro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun newSessionDoesNotCompleteWithoutAnExplicitExit() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)

        repeat(20) { assertTrue(session.visible.value) }

        assertTrue("Construction and observing visibility must not persist completion", writes.isEmpty())
    }

    @Test
    fun backAndReplayCloseNeverCompleteFirstRunOnAnyPage() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)

        for (page in 0 until OnboardingSession.PAGE_COUNT) {
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.BACK, page)))
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.CLOSE_REPLAY, page)))
            assertTrue(session.visible.value)
        }

        assertTrue(writes.isEmpty())
    }

    @Test
    fun finishIsAcceptedOnlyOnTheLastPage() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)

        for (page in 0 until OnboardingSession.PAGE_COUNT - 1) {
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.FINISH, page)))
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
        }

        assertTrue(session.requestExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertFalse(session.visible.value)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun rawSkipNeverCompletesEvenAfterRepeatedCallbacksOnEveryPage() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)

        repeat(100) {
            for (page in 0 until OnboardingSession.PAGE_COUNT) {
                assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.SKIP, page)))
                assertTrue(session.visible.value)
            }
        }

        assertTrue(writes.isEmpty())
    }

    @Test
    fun revisionTwoCompletionRequiresTheNewConfirmedSkipRevision() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(2, writes::add)

        assertEquals(3, OnboardingSession.CURRENT_REVISION)
        assertTrue(session.visible.value)
        assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        assertTrue(writes.isEmpty())
        assertTrue(session.requestExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(listOf(3), writes)
    }

    @Test
    fun confirmedSkipFromAnyValidPageWritesCompletionExactlyOnce() {
        for (page in 0 until OnboardingSession.PAGE_COUNT) {
            val writes = mutableListOf<Int>()
            val session = OnboardingSession(0, writes::add)

            assertTrue(session.requestExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))

            assertFalse(session.visible.value)
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        }
    }

    @Test
    fun duplicateAndSubsequentExitDispatchesDoNotWriteAgain() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val skip = OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)

        assertTrue(session.requestExit(skip))
        assertFalse(session.requestExit(skip))
        assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.BACK, 0)))

        assertFalse(session.visible.value)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun invalidPagesCannotPersistCompletion() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)

        for (page in listOf(-1, OnboardingSession.PAGE_COUNT)) {
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.FINISH, page)))
        }

        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun failedPersistencePropagatesAndRetainsVisibleGuideUntilSuccessfulRetry() {
        val failure = IllegalStateException("Storage unavailable")
        val writes = mutableListOf<Int>()
        var failPersistence = true
        val session = OnboardingSession(0, { revision ->
            if (failPersistence) throw failure
            writes += revision
        })
        val skip = OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)

        try {
            session.requestExit(skip)
            fail("Persistence failure must not be swallowed")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertTrue("An unsuccessful save must not expose main content", session.visible.value)
        assertTrue(writes.isEmpty())

        failPersistence = false
        assertTrue(session.requestExit(skip))
        assertFalse(session.visible.value)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun recreationUsesSavedRevisionAndDoesNotPersistAgain() {
        var savedRevision = 0
        var writes = 0
        val first = OnboardingSession(savedRevision, { revision ->
            savedRevision = revision
            writes++
        })
        assertTrue(first.visible.value)
        assertTrue(first.requestExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))

        val recreated = OnboardingSession(savedRevision, { writes++ })

        assertFalse(recreated.visible.value)
        assertFalse(recreated.requestExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(1, writes)
        assertEquals(OnboardingSession.CURRENT_REVISION, savedRevision)
    }

    @Test
    fun alreadySavedCurrentOrFutureRevisionNeverShowsGuideOrWrites() {
        val writes = mutableListOf<Int>()
        for (revision in listOf(OnboardingSession.CURRENT_REVISION, OnboardingSession.CURRENT_REVISION + 1)) {
            val session = OnboardingSession(revision, writes::add)
            assertFalse(session.visible.value)
            assertFalse(session.requestExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        }
        assertTrue(writes.isEmpty())
    }
}
