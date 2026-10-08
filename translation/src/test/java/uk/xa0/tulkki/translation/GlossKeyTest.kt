package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * A gloss's cache identity: one lookup per word per study language, and never confused with a
 * translation of the same string.
 */
class GlossKeyTest {

    @Test
    fun oneWordIsOneLookupHoweverItWasCapitalised() {
        Assert.assertEquals(GlossKey.of("Talo", "en"), GlossKey.of("talo", "en"))
        Assert.assertEquals(GlossKey.of("  talo ", "en"), GlossKey.of("talo", "en"))
    }

    @Test
    fun theStudyLanguageIsPartOfTheIdentity() {
        Assert.assertNotEquals(GlossKey.of("talo", "en"), GlossKey.of("talo", "de"))
    }

    @Test
    fun aGlossNeverCollidesWithATranslationOfTheSameText() {
        // The two live in one cache table. The namespace is hashed as its own field, so this is a
        // guarantee rather than a very small chance: a collision would mean one of them is silently
        // the wrong answer.
        Assert.assertNotEquals(GlossKey.of("talo", "en"), CacheKey.of("talo", "en"))
        Assert.assertNotEquals(CacheKey.of(GlossKey.NAMESPACE, "talo", "en"), CacheKey.of("talo", "en"))
    }

    @Test
    fun aMissingSurfaceOrLanguageNeverBreaksTheKey() {
        Assert.assertEquals(64, GlossKey.of(null, null).length)
        Assert.assertEquals(64, GlossKey.of("", "").length)
        Assert.assertNotEquals(GlossKey.of(null, "en"), GlossKey.of("talo", "en"))
    }

    @Test
    fun theSameWordInTheSameSentenceIsOneQuestion() {
        Assert.assertEquals(
                GlossKey.of("talo", "en", "Ostin eilen talon."),
                GlossKey.of("talo", "en", "Ostin eilen talon."))
    }

    @Test
    fun theSameWordInAnotherSentenceIsAnotherQuestion() {
        // The sentence is bought with the request, so it is part of what the answer is a function of:
        // "kuusi" is a spruce in one sentence and a six in another, and the first answer must not be
        // served for the second question.
        Assert.assertNotEquals(
                GlossKey.of("kuusi", "en", "Istutin kuusen pihalle."),
                GlossKey.of("kuusi", "en", "Kuusi plus kaksi on kahdeksan."))
    }

    @Test
    fun whitespaceInTheSentenceFolds() {
        Assert.assertEquals(
                GlossKey.of("talo", "en", "Ostin eilen talon."),
                GlossKey.of("talo", "en", "  Ostin   eilen\n talon.  "))
    }

    @Test
    fun noSentenceIsStillTheQuestionTheWordAloneAsks() {
        Assert.assertEquals(GlossKey.of("talo", "en"), GlossKey.of("talo", "en", null))
        Assert.assertNotEquals(
                GlossKey.of("talo", "en"), GlossKey.of("talo", "en", "Ostin talon."))
    }
}
