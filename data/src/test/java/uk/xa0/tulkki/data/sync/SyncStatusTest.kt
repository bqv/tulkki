package uk.xa0.tulkki.data.sync

import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import org.junit.Assert
import org.junit.Test

/**
 * S5-6's sync read model: `docs/MIGRATION.md` "Design: synchronisation" §3.3's `SyncStatus` and its
 * `Flow`, over the state machine S5-8 committed.
 *
 * <p>§3.3's sketch names `AccountId` and `Instant`, neither of which exists in this tree; the
 * substitution - the account uuid `String` and epoch milliseconds in a `Long` - is stated in
 * [SyncStatus]'s own comment and pinned here, so a later reader finds the decision rather than the
 * drift. The phases are asserted through [SyncStateMachine]'s own derivation rather than by hand, so
 * this test and the reducer cannot disagree about what `Live` means.
 */
class SyncStatusTest {

    private val session = 1_700_000_000_000L

    private fun entry(state: Int, gapStart: Long, reason: String? = null) =
        GapLedger.Entry(
            CursorMath.Region(CursorMath.ACCOUNT_WIDE, gapStart, CursorMath.REGION_FIRST),
            state.toLong(),
            reason,
        )

    private fun restored(entries: List<GapLedger.Entry>, sessionInstant: Long = session) =
        SyncStateMachine.restored(
            anchor = CursorMath.Cursor(null, 0L, SyncQueries.ANCHOR_SOURCE_INITIAL.toLong(), 0L),
            entries = entries,
            sessionInstant = sessionInstant,
        )

    /** The six phases translate one for one, through the reducer's own derivation. */
    @Test
    fun theSixPhasesTranslateOneForOne() {
        Assert.assertEquals(
            "no session, nothing owed",
            SyncPhase.Offline(gapOwed = false),
            syncStatusOf("a-1", restored(emptyList(), sessionInstant = 0L), null).phase,
        )
        Assert.assertEquals(
            "no session, a region still owed",
            SyncPhase.Offline(gapOwed = true),
            syncStatusOf("a-1", restored(listOf(entry(SyncQueries.GAP_STATE_OPEN, 10L)), 0L), null).phase,
        )
        Assert.assertEquals(
            "a connection in flight",
            SyncPhase.Connecting,
            syncStatusOf(
                    "a-1",
                    SyncStateMachine.on(
                        restored(listOf(entry(SyncQueries.GAP_STATE_OPEN, 10L))),
                        SyncStateMachine.Event.Connect,
                        Long.MAX_VALUE,
                        750,
                    )
                        .state,
                    null,
                )
                .phase,
        )
        Assert.assertEquals(
            "a session with one region open",
            SyncPhase.CatchingUp(open = 1),
            syncStatusOf("a-1", restored(listOf(entry(SyncQueries.GAP_STATE_OPEN, 10L))), null).phase,
        )
        Assert.assertEquals(
            "a session and nothing owed",
            SyncPhase.Live,
            syncStatusOf("a-1", restored(listOf(entry(SyncQueries.GAP_STATE_COMPLETE, 10L))), null).phase,
        )
        Assert.assertEquals(
            "a region that cannot be proven carries its reason",
            SyncPhase.Degraded(GapLedger.REASON_TIMEOUT),
            syncStatusOf(
                    "a-1",
                    restored(
                        listOf(
                            entry(SyncQueries.GAP_STATE_DEGRADED, 10L, GapLedger.REASON_TIMEOUT)
                        )
                    ),
                    null,
                )
                .phase,
        )
        Assert.assertEquals(
            "CSI inactive is Away, and only when nothing else is owed",
            SyncPhase.Away,
            syncStatusOf(
                    "a-1",
                    SyncStateMachine.on(
                        restored(listOf(entry(SyncQueries.GAP_STATE_COMPLETE, 10L))),
                        SyncStateMachine.Event.CsiInactive,
                        Long.MAX_VALUE,
                        750,
                    )
                        .state,
                    null,
                )
                .phase,
        )
    }

    /** The gap is the owed window, and it is null exactly when nothing is owed. */
    @Test
    fun theGapIsTheOldestOwedRegionAndNullWhenNothingIsOwed() {
        Assert.assertNull(
            "a complete ledger owes no gap",
            syncStatusOf("a-1", restored(listOf(entry(SyncQueries.GAP_STATE_COMPLETE, 10L))), null).gap,
        )
        Assert.assertEquals(
            "one open region spans its own start to the session instant",
            Gap(start = 10L, end = session),
            syncStatusOf("a-1", restored(listOf(entry(SyncQueries.GAP_STATE_OPEN, 10L))), null).gap,
        )
        Assert.assertEquals(
            "two owed regions start at the older one",
            Gap(start = 10L, end = session),
            syncStatusOf(
                    "a-1",
                    restored(
                        listOf(
                            entry(SyncQueries.GAP_STATE_OPEN, 99L),
                            entry(SyncQueries.GAP_STATE_DEGRADED, 10L, GapLedger.REASON_KILLED),
                        )
                    ),
                    null,
                )
                .gap,
        )
    }

    /** The sweep floor is the oldest non-zero `swept_through`, and null when none was swept. */
    @Test
    fun theSweepFloorIsTheOldestNonZeroOrNull() {
        Assert.assertNull("nothing swept at all", sweptFloorOf(listOf(0L, 0L)))
        Assert.assertNull("no conversation rows", sweptFloorOf(emptyList()))
        Assert.assertEquals("the oldest non-zero wins", 30L, sweptFloorOf(listOf(0L, 40L, 30L)))
        Assert.assertEquals(
            "and it is carried into the status unchanged",
            30L,
            syncStatusOf("a-1", restored(emptyList()), sweptFloorOf(listOf(0L, 40L, 30L))).sweptThrough,
        )
    }

    /**
     * The boundary: the read model is `public`, its account and instants are the tree's own types,
     * and the engine hands it out as a `Flow` (`Compose UI` §2.3's "no entity or service reference";
     * `:data` §2.7's "`Flow`s, never `LiveData`").
     */
    @Test
    fun theStatusIsPublicAndTheEnginePublishesItAsAFlow() {
        Assert.assertTrue(
            "the sync read model must cross the module boundary",
            Modifier.isPublic(SyncStatus::class.java.modifiers),
        )
        Assert.assertTrue(
            "and so must the phase",
            Modifier.isPublic(SyncPhase::class.java.modifiers),
        )
        Assert.assertEquals(
            "the engine's read model is a Flow",
            Flow::class.java,
            SyncEngine::class.java.getMethod("getSync").returnType,
        )
        Assert.assertEquals(
            "the account is the tree's own type: the uuid the schema stores",
            String::class.java,
            SyncStatus::class.java.getMethod("getAccount").returnType,
        )
        for (field in listOf("getGap", "getSweptThrough")) {
            Assert.assertFalse(
                "no illustrative wrapper may reappear in the read model: " + field,
                SyncStatus::class.java.getMethod(field).returnType.name.contains("Instant"),
            )
        }
    }
}
