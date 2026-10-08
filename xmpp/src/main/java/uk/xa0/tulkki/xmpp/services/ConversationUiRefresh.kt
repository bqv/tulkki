package uk.xa0.tulkki.xmpp.services

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * The 250 ms throttle every transfer puts in front of `XmppConnectionService.updateConversationUi()`.
 *
 * <p>Split out of `AbstractConnectionManager`. The throttle was `static` state on that class, so it
 * is shared by every manager and every connection in the process; keeping it an `object` preserves
 * that, and `lastUpdateCall` stays the monitor the Java `synchronized (LAST_UI_UPDATE_CALL)` used.
 * The two `SystemClock.elapsedRealtime()` readings are deliberately still taken twice, as the Java
 * did: the check reads one instant and the store takes a fresh one.
 */
internal object ConversationUiRefresh {

    private const val UI_REFRESH_THRESHOLD = 250

    private val lastUpdateCall = AtomicLong(0)

    fun notifyChange(service: XmppConnectionService, force: Boolean) {
        synchronized(lastUpdateCall) {
            if (force ||
                    SystemClock.elapsedRealtime() - lastUpdateCall.get() >= UI_REFRESH_THRESHOLD) {
                lastUpdateCall.set(SystemClock.elapsedRealtime())
                service.updateConversationUi()
            }
        }
    }
}
