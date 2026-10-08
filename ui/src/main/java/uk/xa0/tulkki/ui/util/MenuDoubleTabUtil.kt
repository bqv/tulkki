package uk.xa0.tulkki.ui.util

import android.os.SystemClock
import android.util.Log

import uk.xa0.tulkki.xmpp.Config

object MenuDoubleTabUtil {

    private const val TIMEOUT = 250

    private var lastMenuOpenedTimestamp = 0L

    @JvmStatic
    fun recordMenuOpen() {
        lastMenuOpenedTimestamp = SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun shouldIgnoreTap(): Boolean {
        val ignoreTab = lastMenuOpenedTimestamp + 250 > SystemClock.elapsedRealtime()
        if (ignoreTab) {
            Log.d(Config.LOGTAG, "ignoring tab")
        }
        return ignoreTab
    }
}
