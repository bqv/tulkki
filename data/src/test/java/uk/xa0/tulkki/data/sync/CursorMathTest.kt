package uk.xa0.tulkki.data.sync

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.xmpp.Config

/**
 * S5-8's cursor arithmetic, pure and JVM-testable (`docs/MIGRATION.md`, "Design: synchronisation"
 * §7.1).
 *
 * <p>It is a Kotlin test because [CursorMath] is `internal`: Kotlin mangles internal *function*
 * names in bytecode, so a Java test cannot name them, and widening the class is the one thing the
 * capability package's shape forbids. It is the second Kotlin test in `:data`, after
 * `BlockingDaoExecutionTest`, and it exists for the same reason.
 *
 * <p>**What "the anchor" means in two of the cells below.** §7.1's names for the full-page and
 * partial-page cells say *anchor*, and the arithmetic that distinguishes a full page from a partial
 * one in this design is the **sweep floor** (§1.2: "it advances to the newest `timeSent` it
 * examined so a row left alone is left alone for good"), which is the rule `StartupBacklog.everything`
 * carries today and which the paging anchor never had - a forward page advances the paging anchor to
 * its newest stanza whether or not it was full. The cells pin that rule here, and
 * [CursorMath.anchorAfterPage]'s own cell pins the paging anchor's, so the two are not conflated.
 */
class CursorMathTest {

    /** A session instant: every arithmetic below names the instant it reasons about. */
    private val session = 1_700_000_000_000L

    private fun conversation(
        uuid: String,
        anchorTime: Long,
        anchorStanzaId: String? = null,
        mode: Int = Conversation.MODE_SINGLE,
    ) = CursorMath.ConversationCursor(uuid, mode, anchorStanzaId, anchorTime, anchorTime)

    @Test
    fun aFreshAccountHasNoAnchorAndOpensNoGap() {
        val cursor = CursorMath.initial()

        Assert.assertEquals(0L, cursor.anchorTime)
        Assert.assertNull(cursor.anchorStanzaId)
        Assert.assertEquals(SyncQueries.ANCHOR_SOURCE_INITIAL.toLong(), cursor.anchorSource)
        Assert.assertEquals(0L, cursor.gapEnd)
        Assert.assertTrue(
            "anchor_time == 0 means MAM is never asked, so not even the account-wide region opens",
            CursorMath.regions(cursor, emptyList(), session, Config.MAM_MAX_CATCHUP).isEmpty(),
        )
    }

    @Test
    fun anAnchorAheadOfTheSessionIsClampedAndMarkedInitial() {
        val ahead = CursorMath.Cursor("srv-9", session + 60_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session)

        val clamped = CursorMath.clamped(ahead, session)

        Assert.assertEquals(
            "a start in the future would never close, so the anchor is clamped to the session instant",
            session,
            clamped.anchorTime,
        )
        Assert.assertEquals(
            "and the clamp is recorded as INITIAL rather than silently repaired",
            SyncQueries.ANCHOR_SOURCE_INITIAL.toLong(),
            clamped.anchorSource,
        )
        val behind = ahead.copy(anchorTime = session - 1L)
        Assert.assertEquals(
            "while an anchor behind the session is left exactly where it is",
            behind,
            CursorMath.clamped(behind, session),
        )
    }

    @Test
    fun aReferenceWithoutATimeIsUsableForPagingButNotForWidth() {
        val referenceOnly = CursorMath.Cursor("srv-7", 0L, SyncQueries.ANCHOR_SOURCE_INITIAL.toLong(), session)

        Assert.assertTrue(CursorMath.usableForPaging(referenceOnly))
        Assert.assertFalse(
            "only a time measures, so there is no gap width and no region to open",
            CursorMath.hasWidth(referenceOnly),
        )
        Assert.assertTrue(
            CursorMath.regions(referenceOnly, emptyList(), session, Config.MAM_MAX_CATCHUP).isEmpty(),
        )
        Assert.assertTrue(CursorMath.hasWidth(referenceOnly.copy(anchorTime = 1L)))
    }

    @Test
    fun theAccountAnchorIsNeverBehindAnyConversationAnchor() {
        val account = CursorMath.Cursor("srv-1", 1_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session)

        val raised =
            CursorMath.raised(
                account,
                listOf(
                    conversation("conv-a", anchorTime = 500L, anchorStanzaId = "srv-a"),
                    conversation("conv-b", anchorTime = 3_000L, anchorStanzaId = "srv-b"),
                ),
            )

        Assert.assertEquals(3_000L, raised.anchorTime)
        Assert.assertEquals("srv-b", raised.anchorStanzaId)
        Assert.assertEquals(
            "and a conversation behind the account's own frontier does not lower it",
            1_000L,
            CursorMath.raised(account, listOf(conversation("conv-a", anchorTime = 500L))).anchorTime,
        )
    }

    @Test
    fun aFullPageLeavesTheAnchorAtTheOldestStanzaOfThePageSoTheNextQueryWalksOn() {
        val page = (1..3).map { CursorMath.SweptRow(timeSent = it * 100L, answered = false) }

        Assert.assertEquals(
            "a page read back at the ceiling may have been truncated, so the floor stays just "
                + "behind its oldest row and the next read walks further on",
            99L,
            CursorMath.sweepFloorAfter(page, floor = 0L, readLimit = 3),
        )
    }

    @Test
    fun aPartialPageAdvancesTheAnchorToTheNewestStanza() {
        val page = listOf(CursorMath.SweptRow(100L, answered = false), CursorMath.SweptRow(300L, answered = false))

        Assert.assertEquals(
            "a page that was not full has been examined to its end, so the floor moves to its newest row",
            300L,
            CursorMath.sweepFloorAfter(page, floor = 0L, readLimit = 3),
        )
        Assert.assertEquals(
            "and a read that returned nothing leaves the floor exactly where it was",
            50L,
            CursorMath.sweepFloorAfter(emptyList(), floor = 50L, readLimit = 3),
        )
    }

    @Test
    fun aReverseRegionNeverMovesTheForwardAnchor() {
        val forward = CursorMath.Cursor("srv-5", 5_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session)

        Assert.assertEquals(
            "the reverse region is a read of what the forward walk has not reached, so its page "
                + "cannot advance the paging anchor",
            forward,
            CursorMath.anchorAfterPage(
                forward,
                CursorMath.REGION_RECENT,
                newestTime = 9_000L,
                newestReference = "srv-9",
                source = SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(),
            ),
        )
        Assert.assertEquals(
            "while a forward page does advance it, to the newest stanza it accounted for",
            9_000L,
            CursorMath.anchorAfterPage(
                    forward,
                    CursorMath.REGION_FIRST,
                    newestTime = 9_000L,
                    newestReference = "srv-9",
                    source = SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(),
                )
                .anchorTime,
        )
    }

    @Test
    fun aGapLongerThanMamMaxCatchupSplitsAtExactlyFiveDays() {
        val window = Config.MAM_MAX_CATCHUP
        val cursor = CursorMath.Cursor("srv-old", session - window - 1L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session)

        val regions =
            CursorMath.regions(
                cursor,
                listOf(
                    conversation(
                        "conv-a",
                        anchorTime = session - window - 10_000L,
                        anchorStanzaId = "srv-a",
                    ),
                    conversation("conv-b", anchorTime = session - 1_000L),
                ),
                session,
                window,
            )

        Assert.assertEquals(
            "the account-wide region starts exactly one window back rather than at the anchor",
            CursorMath.Region(CursorMath.ACCOUNT_WIDE, session - window, CursorMath.REGION_FIRST),
            regions[0],
        )
        Assert.assertEquals(
            "and a conversation older than that edge owes both halves: the reverse region from its "
                + "own anchor, and the forward one from the edge",
            listOf(
                CursorMath.Region("conv-a", session - window - 10_000L, CursorMath.REGION_RECENT),
                CursorMath.Region("conv-a", session - window, CursorMath.REGION_FIRST),
            ),
            regions.drop(1),
        )
    }

    @Test
    fun anAbsenceShorterThanMamMaxCatchupOpensOnlyTheAccountWideRegion() {
        val window = Config.MAM_MAX_CATCHUP
        val cursor = CursorMath.Cursor("srv-1", session - 1_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session)

        Assert.assertEquals(
            "inside the window upstream starts the account-wide query alone, and so does this",
            listOf(
                CursorMath.Region(
                    CursorMath.ACCOUNT_WIDE,
                    session - 1_000L,
                    CursorMath.REGION_FIRST,
                ),
            ),
            CursorMath.regions(
                cursor,
                listOf(conversation("conv-a", anchorTime = 0L), conversation("conv-muc", anchorTime = 0L, mode = Conversation.MODE_MULTI)),
                session,
                window,
            ),
        )
    }

    @Test
    fun theSweepFloorAdvancesPastARowThatWasAlreadyAnswered() {
        val page =
            listOf(
                CursorMath.SweptRow(timeSent = 1_000L, answered = true),
                CursorMath.SweptRow(timeSent = 500L, answered = false),
            )

        Assert.assertEquals(
            "the floor moves to the newest row the read examined, answered or not, so the answered "
                + "row is not read again for ever",
            1_000L,
            CursorMath.sweepFloorAfter(page, floor = 0L, readLimit = 8),
        )
        Assert.assertEquals(
            "and it is not a candidate: the never-twice property is the other half of the same rule",
            listOf(500L),
            CursorMath.sweepRows(page, floor = 0L),
        )
    }

    @Test
    fun theSweepFloorIsPerConversationSoOneConversationDoesNotSuppressAnother() {
        val answered = listOf(CursorMath.SweptRow(timeSent = 9_000L, answered = true))
        val owed = listOf(CursorMath.SweptRow(timeSent = 500L, answered = false))

        Assert.assertEquals(
            "the conversation whose rows were all answered still advances its own floor",
            9_000L,
            CursorMath.sweepFloorAfter(answered, floor = 0L, readLimit = 8),
        )
        Assert.assertEquals(
            "and the other conversation's floor is its own, not dragged up by the first",
            500L,
            CursorMath.sweepFloorAfter(owed, floor = 0L, readLimit = 8),
        )
        Assert.assertEquals(
            "so its row is still a candidate",
            listOf(500L),
            CursorMath.sweepRows(owed, floor = 0L),
        )
    }

    @Test
    fun aSecondAccountNeverSeesTheFirstAccountsAnchor() {
        val cursors =
            listOf(
                CursorMath.AccountCursor(
                    "acct-1",
                    CursorMath.Cursor("srv-1", 1_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session),
                ),
                CursorMath.AccountCursor(
                    "acct-2",
                    CursorMath.Cursor("srv-2", 8_000L, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), session),
                ),
            )

        Assert.assertEquals(1_000L, CursorMath.forAccount(cursors, "acct-1").anchorTime)
        Assert.assertEquals("srv-2", CursorMath.forAccount(cursors, "acct-2").anchorStanzaId)
        Assert.assertEquals(
            "and an account with no row of its own is initial(), never another account's",
            CursorMath.initial(),
            CursorMath.forAccount(cursors, "acct-3"),
        )
    }
}
