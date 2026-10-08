package uk.xa0.tulkki.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Test

/**
 * The balance the screen shows: the yuan figure, and nothing else.
 *
 * <p>Every case here is fed as the raw answer DeepSeek sends, through the real parser, because the
 * reported bug was a real answer - two currencies, the dollar entry occasionally first, occasionally
 * zero - and not a mistake in choosing between two already-parsed objects.
 */
class BalanceSnapshotTest {

    private val WHEN = 1_764_000_000_000L

    /** The owner's account, exactly as the live endpoint answered it. */
    private val OWNERS_ANSWER =
            "{\"is_available\":true,\"balance_infos\":[" +
                    "{\"currency\":\"USD\",\"total_balance\":\"-0.00\",\"granted_balance\":\"0.00\"," +
                    "\"topped_up_balance\":\"-0.00\"}," +
                    "{\"currency\":\"CNY\",\"total_balance\":\"15.51\",\"granted_balance\":\"0.00\"," +
                    "\"topped_up_balance\":\"15.51\"}]}"

    /** An answer with the given currency/amount pairs, in the order given. */
    private fun answer(available: Boolean, vararg currencyAndAmount: String): String {
        val infos = JsonArray()
        var i = 0
        while (i + 1 < currencyAndAmount.size) {
            val info = JsonObject()
            info.addProperty("currency", currencyAndAmount[i])
            info.addProperty("total_balance", currencyAndAmount[i + 1])
            info.addProperty("granted_balance", "0.00")
            info.addProperty("topped_up_balance", currencyAndAmount[i + 1])
            infos.add(info)
            i += 2
        }
        val root = JsonObject()
        root.addProperty("is_available", available)
        root.add("balance_infos", infos)
        return root.toString()
    }

    private fun snapshotOf(json: String): BalanceSnapshot {
        return BalanceSnapshot.of(DeepSeekClient.parseBalance(json), WHEN)
    }

    @Test
    fun theOwnersAnswerShowsTheYuanFigureNotTheZeroDollarOne() {
        val snapshot = snapshotOf(OWNERS_ANSWER)

        assertTrue(snapshot.isAvailable)
        assertEquals("CNY", snapshot.currency)
        assertEquals("15.51", snapshot.totalBalance)
        assertEquals("¥15.51", snapshot.displayAmount())
        assertEquals(WHEN, snapshot.fetchedAtMillis)
    }

    @Test
    fun theYuanEntryIsFoundByItsCodeAndNotByItsPosition() {
        // The API returns the two entries in a varying order; the figure must not vary with it.
        val dollarsFirst =
                snapshotOf(answer(true, "USD", "-0.00", "CNY", "15.51"))
        val yuanFirst = snapshotOf(answer(true, "CNY", "15.51", "USD", "-0.00"))

        assertEquals("¥15.51", dollarsFirst.displayAmount())
        assertEquals("¥15.51", yuanFirst.displayAmount())
    }

    @Test
    fun theYuanFigureIsShownEvenWhenTheDollarEntryHoldsMoney() {
        // Never both figures, and never the dollar one: the reading carries exactly one amount.
        val snapshot = snapshotOf(answer(true, "USD", "1.50", "CNY", "15.51"))

        assertEquals("CNY", snapshot.currency)
        assertEquals("¥15.51", snapshot.displayAmount())
    }

    @Test
    fun aSingleYuanEntryIsSimplyShown() {
        val snapshot = snapshotOf(answer(true, "CNY", "15.51"))

        assertEquals("CNY", snapshot.currency)
        assertEquals("¥15.51", snapshot.displayAmount())
    }

    @Test
    fun aDollarBilledAccountShowsNoFigureAtAll() {
        // No yuan entry: the dollar balance is never substituted for it.
        val snapshot = snapshotOf(answer(true, "USD", "1.50"))

        assertTrue(snapshot.isAvailable)
        assertNull(snapshot.currency)
        assertNull(snapshot.totalBalance)
        assertFalse(snapshot.hasAmount())
        assertNull(snapshot.displayAmount())
    }

    @Test
    fun anotherCurrencyIsNeverSubstitutedForTheYuanOne() {
        val snapshot = snapshotOf(answer(true, "EUR", "5.00"))

        assertNull(snapshot.displayAmount())
    }

    @Test
    fun aZeroYuanEntryIsShownAsZeroAndNeverFallsThroughToDollars() {
        // The mirror of the owner's answer, and the property the fix must keep: a zero or negative
        // yuan entry is reported as the yuan figure it is - zero - and never replaced by the dollar
        // entry behind it, whatever that holds.
        val snapshot = snapshotOf(answer(true, "CNY", "-0.00", "USD", "12.00"))

        assertTrue(snapshot.isAvailable)
        assertEquals("CNY", snapshot.currency)
        assertEquals("¥-0.00", snapshot.displayAmount())
    }

    @Test
    fun aZeroYuanBalanceIsShownAsZero() {
        // Out of credit is a real, actionable answer; it is not "we could not find out".
        val snapshot = snapshotOf(answer(true, "CNY", "0.00"))

        assertEquals("CNY", snapshot.currency)
        assertEquals("¥0.00", snapshot.displayAmount())
    }

    @Test
    fun anAccountThatCannotPayKeepsBothTheReadingAndTheReason() {
        val snapshot = snapshotOf(answer(false, "USD", "-0.00", "CNY", "15.51"))

        assertFalse(snapshot.isAvailable)
        assertEquals("¥15.51", snapshot.displayAmount())
    }

    @Test
    fun anAnswerWithNoEntriesShowsNoFigure() {
        // An empty array is an answer, not an error: it is only "nothing to show" that must not be
        // confused with "we could not find out".
        val snapshot = snapshotOf(answer(true))

        assertTrue(snapshot.isAvailable)
        assertNull(snapshot.currency)
        assertNull(snapshot.totalBalance)
        assertFalse(snapshot.hasAmount())
        assertNull(snapshot.displayAmount())
    }

    @Test
    fun aMalformedAnswerIsAnErrorNotAZero() {
        for (payload in arrayOf(
                    "",
                    "<html>we are down for maintenance</html>",
                    "{}",
                    "{\"is_available\":true}",
                    "{\"balance_infos\":[]}",
                    "{\"is_available\":\"yes\",\"balance_infos\":[]}")) {
            try {
                DeepSeekClient.parseBalance(payload)
                fail("expected a TranslationException for: " + payload)
            } catch (e: DeepSeekClient.TranslationException) {
                assertFalse("parsing is not worth retrying: " + payload, e.retryable)
            }
        }
    }

    @Test
    fun theReadingSurvivesTheCacheAsTheSameFigure() {
        val snapshot = snapshotOf(OWNERS_ANSWER)

        val read = BalanceSnapshot.fromJson(snapshot.toJson())!!

        assertTrue(read.isAvailable)
        assertEquals("CNY", read.currency)
        assertEquals("15.51", read.totalBalance)
        assertEquals("¥15.51", read.displayAmount())
        assertEquals(WHEN, read.fetchedAtMillis)
        assertTrue(read.hasAmount())
    }

    @Test
    fun aStoredDollarReadingIsNotAReadingWeCanShow() {
        // A reading cached by an older build must not put a dollar figure on the screen.
        assertNull(
                BalanceSnapshot.fromJson(
                        "{\"is_available\":true,\"currency\":\"USD\",\"total_balance\":\"1.50\"," +
                                "\"fetched_at\":0}"))
    }

    @Test
    fun anUnreadableStoredReadingIsNotAReading() {
        assertNull(BalanceSnapshot.fromJson(null))
        assertNull(BalanceSnapshot.fromJson(""))
        assertNull(BalanceSnapshot.fromJson("not json at all"))
        assertNull(BalanceSnapshot.fromJson("{\"currency\":\"CNY\"}"))
    }
}
