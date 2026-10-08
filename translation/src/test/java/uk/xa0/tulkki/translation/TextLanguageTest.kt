package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/** The detector has to be good enough to leave Finnish alone and to name a room's language. */
class TextLanguageTest {

    @Test
    fun finnish() {
        Assert.assertEquals(
                "fi",
                TextLanguage.detect("Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle.")
                        .code)
    }

    @Test
    fun english() {
        Assert.assertEquals(
                "en",
                TextLanguage.detect("Hey, are we still meeting tomorrow for coffee? I have news.")
                        .code)
    }

    @Test
    fun german() {
        Assert.assertEquals(
                "de",
                TextLanguage.detect("Hallo, treffen wir uns morgen zum Kaffee? Ich habe Neuigkeiten.")
                        .code)
    }

    @Test
    fun swedish() {
        Assert.assertEquals(
                "sv",
                TextLanguage.detect("Hej, ska vi träffas imorgon för att ta en kaffe? Jag har nyheter.")
                        .code)
    }

    @Test
    fun confidenceIsReportedForALongSentence() {
        val guess =
                TextLanguage.detect("Tämä on selvästi suomen kieltä ja siinä on tarpeeksi sanoja.")
        Assert.assertEquals("fi", guess.code)
        Assert.assertTrue(
                "a full sentence should be confidently Finnish, got " + guess,
                guess.isProbably("fi", 0.5))
    }

    @Test
    fun shortGreetingsAreUnknownRatherThanGuessed() {
        Assert.assertTrue(TextLanguage.detect("ok").isUnknown())
        Assert.assertTrue(TextLanguage.detect("ja").isUnknown())
        Assert.assertTrue(TextLanguage.detect("  ").isUnknown())
    }

    @Test
    fun nullIsUnknownAndDoesNotThrow() {
        Assert.assertTrue(TextLanguage.detect(null).isUnknown())
    }

    @Test
    fun unknownGuessIsNotProbablyAnything() {
        val unknown = TextLanguage.detect(null)
        Assert.assertFalse(unknown.isProbably("fi", 0.0))
        Assert.assertFalse(unknown.isProbably(TextLanguage.UNKNOWN, 1.0))
    }
}
