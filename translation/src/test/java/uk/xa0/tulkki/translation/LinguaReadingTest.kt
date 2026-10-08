package uk.xa0.tulkki.translation

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second identifier, on real text: Lingua's own models, asked about the languages in play.
 *
 * <p>This is also the test that proves the dependency is wired up at all - that the models are where
 * the library expects to find them inside the app, and that a detector can be built and asked on a
 * plain JVM. What it cannot prove is Android's own runtime; the app on the phone is what does that.
 */
class LinguaReadingTest {

    private val PLAY: Set<String> = Collections.unmodifiableSet(setOf("fi", "de", "en"))

    @Test
    fun itReadsEachLanguageInPlay() {
        val finnish =
                LinguaReading.read(
                        "Tämä on suomenkielinen lause, joka kertoo aivan tavallisista asioista.",
                        PLAY)!!
        assertNotNull("Lingua must have an opinion about Finnish", finnish)
        assertEquals("fi", finnish.code)
        assertTrue(
                "a whole sentence should be read with some confidence: " + finnish,
                finnish.confidence >= LanguageCheck.MIN_CONFIDENCE)
        assertFalse(finnish.script)

        val german =
                LinguaReading.read(
                        "Dies ist ein deutscher Satz, der von ganz gewöhnlichen Dingen erzählt.",
                        PLAY)!!
        assertNotNull(german)
        assertEquals("de", german.code)

        val english =
                LinguaReading.read(
                        "This is an English sentence about entirely ordinary things.", PLAY)!!
        assertNotNull(english)
        assertEquals("en", english.code)
    }

    @Test
    fun theLanguagesInPlayAreTheOnesItCanBeAskedAbout() {
        // The app's own list minus the ones Lingua has no model for. Finnish, German and English are
        // the three this check is normally asked about, and they are all here.
        val languages = LinguaReading.languages()
        assertTrue(languages.contains("fi"))
        assertTrue(languages.contains("de"))
        assertTrue(languages.contains("en"))
        assertTrue(languages.contains("zh"))
        assertTrue("no models at all means the check is running on two readers", languages.size > 50)
    }

    @Test
    fun nothingToSayIsNoOpinionRatherThanAGuess() {
        // No language in play that Lingua knows: the reader abstains, and the check's other two
        // readers carry on.
        assertNull(LinguaReading.read("Dies ist ein deutscher Satz.", Collections.singleton("xx")))
        assertNull(LinguaReading.read("ok", PLAY))
        assertNull(LinguaReading.read("", PLAY))
        assertNull(LinguaReading.read(null, PLAY))
    }
}
