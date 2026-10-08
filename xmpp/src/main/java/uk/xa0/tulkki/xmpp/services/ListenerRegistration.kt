package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import java.util.function.BooleanSupplier

/**
 * Tulkki: listener registration, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk's ten fields stay on the service: the nine weak listener sets and `LISTENER_LOCK` are
 * read by `threadSafeList` (C48) and by every C49 delegation, so they arrive here **by value** and
 * the lock is the same monitor object - nothing was widened to reach them. `checkListeners()`'s
 * story set is chunk C62's `mOnStoriesUpdates`, which now lives on `StoryCache` and arrives already
 * resolved, and C70's `mNotificationService` is the nullable port the Java dereferenced bare, so it
 * arrives nullable and the one dereference keeps the Java's NPE rather than turning it into
 * `checkNotNullParameter`.
 *
 * The Java's guard order is kept exactly: a setter reads the remaining-listener answer **before**
 * the add, a remover reads it **after** the drop, both inside the lock, and only a true answer
 * fires the foreground/background switch - which travels in as the `Runnable` bound to the
 * service's own private `switchToForeground`/`switchToBackground`. The two conversation-list
 * methods additionally push the live listener count into the notification port inside the lock.
 * The duplicate-registration warnings keep the Java's per-method text, typos included.
 */
object ListenerRegistration {

    /** The Java's `checkListeners()`: true when **no** listener remains. */
    @JvmStatic
    fun checkListeners(
        accountUpdates: Set<OnAccountUpdate>,
        conversationUpdates: Set<OnConversationUpdate>,
        rosterUpdates: Set<OnRosterUpdate>,
        captchaRequested: Set<OnCaptchaRequested>,
        mucRosterUpdate: Set<OnMucRosterUpdate>,
        updateBlocklist: Set<OnUpdateBlocklist>,
        showErrorToasts: Set<OnShowErrorToast>,
        jingleRtp: Set<OnJingleRtpConnectionUpdate>,
        keyStatusUpdated: Set<OnKeyStatusUpdated>,
        storiesUpdates: Set<OnStoriesUpdate>,
    ): Boolean =
        accountUpdates.isEmpty() &&
            conversationUpdates.isEmpty() &&
            rosterUpdates.isEmpty() &&
            captchaRequested.isEmpty() &&
            mucRosterUpdate.isEmpty() &&
            updateBlocklist.isEmpty() &&
            showErrorToasts.isEmpty() &&
            jingleRtp.isEmpty() &&
            keyStatusUpdated.isEmpty() &&
            storiesUpdates.isEmpty()

    private fun <T : Any> warn(listener: T, alreadyMessage: String) {
        Log.w(Config.LOGTAG, listener.javaClass.name + " is already registered as " + alreadyMessage)
    }

    /**
     * The plain setter shape: the remaining-listener answer is read before the add, the add's
     * duplicate answer is the Java's warning, and a true answer switches to the foreground.
     */
    @JvmStatic
    fun <T : Any> add(
        listener: T,
        listeners: MutableSet<T>,
        lock: Any,
        remaining: BooleanSupplier,
        onRemaining: Runnable,
        alreadyMessage: String,
    ) {
        val rem: Boolean
        synchronized(lock) {
            rem = remaining.asBoolean
            if (!listeners.add(listener)) {
                warn(listener, alreadyMessage)
            }
        }
        if (rem) {
            onRemaining.run()
        }
    }

    /** The plain remover shape: the remaining-listener answer is read after the drop. */
    @JvmStatic
    fun <T : Any> remove(
        listener: T,
        listeners: MutableSet<T>,
        lock: Any,
        remaining: BooleanSupplier,
        onRemaining: Runnable,
    ) {
        val rem: Boolean
        synchronized(lock) {
            listeners.remove(listener)
            rem = remaining.asBoolean
        }
        if (rem) {
            onRemaining.run()
        }
    }

    @JvmStatic
    fun setOnConversationListChangedListener(
        listener: OnConversationUpdate,
        listeners: MutableSet<OnConversationUpdate>,
        lock: Any,
        remaining: BooleanSupplier,
        notificationService: NotificationPort?,
        onRemaining: Runnable,
    ) {
        val rem: Boolean
        synchronized(lock) {
            rem = remaining.asBoolean
            if (!listeners.add(listener)) {
                warn(listener, "ConversationListChangedListener")
            }
            notificationService!!.setIsInForeground(listeners.size > 0)
        }
        if (rem) {
            onRemaining.run()
        }
    }

    @JvmStatic
    fun removeOnConversationListChangedListener(
        listener: OnConversationUpdate,
        listeners: MutableSet<OnConversationUpdate>,
        lock: Any,
        remaining: BooleanSupplier,
        notificationService: NotificationPort?,
        onRemaining: Runnable,
    ) {
        val rem: Boolean
        synchronized(lock) {
            listeners.remove(listener)
            notificationService!!.setIsInForeground(listeners.size > 0)
            rem = remaining.asBoolean
        }
        if (rem) {
            onRemaining.run()
        }
    }
}
