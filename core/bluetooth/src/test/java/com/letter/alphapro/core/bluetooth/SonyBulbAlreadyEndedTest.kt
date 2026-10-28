package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SonyBulbAlreadyEndedTest {
    @Test fun anObservedCameraEndMustNotBeFollowedByAnotherStartPress() = runBlocking {
        val token = Any()
        val codes = mutableListOf<Int>()
        var now = 0L
        var stopPresses = 0
        val port = object : RemoteShutterPort {
            override fun currentSession(): Any = token
            override suspend fun send(session: Any, command: ByteArray): Boolean {
                assertSame(token, session)
                codes.add(command[1].toInt() and 0xff)
                return true
            }
        }
        val controller = SonyRemoteShutterController(port, { now }, { now += it })
        assertTrue(controller.runBulbTimer(1000, awaitStartedConfirmation = { true },
            isExposureActive = { false }, onStopPulse = { stopPresses++ }))
        assertEquals(listOf(7, 9, 8, 6), codes)
        assertEquals(0, stopPresses)
    }
}
