package uk.xa0.tulkki.ui.util

import android.util.Rational

object Rationals {

    // between 2.39:1 and 1:2.39 (inclusive).
    private val MIN = Rational(100, 239)
    private val MAX = Rational(239, 100)

    @JvmStatic
    fun clip(input: Rational): Rational {
        if (input < MIN) {
            return MIN
        }
        if (input > MAX) {
            return MAX
        }
        return input
    }
}
