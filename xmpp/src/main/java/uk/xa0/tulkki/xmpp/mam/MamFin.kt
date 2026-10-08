package uk.xa0.tulkki.xmpp.mam

import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService

/**
 * Tulkki: an immutable snapshot of one MAM `<fin>`, island-side.
 *
 * "Design: synchronisation" §3.2. It exists so the sync engine never touches `Query`, which is
 * mutable, inner, locked and version-dependent: a `Query` is rewritten as the paging walks on, so an
 * event that handed one over would describe a region that had already moved. This carries only the
 * fields `MessageArchiveService.processFin` already reads out of the `<fin>` and the query beside it.
 *
 * **Nothing produces one of these yet, and that is the shape of this commit.** The island's call
 * sites - the `<fin>` branch of `processFin`, and the timeout and error branches that reach no
 * trigger at all today (§0) - are the trigger retirement's, and they are deliberately not touched
 * here: S5-8 is additive, and the trigger it replaces is still the only thing that makes the pass
 * over a gap run. The value is declared so the engine and its tests can speak the type before the
 * island is rewired.
 *
 * An island type: it names no module of ours, and its account and conversation are the refs the MAM
 * service itself uses.
 *
 * Ported from `MamFin.java`. It is *ours*, so it is
 * converted in place. Every accessor stays a *function* (`start()`, `account()`, ...) because that is
 * what both the Java and the Kotlin callers (`SyncEngine.facts`, `XmppTulkkiHost.onMamFin`) write;
 * the constructor keeps its thirteen parameters, their order and their types, with the four
 * reference answers nullable exactly as the Java left them. The private constructor properties are
 * not accessors, so none of them collides with the explicit `start()`/`count()` names.
 *
 * The one caller edit the nullable `conversation()` forces is `SyncEngine.facts`: the Java could
 * write `fin.conversation().getUuid()` after a null test on a second call, and Kotlin refuses to
 * smart-cast a function result, so `:421` hoists it into a local first.
 */
class MamFin(
    private val start: Long,
    private val end: Long,
    private val reference: String?,
    private val totalCount: Int,
    private val actualCount: Int,
    private val complete: Boolean,
    private val first: MamReference?,
    private val last: MamReference?,
    private val count: Int?,
    private val order: MessageArchiveService.PagingOrder,
    private val catchup: Boolean,
    private val account: AccountRef,
    private val conversation: ConversationRef?,
) {

    /** The region's own `start`: the `gap_start` the ledger keyed it on. */
    fun start(): Long = start

    /** The region's own `end`: 0 when upstream did not bound it. */
    fun end(): Long = end

    /** The RSM reference the page was paged from, or null. */
    fun reference(): String? = reference

    /** How many stanzas this query has delivered so far, which is what RSM's `count` is compared with. */
    fun totalCount(): Int = totalCount

    /** How many of them were actually inserted. */
    fun actualCount(): Int = actualCount

    /** RSM's `complete` attribute: the primary completeness proof (§2.3). */
    fun complete(): Boolean = complete

    /** RSM's `first`, which is the newest stanza of a reverse page. */
    fun first(): MamReference? = first

    /** RSM's `last`, which is the newest stanza of a forward page. */
    fun last(): MamReference? = last

    /** RSM's `count`, null when the server omitted it; the second proof. */
    fun count(): Int? = count

    /** Which way the page walked. The reverse region's page must not close the forward one. */
    fun order(): MessageArchiveService.PagingOrder = order

    /** Whether this was a catch-up query at all. */
    fun isCatchup(): Boolean = catchup

    fun account(): AccountRef = account

    /** Null for the account-wide catch-up, which is what makes it the ledger's empty scope. */
    fun conversation(): ConversationRef? = conversation
}
