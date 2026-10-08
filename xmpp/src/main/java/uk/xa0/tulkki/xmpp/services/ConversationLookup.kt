package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * Tulkki: the conversation lookup by uuid and by XMPP URI, lifted out of `XmppConnectionService`
 * as the first slice of its partition.
 *
 * Both lookups are pure reads of the service's live conversation list, so they take the list as a
 * parameter and hold no state: the service keeps the `CopyOnWriteArrayList` and passes it in, and
 * the iteration semantics (a snapshot per traversal) are the ones the callers already rely on. The
 * two methods keep the Java names, so `XmppConnectionService`'s own methods stay the surface every
 * caller uses.
 */
object ConversationLookup {

    /**
     * The conversation whose uuid is [uuid], or `null` when none is. [uuid] is nullable because a
     * caller may hand over an absent intent extra; `String.equals(null)` answers false, which is
     * the behaviour the Java body had.
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun byUuid(conversationRefs: List<ConversationRef>, uuid: String?): ConversationRef? {
        for (conversation in conversationRefs) {
            if (conversation.getUuid().equals(uuid)) {
                return conversation
            }
        }
        return null
    }

    /**
     * The one enabled conversation that [xmppUri] names, or `null` when there is none **or more
     * than one** — the two cases are deliberately indistinguishable, as in the Java body. A
     * `?join` URI may only match a multi-user conversation and a bare JID only a one-to-one one.
     *
     * `XmppUri.getJid()` is nullable in the Kotlin port and the Java body dereferenced it
     * unchecked, so a null still throws a `NullPointerException` — and, as in Java, only when the
     * condition that reads it is reached.
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun uniqueByJid(conversationRefs: List<ConversationRef>, xmppUri: XmppUri): ConversationRef? {
        val findings = ArrayList<ConversationRef>()
        for (conversation in conversationRefs) {
            if ((conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account"))
                    .isEnabled() &&
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid().equals(
                    xmppUri.getJid()?.asBareJid()
                        ?: throw NullPointerException("XmppUri has no jid")
                )
                && (conversation.getMode() == ConversationalRef.MODE_MULTI)
                == xmppUri.isAction(XmppUri.ACTION_JOIN)
            ) {
                findings.add(conversation)
            }
        }
        return if (findings.size == 1) findings[0] else null
    }
}
