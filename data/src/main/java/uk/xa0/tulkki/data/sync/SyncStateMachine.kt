package uk.xa0.tulkki.data.sync

/**
 * The account's sync state machine: the reducer that maps `(state, event)` to `(state', owed
 * actions)`.
 *
 * <p>"Design: synchronisation" §2.1 is the phase table and §2.2 the event table; both are read here
 * rather than invented. The phase is **derived** from the facts the state holds - whether a session
 * exists, whether any region is still open, whether a region is degraded, and whether CSI is
 * inactive - which is §2.1's own rule ("the state is derived from the cursor rows plus the session,
 * and is exposed as a value, never as a mutable field a screen can hold"). Nothing here reads a
 * clock: every event carries the instant it happened at, so a fake clock can stand anywhere in a
 * session.
 *
 * <p>**One sweep per gap close** (§4.2, §5.2). The sweep is owed when the *account's* gap becomes
 * complete, not when a region closes: several `<fin>`s closing the regions of one opened gap produce
 * exactly one [Action.Sweep], which is what the five-second floor's coalescing half was for, and a
 * duplicate close cannot produce a second one. The second half of the floor - "a socket that
 * reconnects in a loop should not read the candidate window on every round" - is not a clock either:
 * the sweep query requires `translation_state = 0`, so a pass over a gap that carried nothing
 * readable returns nothing.
 *
 * <p>**A resume is not a fresh bind, and that is upstream's distinction.** `catchup()` runs from
 * `onAdvancedStreamFeaturesAvailable`, a fresh bind; an SM resume folds the resumed stanzas in as
 * deliveries and never enumerates. So a resume here re-opens what the ledger already holds from the
 * same `gap_start` and queries it, and it neither opens a new region nor closes one - the ledger's
 * completeness proof closes regions, and only a `<fin>` feeds it.
 */
internal object SyncStateMachine {

    /** §2.1's six phases, as a value. */
    sealed interface Phase {
        /** No session. `gapOwed` is what separates "offline and current" from "offline owing a gap". */
        data class Offline(val gapOwed: Boolean) : Phase

        /** A connection in flight. Must not open a gap and must not move an anchor. */
        object Connecting : Phase

        /** A session, and at least one region still `OPEN`. */
        data class CatchingUp(val open: Int) : Phase

        /** A session, and no region owed. */
        object Live : Phase

        /** A session, and a region that was opened and cannot be proven. */
        data class Degraded(val reason: String) : Phase

        /** A session with CSI inactive: nothing on the wire, and no conversation is "current". */
        object Away : Phase
    }

    /**
     * Everything the phase and the next answer are derived from. [entries] is the ledger; [anchor] is
     * the paging anchor; [sessionInstant] is 0 until a session is established.
     */
    data class State(
        val phase: Phase,
        val anchor: CursorMath.Cursor,
        val sessionInstant: Long,
        val connecting: Boolean,
        val resumed: Boolean,
        val csiInactive: Boolean,
        val entries: List<GapLedger.Entry>,
    )

    /** What the engine owes after an event. */
    sealed interface Action {
        /** Persist these regions `OPEN` **before** the queries go out (§2.2). */
        data class OpenRegions(val regions: List<CursorMath.Region>) : Action

        /** Ask the server for this region. */
        data class Query(val region: CursorMath.Region) : Action

        /** Record the region proved `COMPLETE`. */
        data class CloseRegion(val region: CursorMath.Region) : Action

        /** Record the region `DEGRADED`, with the reason. */
        data class DegradeRegion(val region: CursorMath.Region, val reason: String) : Action

        /** Run the gap sweep for one closed gap. Owed once per gap, never per region. */
        data class Sweep(val gapStart: Long) : Action

        /** The account is gone: its cursor and its regions go with it (the cascade, plus §1.5). */
        object DeleteCursor : Action
    }

    /** §2.2's events. */
    sealed interface Event {
        object Connect : Event

        /**
         * A session is established. `resumed` is the SM resume; [conversationCursors] is only read for a
         * fresh bind, which is the path that enumerates.
         */
        data class SessionEstablished(
            val sessionInstant: Long,
            val resumed: Boolean,
            val anchor: CursorMath.Cursor,
            val conversationCursors: List<CursorMath.ConversationCursor> = emptyList(),
        ) : Event

        /** A `<fin>`, matched to its own region by the ledger. */
        data class Fin(val fin: GapLedger.Fin) : Event

        /** A timed-out, killed or errored query: the path that reaches no trigger today (§0). */
        data class Aborted(val region: CursorMath.Region, val reason: String) : Event

        object CsiInactive : Event

        object CsiActive : Event

        object SessionEnded : Event

        object AccountRemoved : Event
    }

    data class Transition(val state: State, val actions: List<Action>)

    fun initial(): State =
        State(
            phase = Phase.Offline(gapOwed = false),
            anchor = CursorMath.initial(),
            sessionInstant = 0L,
            connecting = false,
            resumed = false,
            csiInactive = false,
            entries = emptyList(),
        )

    /**
     * The state a surviving file holds, for the first event after a process death: the anchor and the
     * ledger's rows are the facts, and the phase is [derive]d from them. `sessionInstant` is the
     * persisted `gap_end`, or 0 when no session has been recorded - a phase of
     * `Offline(gapOwed = true)` is then the honest answer, and the next session's establishment
     * re-measures it.
     */
    fun restored(anchor: CursorMath.Cursor, entries: List<GapLedger.Entry>, sessionInstant: Long): State =
        derive(
            State(
                phase = Phase.Offline(gapOwed = false),
                anchor = anchor,
                sessionInstant = sessionInstant,
                connecting = false,
                resumed = false,
                csiInactive = false,
                entries = entries,
            )
        )

    fun on(state: State, event: Event, window: Long, maxMessages: Int): Transition =
        when (event) {
            Event.Connect ->
                Transition(
                    derive(state.copy(connecting = true, sessionInstant = 0L, resumed = false, csiInactive = false)),
                    emptyList(),
                )
            Event.CsiInactive -> Transition(derive(state.copy(csiInactive = true)), emptyList())
            Event.CsiActive -> onCsiActive(state)
            Event.SessionEnded -> onSessionEnded(state)
            Event.AccountRemoved -> Transition(initial(), listOf(Action.DeleteCursor))
            is Event.SessionEstablished -> onSessionEstablished(state, event, window)
            is Event.Fin -> onFin(state, event.fin, maxMessages)
            is Event.Aborted ->
                Transition(
                    derive(state.copy(entries = GapLedger.abort(state.entries, event.region, event.reason))),
                    emptyList(),
                )
        }

    private fun onSessionEstablished(state: State, event: Event.SessionEstablished, window: Long): Transition {
        val base =
            state.copy(
                anchor = event.anchor,
                sessionInstant = event.sessionInstant,
                connecting = false,
                resumed = event.resumed,
                csiInactive = false,
            )
        val actions = ArrayList<Action>()
        if (event.resumed) {
            // A resume narrows the gap - the session instant is re-measured - and never closes one.
            val reopened = GapLedger.reopened(state.entries)
            val changed = changed(state.entries, reopened)
            if (changed.isNotEmpty()) {
                actions += Action.OpenRegions(changed.map { it.region })
                actions += changed.map { Action.Query(it.region) }
            }
            return Transition(derive(base.copy(entries = reopened)), actions)
        }
        if (!CursorMath.hasWidth(event.anchor)) {
            // anchor_time == 0: no gap, and MAM is never asked (upstream's own early return, §1.3).
            return Transition(derive(base.copy(entries = emptyList())), actions)
        }
        val regions = CursorMath.regions(event.anchor, event.conversationCursors, event.sessionInstant, window)
        val reopened = GapLedger.reopened(state.entries)
        val reopens = changed(state.entries, reopened)
        val held = reopened.map { it.region }.toSet()
        val missing = regions.filter { it !in held }
        // PERSIST FIRST, then query: a process death between "asked" and "answered" must be a
        // DEGRADED row rather than a memory (§2.2).
        val toOpen = reopens.map { it.region } + missing
        if (toOpen.isNotEmpty()) {
            actions += Action.OpenRegions(toOpen)
        }
        actions += regions.map { Action.Query(it) }
        return Transition(derive(base.copy(entries = reopened + GapLedger.openRegions(missing))), actions)
    }

    private fun onCsiActive(state: State): Transition {
        val reopened = GapLedger.reopened(state.entries)
        val changed = changed(state.entries, reopened)
        val actions = ArrayList<Action>()
        if (changed.isNotEmpty()) {
            // The cheapest reconcile point there is: a degraded region is re-opened from the same
            // gap_start, without waiting for a reconnect - and CSI is allowed to trigger nothing else.
            actions += Action.OpenRegions(changed.map { it.region })
            actions += changed.map { Action.Query(it.region) }
        }
        return Transition(derive(state.copy(csiInactive = false, entries = reopened)), actions)
    }

    private fun onSessionEnded(state: State): Transition {
        val lost = GapLedger.sessionLost(state.entries)
        val actions =
            changed(state.entries, lost).map { Action.DegradeRegion(it.region, it.reason ?: GapLedger.REASON_SESSION_LOST) }
        // The anchors are untouched: they are the record of what was accounted for, and losing the
        // socket changes nothing about it.
        return Transition(derive(state.copy(sessionInstant = 0L, connecting = false, csiInactive = false, entries = lost)), actions)
    }

    private fun onFin(state: State, fin: GapLedger.Fin, maxMessages: Int): Transition {
        val wasComplete = GapLedger.complete(state.entries)
        val outcome = GapLedger.applyFin(state.entries, fin, maxMessages)
        val actions = ArrayList<Action>()
        if (outcome.closed != null) {
            actions += Action.CloseRegion(outcome.closed)
        } else {
            val degraded = changed(state.entries, outcome.entries).firstOrNull()
            if (degraded != null) {
                actions += Action.DegradeRegion(degraded.region, degraded.reason ?: GapLedger.REASON_ABORTED_AT_LIMIT)
            }
        }
        var anchor = state.anchor
        if (outcome.advanceAnchor) {
            anchor =
                CursorMath.anchorAfterPage(
                    anchor,
                    CursorMath.REGION_FIRST,
                    fin.newestTime,
                    fin.newestReference,
                    SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(),
                )
        }
        if (GapLedger.complete(outcome.entries) && !wasComplete) {
            // The account's gap just closed: one sweep, once, for the gap - not once per region.
            actions += Action.Sweep(gapIdentity(outcome.entries))
        }
        return Transition(derive(state.copy(anchor = anchor, entries = outcome.entries)), actions)
    }

    /** The gap's identity for the sweep: the account-wide region's `gap_start`, or the oldest one. */
    private fun gapIdentity(entries: List<GapLedger.Entry>): Long =
        (entries.firstOrNull { it.region.conversation == CursorMath.ACCOUNT_WIDE } ?: entries.first()).region.gapStart

    /** The entries that differ between two ledgers of the same length, in order. */
    private fun changed(before: List<GapLedger.Entry>, after: List<GapLedger.Entry>): List<GapLedger.Entry> =
        after.filterIndexed { at, entry -> entry != before[at] }

    /** §2.1: the phase is derived from the rows plus the session, never carried as a mutable field. */
    private fun derive(state: State): State {
        val open = state.entries.count { it.state == SyncQueries.GAP_STATE_OPEN.toLong() }
        val degraded = state.entries.firstOrNull { it.state == SyncQueries.GAP_STATE_DEGRADED.toLong() }
        val phase =
            when {
                state.connecting -> Phase.Connecting
                state.sessionInstant == 0L ->
                    Phase.Offline(
                        gapOwed =
                            state.entries.any { it.state != SyncQueries.GAP_STATE_COMPLETE.toLong() },
                    )
                open > 0 -> Phase.CatchingUp(open)
                degraded != null -> Phase.Degraded(degraded.reason ?: GapLedger.REASON_KILLED)
                state.csiInactive -> Phase.Away
                else -> Phase.Live
            }
        return state.copy(phase = phase)
    }
}
