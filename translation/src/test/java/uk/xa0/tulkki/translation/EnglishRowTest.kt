package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * What the English row is, where it sits, and what it costs.
 *
 * <p>The two tests that carry the rules are
 * {@code theMasterSwitchOffIsTheOnlyStateThatBuysNothing} - nothing English exists unless the setting
 * says so, so nothing can be spent by a screen that never asked - and
 * {@code anOriginalThatWasAlreadyEnglishNeedsNoPurchase}: the owner's exception, where the English
 * section is the message's own original and there is nothing to buy.
 *
 * <p>The interpreter's off state is the third rule and it is stronger than either setting: with the
 * app or study language English or {@code none}, {@code of} and {@code ofSent} answer {@code NONE}
 * before they read a setting, so there is no row at all - and, because that guard is above the
 * original-was-English branch, the one approved exception is hidden with it: off, no original becomes
 * readable anywhere. The cells below walk the same shapes the on-state cells do, each beside its
 * on-state control, so an absent row cannot be the input's answer.
 */
class EnglishRowTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages.
     * {@code needsBuying} hands it to {@link TranslationDecision#classify}, which requires it.
     */
    private val ON = Interpreter.of("fi", "de")

    /** The off pair: app Finnish with study Finnish, the same language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    private val APP = "fi"
    private val ROOM = "de"

    /** A Finnish sentence the detector is confident about, as the app-language tests use. */
    private val FINNISH =
            "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    /** The same for English, which is what this row is about. */
    private val ENGLISH_TEXT =
            "Hey, are we still meeting tomorrow for coffee? I have news."

    @Test
    fun theMasterSwitchOffIsTheOnlyStateThatBuysNothing() {
        // Every combination of what the message and the caches look like still yields no row at all
        // when the setting is off: there is nothing to draw, so nothing can be bought by a listener
        // that is not installed.
        for (originalIsEnglish in booleanArrayOf(true, false)) {
            for (bought in booleanArrayOf(true, false)) {
                for (revealed in booleanArrayOf(true, false)) {
                    val row =
                            EnglishRow.of(
                                    false,
                                    true,
                                    APP,
                                    ROOM,
                                    if (originalIsEnglish) "en" else null,
                                    bought,
                                    revealed,
                                    ON)
                    Assert.assertEquals(EnglishRow.Kind.NONE, row.kind())
                    Assert.assertEquals(EnglishRow.Placement.NONE, row.placement())
                    Assert.assertFalse(row.isShown)
                    Assert.assertFalse(
                            "no row means nothing English is in the bubble at all",
                            row.hasEnglish())
                }
            }
        }
    }

    @Test
    fun aBubbleWithNothingToDivideHasNoRow() {
        Assert.assertEquals(
                EnglishRow.Kind.NONE,
                EnglishRow.of(true, false, APP, ROOM, null, false, false, ON).kind())
    }

    @Test
    fun anAppLanguageThatIsAlreadyEnglishHasNoRowToDuplicateTheTopHalf() {
        // The settings screen greys the master switch for this same reason; the decision holds the
        // line even when the switch was left on and the app language was changed under it.
        for (appLanguage in arrayOf("en", "EN", " en ")) {
            val row =
                    EnglishRow.of(true, true, appLanguage, ROOM, null, true, false, ON)
            Assert.assertEquals(
                    "app language " + appLanguage, EnglishRow.Kind.NONE, row.kind())
        }
    }

    @Test
    fun theConversationsLanguageDecidesWhereTheRowSits() {
        Assert.assertEquals(
                EnglishRow.Placement.REPLACES_ORIGINAL,
                EnglishRow.of(true, true, APP, "en", null, false, false, ON).placement())
        Assert.assertEquals(
                "a room that speaks English already has the English as its own side",
                EnglishRow.Placement.THIRD_ROW,
                EnglishRow.of(true, true, APP, ROOM, null, false, false, ON).placement())
        Assert.assertEquals(
                "an unknown conversation language is not English",
                EnglishRow.Placement.THIRD_ROW,
                EnglishRow.of(true, true, APP, null, null, false, false, ON).placement())
        Assert.assertTrue(
                EnglishRow.of(true, true, APP, "en", null, false, false, ON).replacesOriginalStrip())
        Assert.assertFalse(
                EnglishRow.of(true, true, APP, ROOM, null, false, false, ON).replacesOriginalStrip())
    }

    @Test
    fun anOriginalThatWasAlreadyEnglishNeedsNoPurchase() {
        val row = EnglishRow.of(true, true, APP, ROOM, "en", false, false, ON)
        Assert.assertEquals(
                "the English is in hand, because the original is the English",
                EnglishRow.Kind.BLURRED,
                row.kind())
        Assert.assertTrue(row.englishIsTheOriginal())
        Assert.assertTrue(row.hasEnglish())
    }

    @Test
    fun anOriginalThatWasAlreadyEnglishIsStillNotReadableUntilTapped() {
        // Nothing unblurs on its own, and "there was nothing to buy" is not a licence to skip the
        // blur: the row is the same row, with a free reveal behind it.
        Assert.assertEquals(
                EnglishRow.Kind.BLURRED,
                EnglishRow.of(true, true, APP, ROOM, "en", false, false, ON).kind())
        Assert.assertEquals(
                EnglishRow.Kind.READABLE,
                EnglishRow.of(true, true, APP, ROOM, "en", false, true, ON).kind())
    }

    @Test
    fun withoutTheEnglishInHandTheRowIsABareBar() {
        val row = EnglishRow.of(true, true, APP, ROOM, "de", false, false, ON)
        Assert.assertEquals(EnglishRow.Kind.BARE_BAR, row.kind())
        Assert.assertTrue(row.isBareBar)
        Assert.assertFalse(
                "there is nothing to blur before the English has been bought", row.hasEnglish())
        Assert.assertFalse(row.englishIsTheOriginal())
    }

    @Test
    fun boughtEnglishIsBlurredAndNotBoughtAgain() {
        val row = EnglishRow.of(true, true, APP, ROOM, "de", true, false, ON)
        Assert.assertEquals(EnglishRow.Kind.BLURRED, row.kind())
        Assert.assertTrue(row.isBlurred)
        Assert.assertTrue(row.hasEnglish())
        Assert.assertFalse(
                "a bought row is not the original, so nothing may read the body out of it",
                row.englishIsTheOriginal())
    }

    @Test
    fun theOwnersTapRevealsAndChangesNothingElse() {
        val blurred = EnglishRow.of(true, true, APP, ROOM, "de", true, false, ON)
        val revealed = EnglishRow.of(true, true, APP, ROOM, "de", true, true, ON)
        Assert.assertEquals(EnglishRow.Kind.READABLE, revealed.kind())
        Assert.assertTrue(revealed.isReadable)
        Assert.assertEquals(
                "the reveal moves nothing: same row, same place",
                blurred.placement(),
                revealed.placement())
    }

    @Test
    fun revealingWithNothingInHandIsNotAReadableRow() {
        // A tap that bought nothing - a refusal, or a race - must not leave an empty text in place of
        // the bar; the row stays a bar and the tap is still the way to try again.
        Assert.assertEquals(
                EnglishRow.Kind.BARE_BAR,
                EnglishRow.of(true, true, APP, ROOM, "de", false, true, ON).kind())
    }

    @Test
    fun theEagerSettingIsWhatBuysOnArrival() {
        Assert.assertTrue(EnglishRow.buysOnArrival(true, false, APP, ON))
        Assert.assertFalse(
                "the default is the lazy one: the tap is what buys",
                EnglishRow.buysOnArrival(true, true, APP, ON))
        Assert.assertFalse(
                "the master switch off buys nothing, arrival included",
                EnglishRow.buysOnArrival(false, false, APP, ON))
        Assert.assertFalse(
                "an app language of English has no row, so there is nothing to buy for it",
                EnglishRow.buysOnArrival(true, false, "en", ON))
    }

    @Test
    fun englishIsTheHardcodedCodeAndNothingElse() {
        Assert.assertTrue(EnglishRow.isEnglish("en"))
        Assert.assertTrue(EnglishRow.isEnglish("EN"))
        Assert.assertTrue(EnglishRow.isEnglish(" en "))
        Assert.assertFalse(EnglishRow.isEnglish("fi"))
        Assert.assertFalse(EnglishRow.isEnglish("eng"))
        Assert.assertFalse(EnglishRow.isEnglish(""))
        Assert.assertFalse(EnglishRow.isEnglish(null))
    }

    @Test
    fun aBodyThatIsAlreadyEnglishIsNothingToBuy() {
        Assert.assertFalse(
                EnglishRow.needsBuying(ENGLISH_TEXT, null, APP, ON))
        Assert.assertTrue(EnglishRow.needsBuying(FINNISH, null, APP, ON))
        Assert.assertFalse(
                "prose is what is bought; a link is not prose",
                EnglishRow.needsBuying("https://example.com/a/b", null, APP, ON))
        Assert.assertFalse(
                "an app language of English means no row at all, so nothing is bought for one",
                EnglishRow.needsBuying(FINNISH, null, "en", ON))
        Assert.assertFalse(
                "a ping is not a language",
                EnglishRow.needsBuying("Matti: ", null, APP, ON))
    }

    @Test
    fun theShippedDefaultsAreOffAndLazy() {
        // The two settings are the only thing that can start spending on the English, so their
        // defaults are pinned here rather than left in a comment: an install that never opens the
        // settings screen must buy no English at all.
        Assert.assertFalse(EnglishRow.SHOW_BY_DEFAULT)
        Assert.assertTrue(EnglishRow.UNBLUR_ON_TAP_BY_DEFAULT)
        Assert.assertEquals(
                EnglishRow.Kind.NONE,
                EnglishRow.defaults(true, APP, ROOM, null, true, true, ON).kind())
    }

    /** The sent half: an English retranslation of what went on the wire. */

    @Test
    fun aSentRowDoesNotExistInARoomThatAlreadySpeaksEnglish() {
        // The owner's rule for the collision: the wire *is* the English there, so the first of the two
        // colliding settings wins and no English row is drawn, whatever the switch says.
        for (room in arrayOf("en", "EN", " en ")) {
            val row = EnglishRow.ofSent(true, room, true, true, ON)
            Assert.assertEquals("room " + room, EnglishRow.Kind.NONE, row.kind())
            Assert.assertFalse(row.isShown)
        }
    }

    @Test
    fun aSentRowSitsThirdAndIsNeverTheOriginal() {
        Assert.assertEquals(
                EnglishRow.Kind.BARE_BAR, EnglishRow.ofSent(true, ROOM, false, false, ON).kind())
        Assert.assertEquals(
                EnglishRow.Kind.BLURRED, EnglishRow.ofSent(true, ROOM, true, false, ON).kind())
        Assert.assertEquals(
                EnglishRow.Kind.READABLE, EnglishRow.ofSent(true, ROOM, true, true, ON).kind())
        val blurred = EnglishRow.ofSent(true, ROOM, true, false, ON)
        Assert.assertEquals(EnglishRow.Placement.THIRD_ROW, blurred.placement())
        Assert.assertTrue(blurred.isBlurred)
        Assert.assertFalse(
                "a retranslation is never the original, so nothing may read a body out of it",
                blurred.englishIsTheOriginal())
        Assert.assertFalse(
                "a sent row never takes the concealed strip's place", blurred.replacesOriginalStrip())
    }

    @Test
    fun aSentRowWithTheMasterSwitchOffIsNotThere() {
        Assert.assertEquals(
                EnglishRow.Kind.NONE, EnglishRow.ofSent(false, ROOM, true, true, ON).kind())
    }

    @Test
    fun revealingASentRowWithNothingInHandStaysABar() {
        Assert.assertEquals(
                EnglishRow.Kind.BARE_BAR, EnglishRow.ofSent(true, ROOM, false, true, ON).kind())
    }

    @Test
    fun theSentShippedDefaultIsOff() {
        Assert.assertFalse(EnglishRow.SHOW_SENT_BY_DEFAULT)
    }

    /**
     * Off is one fact about the row, and it is the same four answers wherever the shape is drawn.
     * The placement matters as much as the kind: a row that is not drawn must not be placed either.
     */
    private fun assertNoRow(because: String, row: EnglishRow) {
        Assert.assertEquals(because, EnglishRow.Kind.NONE, row.kind())
        Assert.assertEquals(because, EnglishRow.Placement.NONE, row.placement())
        Assert.assertFalse(because, row.isShown)
        Assert.assertFalse("no row means nothing English is in the bubble at all", row.hasEnglish())
        Assert.assertFalse(because, row.englishIsTheOriginal())
        Assert.assertFalse(because, row.replacesOriginalStrip())
    }

    /** The interpreter's off states this rule may not be reading a different one from. */
    private fun offModes(): Array<Interpreter?> =
            arrayOf(OFF, Interpreter.of("fi", Interpreter.NONE), null)

    // ---- the interpreter off: no row, for the shapes that are rows when it is on ----

    /**
     * The ordinary received shape: a received bubble with a translation to divide, the English in
     * hand, everything the setting asks for. On it is a blur; off it is no row at all - for the
     * same-language pair, for a `none` language, and for a caller that handed nothing in.
     */
    @Test
    fun anOffInterpreterHasNoRowForAReceivedBubbleWithATranslation() {
        Assert.assertEquals(
                "the on-state control: this is a row, and a blurred one",
                EnglishRow.Kind.BLURRED,
                EnglishRow.of(true, true, APP, ROOM, "de", true, false, ON).kind())

        for (off in offModes()) {
            assertNoRow(
                    "off",
                    EnglishRow.of(true, true, APP, ROOM, "de", true, false, off))
        }
    }

    /**
     * The one approved exception, and the clause §2.7 says the guard also has to cover: a received
     * original that was already English is shown in this row - blurred, and readable once tapped -
     * and off there is no row, so no original becomes readable anywhere. Both halves are asserted:
     * the blurred one and the revealed one, because the revealed one is what a plain client must
     * never draw.
     */
    @Test
    fun anOffInterpreterHidesTheOriginalThatWasAlreadyEnglish() {
        Assert.assertEquals(
                "on: the exception is a blurred row that is the original",
                EnglishRow.Kind.BLURRED,
                EnglishRow.of(true, true, APP, ROOM, "en", false, false, ON).kind())
        Assert.assertTrue(
                EnglishRow.of(true, true, APP, ROOM, "en", false, false, ON).englishIsTheOriginal())
        Assert.assertEquals(
                "on, tapped: the original is readable, which is the exception itself",
                EnglishRow.Kind.READABLE,
                EnglishRow.of(true, true, APP, ROOM, "en", false, true, ON).kind())

        for (off in offModes()) {
            assertNoRow(
                    "off, untapped",
                    EnglishRow.of(true, true, APP, ROOM, "en", false, false, off))
            assertNoRow(
                    "off, tapped: a plain client has no readable original here either",
                    EnglishRow.of(true, true, APP, ROOM, "en", false, true, off))
        }
    }

    /** The owner's own bubble: no row, and therefore no retranslation of the wire, off. */
    @Test
    fun anOffInterpreterHasNoSentRow() {
        Assert.assertEquals(
                "the on-state control: an English retranslation of the wire",
                EnglishRow.Kind.READABLE,
                EnglishRow.ofSent(true, ROOM, true, true, ON).kind())

        for (off in offModes()) {
            assertNoRow("off, in hand", EnglishRow.ofSent(true, ROOM, true, true, off))
            assertNoRow("off, nothing in hand", EnglishRow.ofSent(true, ROOM, false, false, off))
        }
    }

    /** The shipped-defaults entry point is a second way into {@link EnglishRow#of}, so it is asked too. */
    @Test
    fun theDefaultsEntryPointHasNoRowOffEither() {
        for (off in offModes()) {
            assertNoRow(
                    "off",
                    EnglishRow.defaults(true, APP, ROOM, "en", true, true, off))
        }
    }

    /**
     * The eager purchase: the setting that buys the English as the message arrives. Off, `false`
     * whatever the two switches say - including the shape whose whole purpose is to buy on arrival
     * (`show` on, `tap to unblur` off).
     */
    @Test
    fun anOffInterpreterBuysNothingOnArrival() {
        Assert.assertTrue(
                "the on-state control: the eager setting, with a real app language",
                EnglishRow.buysOnArrival(true, false, APP, ON))

        for (off in offModes()) {
            Assert.assertFalse(
                    "off buys nothing on arrival, whatever the two switches say",
                    EnglishRow.buysOnArrival(true, false, APP, off))
            Assert.assertFalse(
                    "and the master switch cannot be the reason here",
                    EnglishRow.buysOnArrival(true, true, APP, off))
        }
    }

    /**
     * The local "is it worth buying" question, for the body that is worth buying when on. Off it is
     * `false` - and by the delegation, not by a second guard here: {@code needsBuying} hands the
     * interpreter to {@link TranslationDecision#classify}, whose own first line answers
     * `NO_LANGUAGE` off (S4-3), so no body is ever `TRANSLATE` while the interpreter is off.
     */
    @Test
    fun anOffInterpreterBuysNoEnglishForABodyWorthBuying() {
        Assert.assertTrue(
                "the on-state control: Finnish prose is worth an English translation",
                EnglishRow.needsBuying(FINNISH, null, APP, ON))

        Assert.assertFalse(
                "off, there is nothing worth buying",
                EnglishRow.needsBuying(FINNISH, null, APP, OFF))
        Assert.assertFalse(
                "and the `none` sentinel is not a licence either",
                EnglishRow.needsBuying(FINNISH, null, APP, Interpreter.of(APP, Interpreter.NONE)))
    }
}
