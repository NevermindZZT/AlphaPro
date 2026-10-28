package com.letter.alphapro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterSliderScaleTest {
    @Test fun endpointsAreExactForBothScales() {
        for (logarithmic in listOf(false, true)) {
            val range = 1..3600
            assertEquals(0f, ParameterSliderScale.toPosition(range.first, range, logarithmic), 0f)
            assertEquals(1f, ParameterSliderScale.toPosition(range.last, range, logarithmic), 0f)
            assertEquals(range.first, ParameterSliderScale.fromPosition(0f, range, logarithmic))
            assertEquals(range.last, ParameterSliderScale.fromPosition(1f, range, logarithmic))
        }
    }

    @Test fun linearPositionsUseTheWholeIntegerRangeAndRoundToNearest() {
        assertEquals(0.5f, ParameterSliderScale.toPosition(500, 1..999), 0f)
        assertEquals(500, ParameterSliderScale.fromPosition(0.5f, 1..999))
        assertEquals(6, ParameterSliderScale.fromPosition(0.5f, 1..10))
        assertEquals(0, ParameterSliderScale.fromPosition(0.5f, -10..10))
        assertEquals(0.5f, ParameterSliderScale.toPosition(0, -10..10), 0f)
    }

    @Test fun logarithmicMidpointIsGeometricAndReservesSpaceForSmallValues() {
        assertEquals(10, ParameterSliderScale.fromPosition(0.5f, 1..100, true))
        assertEquals(0.5f, ParameterSliderScale.toPosition(10, 1..100, true), 0.000001f)
        val logarithmicGap = ParameterSliderScale.toPosition(2, 1..3600, true)
        val linearGap = ParameterSliderScale.toPosition(2, 1..3600)
        assertTrue(logarithmicGap > linearGap)
    }

    @Test fun outOfBoundsInputsAreClampedForBothScales() {
        val range = 1..3600
        for (logarithmic in listOf(false, true)) {
            assertEquals(0f, ParameterSliderScale.toPosition(Int.MIN_VALUE, range, logarithmic), 0f)
            assertEquals(1f, ParameterSliderScale.toPosition(Int.MAX_VALUE, range, logarithmic), 0f)
            for (position in listOf(-1f, Float.NEGATIVE_INFINITY, Float.NaN)) {
                assertEquals(range.first, ParameterSliderScale.fromPosition(position, range, logarithmic))
            }
            for (position in listOf(2f, Float.POSITIVE_INFINITY)) {
                assertEquals(range.last, ParameterSliderScale.fromPosition(position, range, logarithmic))
            }
        }
    }

    @Test fun singletonRangeHasOneValueAtPositionZero() {
        for (logarithmic in listOf(false, true)) {
            assertEquals(0f, ParameterSliderScale.toPosition(100, 7..7, logarithmic), 0f)
            for (position in listOf(Float.NaN, -1f, 0f, 0.5f, 1f, 2f)) {
                assertEquals(7, ParameterSliderScale.fromPosition(position, 7..7, logarithmic))
            }
        }
        assertEquals(0, ParameterSliderScale.fromPosition(0.5f, 0..0))
    }

    @Test fun bothDirectionsAreMonotonicForBothScales() {
        val range = 1..3600
        for (logarithmic in listOf(false, true)) {
            var previousPosition = -1f
            for (value in range) {
                val position = ParameterSliderScale.toPosition(value, range, logarithmic)
                assertTrue("Position must increase at $value", position >= previousPosition)
                assertTrue(position in 0f..1f)
                previousPosition = position
            }
            var previousValue = range.first
            for (step in 0..10000) {
                val value = ParameterSliderScale.fromPosition(step / 10000f, range, logarithmic)
                assertTrue("Value must not decrease at $step", value >= previousValue)
                assertTrue(value in range)
                previousValue = value
            }
        }
    }

    @Test fun everySupportedSecondAndCountRoundTripsExactly() {
        for (range in listOf(1..3600, 1..999)) {
            for (logarithmic in listOf(false, true)) {
                for (value in range) {
                    val position = ParameterSliderScale.toPosition(value, range, logarithmic)
                    assertEquals(
                        "Round trip for $value in $range, logarithmic=$logarithmic",
                        value,
                        ParameterSliderScale.fromPosition(position, range, logarithmic)
                    )
                }
            }
        }
    }

    @Test fun wideIntegerRangesDoNotOverflow() {
        val range = Int.MIN_VALUE..Int.MAX_VALUE
        assertEquals(0f, ParameterSliderScale.toPosition(Int.MIN_VALUE, range), 0f)
        assertEquals(1f, ParameterSliderScale.toPosition(Int.MAX_VALUE, range), 0f)
        assertEquals(Int.MIN_VALUE, ParameterSliderScale.fromPosition(0f, range))
        assertEquals(Int.MAX_VALUE, ParameterSliderScale.fromPosition(1f, range))
        assertEquals(0, ParameterSliderScale.fromPosition(0.5f, range))
        assertTrue(ParameterSliderScale.fromPosition(0.75f, range) > 0)
        assertTrue(ParameterSliderScale.fromPosition(0.25f, range) < 0)
    }

    @Test fun emptyRangesAreRejectedInBothDirections() {
        for (logarithmic in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) {
                ParameterSliderScale.toPosition(1, 2..1, logarithmic)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ParameterSliderScale.fromPosition(0.5f, 2..1, logarithmic)
            }
        }
    }

    @Test fun logarithmicRangesMustBePositiveEvenForSingletonsAndNan() {
        for (range in listOf(0..3600, -1..3600, -100..-1, 0..0)) {
            assertThrows(IllegalArgumentException::class.java) {
                ParameterSliderScale.toPosition(1, range, true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ParameterSliderScale.fromPosition(Float.NaN, range, true)
            }
        }
    }
}
