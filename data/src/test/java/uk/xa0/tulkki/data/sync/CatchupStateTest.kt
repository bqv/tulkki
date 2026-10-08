package uk.xa0.tulkki.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S5-6's `catchup` cell: the per-conversation badge, as the rule the plan's row carries.
 *
 * <p><strong>The rule, one cell each.</strong> The badge is the **most specific open region covering
 * the conversation** - its own row if one is open, else the account-wide row, else nothing; a region
 * that is `COMPLETE` or `DEGRADED` is not "open", so it is not this badge; and an account-wide row
 * covers every conversation, so a conversation with no row of its own still takes it. The phase comes
 * from `SyncStateMachine.restored` and `syncPhaseOf` - the reducer's own translation - so the cell that
 * compares it with `syncStatusOf` is the agreement between the badge and the account-level status,
 * not a second derivation of it.
 *
 * <p>Nothing here needs a database: the inputs are the ledger entries the engine loads, and the
 * producer is `SyncEngine.catchup`.
 */
class CatchupStateTest {

    private val accountWide = CursorMath.ACCOUNT_WIDE

    /** The conversation's own open region wins, even when the account-wide one is open too. */
    @Test
    fun aConversationWithItsOwnOpenRegionTakesItsOwnAndNotTheWiderOne() {
        val entries =
            listOf(
                open("conv-1", 100L, 1_000L),
                open(accountWide, 200L, 1_000L),
            )

        assertEquals(
            "the narrower region is the one that answers for conv-1",
            CatchupState(SyncPhase.CatchingUp(open = 1)),
            catchupOf("conv-1", entries, sessionInstant = 1_000L),
        )
        assertEquals(
            "and a conversation with no row of its own takes the account-wide one, because it "
                    + "covers every conversation",
            CatchupState(SyncPhase.CatchingUp(open = 1)),
            catchupOf("conv-2", entries, sessionInstant = 1_000L),
        )
    }

    /** A conversation covered only by the account-wide row takes that one. */
    @Test
    fun aConversationCoveredOnlyByTheAccountWideRowTakesThatOne() {
        val entries = listOf(open(accountWide, 200L, 1_000L))

        assertEquals(
            "the account-wide row is every conversation's",
            CatchupState(SyncPhase.CatchingUp(open = 1)),
            catchupOf("conv-1", entries, sessionInstant = 1_000L),
        )
    }

    /**
     * No open region means no badge - and a region that is `COMPLETE` or `DEGRADED` is not open. The
     * second half is the clause worth a cell: a `DEGRADED` region is one nothing is querying, so a
     * badge saying "catching up" for it would promise work that is not happening.
     */
    @Test
    fun noOpenRegionMeansNoBadgeAndDegradedIsNotOpen() {
        assertNull(
            "an account with nothing owed has no badge for any conversation",
            catchupOf("conv-1", emptyList(), sessionInstant = 1_000L),
        )
        assertNull(
            "a conversation whose only row is complete is caught up",
            catchupOf(
                "conv-1",
                listOf(entry("conv-1", 100L, SyncQueries.GAP_STATE_COMPLETE.toLong(), null)),
                sessionInstant = 1_000L,
            ),
        )
        assertNull(
            "and a degraded row is not a badge: nothing is querying that region",
            catchupOf(
                "conv-1",
                listOf(
                    entry(
                        "conv-1",
                        100L,
                        SyncQueries.GAP_STATE_DEGRADED.toLong(),
                        GapLedger.REASON_TIMEOUT,
                    )
                ),
                sessionInstant = 1_000L,
            ),
        )
    }

    /**
     * The badge's phase is the account-level translation's, over a narrower set of regions: the same
     * covering entries handed to `syncStatusOf` answer the same phase. That is the "the two cannot
     * disagree about what caught up means" clause, measured rather than asserted in prose.
     */
    @Test
    fun theBadgePhaseIsTheSameTranslationTheAccountStatusUses() {
        val covering = listOf(open("conv-1", 100L, 1_000L), open("conv-1", 50L, 1_000L))

        val badge = catchupOf("conv-1", covering, sessionInstant = 1_000L)
        val account =
            syncStatusOf(
                "acct-1",
                SyncStateMachine.restored(CursorMath.initial(), covering, 1_000L),
                sweptThrough = null,
            )

        assertEquals(
            "the badge counts the conversation's own open regions, and the account's status the same "
                    + "set would report for them",
            account.phase,
            badge!!.phase,
        )
        assertEquals(
            "which is both of them, not one",
            SyncPhase.CatchingUp(open = 2),
            badge.phase,
        )
    }

    private fun open(conversation: String, gapStart: Long, sessionInstant: Long): GapLedger.Entry =
        entry(conversation, gapStart, SyncQueries.GAP_STATE_OPEN.toLong(), null)

    private fun entry(
        conversation: String,
        gapStart: Long,
        state: Long,
        reason: String?,
    ): GapLedger.Entry =
        GapLedger.Entry(
            region = CursorMath.Region(conversation, gapStart, CursorMath.REGION_FIRST),
            state = state,
            reason = reason,
        )
}
