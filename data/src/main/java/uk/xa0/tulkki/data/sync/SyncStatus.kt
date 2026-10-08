package uk.xa0.tulkki.data.sync

/**
 * What one account's sync is doing, as a screen may read it (S5-6): the read model
 * `docs/MIGRATION.md` "Design: synchronisation" §3.3 declares under `:data`.
 *
 * <p>§3.3 sketches `SyncStatus(account: AccountId, phase: SyncPhase, gap: Gap?, sweptThrough:
 * Instant?)`. **This commit substitutes the tree's own types for the design's illustrative
 * wrappers**, per the row's brief: there is no `AccountId` and no `Instant` in this tree, every
 * `:data` surface names an account by its uuid `String` and an instant as milliseconds in a `Long`,
 * and inventing a wrapper merely to match the sketch's spelling would put a second spelling of the
 * same fact beside `SyncCursorEntity.accountUuid`. So [account] is the account uuid, [gap]'s two
 * fields and [sweptThrough] are epoch milliseconds.
 *
 * <p>**The phase is a value, never a mutable field a screen can hold** (§2.1's own rule), and it is
 * [SyncStateMachine]'s derivation, not a second one: [syncStatusOf] translates that reducer's
 * `Phase` one variant for one. Nothing here reads a clock, and nothing here writes.
 */
data class SyncStatus(
    /** The account uuid; the design's `AccountId`. */
    val account: String,
    /** §2.1's six phases, as a value. */
    val phase: SyncPhase,
    /**
     * The catch-up still owed, or null when nothing is owed. [Gap.start] is the oldest unproven
     * region's `gap_start`; [Gap.end] is the session instant the regions were opened against
     * (`sync_gap.gap_end`), `0` when no session is recorded - the same `0` [CursorMath.initial]
     * uses for "nothing".
     */
    val gap: Gap?,
    /**
     * The live-miss sweep's floor for the account: the oldest non-zero `swept_through` among its
     * conversation rows, or null when none has been swept. The design marks this "for the
     * diagnostics screen only"; it is read here and never decided on.
     */
    val sweptThrough: Long?,
)

/** §2.1's six phases, one variant each. */
sealed interface SyncPhase {

    /** No session. [gapOwed] separates "offline and current" from "offline owing a gap". */
    data class Offline(val gapOwed: Boolean) : SyncPhase

    /** A connection in flight. It must not open a gap and must not move an anchor. */
    data object Connecting : SyncPhase

    /** A session, and [open] regions still unproven. */
    data class CatchingUp(val open: Int) : SyncPhase

    /** A session, and no region owed. */
    data object Live : SyncPhase

    /** A session, and a region that was opened and cannot be proven; [reason] is the ledger's word. */
    data class Degraded(val reason: String) : SyncPhase

    /** A session with CSI inactive: nothing on the wire, and no conversation is "current". */
    data object Away : SyncPhase
}

/** A catch-up window: [start] and [end] are epoch milliseconds. */
data class Gap(val start: Long, val end: Long)

/**
 * [SyncStateMachine]'s state as the read model, plus the two facts the reducer does not hold.
 *
 * <p>The phase translation is one variant for one, so the two cannot drift; the gap is the account's
 * owed regions - the same "not `COMPLETE`" predicate the reducer's own `Offline(gapOwed)` uses - and
 * its `start` is their oldest `gap_start`.
 */
internal fun syncStatusOf(
    account: String,
    state: SyncStateMachine.State,
    sweptThrough: Long?,
): SyncStatus =
    SyncStatus(
        account = account,
        phase = syncPhaseOf(state.phase),
        gap = gapOf(state),
        sweptThrough = sweptThrough,
    )

internal fun syncPhaseOf(phase: SyncStateMachine.Phase): SyncPhase =
    when (phase) {
        is SyncStateMachine.Phase.Offline -> SyncPhase.Offline(phase.gapOwed)
        is SyncStateMachine.Phase.Connecting -> SyncPhase.Connecting
        is SyncStateMachine.Phase.CatchingUp -> SyncPhase.CatchingUp(phase.open)
        is SyncStateMachine.Phase.Live -> SyncPhase.Live
        is SyncStateMachine.Phase.Degraded -> SyncPhase.Degraded(phase.reason)
        is SyncStateMachine.Phase.Away -> SyncPhase.Away
    }

/**
 * The owed gap, or null. A region is owed while it is not `COMPLETE`, which is `SyncStateMachine`'s
 * own `gapOwed`; the window is measured from the oldest such region to the recorded session instant.
 */
internal fun gapOf(state: SyncStateMachine.State): Gap? {
    val owed =
        state.entries.filter { it.state != SyncQueries.GAP_STATE_COMPLETE.toLong() }
    if (owed.isEmpty()) {
        return null
    }
    return Gap(
        start = owed.minOf { it.region.gapStart },
        end = state.sessionInstant,
    )
}

/**
 * The account's live-miss floor from its conversation rows' `swept_through` values: the oldest
 * non-zero one, or null when none has been swept. `0` is the column's own "never swept", so it is
 * filtered rather than read as an instant.
 */
internal fun sweptFloorOf(swept: List<Long>): Long? = swept.filter { it > 0L }.minOrNull()
