package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.CommentRef

/**
 * Tulkki: the comment listeners and their fan-out, lifted out of `XmppConnectionService`
 *.
 *
 * The Java held the list in a plain `ArrayList` and guarded all three methods with
 * `synchronized (mOnCommentReceivedListeners)`, so the monitor is the list object and it moves with
 * the list; no other member of the service ever read it. Nothing crosses the seam but the two
 * parameters the Java's `notify` forwarded unchanged, and the listener loop keeps the Java's
 * swallow-everything `catch` — a listener removed mid-flight is still silently ignored.
 *
 * 3.7 C5-D, carried with the body it annotated: the model cast is gone rather than moved — nothing
 * in `notify` needed the model, so the ref travels to the listeners, which name it themselves. That
 * is also why both parameters here are nullable and stay nullable.
 */
object CommentNotifications {

    private val listeners = ArrayList<OnCommentReceived>()

    @JvmStatic
    fun add(listener: OnCommentReceived) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    @JvmStatic
    fun remove(listener: OnCommentReceived) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    @JvmStatic
    fun notify(originalPostUuid: String?, commentRef: CommentRef?) {
        synchronized(listeners) {
            for (listener in listeners) {
                try {
                    listener.onCommentReceived(originalPostUuid, commentRef)
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "safe to ignore, listener has been removed")
                }
            }
        }
    }
}
