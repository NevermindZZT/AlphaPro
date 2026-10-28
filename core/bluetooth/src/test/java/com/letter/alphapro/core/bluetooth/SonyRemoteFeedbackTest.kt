package com.letter.alphapro.core.bluetooth

import org.junit.Assert.*
import org.junit.Test

class SonyRemoteFeedbackTest {
    @Test fun independentSignalsAreUnknownUntilObserved() {
        val empty = SonyRemoteFeedback()
        val shutter = SonyRemoteFeedbackParser.update(empty, byteArrayOf(2, 0xa0.toByte(), 0x20))!!
        assertEquals(true, shutter.shutterActive); assertNull(shutter.recording); assertNull(shutter.focusAcquired)
        val movie = SonyRemoteFeedbackParser.update(shutter, byteArrayOf(2, 0xd5.toByte(), 0x20))!!
        assertEquals(true, movie.recording); assertEquals(1L, movie.shutterSequence); assertEquals(2L, movie.sequence)
        val focus = SonyRemoteFeedbackParser.update(movie, byteArrayOf(2, 0x3f, 0x20))!!
        assertEquals(true, focus.focusAcquired); assertEquals(true, focus.shutterActive)
        val ended = SonyRemoteFeedbackParser.update(focus, byteArrayOf(2, 0xa0.toByte(), 0))!!
        assertEquals(false, ended.shutterActive); assertEquals(2L, ended.shutterSequence)
    }
    @Test fun malformedOrUnrecognizedSignalsDoNotInventState() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(2, 0xa0.toByte()), byteArrayOf(1, 0xa0.toByte(), 0x20),
            byteArrayOf(2, 0xa0.toByte(), 1), byteArrayOf(2, 0x55, 0x20))) {
            assertNull(SonyRemoteFeedbackParser.update(SonyRemoteFeedback(), bytes))
        }
    }
}
