package uk.xa0.tulkki.translation

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Java numeric, division, equality and null semantics the Kotlin port had to keep or decide,
 * pinned site by site.
 *
 * <p>This is the other half of {@link JavaStringSemanticsTest}: same reading of `docs/MIGRATION.md`
 * "Design: Kotlin" &sect;5.3, the categories that are not string predicates. Each test names one
 * production site and the one answer Java and Kotlin are allowed to disagree on:
 *
 * <ul>
 *   <li><b>Numeric parses.</b> Java's {@code Double.parseDouble} accepts hex floats, folds a
 *       malformed value into {@code NumberFormatException}, and parses {@code "1e400"} to
 *       {@code Infinity} without throwing. Kotlin's {@code toDouble()} is that same call, so
 *       {@link TokenPrices#of} keeps Java's {@code try}/{@code catch} <em>and</em> its
 *       {@code isFinite && > 0} guard: the guard, not the exception, is what rejects the overflow.
 *   <li><b>Integer division.</b> Java and Kotlin both truncate {@code /} toward zero and both make
 *       {@code %} take the dividend's sign, so the port had to reach for {@code Math.floorMod} where
 *       a pre-epoch instant must stay in {@code 0..1439}; {@code %}-as-written would answer a
 *       negative minute and put a peak call off-peak.
 *   <li><b>Collection and value equality.</b> Java's {@link TokenPrices.Model} and
 *       {@link TokenPrices.Selection} were plain classes with no {@code equals}/{@code hashCode}, so
 *       they compared by identity; Kotlin's {@code data class} compares by value. Nothing in the app
 *       branches on two of them, so the difference is not observable and the value reading is the
 *       decided answer - these tests are what makes that a decision rather than an inheritance.
 *   <li><b>Null versus throw.</b> A parameter Java tolerated as {@code null} stays nullable:
 *       {@code TokenPrices.model(null)}, an absent stored price and an absent holiday date all take
 *       Java's answer instead of a {@code NullPointerException} or an {@code !!}.
 * </ul>
 *
 * <p>NBSP is written as an escape in every literal so the test is readable in any editor.
 */
class JavaValueSemanticsTest {

    private val TOLERANCE = 1e-9

    /** A non-breaking space: the one character Java's trim keeps and Kotlin's strips. */
    private val NBSP = "\u00A0"

    private fun utc(iso: String): Long {
        return java.time.ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    }

    // -- numeric parses ---------------------------------------------------------------------------

    @Test
    fun anOverflowingPriceIsRejectedByTheFiniteTestAndNotByTheParse() {
        // Double.parseDouble("1e400") answers Infinity and throws nothing, so Java's catch did not
        // see it: the isFinite guard is what turns it into the fallback. A port that had dropped the
        // guard would price every call at infinity.
        val prices =
                TokenPrices.of("1e400", null, null, null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, prices.peakCacheHit, TOLERANCE)
    }

    @Test
    fun aMalformedPriceFallsBackRatherThanThrowing() {
        // Java folded malformed and overflow into one NumberFormatException at one catch. Kotlin's
        // toDouble() throws the same exception, so the same catch is the call-for-call equivalent;
        // toDoubleOrNull() would also work here, which is why the test pins the answer and not the
        // spelling.
        val prices = TokenPrices.of("abc", "2.00", "8.00", null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, prices.peakCacheHit, TOLERANCE)
    }

    @Test
    fun aBlankOrNonPositivePriceIsTheDefaultAndNeverZero() {
        // Zero is the one wrong answer on a screen that reports spend, so a blank field, "0" and a
        // negative all take the embedded default - Java's documented fallback, kept.
        val blank = TokenPrices.of("", " ", "-1", null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, blank.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_MISS, blank.peakCacheMiss, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, blank.peakOutput, TOLERANCE)
        val zero = TokenPrices.of("0", "0.0", "0", null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, zero.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, zero.peakOutput, TOLERANCE)
    }

    @Test
    fun aPriceIsTrimmedAsJavaTrimmedBeforeItIsParsed() {
        // Java trimmed with String.trim() and then parsed. Pad with ordinary spaces and it parses.
        val padded =
                TokenPrices.of(" 2.5 ", " 3.5 ", " 4.5 ", null as TokenPrices.Model?)
        assertEquals(2.5, padded.peakCacheHit, TOLERANCE)
        assertEquals(3.5, padded.peakCacheMiss, TOLERANCE)
        assertEquals(4.5, padded.peakOutput, TOLERANCE)
        // Pad with an NBSP instead: Java's trim keeps it, the parse throws, and the fallback holds.
        // Kotlin's trim() would strip it and accept a price the Java refused - the two halves of this
        // test are one site.
        val nbsp = TokenPrices.of("2.5" + NBSP, null, null, null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, nbsp.peakCacheHit, TOLERANCE)
    }

    // -- integer division -------------------------------------------------------------------------

    @Test
    fun aPreEpochInstantStaysInItsOwnUtcWindow() {
        // 1969-12-31T02:00Z is 120 minutes past midnight UTC, inside the 01:00-04:00 peak window.
        // floorMod answers 120 because the epoch is negative; a truncated "%" would answer -1320 and
        // call a peak call off-peak. Both languages truncate, so the floorMod is a decision.
        val preEpoch = utc("1969-12-31T02:00:00Z")
        assertTrue(
                "a pre-epoch instant in the morning window is peak",
                PeakTariff.isPeak(preEpoch, DayOfWeek.WEDNESDAY))
        // And the Beijing date is the same calendar day, so the holiday table sees a plain weekday.
        assertEquals(19691231, PeakTariff.beijingDate(preEpoch))
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(PeakTariff.beijingDate(preEpoch)))
    }

    @Test
    fun theWindowEdgesDivideTheMinuteAsJavaDid() {
        // The window's own label is start/60 and start%60: integer division and remainder on
        // positives, identical in both languages, pinned so a move to a floor or a round is visible.
        assertEquals("01:00-04:00 and 06:00-10:00 UTC", PeakTariff.Schedule.SHIPPED.toString())
    }

    // -- value and collection equality ------------------------------------------------------------

    @Test
    fun twoEqualValuedRowsAreEqualByValue() {
        // Java's Model had no equals: these two were distinct to it. Kotlin's data class says equal,
        // and that is the decision - nothing branches on two rows, so the value reading is free and
        // the one an immutable price row wants.
        val left =
                TokenPrices.Model("deepseek-flash", 0.04, 2.00, 8.00, true)
        val right =
                TokenPrices.Model("deepseek-flash", 0.04, 2.00, 8.00, true)
        assertNotSame(left, right)
        assertEquals(left, right)
        assertEquals("equal values must hash alike", left.hashCode(), right.hashCode())
        // A row that differs in one field is a different row, and the derived flag counts.
        assertNotEquals(left, TokenPrices.Model("deepseek-flash", 0.04, 2.00, 8.00, false))
    }

    @Test
    fun twoEqualValuedSelectionsAreEqualByValue() {
        // The same decision on Selection. Java's identity equality would fail this assertion; the
        // port keeps Kotlin's value equality deliberately.
        assertEquals(
                TokenPrices.select("deepseek-flash", null),
                TokenPrices.select("deepseek-flash", null))
        // The three-valued `listed` is part of the value: an unreadable list is not "not offered".
        assertNotEquals(
                TokenPrices.select("deepseek-flash", null),
                TokenPrices.select("deepseek-flash", listOf()))
    }

    // -- null versus throw ------------------------------------------------------------------------

    @Test
    fun aMissingModelIdAnswersNullRatherThanThrowing() {
        // model() took a nullable id in Java and still does: null is "the table does not know it",
        // which inForceFor turns into the expensive fallback.
        assertNull(TokenPrices.model(null))
        assertNull(TokenPrices.model("  "))
        assertNull(TokenPrices.model("deepseek-nothing"))
        // A known id is trimmed with Java's trim before the lookup, so ordinary padding is fine.
        assertEquals(TokenPrices.FLASH, TokenPrices.model("  deepseek-flash  "))
    }

    @Test
    fun anAbsentValueTakesJavaDefaultInsteadOfACrash() {
        // A null row prices from the shipped row rather than dereferencing; Java's of(...) said the
        // same with a null check, and Kotlin keeps it.
        val fromNullRow = TokenPrices.of(null, null, null, null as TokenPrices.Model?)
        assertEquals(TokenPrices.DEFAULT_PEAK_CACHE_HIT, fromNullRow.peakCacheHit, TOLERANCE)
        assertEquals(TokenPrices.DEFAULT_PEAK_OUTPUT, fromNullRow.peakOutput, TOLERANCE)
        // A null date is "no holiday", which is Java's answer and the direction that never
        // under-reports the spend.
        assertFalse(PeakTariff.ChineseHolidays.isHoliday(null as LocalDate?))
        // And plus(null) is a no-op rather than a throw, as Java's null test was.
        assertEquals(0, TokenUsage.zero().plus(null).totalTokens())
    }

    @Test
    fun aCountBelowZeroIsClampedAndNotWrapped() {
        // Java's Math.max(0, x) and Kotlin's coerceAtLeast(0) agree for every int, including
        // Integer.MIN_VALUE, which the subtraction path could otherwise turn back into a negative.
        val clamped =
                TokenUsage(
                        Integer.MIN_VALUE, -1, -2, Integer.MIN_VALUE, -3, -4)
        assertEquals(0, clamped.peakCacheHit)
        assertEquals(0, clamped.peakCacheMiss)
        assertEquals(0, clamped.peakOutput)
        assertEquals(0, clamped.offPeakCacheHit)
        assertEquals(0, clamped.offPeakCacheMiss)
        assertEquals(0, clamped.offPeakOutput)
        assertTrue(clamped.isEmpty())
    }
}
