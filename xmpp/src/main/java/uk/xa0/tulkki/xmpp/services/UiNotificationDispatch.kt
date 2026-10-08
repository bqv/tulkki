package uk.xa0.tulkki.xmpp.services

import java.util.Collections
import java.util.WeakHashMap

/**
 * Tulkki: the error-toast, conversation and call-log fan-out, lifted out of `XmppConnectionService`
 *.
 *
 * `showErrorToastInUi` and the two `updateConversationUi` overloads fan out over chunk `C34`'s
 * private weak listener sets, so the **snapshot travels in**: the service still takes it under
 * `LISTENER_LOCK` through `threadSafeList` and hands the already-copied list over, exactly as
 * `UiUpdateDispatch` (chunk C49) does. `threadSafeList` itself stays on the service because eleven
 * not-yet-moved members still read it; only its two C48 callers move here.
 *
 * The call-log set moves **whole** — nothing outside these four members ever read it — and it keeps
 * the Java's monitor: the Java guarded all three accesses with `synchronized (LISTENER_LOCK)`, so
 * the lock arrives by value and the Kotlin locks that same object rather than minting a second one.
 * The snapshot is taken inside the lock and the listeners are called outside it, which is the
 * Java's `for (… : threadSafeList(…))` shape.
 */
object UiNotificationDispatch {

    private val callLogListeners: MutableSet<OnCallLogUpdated> =
        Collections.newSetFromMap(WeakHashMap<OnCallLogUpdated, Boolean>())

    @JvmStatic
    fun showErrorToast(resId: Int, listeners: List<OnShowErrorToast>) {
        for (listener in listeners) {
            listener.onShowErrorToast(resId)
        }
    }

    @JvmStatic
    fun updateConversation(newCaps: Boolean, listeners: List<OnConversationUpdate>) {
        for (listener in listeners) {
            listener.onConversationUpdate(newCaps)
        }
    }

    @JvmStatic
    fun addCallLogListener(listener: OnCallLogUpdated, lock: Any) {
        synchronized(lock) {
            callLogListeners.add(listener)
        }
    }

    @JvmStatic
    fun removeCallLogListener(listener: OnCallLogUpdated, lock: Any) {
        synchronized(lock) {
            callLogListeners.remove(listener)
        }
    }

    @JvmStatic
    fun updateCallLog(lock: Any) {
        val snapshot: List<OnCallLogUpdated> = synchronized(lock) {
            if (callLogListeners.isEmpty()) {
                emptyList()
            } else {
                ArrayList(callLogListeners)
            }
        }
        for (listener in snapshot) {
            listener.onCallLogUpdated()
        }
    }
}
