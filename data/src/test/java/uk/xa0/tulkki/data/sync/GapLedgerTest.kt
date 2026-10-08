package uk.xa0.tulkki.data.sync

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.xmpp.Config

/**
 * S5-8's completeness proof, the eleven pure cells of `docs/MIGRATION.md`, "Design: synchronisation"
 * §7.1 - and what replaces `MessageArchiveService.anyCatchup` (§2.3).
 *
 * <p>Kotlin for the same reason `CursorMathTest` is: [GapLedger] is `internal` and Kotlin mangles
 * internal function names in bytecode.
 *
 * <p>**One wording is read rather than repeated.** §5.2's name for the abort cell says the region is
 * left "open", while §2.2's event table - the operative statement of what each event owes - says the
 * aborted region is `DEGRADED` with `ABORTED_AT_LIMIT`. The cell asserts §2.2's answer, and the
 * property §5.2 is actually after (the account's gap is *not* `COMPLETE`, and the anchor does not
 * move) holds either way, because `COMPLETE` is the only state that closes anything.
 */
class GapLedgerTest {

    private val session = 1_700_000_000_000L

    private val open = SyncQueries.GAP_STATE_OPEN.toLong()
    private val complete = SyncQueries.GAP_STATE_COMPLETE.toLong()
    private val degraded = SyncQueries.GAP_STATE_DEGRADED.toLong()

    private fun region(conversation: String, gapStart: Long, kind: Long) =
        CursorMath.Region(conversation, gapStart, kind)

    private fun conversation(uuid: String, anchorTime: Long, mode: Int = 0) =
        CursorMath.ConversationCursor(uuid, mode, null, anchorTime, anchorTime)

    @Test
    fun theLedgerEnumeratesEveryRegionUpstreamWouldQuery() {
        val window = Config.MAM_MAX_CATCHUP
        val cursor =
            CursorMath.Cursor(
                "srv-old",
                session - window - 1L,
                SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(),
                session,
            )

        val entries =
            GapLedger.openRegions(
                CursorMath.regions(
                    cursor,
                    listOf(
                        conversation("conv-a", anchorTime = session - window - 10_000L),
                        conversation("conv-muc", anchorTime = session - window - 10_000L, mode = 1),
                    ),
                    session,
                    window,
                ),
            )

        Assert.assertEquals(
            "the account-wide region, and for the one single conversation older than the edge both "
                + "the reverse region from its own anchor and the forward one from the edge - the "
                + "MUC owes nothing here, its catch-up is its join's",
            listOf(
                region(CursorMath.ACCOUNT_WIDE, session - window, CursorMath.REGION_FIRST),
                region("conv-a", session - window - 10_000L, CursorMath.REGION_RECENT),
                region("conv-a", session - window, CursorMath.REGION_FIRST),
            ),
            entries.map { it.region },
        )
        Assert.assertEquals(
            "and every region the enumeration hands over starts OPEN",
            listOf(open, open, open),
            entries.map { it.state },
        )
    }

    @Test
    fun aFinWithCompleteTrueClosesOnlyItsOwnRegion() {
        val entries =
            GapLedger.openRegions(
                listOf(
                    region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST),
                    region("conv-a", 100L, CursorMath.REGION_RECENT),
                ),
            )

        val outcome =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin(
                    conversation = CursorMath.ACCOUNT_WIDE,
                    start = 100L,
                    order = GapLedger.ORDER_NORMAL,
                    complete = true,
                    count = null,
                    totalCount = 3,
                ),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )

        Assert.assertEquals(complete, outcome.entries[0].state)
        Assert.assertEquals(
            "the reverse region shares the gap_start and is a different region: it stays open",
            open,
            outcome.entries[1].state,
        )
        Assert.assertEquals(region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST), outcome.closed)
        Assert.assertTrue("a proven close is what lets the anchor move", outcome.advanceAnchor)
    }

    @Test
    fun aFinWithoutCompleteLeavesTheRegionOpen() {
        val entries =
            GapLedger.openRegions(listOf(region("conv-a", 100L, CursorMath.REGION_FIRST)))

        val outcome =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin("conv-a", 100L, GapLedger.ORDER_NORMAL, complete = false, count = null, totalCount = 5),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )

        Assert.assertEquals("the server pages on, and the ledger waits for it", open, outcome.entries[0].state)
        Assert.assertNull(outcome.closed)
        Assert.assertFalse(outcome.advanceAnchor)
        Assert.assertFalse(GapLedger.complete(outcome.entries))
    }

    @Test
    fun theCountProofClosesTheRegionTheSameWayAnUpgradeDoes() {
        val entries =
            GapLedger.openRegions(listOf(region("conv-a", 100L, CursorMath.REGION_FIRST)))

        val proved =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin("conv-a", 100L, GapLedger.ORDER_NORMAL, complete = false, count = 5, totalCount = 5),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertEquals(
            "upstream's own proof: the server's count no larger than what was delivered",
            complete,
            proved.entries[0].state,
        )
        Assert.assertTrue(proved.advanceAnchor)

        val unproved =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin("conv-a", 100L, GapLedger.ORDER_NORMAL, complete = false, count = 9, totalCount = 5),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertEquals(
            "and a count larger than what arrived proves nothing",
            open,
            unproved.entries[0].state,
        )
    }

    @Test
    fun theAbortAtMamMaxMessagesLeavesTheRegionOpenAndKeepsTheAnchor() {
        val entries =
            GapLedger.openRegions(listOf(region("conv-a", 100L, CursorMath.REGION_FIRST)))

        val outcome =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin(
                    "conv-a",
                    100L,
                    GapLedger.ORDER_NORMAL,
                    complete = false,
                    count = null,
                    totalCount = Config.MAM_MAX_MESSAGES,
                ),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )

        Assert.assertEquals(
            "the region is not COMPLETE, so the account's gap is not either",
            degraded,
            outcome.entries[0].state,
        )
        Assert.assertEquals(GapLedger.REASON_ABORTED_AT_LIMIT, outcome.entries[0].reason)
        Assert.assertNull(outcome.closed)
        Assert.assertFalse(
            "and the anchor may not advance past a region nobody read",
            outcome.advanceAnchor,
        )
        Assert.assertFalse(GapLedger.complete(outcome.entries))
    }

    @Test
    fun theAccountWideAndTheReverseRegionDoNotCloseEachOther() {
        val entries =
            GapLedger.openRegions(
                listOf(
                    region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST),
                    region("conv-a", 100L, CursorMath.REGION_RECENT),
                ),
            )

        val reverse =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin("conv-a", 100L, GapLedger.ORDER_REVERSE, complete = true, count = null, totalCount = 2),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertEquals("the account-wide region is not the reverse one", open, reverse.entries[0].state)
        Assert.assertEquals(complete, reverse.entries[1].state)

        val both =
            GapLedger.applyFin(
                reverse.entries,
                GapLedger.Fin(
                    CursorMath.ACCOUNT_WIDE,
                    100L,
                    GapLedger.ORDER_NORMAL,
                    complete = true,
                    count = null,
                    totalCount = 2,
                ),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertTrue(GapLedger.complete(both.entries))
    }

    @Test
    fun aMucJoinClosesItsOwnRegionAndNotTheAccounts() {
        val entries =
            GapLedger.openRegions(
                listOf(
                    region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST),
                    region("conv-muc", 50L, CursorMath.REGION_FIRST),
                ),
            )

        val outcome =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin("conv-muc", 50L, GapLedger.ORDER_NORMAL, complete = true, count = null, totalCount = 1),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )

        Assert.assertEquals(
            "a room join's catch-up used to defer or trigger the account's; now it is its own row",
            open,
            outcome.entries[0].state,
        )
        Assert.assertEquals(complete, outcome.entries[1].state)
        Assert.assertFalse(GapLedger.complete(outcome.entries))
    }

    @Test
    fun theGapIsCompleteOnlyWhenEveryOpenedRegionIsComplete() {
        val entries =
            GapLedger.openRegions(
                listOf(
                    region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST),
                    region("conv-a", 100L, CursorMath.REGION_FIRST),
                ),
            )

        val one =
            GapLedger.applyFin(
                entries,
                GapLedger.Fin(
                    CursorMath.ACCOUNT_WIDE,
                    100L,
                    GapLedger.ORDER_NORMAL,
                    complete = true,
                    count = null,
                    totalCount = 1,
                ),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertFalse(GapLedger.complete(one.entries))

        val two =
            GapLedger.applyFin(
                one.entries,
                GapLedger.Fin("conv-a", 100L, GapLedger.ORDER_NORMAL, complete = true, count = null, totalCount = 1),
                maxMessages = Config.MAM_MAX_MESSAGES,
            )
        Assert.assertTrue(GapLedger.complete(two.entries))
        Assert.assertFalse(
            "a ledger with no region has no gap to prove: the engine asks isEmpty() first",
            GapLedger.complete(emptyList()),
        )
    }

    @Test
    fun aTimedOutRegionIsDegradedAndNotComplete() {
        val entries =
            GapLedger.openRegions(
                listOf(
                    region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST),
                    region("conv-a", 100L, CursorMath.REGION_FIRST),
                ),
            )

        val aborted = GapLedger.abort(entries, entries[1].region, GapLedger.REASON_TIMEOUT)

        Assert.assertEquals(open, aborted[0].state)
        Assert.assertEquals(degraded, aborted[1].state)
        Assert.assertEquals(GapLedger.REASON_TIMEOUT, aborted[1].reason)
        Assert.assertFalse(GapLedger.complete(aborted))
        Assert.assertEquals(
            "an abort for a region the ledger does not hold changes nothing",
            aborted,
            GapLedger.abort(aborted, region("conv-b", 100L, CursorMath.REGION_FIRST), GapLedger.REASON_KILLED),
        )
    }

    @Test
    fun aSessionLossMarksEveryOpenRegionOfThatSessionDegraded() {
        val entries =
            listOf(
                GapLedger.Entry(region(CursorMath.ACCOUNT_WIDE, 100L, CursorMath.REGION_FIRST), open, null),
                GapLedger.Entry(region("conv-a", 100L, CursorMath.REGION_FIRST), complete, null),
                GapLedger.Entry(region("conv-a", 200L, CursorMath.REGION_RECENT), open, null),
            )

        val lost = GapLedger.sessionLost(entries)

        Assert.assertEquals(
            "opened and never proven is DEGRADED, never COMPLETE, and a closed region is left alone",
            listOf(degraded, complete, degraded),
            lost.map { it.state },
        )
        Assert.assertEquals(
            listOf(GapLedger.REASON_SESSION_LOST, null, GapLedger.REASON_SESSION_LOST),
            lost.map { it.reason },
        )
    }

    @Test
    fun aReopenedDegradedRegionUsesTheSameGapStartAndDoesNotSpendTwice() {
        val degradedEntry =
            GapLedger.Entry(
                region("conv-a", 100L, CursorMath.REGION_FIRST),
                degraded,
                GapLedger.REASON_SESSION_LOST,
            )

        val reopened = GapLedger.reopened(listOf(degradedEntry))

        Assert.assertEquals(open, reopened[0].state)
        Assert.assertNull(reopened[0].reason)
        Assert.assertEquals(
            "the same region, so the same primary key: re-opening cannot add a row, and one absence "
                + "cannot owe two sweeps",
            degradedEntry.region,
            reopened[0].region,
        )
        Assert.assertEquals(100L, reopened[0].region.gapStart)
        Assert.assertFalse(GapLedger.complete(reopened))

        val closed = listOf(GapLedger.Entry(region("conv-a", 100L, CursorMath.REGION_FIRST), complete, null))
        Assert.assertEquals("a closed region is not re-opened", closed, GapLedger.reopened(closed))
    }
}
