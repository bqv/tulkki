package uk.xa0.tulkki.data.sync

/**
 * The per-conversation catch-up badge: what one conversation's row may draw about the gap it is in.
 *
 * <p>**The rule, and it is the one the plan's row was told to implement.** The badge answers the
 * **most specific open region that covers that conversation**: a `sync_gap` row whose `conversation`
 * is *this conversation's* uuid, if one is open; otherwise the account-wide row
 * (`conversation = ''`), if one is open; otherwise there is no badge at all. An account-wide row means
 * the whole account's catch-up, so it covers every conversation - which is the one reading that makes
 * the empty-string convention (`AGENTS.md`'s gap-sweep finding, and [CursorMath.ACCOUNT_WIDE])
 * coherent per row: the empty string is "every conversation of this account", not "no conversation".
 *
 * <p><strong>"Open" is `sync_gap.state = OPEN`, and nothing else.** A `COMPLETE` region is caught up;
 * a `DEGRADED` one was opened and could not be proven, and it is *not* what this badge answers - the
 * account-level [SyncStatus] shows that state, and a per-conversation badge that promised "catching
 * up" for a region nothing is querying would be a lie about the wire.
 *
 * <p><strong>The phase is the state machine's own, not a second derivation.** The covering regions are
 * handed to [SyncStateMachine.restored] - the reducer's own entry point for a state rebuilt from
 * persisted rows - and its phase is translated by [syncPhaseOf], the same one-variant-for-one function
 * the account-level [syncStatusOf] uses. So the two cannot disagree about what "caught up" means: this
 * is the account's phase computed over a narrower set of regions.
 *
 * <p>**Where the badge is drawn is not this file's.** `docs/MIGRATION.md`, "Design: synchronisation"
 * §3.3 puts it on the conversation read model (`ConversationSummary(..., val catchup: CatchupState)`),
 * whose `:ui` half is `ui-2`; this is the `:data` half - the rule, as a value a screen may hold, with
 * [SyncEngine.catchup] as its one producer.
 */
data class CatchupState(
    /**
     * The phase of the most specific open region covering the conversation. It is a `CatchingUp`
     * variant for every region this answers for, because only `OPEN` rows are considered; it is the
     * reducer's phase rather than a literal so that a future phase cannot be spelled twice.
     */
    val phase: SyncPhase,
)

/**
 * The badge for one conversation, from its account's ledger entries and the session they were opened
 * against; null when no open region covers it.
 */
internal fun catchupOf(
    conversation: String,
    entries: List<GapLedger.Entry>,
    sessionInstant: Long,
): CatchupState? {
    val open = entries.filter { it.state == SyncQueries.GAP_STATE_OPEN.toLong() }
    val itsOwn = open.filter { it.region.conversation == conversation }
    val covering =
        if (itsOwn.isNotEmpty()) {
            itsOwn
        } else {
            open.filter { it.region.conversation == CursorMath.ACCOUNT_WIDE }
        }
    if (covering.isEmpty()) {
        return null
    }
    val narrowed = SyncStateMachine.restored(CursorMath.initial(), covering, sessionInstant)
    return CatchupState(syncPhaseOf(narrowed.phase))
}
