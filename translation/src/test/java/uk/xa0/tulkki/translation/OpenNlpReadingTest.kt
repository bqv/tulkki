package uk.xa0.tulkki.translation

import java.util.Collections
import opennlp.tools.langdetect.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third identifier, on real text: OpenNLP's maximum-entropy classifier over its 103 languages.
 *
 * <p>Two things here are the reader's own and worth pinning: that the bundled model is where this
 * code looks for it inside the app, and that its ISO 639-3 answers come back as the app's ISO 639-1
 * codes, which is the mapping everything else in the app compares against.
 */
class OpenNlpReadingTest {

    private val PLAY: Set<String> = Collections.unmodifiableSet(setOf("fi", "de", "en"))

    @Test
    fun itReadsEachLanguageInPlay() {
        val finnish =
                OpenNlpReading.read(
                        "Tämä on suomenkielinen lause, joka kertoo aivan tavallisista asioista.",
                        PLAY)!!
        assertNotNull("OpenNLP must have an opinion about Finnish", finnish)
        assertEquals("fi", finnish.code)
        assertTrue(
                "a whole sentence should stand clear of the runner-up: " + finnish.confidence,
                finnish.confidence >= LanguageCheck.MIN_CONFIDENCE)

        val german =
                OpenNlpReading.read(
                        "Dies ist ein deutscher Satz, der von ganz gewöhnlichen Dingen erzählt.",
                        PLAY)!!
        assertNotNull(german)
        assertEquals("de", german.code)

        val english =
                OpenNlpReading.read("This is an English sentence about ordinary things.", PLAY)!!
        assertNotNull(english)
        assertEquals("en", english.code)
    }

    @Test
    fun theModelsOwnCodesAreThreeLetterOnesAndTheAppsAreTwo() {
        val model = OpenNlpReading.modelLanguages()

        assertEquals(103, model.size)
        assertTrue(model.contains("fin"))
        assertTrue(model.contains("deu"))
        assertTrue(model.contains("ell"))

        // Derived from the platform's own locale table rather than typed out here, so the two cannot
        // drift apart.
        assertEquals("fi", OpenNlpReading.iso1("fin"))
        assertEquals("de", OpenNlpReading.iso1("deu"))
        assertEquals("el", OpenNlpReading.iso1("ell"))
        assertEquals("en", OpenNlpReading.iso1("eng"))
        // The model's own codes for two languages the app spells differently: Mandarin is the app's
        // Chinese, and Bokmål is the app's Norwegian.
        assertEquals("zh", OpenNlpReading.iso1("cmn"))
        assertEquals("no", OpenNlpReading.iso1("nob"))
        // Nynorsk is a different written language and is not aliased onto the app's one Norwegian.
        assertEquals("nn", OpenNlpReading.iso1("nno"))
        assertNull(OpenNlpReading.iso1("zzz"))
        assertNull(OpenNlpReading.iso1(null))
    }

    @Test
    fun nothingToSayIsNoOpinionRatherThanAGuess() {
        assertNull(OpenNlpReading.read(null, PLAY))
        assertNull(OpenNlpReading.read("", PLAY))
        assertNull(OpenNlpReading.read("ok", PLAY))
    }

    @Test
    fun itsCertaintyIsAMarginNotAProbability() {
        // OpenNLP spreads its probability over 103 languages, so its absolute numbers are small: what
        // the check needs is how far the winner stands above the runner-up.
        assertEquals(0.5, OpenNlpReading.certainty(arrayOf(Language("fin", 0.08), Language("est", 0.04))), 1e-9)
        assertEquals(1.0, OpenNlpReading.certainty(arrayOf(Language("fin", 0.08))), 1e-9)
        assertEquals(0.0, OpenNlpReading.certainty(arrayOf(Language("fin", 0.04), Language("est", 0.04))), 1e-9)
        assertEquals(0.0, OpenNlpReading.certainty(emptyArray<Language>()), 1e-9)
        assertEquals(0.0, OpenNlpReading.certainty(null), 1e-9)
    }
}
