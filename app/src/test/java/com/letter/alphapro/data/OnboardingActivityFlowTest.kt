package com.letter.alphapro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnboardingActivityFlowTest {

    @Test
    fun manyRawSkipsOnlyRequestConfirmationAndNeverWriteOrOpenMain() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        // Regression for an unsolicited page-zero callback: repetition is not consent.
        repeat(100) {
            for (page in 0 until OnboardingSession.PAGE_COUNT) {
                assertEquals(
                    OnboardingDestination.CONFIRM_SKIP,
                    flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, page))
                )
                assertTrue(session.visible.value)
                assertTrue(writes.isEmpty())
            }
        }
    }

    @Test
    fun matchedConfirmationFromAnyPageOpensMainAndPersistsExactlyOnce() {
        for (page in 0 until OnboardingSession.PAGE_COUNT) {
            val writes = mutableListOf<Int>()
            val session = OnboardingSession(0, writes::add)
            val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

            assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, page)))
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
            assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))
            assertFalse(session.visible.value)
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)

            assertTerminalIgnoresFurtherExits(flow)
            flow.cancelSkip()
            assertTerminalIgnoresFurtherExits(flow)
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        }
    }

    @Test
    fun confirmationWithoutARequestIsIgnoredOnEveryPage() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        for (page in 0 until OnboardingSession.PAGE_COUNT) {
            assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))
        }
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun wrongPageConfirmationDoesNotConsumeTheMatchingPendingRequest() {
        for (pendingPage in 0 until OnboardingSession.PAGE_COUNT) {
            val writes = mutableListOf<Int>()
            val session = OnboardingSession(0, writes::add)
            val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)
            assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, pendingPage)))

            for (page in -1..OnboardingSession.PAGE_COUNT) {
                if (page == pendingPage) continue
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))
                assertTrue(session.visible.value)
                assertTrue(writes.isEmpty())
            }
            assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, pendingPage)))
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        }
    }

    @Test
    fun confirmationAfterCancellationIsIgnoredUntilAnotherRequest() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        flow.cancelSkip()
        repeat(3) {
            assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        }
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())

        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 1)))
        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)))
        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 1)))
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun failedPersistenceKeepsPendingConfirmationForSuccessfulRetry() {
        val failure = IllegalStateException("Storage unavailable")
        var failPersistence = true
        var attempts = 0
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, { revision ->
            attempts++
            if (failPersistence) throw failure
            writes += revision
        })
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)
        val confirmation = OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, 0)

        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        try {
            flow.onExit(confirmation)
            fail("Persistence failure must not be swallowed")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, attempts)
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
        assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertEquals(1, attempts)

        failPersistence = false
        assertEquals(OnboardingDestination.MAIN, flow.onExit(confirmation))
        assertFalse(session.visible.value)
        assertEquals(2, attempts)
        assertTerminalIgnoresFurtherExits(flow)
        assertEquals(2, attempts)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun pendingSkipIgnoresLastFinishBackAndReplayCloseUntilCancelled() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.CONFIRM_SKIP, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, 0)))
        for (reason in listOf(OnboardingExitReason.FINISH, OnboardingExitReason.BACK, OnboardingExitReason.CLOSE_REPLAY)) {
            for (page in 0 until OnboardingSession.PAGE_COUNT) {
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(reason, page)))
            }
        }
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())

        flow.cancelSkip()
        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertFalse(session.visible.value)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun invalidSkipPagesDoNotAuthorizeAnyConfirmationOrBlockOrdinaryFinish() {
        for (invalidPage in listOf(Int.MIN_VALUE, -1, OnboardingSession.PAGE_COUNT, Int.MAX_VALUE)) {
            val writes = mutableListOf<Int>()
            val session = OnboardingSession(0, writes::add)
            val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

            assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.SKIP, invalidPage)))
            for (page in listOf(invalidPage) + (0 until OnboardingSession.PAGE_COUNT).toList()) {
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.CONFIRMED_SKIP, page)))
            }
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
            assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
            assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        }
    }

    @Test
    fun firstRunLastPageFinishOpensMainAndPersistsExactlyOnce() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertFalse(session.visible.value)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
        assertTerminalIgnoresFurtherExits(flow)
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun earlyFinishStaysWithoutPersistingAndDoesNotPreventLaterCompletion() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        for (page in 0 until OnboardingSession.PAGE_COUNT - 1) {
            repeat(2) {
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, page)))
            }
            assertTrue(session.visible.value)
            assertTrue(writes.isEmpty())
        }
        assertEquals(OnboardingDestination.MAIN, flow.onExit(OnboardingExit(OnboardingExitReason.FINISH, OnboardingSession.PAGE_COUNT - 1)))
        assertEquals(listOf(OnboardingSession.CURRENT_REVISION), writes)
    }

    @Test
    fun firstPageBackClosesWithoutPersistingAndFurtherExitsStay() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        assertEquals(OnboardingDestination.CLOSE, flow.onExit(OnboardingExit(OnboardingExitReason.BACK, 0)))
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
        assertTerminalIgnoresFurtherExits(flow)
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun everyReplayExitIncludingConfirmedSkipClosesWithoutPersistingEvenForIncompleteSessions() {
        val revisions = listOf(0, OnboardingSession.CURRENT_REVISION - 1, OnboardingSession.CURRENT_REVISION, OnboardingSession.CURRENT_REVISION + 1).distinct()
        for (revision in revisions) {
            for (reason in OnboardingExitReason.values()) {
                // Replay must close independently of session completion and page validity.
                for (page in -1..OnboardingSession.PAGE_COUNT) {
                    val writes = mutableListOf<Int>()
                    val session = OnboardingSession(revision, writes::add)
                    val wasVisible = session.visible.value
                    val flow = OnboardingActivityFlow(OnboardingEntry.REPLAY, session)

                    assertEquals("revision=$revision reason=$reason page=$page", OnboardingDestination.CLOSE, flow.onExit(OnboardingExit(reason, page)))
                    assertEquals(wasVisible, session.visible.value)
                    assertTrue(writes.isEmpty())
                    assertTerminalIgnoresFurtherExits(flow)
                    assertEquals(wasVisible, session.visible.value)
                    assertTrue(writes.isEmpty())
                }
            }
        }
    }

    @Test
    fun invalidFirstRunCompletionPagesStayWithoutPersisting() {
        val writes = mutableListOf<Int>()
        val session = OnboardingSession(0, writes::add)
        val flow = OnboardingActivityFlow(OnboardingEntry.FIRST_RUN, session)

        for (page in listOf(-1, OnboardingSession.PAGE_COUNT)) {
            for (reason in listOf(OnboardingExitReason.SKIP, OnboardingExitReason.CONFIRMED_SKIP, OnboardingExitReason.FINISH)) {
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(reason, page)))
            }
        }
        assertTrue(session.visible.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun requiresFirstRunChecksTheCurrentRevisionBoundaryIncludingRevisionTwo() {
        assertEquals(3, OnboardingSession.CURRENT_REVISION)
        assertTrue(OnboardingActivityFlow.requiresFirstRun(0))
        assertTrue(OnboardingActivityFlow.requiresFirstRun(2))
        assertTrue(OnboardingActivityFlow.requiresFirstRun(OnboardingSession.CURRENT_REVISION - 1))
        assertFalse(OnboardingActivityFlow.requiresFirstRun(OnboardingSession.CURRENT_REVISION))
        assertFalse(OnboardingActivityFlow.requiresFirstRun(OnboardingSession.CURRENT_REVISION + 1))
    }

    private fun assertTerminalIgnoresFurtherExits(flow: OnboardingActivityFlow) {
        for (reason in OnboardingExitReason.values()) {
            for (page in -1..OnboardingSession.PAGE_COUNT) {
                assertEquals(OnboardingDestination.STAY, flow.onExit(OnboardingExit(reason, page)))
            }
        }
    }
}
