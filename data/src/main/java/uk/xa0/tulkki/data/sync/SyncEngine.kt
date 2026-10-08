package uk.xa0.tulkki.data.sync

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.mam.MamAbort
import uk.xa0.tulkki.xmpp.mam.MamFin
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * The sync engine's persisted half: the reducer of [SyncStateMachine] driven against the file, and
 * the one writer of `sync_cursor`, `sync_conversation` and `sync_gap`.
 *
 * <p>S5-8 committed the engine's arithmetic, its ledger and its state machine as pure values and
 * declared the island seam (`SyncEvents`/`SyncAnchors`) without installing or calling any of it.
 * This class is the wiring S5-4 owes: it turns an island event into a reducer transition, writes the
 * transition's actions through `sync/`'s DAOs, and hands back the sweep the gap's close owes. The
 * `:app` composition root implements the two island interfaces over it, because an island may not
 * name a module of ours and this class may not name the translation queue.
 *
 * <p><strong>Persist first, then the queries go out.</strong> [onSessionEstablished] records every
 * region `OPEN` before the island sends a single MAM query, so a process death between "asked" and
 * "answered" is a `DEGRADED` row on the next launch rather than a memory. The island's paging is
 * untouched: this class enumerates and records; upstream still issues the queries.
 *
 * <p><strong>One engine per process, one state per account.</strong> The reducer's state is held in
 * memory while the process lives and rebuilt from the file on the first event for an account, so a
 * restart mid-gap is a set of persisted rows and nothing else. [onMamFin] is the only thing that can
 * close a region, and the sweep is owed once per *gap*, not once per region
 * (`SyncStateMachineTest.aSweepDoesNotStartTwiceForOneGapClose`).
 *
 * <p>**The engine owns one thread, and nothing it does happens on the caller's.** Every event is
 * dispatched onto a single daemon thread (`dispatch`), so a caller cannot make Room's blocking calls
 * from its own thread however it arrived - which is the fix for the launch crash the owner's phone
 * reported: `onClientStateChanged` reaches this class from
 * `XmppConnectionService.switchToForeground` inside `onServiceConnected`, i.e. from the main thread,
 * and `run`'s reads and writes used to happen right there. The two `anchorFor` asks are the island's
 * synchronous protocol and wait for their answer; they are the one remaining synchronous touch, they
 * are named in `await`, and the caller is the island's MAM path.
 *
 * <p>An event that owes a sweep hands its uuids to the [SweepSink] once its body is done. The queue
 * that spends on them lives in `:translation`, which this module may not name, so the app's
 * composition root hands them on. The list is the archive half of the sweep (`delivery = 1` rows
 * inside the account's open regions), which is the half the retired `history_watermark` pass used to
 * read.
 */
class SyncEngine private constructor(
    private val store: SyncStore,
    private val worker: Executor,
) {

    /**
     * Where a sweep goes. Installed by the app's composition root rather than passed to [get],
     * because the engine is a singleton reached from two places - one of them a static hook - and the
     * sink is the app's business either way. It starts as a no-op, so a sweep owed before the sink is
     * installed is dropped rather than thrown; the install happens on the host's first call.
     */
    @Volatile private var sweeps: SweepSink = SweepSink { _, _ -> }

    private val states = HashMap<String, SyncStateMachine.State>()

    /**
     * The read model "Design: synchronisation" §3.3 declares: `sync: Flow<Map<AccountId,
     * SyncStatus>>`, so a screen learns "a catch-up is running" without reaching a service (today's
     * only way is `ConversationFragment`'s `isCatchingUp(conversation)` call). It starts empty and
     * every account appears the first time an event touches it - the engine is built on the first
     * island event, and no account has a status before it has one.
     */
    private val statuses = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())

    /** The current read model, re-emitted on every transition. */
    val sync: Flow<Map<String, SyncStatus>> = statuses.asStateFlow()

    /** One account's status, or null when no event has touched it yet. */
    fun status(account: String): SyncStatus? = statuses.value[account]

    /**
     * One conversation's catch-up badge, or null when no open region covers it: the most specific
     * open `sync_gap` row, else the account-wide one, else nothing - see [CatchupState].
     *
     * <p>It answers from the account's own state, the one [persist] writes and [syncStatusOf]
     * publishes, so a badge and the account's [sync] cannot disagree; an account no event has touched
     * yet is loaded first, on the engine's thread like every other read here.
     */
    fun catchup(account: String, conversation: String): CatchupState? =
        await {
            val state = states.getOrPut(account) { load(account) }
            catchupOf(conversation, state.entries, state.sessionInstant)
        }

    // -- the island's events, as the reducer's -------------------------------------------------

    /** A session was established. A fresh bind enumerates; a resume only re-opens what is owed. */
    fun onSessionEstablished(account: AccountRef, atWallClock: Long, resumed: Boolean) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        dispatch(uuid) {
            run(
                uuid,
                SyncStateMachine.Event.SessionEstablished(
                    sessionInstant = atWallClock,
                    resumed = resumed,
                    anchor = cursor(uuid),
                    conversationCursors = conversationCursors(uuid),
                ),
            )
        }
    }

    /** A MAM `<fin>`: the only event that can close a region, and so the only sweep trigger. */
    fun onMamFin(account: AccountRef, fin: MamFin) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        dispatch(uuid) { run(uuid, SyncStateMachine.Event.Fin(facts(fin))) }
    }

    /**
     * A query that reached no `<fin>`: the timeout and error branches of the island's `execute`, and
     * `kill`. The region is named by the facts the island already holds - the conversation the query
     * was for (null for the account-wide one), the `start` it was opened with, and its paging order -
     * which is the same tuple the ledger keyed it on.
     */
    fun onMamAborted(account: AccountRef, reason: MamAbort) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        val row = reasonOf(reason)
        dispatch(uuid) {
            val open =
                states.getOrPut(uuid) { load(uuid) }
                    .entries
                    .filter { it.state == SyncQueries.GAP_STATE_OPEN.toLong() }
            var owed = emptyList<String>()
            for (entry in open) {
                owed = owed + run(uuid, SyncStateMachine.Event.Aborted(entry.region, row))
            }
            owed
        }
    }

    /** CSI went inactive or active. Active is a reconcile point, never a gap-opener (section 2.2). */
    fun onClientStateChanged(account: AccountRef, active: Boolean) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        dispatch(uuid) {
            run(
                uuid,
                if (active) SyncStateMachine.Event.CsiActive else SyncStateMachine.Event.CsiInactive,
            )
        }
    }

    /** The session ended: every region still open becomes `DEGRADED`, and the anchors stay put. */
    fun onSessionEnded(account: AccountRef) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        dispatch(uuid) { run(uuid, SyncStateMachine.Event.SessionEnded) }
    }

    /**
     * A message reached the database. `archived` is the marker the row is written with: an archive
     * delivery is sweepable by the gap sweep, a live one belongs to the live-miss sweep, and a row
     * written before schema 76 is `UNKNOWN` and is neither (`Schema76.DELIVERY_UNKNOWN`).
     *
     * <p>The write is dispatched like every other event, so nothing about a stanza's arrival reaches
     * the file on the thread that delivered it.
     */
    fun onMessageStored(
        account: AccountRef,
        conversation: ConversationRef?,
        timeSent: Long,
        archived: Boolean,
    ) {
        val conversationUuid = conversation?.getUuid() ?: return
        val marker =
            if (archived) {
                SyncQueries.DELIVERY_ARCHIVE
            } else {
                SyncQueries.DELIVERY_LIVE
            }
        dispatch(account.getUuid() ?: throw NullPointerException("account has no uuid")) {
            store.markDelivery(conversationUuid, timeSent, marker)
            emptyList()
        }
    }

    /**
     * One event's whole body, on the engine's own thread - the fix for the owner's crash.
     *
     * <p><strong>Why this is the shape.</strong> The engine's entry points are called from wherever
     * the island happens to be: `onClientStateChanged` arrives from
     * `XmppConnectionService.switchToForeground` inside `onServiceConnected`, which is the **main**
     * thread, and the body of `run` reads and writes through Room, whose blocking calls assert on it -
     * so the owner's app drew the conversation list and then died 17 ms later, and again on opening
     * Settings. The rule is therefore not "the caller should be off the main thread" but "the engine
     * does its work on the one thread it owns": every event body goes through here, so no DAO call can
     * happen on a caller's thread, whichever entry point fired.
     *
     * <p>The sweep is handed on through the sink once the body is done, because the queue that spends
     * on it is `:translation`'s and this module may not name it.
     */
    private fun dispatch(account: String, body: () -> List<String>) {
        worker.execute {
            val owed = body()
            if (owed.isNotEmpty()) {
                sweeps.onSweep(account, owed)
            }
        }
    }

    /**
     * A body whose answer the caller needs now, run on the engine's thread and waited for.
     *
     * <p>The two `anchorFor` asks are the island's own protocol (`SyncAnchors` is the island asking
     * and the app answering) and cannot be handed back later, so they run where every other body runs
     * and the caller waits - which is why the wait is on a caller that is not the main thread. That is
     * the remaining synchronous touch of the file in this class, and it is named rather than hidden: it
     * is a read (and, through `seedCursor`, one write) on the island's MAM path, and a future island
     * seam that can answer asynchronously would remove it.
     */
    private fun <T> await(body: () -> T): T {
        val answer = FutureTask(body)
        worker.execute(answer)
        return answer.get()
    }

    // -- what the island asks (`SyncAnchors`) --------------------------------------------------

    /** Where the account-wide catch-up starts; a zero reference when nothing has been seen. */
    fun anchorFor(account: AccountRef): MamReference =
        await { reference(cursor(account.getUuid() ?: throw NullPointerException("account has no uuid"))) }

    /** Where one conversation's paging starts; a zero reference when it has no known history. */
    fun anchorFor(conversation: ConversationRef): MamReference =
        await {
            // `Conversation.getUuid()` is nullable, and a conversation with no uuid has no
            // `sync_conversation` row to read - the same answer as one that was never written, which
            // is the zero reference this method promises. A null uuid used to bind `NULL` against a
            // never-null column and match nothing, so the tolerance is the Java's own behaviour and
            // not a new branch.
            val uuid = conversation.getUuid() ?: return@await MamReference(0L)
            val row = store.syncConversationDao().byConversation(uuid)
            if (row == null) MamReference(0L) else MamReference(row.anchorTime, row.anchorStanzaId)
        }

    /**
     * Give an account the cursor the 75 -> 76 seed would have written, for one the migration never
     * saw - an account added after it. The island's `anchorFor` is where the old derivation still
     * lives (`DatabaseBackend.getLastMessageReceived`/`getLastClearDate`, which is exactly what the
     * seed copied), and recording its answer here means the region enumeration, the `<fin>`s and the
     * island's own paging all use one anchor rather than two.
     */
    fun seedCursor(account: AccountRef, anchor: MamReference) {
        val uuid = account.getUuid() ?: throw NullPointerException("account has no uuid")
        if (anchor.getTimestamp() <= 0L) {
            return
        }
        worker.execute { seed(uuid, anchor) }
    }

    private fun seed(uuid: String, anchor: MamReference) {
        val seeded =
            CursorMath.Cursor(
                anchor.getReference(),
                anchor.getTimestamp(),
                SyncQueries.ANCHOR_SOURCE_SEEDED_FROM_STORE.toLong(),
                0L,
            )
        store.syncCursorDao()
            .upsert(
                uuid,
                seeded.anchorStanzaId,
                seeded.anchorTime,
                seeded.anchorSource,
                seeded.gapEnd,
                System.currentTimeMillis(),
            )
        states[uuid] = states.getOrPut(uuid) { load(uuid) }.copy(anchor = seeded)
        publish(uuid, states.getValue(uuid))
    }

    // -- the reducer, driven against the file ---------------------------------------------------

    private fun run(account: String, event: SyncStateMachine.Event): List<String> {
        val before = states.getOrPut(account) { load(account) }
        val transition = SyncStateMachine.on(before, event, Config.MAM_MAX_CATCHUP, Config.MAM_MAX_MESSAGES)
        persist(account, transition)
        states[account] = transition.state
        publish(account, transition.state)
        val owed = transition.actions.any { it is SyncStateMachine.Action.Sweep }
        return if (owed) store.syncGapDao().gapSweepCandidates(account) else emptyList()
    }

    /** The read model's one writer: the reducer's state, translated - never a second derivation. */
    private fun publish(account: String, state: SyncStateMachine.State) {
        statuses.value = statuses.value + (account to syncStatusOf(account, state, sweptFloor(account)))
    }

    /**
     * The account's live-miss floor: the oldest non-zero `swept_through` among its conversation rows,
     * or null when the sweep has not decided for any of them. `0` is the column's "never swept",
     * which is why it is filtered rather than taken as an instant.
     */
    private fun sweptFloor(account: String): Long? =
        sweptFloorOf(store.syncConversationDao().byAccount(account).map { it.sweptThrough })

    /** Rebuild one account's state from the rows that survived a process: a gap outlives a thread. */
    private fun load(account: String): SyncStateMachine.State {
        val row = store.syncCursorDao().byAccount(account)
        val entries =
            store.syncGapDao()
                .forAccount(account)
                .map {
                    GapLedger.Entry(
                        CursorMath.Region(it.conversationUuid, it.gapStart, it.region),
                        it.state,
                        it.reason,
                    )
                }
        return SyncStateMachine.restored(
            anchor = cursor(account),
            entries = entries,
            sessionInstant = if (row == null) 0L else row.gapEnd,
        )
    }

    private fun persist(account: String, transition: SyncStateMachine.Transition) {
        val now = System.currentTimeMillis()
        val sessionInstant = transition.state.sessionInstant
        for (action in transition.actions) {
            when (action) {
                is SyncStateMachine.Action.OpenRegions ->
                    for (region in action.regions) {
                        store.syncGapDao()
                            .open(
                                account,
                                region.conversation,
                                region.gapStart,
                                sessionInstant,
                                region.kind,
                                SyncQueries.GAP_STATE_OPEN.toLong(),
                                null,
                                now,
                                null,
                            )
                    }
                is SyncStateMachine.Action.CloseRegion ->
                    store.syncGapDao()
                        .close(
                            account,
                            action.region.conversation,
                            action.region.gapStart,
                            action.region.kind,
                            SyncQueries.GAP_STATE_COMPLETE.toLong(),
                            null,
                            now,
                        )
                is SyncStateMachine.Action.DegradeRegion ->
                    store.syncGapDao()
                        .close(
                            account,
                            action.region.conversation,
                            action.region.gapStart,
                            action.region.kind,
                            SyncQueries.GAP_STATE_DEGRADED.toLong(),
                            action.reason,
                            now,
                        )
                SyncStateMachine.Action.DeleteCursor -> store.syncCursorDao().remove(account)
                is SyncStateMachine.Action.Query, is SyncStateMachine.Action.Sweep -> Unit
            }
        }
        // The cursor row is written whole on every transition: `gap_end` is where the session instant
        // is kept, and it is the instant the ledger's regions were opened against (section 1.1).
        val anchor = transition.state.anchor
        store.syncCursorDao()
            .upsert(
                account,
                anchor.anchorStanzaId,
                anchor.anchorTime,
                anchor.anchorSource,
                sessionInstant,
                now,
            )
    }

    private fun cursor(account: String): CursorMath.Cursor {
        val row = store.syncCursorDao().byAccount(account) ?: return CursorMath.initial()
        return CursorMath.Cursor(row.anchorStanzaId, row.anchorTime, row.anchorSource, row.gapEnd)
    }

    /**
     * The conversation cursors the enumeration reads. `sync_conversation` carries no mode - the region
     * rule asks about single conversations only - so it comes from the conversation the row points at,
     * which is the same row `CursorMath.regions` is reasoning about.
     */
    private fun conversationCursors(account: String): List<CursorMath.ConversationCursor> =
        store.syncConversationDao()
            .byAccount(account)
            .map {
                CursorMath.ConversationCursor(
                    it.conversationUuid,
                    modeOf(it.conversationUuid),
                    it.anchorStanzaId,
                    it.anchorTime,
                    it.sweptThrough,
                )
            }

    private fun modeOf(conversationUuid: String): Int {
        val mode = store.conversationDao().byUuid(conversationUuid)?.mode
        return if (mode != null && mode == Conversation.MODE_MULTI.toLong()) {
            Conversation.MODE_MULTI
        } else {
            Conversation.MODE_SINGLE
        }
    }

    private fun facts(fin: MamFin): GapLedger.Fin {
        val order = orderOf(fin.order())
        val newest = if (order == GapLedger.ORDER_REVERSE) fin.first() else fin.last()
        val conversation = fin.conversation()
        return GapLedger.Fin(
            conversation =
                if (conversation == null) {
                    CursorMath.ACCOUNT_WIDE
                } else {
                    // A `GapLedger.Fin` names the region by a non-null key, so a conversation with no
                    // uuid cannot name the region this close belongs to; the Java-era call reached the
                    // non-null `Fin` parameter and crashed there, and this names the same crash rather
                    // than folding the row into the account-wide region.
                    conversation.getUuid() ?: throw NullPointerException("conversation has no uuid")
                },
            start = fin.start(),
            order = order,
            complete = fin.complete(),
            count = fin.count(),
            totalCount = fin.totalCount(),
            newestTime = if (newest == null) 0L else newest.getTimestamp(),
            newestReference = if (newest == null) null else newest.getReference(),
        )
    }

    private fun reference(cursor: CursorMath.Cursor): MamReference =
        if (cursor.anchorTime <= 0L) MamReference(0L) else MamReference(cursor.anchorTime, cursor.anchorStanzaId)

    private fun orderOf(order: MessageArchiveService.PagingOrder): Long =
        if (order == MessageArchiveService.PagingOrder.REVERSE) {
            GapLedger.ORDER_REVERSE
        } else {
            GapLedger.ORDER_NORMAL
        }

    /** `MamAbort`'s three reasons, as `sync_gap.reason` spells them. */
    private fun reasonOf(reason: MamAbort): String =
        when (reason) {
            MamAbort.TIMEOUT -> GapLedger.REASON_TIMEOUT
            MamAbort.ERROR -> GapLedger.REASON_ERROR
            MamAbort.KILLED -> GapLedger.REASON_KILLED
        }

    companion object {

        @Volatile private var engine: SyncEngine? = null

        /** The sink installed before the engine existed, handed to it as it is built. */
        @Volatile private var pendingSink: SweepSink? = null

        /** The one engine, over the one open database; built on the first island event. */
        /** The sink for the sweeps the engine owes. Idempotent; the last install wins. */
        @JvmStatic
        fun installSweepSink(sink: SweepSink) {
            engine?.sweeps = sink
            pendingSink = sink
        }

        @JvmStatic
        fun get(context: Context): SyncEngine {
            engine?.let { return it }
            return synchronized(this) {
                engine?.let { return it }
                val built =
                    SyncEngine(
                        store = RoomSyncStore(HistoryDatabase.opened(context)),
                        // One daemon thread: the engine's work is ordered, serial and never the
                        // caller's. A pool would let two events for one account race the reducer.
                        worker =
                            Executors.newSingleThreadExecutor { runnable ->
                                Thread(runnable, "tulkki-sync").apply { isDaemon = true }
                            },
                    )
                pendingSink?.let { built.sweeps = it }
                engine = built
                built
            }
        }

        /**
         * The engine with its two collaborators supplied: the JVM cell that pins "no DAO call on the
         * caller's thread" builds one of these with a recording executor and a store that records
         * which thread touched it.
         */
        internal fun forTest(store: SyncStore, worker: Executor, sweeps: SweepSink): SyncEngine =
            SyncEngine(store, worker).apply { this.sweeps = sweeps }
    }
}
