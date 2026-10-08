package uk.xa0.tulkki.data.sync

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.xmpp.Config

/**
 * S5-8's state machine, pure and JVM-testable (`docs/MIGRATION.md`, "Design: synchronisation" §7.1)
 * - plus §5.2's own proof that the five-second floor is gone for a reason:
 * [ASweepDoesNotStartTwiceForOneGapClose].
 *
 * <p>Kotlin for the reason the other two pure tests are: [SyncStateMachine] is `internal`.
 *
 * <p>The "fake clock" the design asks for is the events' own instants: nothing in the reducer reads
 * a clock, so a session can be placed at any moment by naming it, and the same gap can be closed at
 * two different instants.
 */
class SyncStateMachineTest {

    private val session = 1_700_000_000_000L
    private val window = Config.MAM_MAX_CATCHUP
    private val maxMessages = Config.MAM_MAX_MESSAGES

    private val open = SyncQueries.GAP_STATE_OPEN.toLong()
    private val complete = SyncQueries.GAP_STATE_COMPLETE.toLong()
    private val degraded = SyncQueries.GAP_STATE_DEGRADED.toLong()

    private fun anchored(time: Long, reference: String? = "srv-anchor") =
        CursorMath.Cursor(reference, time, SyncQueries.ANCHOR_SOURCE_MAM_FIN.toLong(), time)

    private fun region(conversation: String, gapStart: Long, kind: Long = CursorMath.REGION_FIRST) =
        CursorMath.Region(conversation, gapStart, kind)

    private fun entry(region: CursorMath.Region, state: Long, reason: String? = null) =
        GapLedger.Entry(region, state, reason)

    private fun fin(
        conversation: String,
        start: Long,
        order: Long = GapLedger.ORDER_NORMAL,
        complete: Boolean = true,
        count: Int? = null,
        totalCount: Int = 1,
        newestTime: Long = 0L,
        newestReference: String? = null,
    ) =
        GapLedger.Fin(
            conversation = conversation,
            start = start,
            order = order,
            complete = complete,
            count = count,
            totalCount = totalCount,
            newestTime = newestTime,
            newestReference = newestReference,
        )

    private fun run(state: SyncStateMachine.State, event: SyncStateMachine.Event) =
        SyncStateMachine.on(state, event, window, maxMessages)

    private fun bind(
        anchor: CursorMath.Cursor,
        resumed: Boolean = false,
        at: Long = session,
        conversationCursors: List<CursorMath.ConversationCursor> = emptyList(),
    ) = SyncStateMachine.Event.SessionEstablished(at, resumed, anchor, conversationCursors)

    @Test
    fun aFreshBindWithNoAnchorGoesLiveAndNeverQueriesMam() {
        val transition = run(SyncStateMachine.initial(), bind(CursorMath.initial()))

        Assert.assertEquals(SyncStateMachine.Phase.Live, transition.state.phase)
        Assert.assertEquals(
            "anchor_time == 0 is upstream's own early return: MAM is never asked",
            emptyList<SyncStateMachine.Action>(),
            transition.actions,
        )
        Assert.assertTrue(transition.state.entries.isEmpty())
    }

    @Test
    fun aFreshBindWithAnAnchorOpensAGapBeforeTheFirstQueryIsSent() {
        val transition = run(SyncStateMachine.initial(), bind(anchored(session - 1_000L)))

        val owed = region(CursorMath.ACCOUNT_WIDE, session - 1_000L)
        Assert.assertEquals(
            "the region is persisted OPEN first, and only then queried: a process death between "
                + "'asked' and 'answered' is a DEGRADED row rather than a memory",
            listOf(SyncStateMachine.Action.OpenRegions(listOf(owed)), SyncStateMachine.Action.Query(owed)),
            transition.actions,
        )
        Assert.assertEquals(SyncStateMachine.Phase.CatchingUp(1), transition.state.phase)
    }

    @Test
    fun aResumeNarrowsTheGapButNeverClosesIt() {
        val opening = run(SyncStateMachine.initial(), bind(anchored(session - 1_000L)))
        val resumed = run(opening.state, bind(anchored(session + 60_000L), resumed = true, at = session + 60_000L))

        Assert.assertEquals(
            "the session instant is re-measured, which is the whole of 'narrows'",
            session + 60_000L,
            resumed.state.sessionInstant,
        )
        Assert.assertEquals(
            "and the region is still owed, from the same gap_start",
            opening.state.entries[0].region,
            resumed.state.entries[0].region,
        )
        Assert.assertEquals(open, resumed.state.entries[0].state)
        Assert.assertEquals(SyncStateMachine.Phase.CatchingUp(1), resumed.state.phase)
        Assert.assertFalse(
            "only a MAM close closes a region, and a resume is not one",
            resumed.actions.any {
                it is SyncStateMachine.Action.CloseRegion || it is SyncStateMachine.Action.Sweep
            },
        )
        Assert.assertTrue("and a resume does not enumerate: that is a fresh bind's path", resumed.actions.isEmpty())

        // A region left DEGRADED by an earlier session is re-opened from the same gap_start and asked
        // for again: the same key, so the absence cannot grow a second row.
        val lost =
            opening.state.copy(
                entries = listOf(opening.state.entries[0].copy(state = degraded, reason = GapLedger.REASON_SESSION_LOST)),
            )
        val again = run(lost, bind(anchored(session + 120_000L), resumed = true, at = session + 120_000L))
        Assert.assertEquals(open, again.state.entries[0].state)
        Assert.assertEquals(lost.entries[0].region, again.state.entries[0].region)
        Assert.assertEquals(
            listOf(
                SyncStateMachine.Action.OpenRegions(listOf(lost.entries[0].region)),
                SyncStateMachine.Action.Query(lost.entries[0].region),
            ),
            again.actions,
        )
    }

    @Test
    fun aSmResumeWithoutAnAnchorStaysLiveAndOwesNothing() {
        val transition = run(SyncStateMachine.initial(), bind(CursorMath.initial(), resumed = true))

        Assert.assertEquals(SyncStateMachine.Phase.Live, transition.state.phase)
        Assert.assertEquals(emptyList<SyncStateMachine.Action>(), transition.actions)
        Assert.assertTrue(transition.state.entries.isEmpty())
    }

    @Test
    fun csiInactiveDoesNotOpenAGapAndDoesNotStopTheQueue() {
        val live = run(SyncStateMachine.initial(), bind(CursorMath.initial()))
        val away = run(live.state, SyncStateMachine.Event.CsiInactive)

        Assert.assertEquals(SyncStateMachine.Phase.Away, away.state.phase)
        Assert.assertTrue(away.state.entries.isEmpty())
        Assert.assertEquals(
            "nothing on the wire, and nothing that stops the translation queue: that is :translation's",
            emptyList<SyncStateMachine.Action>(),
            away.actions,
        )
    }

    @Test
    fun csiActiveReopensADegradedGapWithoutAReconnect() {
        val owed = region(CursorMath.ACCOUNT_WIDE, 100L)
        val state =
            SyncStateMachine.initial()
                .copy(
                    sessionInstant = session,
                    csiInactive = true,
                    entries = listOf(entry(owed, degraded, GapLedger.REASON_SESSION_LOST)),
                )

        val transition = run(state, SyncStateMachine.Event.CsiActive)

        Assert.assertEquals(open, transition.state.entries[0].state)
        Assert.assertEquals(owed, transition.state.entries[0].region)
        Assert.assertEquals(
            listOf(SyncStateMachine.Action.OpenRegions(listOf(owed)), SyncStateMachine.Action.Query(owed)),
            transition.actions,
        )
        Assert.assertEquals(
            "without a reconnect: the session instant is untouched",
            session,
            transition.state.sessionInstant,
        )
        Assert.assertEquals(SyncStateMachine.Phase.CatchingUp(1), transition.state.phase)
    }

    @Test
    fun aFinArrivingWhileAwayIsStillProcessed() {
        val owed = region(CursorMath.ACCOUNT_WIDE, 100L)
        val state =
            SyncStateMachine.initial()
                .copy(
                    sessionInstant = session,
                    csiInactive = true,
                    anchor = anchored(100L),
                    entries = listOf(entry(owed, open)),
                )

        val transition =
            run(
                state,
                SyncStateMachine.Event.Fin(
                    fin(
                        CursorMath.ACCOUNT_WIDE,
                        100L,
                        totalCount = 4,
                        newestTime = session,
                        newestReference = "srv-new",
                    ),
                ),
            )

        Assert.assertEquals(complete, transition.state.entries[0].state)
        Assert.assertTrue(transition.actions.contains(SyncStateMachine.Action.Sweep(100L)))
        Assert.assertEquals(
            "CSI is still inactive: the account is away, not catching up",
            SyncStateMachine.Phase.Away,
            transition.state.phase,
        )
        Assert.assertEquals(
            "and a proven close moves the anchor to the newest stanza the page accounted for",
            "srv-new",
            transition.state.anchor.anchorStanzaId,
        )
    }

    @Test
    fun networkLossKeepsTheGapOwedAndTheAnchorUnmoved() {
        val owed = region("conv-a", 100L)
        val before =
            SyncStateMachine.initial()
                .copy(sessionInstant = session, anchor = anchored(100L), entries = listOf(entry(owed, open)))

        val transition = run(before, SyncStateMachine.Event.SessionEnded)

        Assert.assertEquals(
            "losing the socket changes nothing about what was accounted for",
            anchored(100L),
            transition.state.anchor,
        )
        Assert.assertEquals(SyncStateMachine.Phase.Offline(gapOwed = true), transition.state.phase)
        Assert.assertEquals(degraded, transition.state.entries[0].state)
        Assert.assertEquals(GapLedger.REASON_SESSION_LOST, transition.state.entries[0].reason)
        Assert.assertEquals(
            listOf(SyncStateMachine.Action.DegradeRegion(owed, GapLedger.REASON_SESSION_LOST)),
            transition.actions,
        )
    }

    @Test
    fun accountRemovalDeletesTheCursorAndItsRegions() {
        val state =
            SyncStateMachine.initial()
                .copy(
                    sessionInstant = session,
                    anchor = anchored(session - 1_000L),
                    entries = listOf(entry(region(CursorMath.ACCOUNT_WIDE, 100L), open)),
                )

        val transition = run(state, SyncStateMachine.Event.AccountRemoved)

        Assert.assertEquals(listOf(SyncStateMachine.Action.DeleteCursor), transition.actions)
        Assert.assertTrue("nothing survives to describe an account that is not there", transition.state.entries.isEmpty())
        Assert.assertEquals(CursorMath.initial(), transition.state.anchor)
    }

    /**
     * §5.2's missing proof, and the one that would have caught a reintroduction of the floor: several
     * `<fin>`s closing the regions of **one** opened gap produce exactly one sweep, and a duplicate
     * close produces none. Driven at two different instants, because the floor it replaces was a
     * clock and this is an event.
     */
    @Test
    fun ASweepDoesNotStartTwiceForOneGapClose() {
        val accountWide = region(CursorMath.ACCOUNT_WIDE, 100L)
        val conversation = region("conv-a", 100L)
        val state =
            SyncStateMachine.initial()
                .copy(
                    sessionInstant = session,
                    entries = listOf(entry(accountWide, open), entry(conversation, open)),
                )

        val first = run(state, SyncStateMachine.Event.Fin(fin(CursorMath.ACCOUNT_WIDE, 100L, totalCount = 3)))
        Assert.assertFalse(
            "one region closed is not the account's gap closed",
            first.actions.any { it is SyncStateMachine.Action.Sweep },
        )

        val second = run(first.state, SyncStateMachine.Event.Fin(fin("conv-a", 100L, totalCount = 2)))
        Assert.assertEquals(
            "the gap just closed: exactly one sweep, for the gap and not for its regions",
            1,
            second.actions.count { it is SyncStateMachine.Action.Sweep },
        )
        Assert.assertEquals(
            SyncStateMachine.Action.Sweep(100L),
            second.actions.first { it is SyncStateMachine.Action.Sweep },
        )

        val duplicate =
            run(second.state, SyncStateMachine.Event.Fin(fin("conv-a", 100L, totalCount = 2)))
        Assert.assertEquals(
            "and a duplicate close cannot start a second one",
            0,
            duplicate.actions.count { it is SyncStateMachine.Action.Sweep },
        )
    }
}
