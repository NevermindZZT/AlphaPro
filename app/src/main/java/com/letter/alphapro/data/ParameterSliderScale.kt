package com.letter.alphapro.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Pure integer/normalized-slider conversion, independent of Compose and Android.
 *
 * Logarithmic ranges must be strictly positive. Empty ranges are invalid. Values and
 * positions are clamped; NaN positions resolve to the minimum, like negative infinity.
 * Singleton ranges always resolve to their only value (at position zero).
 */
object ParameterSliderScale {
    fun toPosition(value: Int, range: IntRange, logarithmic: Boolean = false): Float {
        validate(range, logarithmic)
        val clamped = value.coerceIn(range)
        if (range.first == range.last || clamped == range.first) return 0f
        if (clamped == range.last) return 1f
        val minimum = range.first.toDouble()
        val maximum = range.last.toDouble()
        val position = if (logarithmic) {
            ln(clamped.toDouble() / minimum) / ln(maximum / minimum)
        } else {
            (clamped.toDouble() - minimum) / (maximum - minimum)
        }
        return position.toFloat().coerceIn(0f, 1f)
    }

    fun fromPosition(position: Float, range: IntRange, logarithmic: Boolean = false): Int {
        validate(range, logarithmic)
        val clamped = if (position.isNaN()) 0f else position.coerceIn(0f, 1f)
        if (range.first == range.last || clamped == 0f) return range.first
        if (clamped == 1f) return range.last
        val minimum = range.first.toDouble()
        val maximum = range.last.toDouble()
        val value = if (logarithmic) {
            minimum * exp(clamped.toDouble() * ln(maximum / minimum))
        } else {
            minimum + clamped.toDouble() * (maximum - minimum)
        }
        return value.roundToInt().coerceIn(range)
    }

    private fun validate(range: IntRange, logarithmic: Boolean) {
        require(!range.isEmpty()) { "Parameter slider range must not be empty" }
        require(!logarithmic || range.first > 0) {
            "Logarithmic parameter slider range must be positive"
        }
    }
}
