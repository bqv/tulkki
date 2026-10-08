package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.EnglishRow
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationLanguages

/**
 * The composer's language chip as a rule: whether it is drawn, and what each of its two tiers holds.
 *
 * **What this is and is not.** `ConversationFragment` is a Fragment and this module hosts no Activity,
 * no Fragment and no Robolectric, so the chip as drawn - its colours, the two sizes that make the fade
 * carry through more than hue, the alpha, the background that says detected or owner-set, and the
 * `GONE` with its two listeners dropped - is a *device look* and is not claimed here. What is
 * JVM-asserted is the decision the drawing reads: off, no chip; on, the app language on top and the
 * conversation's below, each as a code or the unknown marker.
 *
 * That ordering is the invariant the row is really about, and it is invisible in most screenshots - an
 * app language and a conversation language that happen to be the same would look identical either way
 * round - which is why it is pinned here as two named tiers rather than as two locals in the fragment.
 */
class LanguageChipTest {

    @Test
    fun nothingIsDrawnWhileTheInterpreterIsOff() {
        Assert.assertTrue("fi x de: interpreting", LanguageChip.isDrawn(ON))
        Assert.assertFalse("fi x fi: one language on both sides", LanguageChip.isDrawn(OFF))
        Assert.assertFalse(
            "the sentinel on either side is off too",
            LanguageChip.isDrawn(Interpreter.of(Interpreter.NONE, "de")),
        )
        Assert.assertFalse(
            "and so is the study side's sentinel",
            LanguageChip.isDrawn(Interpreter.of("fi", Interpreter.NONE)),
        )
        Assert.assertTrue(
            "an English app language is a language like any other, so this pair is drawn",
            LanguageChip.isDrawn(Interpreter.of(EnglishRow.ENGLISH, "de")),
        )
        Assert.assertFalse(
            "but English on both sides is one language",
            LanguageChip.isDrawn(Interpreter.of(EnglishRow.ENGLISH, EnglishRow.ENGLISH)),
        )
        Assert.assertFalse("a caller with no mode has nothing to draw", LanguageChip.isDrawn(null))
    }

    @Test
    fun theAppLanguageIsTheTopTierAndTheConversationLanguageTheBottom() {
        val tiers = LanguageChip.tiers("fi", "de", true)
        Assert.assertEquals("the top tier is the app language", "FI", tiers.app)
        Assert.assertEquals("the bottom tier is the conversation's", "DE", tiers.conversation)

        // Casing and padding are folded by the project's one normaliser, and the chip writes the code
        // upper case; it never writes a language's name.
        val padded = LanguageChip.tiers(" fi ", " de ", true)
        Assert.assertEquals("FI", padded.app)
        Assert.assertEquals("DE", padded.conversation)
        Assert.assertNotEquals("German", padded.conversation)
    }

    @Test
    fun anUnknownConversationTierIsTheMarkerAndNotALanguage() {
        val unknown = LanguageChip.tiers("fi", null, false)
        Assert.assertEquals("the app language is there either way", "FI", unknown.app)
        Assert.assertEquals(
            "with nothing detected or overridden the pair says so rather than naming a language",
            LanguageChip.UNKNOWN,
            unknown.conversation,
        )

        // A code the caller has but does not believe is not consulted either: "known" is the caller's
        // answer, not something a tier guesses from the text.
        Assert.assertEquals(LanguageChip.UNKNOWN, LanguageChip.tiers("fi", "de", false).conversation)
    }

    @Test
    fun aTierIsACodeOrTheMarkerAndNeverALanguageName() {
        Assert.assertEquals("DE", LanguageChip.code("de"))
        Assert.assertEquals("DE", LanguageChip.code(" DE "))
        Assert.assertEquals("FI", LanguageChip.code("fi"))
        Assert.assertEquals(
            "the drawn marker, not a name: the word for it comes from the fragment's string",
            LanguageChip.UNKNOWN,
            LanguageChip.code(null),
        )
        Assert.assertEquals(LanguageChip.UNKNOWN, LanguageChip.code(""))
        Assert.assertEquals(LanguageChip.UNKNOWN, LanguageChip.code("   "))
        Assert.assertEquals(
            "the same question the rest of the app asks about a language-less body",
            LanguageChip.UNKNOWN,
            LanguageChip.code("und"),
        )
    }

    /**
     * The unset state draws **three question marks**, never the word "not known yet" and never the
     * sentinel `und`: three glyphs are unmistakably "unknown" where a lone `?` reads as punctuation,
     * and the sentinel stays `ComposerGate.isUnknownLanguage`'s (`TextLanguage.UNKNOWN`) rather than a
     * thing the chip writes. The wording for the unset state belongs to the spoken name (see
     * `tulkki_language_chip_content_unknown`), not to a tier a screenshot can measure.
     */
    @Test
    fun theUnknownTierIsTheThreeGlyphMarker() {
        Assert.assertEquals("three glyphs, all question marks", "???", LanguageChip.UNKNOWN)
        for (code in TranslationLanguages.codes()) {
            val tiers = LanguageChip.tiers("fi", code, true)
            Assert.assertEquals("the app tier is a code, not a name: $code", 2, tiers.app.length)
            Assert.assertEquals("the conversation tier is a code, not a sentence: $code", 2, tiers.conversation.length)
        }
        Assert.assertEquals(
            "and the unset state is the marker in that same slot, not the sentence \"not known yet\"",
            LanguageChip.UNKNOWN,
            LanguageChip.tiers("fi", null, false).conversation,
        )
    }

    /**
     * The sentinel belongs to the language settings and never becomes a chip tier: the app language
     * cannot be the sentinel while the chip is drawn (that is one of the ways the interpreter is off),
     * and off nothing is drawn at all. So the marker and the sentinel cannot be confused on screen.
     */
    @Test
    fun theSentinelsLanguageNeverBecomesATier() {
        Assert.assertFalse(
            "the sentinel app language is off, so there is no chip",
            LanguageChip.isDrawn(Interpreter.of(Interpreter.NONE, "de")),
        )
        Assert.assertFalse(
            "and it is not a language the picker may name either",
            TranslationLanguages.isKnown(Interpreter.NONE),
        )
        Assert.assertTrue(TranslationLanguages.isKnown("de"))
    }

    private companion object {
        /** The off pair: app Finnish with study Finnish, one language on both sides. */
        val OFF = Interpreter.of("fi", "fi")

        /** Interpreting, which is the only mode in which there is a chip to draw. */
        val ON = Interpreter.of("fi", "de")
    }
}
