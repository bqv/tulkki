package uk.xa0.tulkki.ui.ledger

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.translation.UsageByOrigin
import uk.xa0.tulkki.data.translation.UsageCounts
import uk.xa0.tulkki.data.translation.UsageOrigin
import uk.xa0.tulkki.data.translation.UsageOriginKind
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TokenUsage

/**
 * The day drill-down's cells: the pure half of the owner's `ui-3`, over `:data`'s grouped read
 * (`TranslationUsageStore.byOrigin`) - "**Screen state is a pure reducer**, not a Composable"
 * (docs/MIGRATION.md "Design: the Compose UI" §7.4).
 *
 * <p>The three things these hold are the three the read model leaves to the screen: every one of the
 * five kinds appears under its own heading (the several-rooms batch is not the not-tied bucket); a
 * bucket the read could not name is labelled by its kind and never by the stored key; and the money is
 * `TokenPrices.yuan` over the same counts, with the day's own row as the total rather than a second sum.
 */
class LedgerBreakdownTest {

    @Test
    fun everyKindIsEnumeratedAndEachBucketLandsUnderItsOwn() {
        val read = read(origins = listOf(origin("c", UsageOriginKind.ONE_TO_ONE, "c@example.org"), origin("a", UsageOriginKind.NOT_TIED_TO_A_ROOM), origin("b", UsageOriginKind.MULTI_ROOM, originKey = UsageOrigin.MULTI_ROOM_ORIGIN), origin("d", UsageOriginKind.DELETED_CONVERSATION), origin("e", UsageOriginKind.GROUP_CHAT, "e@rooms.example")))
        val breakdown = LedgerBreakdown.of(read, TokenPrices.defaults())
        Assert.assertTrue("a good read draws its parts", breakdown.addsUp)
        Assert.assertEquals("every bucket appears", 5, breakdown.lines.size)
        Assert.assertEquals(
            "the ordering is the enum's own case list, so a sixth kind needs no edit here",
            UsageOriginKind.entries.toList(),
            breakdown.lines.map { it.kind },
        )
    }

    /**
     * The distinction the whole grouping exists for: a catch-up batch that covered up to eight rooms
     * is room traffic, and the store's reserved marker is classified before the join, so the screen
     * must not fold it into "not tied to a room".
     */
    @Test
    fun theSeveralRoomsBatchIsNotTheNotTiedBucket() {
        val read = read(origins = listOf(origin("a", UsageOriginKind.NOT_TIED_TO_A_ROOM), origin("b", UsageOriginKind.MULTI_ROOM, originKey = UsageOrigin.MULTI_ROOM_ORIGIN)))
        val kinds = LedgerBreakdown.of(read, TokenPrices.defaults()).lines.map { it.kind }
        Assert.assertTrue(UsageOriginKind.MULTI_ROOM in kinds)
        Assert.assertTrue(UsageOriginKind.NOT_TIED_TO_A_ROOM in kinds)
        Assert.assertNotEquals(
            "the two are different sentences on screen",
            originKindLabel(UsageOriginKind.NOT_TIED_TO_A_ROOM),
            originKindLabel(UsageOriginKind.MULTI_ROOM),
        )
        Assert.assertEquals(
            "and every kind has a label of its own",
            UsageOriginKind.entries.size,
            UsageOriginKind.entries.map { originKindLabel(it) }.toSet().size,
        )
    }

    /**
     * The stored key is a conversation's uuid, and a deleted conversation's no longer resolves to
     * anything - so the line says what happened and never prints the key. The type is the guard: there
     * is no field for it to be printed from.
     */
    @Test
    fun aDeletedConversationIsLabelledAndNeverShownAsItsKey() {
        val uuid = "9f3c1a2e-0000-4444-8888-abcdefabcdef"
        val read = read(origins = listOf(origin(uuid, UsageOriginKind.DELETED_CONVERSATION)))
        val line = LedgerBreakdown.of(read, TokenPrices.defaults()).lines.single()
        Assert.assertEquals(UsageOriginKind.DELETED_CONVERSATION, line.kind)
        Assert.assertNull("there was nothing to resolve, so there is no address", line.address)
        for (field in OriginLine::class.java.declaredFields) {
            if (field.type != String::class.java) {
                continue
            }
            field.isAccessible = true
            Assert.assertNotEquals("the bucket's key must not be a field: ${field.name}", uuid, field.get(line))
        }
    }

    @Test
    fun anAddressIsCarriedOnlyWhenTheReadResolvedOne() {
        val read = read(origins = listOf(origin("a", UsageOriginKind.GROUP_CHAT, "study@rooms.example"), origin("b", UsageOriginKind.ONE_TO_ONE)))
        val lines = LedgerBreakdown.of(read, TokenPrices.defaults()).lines.associateBy { it.kind }
        Assert.assertEquals("study@rooms.example", lines.getValue(UsageOriginKind.GROUP_CHAT).address)
        Assert.assertNull("an unresolved name is not invented", lines.getValue(UsageOriginKind.ONE_TO_ONE).address)
    }

    @Test
    fun theMoneyIsTheTreesOwnArithmeticAtThePricesInForce() {
        val prices = TokenPrices.defaults()
        val read = read(origins = listOf(origin("a", UsageOriginKind.GROUP_CHAT, "a@example.org", count(1_000, 2_000, 3_000))))
        val breakdown = LedgerBreakdown.of(read, prices)
        val line = breakdown.lines.single()
        Assert.assertEquals(
            "a bucket is priced by TokenPrices.yuan over the same six counts",
            prices.yuan(TokenUsage(1_000, 2_000, 3_000, 0, 0, 0)),
            line.yuan,
            0.0,
        )
        Assert.assertEquals(
            "the total is the day's own row, not a second sum of the buckets",
            prices.yuan(TokenUsage(1_000, 2_000, 3_000, 0, 0, 0)),
            breakdown.totalYuan,
            0.0,
        )
    }

    /**
     * A read whose parts do not make the whole is a defect, and the honest answer is nothing to render:
     * the flag says so and there are no lines for a screen to draw by forgetting to ask.
     */
    @Test
    fun aBreakdownThatDoesNotAddUpProducesNothingToRender() {
        val read = UsageByOrigin("2026-09-30", listOf(origin("a", UsageOriginKind.NOT_TIED_TO_A_ROOM, null, count(1, 2, 3))), count(9, 9, 9))
        Assert.assertFalse("the fixture is the mismatch it claims to be", read.addsUp())
        val breakdown = LedgerBreakdown.of(read, TokenPrices.defaults())
        Assert.assertFalse(breakdown.addsUp)
        Assert.assertEquals("a breakdown that does not add up is worse than none", emptyList<OriginLine>(), breakdown.lines)
    }

    private fun read(origins: List<UsageOrigin>): UsageByOrigin =
        UsageByOrigin(
            day = "2026-09-30",
            origins = origins,
            total = origins.fold(UsageCounts.zero()) { running, origin -> running + origin.usage },
        )

    private fun origin(
        key: String,
        kind: UsageOriginKind,
        address: String? = null,
        usage: UsageCounts = count(1_000, 200, 50),
        originKey: String = key,
    ): UsageOrigin = UsageOrigin(originKey, kind, address, usage)

    private fun count(hit: Int, miss: Int, output: Int): UsageCounts = UsageCounts(hit, miss, output, 0, 0, 0)
}
