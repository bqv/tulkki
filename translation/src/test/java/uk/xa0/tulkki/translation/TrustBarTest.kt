package uk.xa0.tulkki.translation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one bar a local detector reading must clear, pinned at all four sites that read one.
 *
 * <p>`0.90` used to be spelled in three named constants - `SAME_LANGUAGE_CONFIDENCE`,
 * `FOREIGN_CONFIDENCE` and `DETECTED_CONFIDENCE` - each with a comment saying it was the
 * same bar as the others for the same reason. It is now {@link TextLanguage#TRUSTWORTHY_CONFIDENCE},
 * and these tests are the property the three names asserted only in prose: one body whose reading
 * crosses the bar changes what <em>every</em> site decides, together.
 *
 * <p>The two fixtures are deliberately within a few hundredths of the bar rather than far from it, so
 * a re-tune of the number shows up here instead of quietly changing one path's behaviour.
 */
class TrustBarTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages. The bar is
     * about what a reading decides when the interpreter is running.
     */
    private val ON = Interpreter.of("fi", "de")

    /** The detector reads this as German at 0.88: just below the bar. */
    private val JUST_BELOW = "Guten Morgen"

    /** ... and this as German at 0.96: just above it, and the same language either side. */
    private val JUST_ABOVE = "Nicht so gut"

    /** Icelandic, which the detector is certain about; "IS" is the code Turkish case-folding breaks. */
    private val ICELANDIC =
            "Ég ætla að fara út að borða með vinum mínum í kvöld ef það er í lagi með þig."

    /** The premise, asserted first so the tests below cannot pass on a fixture that proves nothing. */
    @Test
    fun theFixturesStraddleTheBar() {
        assertTrue(
                "the low fixture must be the same language, just weaker: "
                        + TextLanguage.detect(JUST_BELOW),
                TextLanguage.detect(JUST_BELOW).code.equals("de")
                        && TextLanguage.detect(JUST_BELOW).confidence
                                < TextLanguage.TRUSTWORTHY_CONFIDENCE)
        assertTrue(
                "the high fixture must be the same language, just stronger: "
                        + TextLanguage.detect(JUST_ABOVE),
                TextLanguage.detect(JUST_ABOVE).code.equals("de")
                        && TextLanguage.detect(JUST_ABOVE).confidence
                                >= TextLanguage.TRUSTWORTHY_CONFIDENCE)
        assertTrue("both must be prose, or the bar is not the question", 
                ComposerGate.isProse(JUST_BELOW) && ComposerGate.isProse(JUST_ABOVE))
        assertTrue(
                "the Icelandic fixture must be certain: " + TextLanguage.detect(ICELANDIC),
                TextLanguage.detect(ICELANDIC)
                        .isProbably("is", TextLanguage.TRUSTWORTHY_CONFIDENCE))
    }

    /**
     * The bar, read by each site against the target that site cares about: the app language for the
     * receive path, the composer gate's app language, and the conversation's own language for the two
     * that may name it.
     */
    @Test
    fun oneNumberDecidesEverySiteThatActsOnALocalGuess() {
        // Above the bar, every site acts on the reading.
        assertEquals(
                TranslationDecision.Verdict.SAME_LANGUAGE,
                TranslationDecision.classify(JUST_ABOVE, "de", null, ON))
        assertEquals(
                ComposerGate.Verdict.NOT_APP_LANGUAGE,
                ComposerGate.verdict(JUST_ABOVE, "fi", "de", null, ON))
        assertEquals("de", LanguageSample.readOne(JUST_ABOVE, "fi", null))
        assertEquals(
                "de", ConversationLanguage.read(JUST_ABOVE, null, "fi", null, null).language)

        // Below it, no site does - and each falls to its own safe answer rather than to a shared one.
        assertEquals(
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify(JUST_BELOW, "de", null, ON))
        assertEquals(
                ComposerGate.Verdict.TRANSLATE, ComposerGate.verdict(JUST_BELOW, "fi", "de", null, ON))
        assertNull(LanguageSample.readOne(JUST_BELOW, "fi", null))
        assertNull(ConversationLanguage.read(JUST_BELOW, null, "fi", null, null).language)
    }

    /** The number itself: one bar, and a re-tune is a deliberate edit with this failing. */
    @Test
    fun theBarIsOneNumber() {
        assertEquals(
                "the bar a local reading must clear",
                0.90,
                TextLanguage.TRUSTWORTHY_CONFIDENCE,
                0.0)
    }

    /**
     * The latent defect the merge fixed: the receive path lowered its target with a bare
     * `toLowerCase()`, and in a Turkish locale that maps `I` to a dotless `ı` - so
     * an Icelandic message stopped looking like it was already the app language. Every other site
     * folded the code with {@link ComposerGate#normalize}, which is `Locale.ROOT`.
     */
    @Test
    fun theBarIsFoldedTheSameWayInEveryLocale() {
        assertTrue(
                "the fixture must really be Icelandic: " + TextLanguage.detect(ICELANDIC),
                TextLanguage.detect(ICELANDIC)
                        .isProbably("is", TextLanguage.TRUSTWORTHY_CONFIDENCE))
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(
                    "IS is Iceland, in every locale",
                    TranslationDecision.Verdict.SAME_LANGUAGE,
                    TranslationDecision.classify(ICELANDIC, "IS", null, ON))
            assertEquals("is", LanguageSample.readOne(ICELANDIC, "fi", null))
        } finally {
            Locale.setDefault(original)
        }
    }
}
