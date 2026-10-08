package uk.xa0.tulkki.ui.text

import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class DividerSpan(val isLarge: Boolean) : MetricAffectingSpan() {

    override fun updateDrawState(tp: TextPaint) {
        tp.textSize = tp.textSize * PROPORTION
    }

    override fun updateMeasureState(p: TextPaint) {
        p.textSize = p.textSize * PROPORTION
    }

    private companion object {
        const val PROPORTION = 0.3f
    }
}
