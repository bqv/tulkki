package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * Tulkki: the ui-facing conversation lookups, lifted out of `XmppConnectionService`
 *.
 *
 * The three `find` overloads, the muc predicate and the message search all live here; every
 * service method keeps its name, visibility and signature as a one-line delegation, so no call
 * site moves.
 *
 * Two of the Java body's dependencies belong to chunks that do not move with this one, and both
 * travel in rather than having a visibility widened: the **private** four-argument `find` of chunk
 * `C31a` arrives as [CounterpartFinder], and `messageSearch()` — chunk `C76`'s private
 * `require`-backed accessor — is resolved by the service and passed in as a non-null port, the same
 * shape `C07` used for the avatar getter.
 */
object UiConversationLookup {

    /** The private four-argument scan of chunk `C31a`, supplied by the service's delegation. */
    fun interface CounterpartFinder {
        // Suppressed so Java sees the invariant `Iterable<ConversationRef>` the private scanner
        // takes; otherwise Kotlin emits `Iterable<? extends ConversationRef>` and the service's
        // `this::find` method reference does not apply.
        @JvmSuppressWildcards
        fun find(
            haystack: Iterable<ConversationRef>,
            account: AccountRef?,
            jid: Jid?,
            counterpart: Jid?,
        ): ConversationRef?
    }

    @JvmStatic
    fun findByBookmark(service: XmppConnectionService, bookmark: BookmarkRef): ConversationRef? =
        findByJid(service, bookmark.getAccount(), bookmark.getJid())

    /**
     * Reads the live conversation list without a lock, exactly as the Java did, and hands it to
     * chunk `C31a`'s public three-argument scan.
     */
    @JvmStatic
    fun findByJid(
        service: XmppConnectionService,
        account: AccountRef?,
        jid: Jid?,
    ): ConversationRef? = service.find(service.getConversationList(), account, jid)

    /** Delegates to chunk `C31a`'s private scan, which arrives as [counterpartFinder]. */
    @JvmStatic
    fun findByCounterpart(
        service: XmppConnectionService,
        account: AccountRef?,
        jid: Jid?,
        counterpart: Jid?,
        counterpartFinder: CounterpartFinder,
    ): ConversationRef? =
        counterpartFinder.find(service.getConversationList(), account, jid, counterpart)

    @JvmStatic
    fun isMuc(service: XmppConnectionService, account: AccountRef?, jid: Jid?): Boolean {
        val conversation = findByJid(service, account, jid)
        return conversation != null && conversation.getMode() == ConversationalRef.MODE_MULTI
    }

    @JvmStatic
    fun search(
        port: MessageSearchPort,
        service: XmppConnectionService,
        term: List<String>,
        uuid: String?,
        onSearchResultsAvailable: SearchResultsHook,
    ) {
        port.search(service, term, uuid, onSearchResultsAvailable)
    }
}
