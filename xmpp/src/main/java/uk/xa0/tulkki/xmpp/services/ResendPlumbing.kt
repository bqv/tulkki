package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the unsent-message flush and the resend plumbing, lifted out of `XmppConnectionService`
 *.
 *
 * Three public `resendMessage` overloads, the private join guard and the flush of a conversation's
 * waiting rows. The resends re-enter chunk `C25b`'s **private** six-argument `sendMessage` through
 * [OutgoingStanzaSender] — the same seam `C25a` uses, which is why the two chunks land in one
 * commit — with the Java's `resend=true` and the per-overload preview/delay/callback arguments
 * preserved one for one.
 *
 * `sendUnsentMessages` holds `synchronized (conversation)` exactly as the Java did, and the waiting
 * rows are drained through the conversation's own `findWaitingMessages`, so the lock is still the
 * conversation's monitor.
 *
 * `isOnboarding()` is kept as the record says it stands: `Config.ONBOARDING_DOMAIN` is deleted, so
 * nothing can be the onboarding account and the predicate answers `false` unconditionally. Its
 * callers (the onboarding UI) stay where they are.
 */
object ResendPlumbing {

    /**
     * True while a room join is in flight (or pending), which is what holds a message back. The
     * single-mode answer is `false`; the Java's extra block and its log line are kept.
     */
    @JvmStatic
    fun isJoinInProgress(conversation: ConversationRef): Boolean {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
            val inProgress = account.isConferenceJoinInProgress(conversation)
            val pending = account.isConferenceJoinPending(conversation)
            val inProgressJoin = inProgress || pending
            if (inProgressJoin) {
                Log.d(
                    Config.LOGTAG,
                    "" + account.getJid().asBareJid()
                        + ": holding back message to group. inProgress="
                        + inProgress
                        + ", pending="
                        + pending,
                )
            }
            return inProgressJoin
        }
        return false
    }

    /** Flushes the waiting rows of one conversation, under its own monitor. */
    @JvmStatic
    fun sendUnsentMessages(conversation: ConversationRef, sender: OutgoingStanzaSender) {
        synchronized(conversation) {
            conversation.findWaitingMessages(
                object : ConversationRef.OnMessageFound {
                    override fun onMessageFound(message: MessageRef) {
                        resendMessage(message, true, sender)
                    }
                },
            )
        }
    }

    @JvmStatic
    fun resendMessage(
        message: MessageRef,
        delay: Boolean,
        sender: OutgoingStanzaSender,
    ) {
        sender.sendMessage(message, true, false, delay, null, false)
    }

    @JvmStatic
    fun resendMessage(
        message: MessageRef,
        delay: Boolean,
        cb: Runnable?,
        sender: OutgoingStanzaSender,
    ) {
        sender.sendMessage(message, true, false, delay, cb, false)
    }

    @JvmStatic
    fun resendMessage(
        message: MessageRef,
        delay: Boolean,
        previewedLinks: Boolean,
        sender: OutgoingStanzaSender,
    ) {
        sender.sendMessage(message, true, previewedLinks, delay, null, false)
    }

    /** Always `false`: the onboarding domain is gone (see the class note). */
    @JvmStatic
    fun isOnboarding(): Boolean = false
}
