package uk.xa0.tulkki.xmpp.services

import java.util.Objects
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * Tulkki: the conversation-list scans, lifted out of `XmppConnectionService`
 *.
 *
 * Every one of the six bodies reads the service's live conversation list and nothing else, so the
 * list is a **parameter** here and the Kotlin holds no state at all. The caller hands over the same
 * `CopyOnWriteArrayList` it always did, which keeps three contracts the rows name: the scans that
 * took no lock still take none, the two `is…` methods still `synchronized` on that very list object,
 * and a mutation by a caller that holds `getConversationList()` still mutates the service's list.
 *
 * The two reference comparisons the Java wrote are kept as `===`, never `==`: `find(ContactRef)`
 * matches the contact **object**, and `isConversationListEmpty`/`isConversationStillOpen` compare
 * conversations by identity, as the row warns.
 *
 * The private four-argument overload stays the seam `UiConversationLookup.CounterpartFinder` names;
 * its null argument decides which of the two loops runs, in the Java's order.
 */
object ConversationScans {

    /** Finds every room the contact is in, and the contact's own 'fake' conversation. */
    @JvmStatic
    fun findAllConferencesWith(
        list: List<ConversationRef>,
        contact: ContactRef,
    ): List<ConversationRef> {
        val results = ArrayList<ConversationRef>()
        for (c in list) {
            if (c.getMode() != ConversationalRef.MODE_MULTI) {
                continue
            }
            val mucOptions = c.getMucOptions()
            if ((c.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid().equals(contact.getJid().asBareJid())
                || (mucOptions != null && mucOptions.isContactInRoom(contact))
            ) {
                results.add(c)
            }
        }
        return results
    }

    /** The conversation whose contact is **this object**, by identity, exactly as the Java read it. */
    @JvmStatic
    fun find(list: List<ConversationRef>, contact: ContactRef): ConversationRef? {
        for (conversation in list) {
            if (conversation.getContact() === contact) {
                return conversation
            }
        }
        return null
    }

    /** Scans a caller-supplied haystack; a null JID or haystack answers null before the loop. */
    @JvmStatic
    fun find(
        haystack: Iterable<ConversationRef>?,
        account: AccountRef?,
        jid: Jid?,
    ): ConversationRef? {
        if (jid == null || haystack == null) {
            return null
        }
        for (conversation in haystack) {
            if ((account == null || conversation.getAccount() === account)
                && (conversation.getJid()
                    ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid().equals(jid.asBareJid())
            ) {
                return conversation
            }
        }
        return null
    }

    /**
     * The private overload: a non-null counterpart matches by equality, a null one demands a
     * conversation with no next counterpart. Both loops are the Java's, unsplit.
     */
    @JvmStatic
    fun find(
        haystack: Iterable<ConversationRef>,
        account: AccountRef?,
        jid: Jid?,
        counterpart: Jid?,
    ): ConversationRef? {
        if (jid == null) {
            return null
        }
        if (counterpart != null) {
            for (conversation in haystack) {
                if ((account == null || conversation.getAccount() === account)
                    && (conversation.getJid()
                        ?: throw NullPointerException("conversation has no jid"))
                        .asBareJid().equals(jid.asBareJid())
                    && Objects.equals(conversation.getNextCounterpart(), counterpart)
                ) {
                    return conversation
                }
            }
        } else {
            for (conversation in haystack) {
                if ((account == null || conversation.getAccount() === account)
                    && (conversation.getJid()
                        ?: throw NullPointerException("conversation has no jid"))
                        .asBareJid().equals(jid.asBareJid())
                    && conversation.getNextCounterpart() == null
                ) {
                    return conversation
                }
            }
        }
        return null
    }

    /**
     * True when nothing is open but the ignored conversation. The lock is the list's own, so the
     * caller and the file-deletion bookkeeping of chunk `C29` still exclude each other.
     */
    @JvmStatic
    fun isConversationListEmpty(list: List<ConversationRef>, ignore: ConversationRef?): Boolean {
        synchronized(list) {
            val size = list.size
            return size == 0 || size == 1 && list[0] === ignore
        }
    }

    /** Identity, inside the list's lock, exactly as the Java wrote it. */
    @JvmStatic
    fun isConversationStillOpen(
        list: List<ConversationRef>,
        conversation: ConversationRef,
    ): Boolean {
        synchronized(list) {
            for (current in list) {
                if (current === conversation) {
                    return true
                }
            }
        }
        return false
    }
}
