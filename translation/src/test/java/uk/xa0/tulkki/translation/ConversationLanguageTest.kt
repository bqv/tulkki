package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * What the conversation's language is, and how it is known. The bar says both, and which of the two
 * a language came from is what tells the owner whether clearing it does anything - so this is the
 * part worth pinning down without a device.
 */
class ConversationLanguageTest {

    @Test
    fun nothingStoredIsUnknown() {
        val resolved = ConversationLanguage.resolve(null, null)
        Assert.assertEquals(ConversationLanguage.Source.UNKNOWN, resolved.source())
        Assert.assertTrue(resolved.isUnknown)
        Assert.assertFalse(resolved.isKnown)
        Assert.assertEquals(TextLanguage.UNKNOWN, resolved.code())
        Assert.assertNull(resolved.storedCode())
        Assert.assertEquals("unknown", ConversationLanguage.languageName(resolved.code()))
    }

    @Test
    fun blankAndUndCountAsNothingRatherThanAsALanguage() {
        Assert.assertTrue(ConversationLanguage.resolve("", "").isUnknown)
        Assert.assertTrue(ConversationLanguage.resolve("und", "und").isUnknown)
        Assert.assertTrue(ConversationLanguage.resolve("   ", null).isUnknown)
    }

    @Test
    fun aDetectedLanguageIsUsedAndSaysItWasDetected() {
        val resolved = ConversationLanguage.resolve("de", null)
        Assert.assertEquals("de", resolved.code())
        Assert.assertEquals(ConversationLanguage.Source.DETECTED, resolved.source())
        Assert.assertTrue(resolved.isAutomatic)
        Assert.assertFalse(resolved.isSet)
        Assert.assertTrue(resolved.isKnown)
        Assert.assertEquals("de", resolved.detected())
    }

    @Test
    fun anOverrideBeatsTheDetection() {
        val resolved = ConversationLanguage.resolve("de", "sv")
        Assert.assertEquals("sv", resolved.code())
        Assert.assertEquals(ConversationLanguage.Source.SET, resolved.source())
        Assert.assertTrue(resolved.isSet)
        Assert.assertFalse(resolved.isAutomatic)
        // The guess is still reported, so the picker can offer it as the automatic choice.
        Assert.assertEquals("de", resolved.detected())
    }

    @Test
    fun anOverrideBeatsNothingDetectedToo() {
        val resolved = ConversationLanguage.resolve(null, "fr")
        Assert.assertEquals("fr", resolved.code())
        Assert.assertEquals(ConversationLanguage.Source.SET, resolved.source())
        Assert.assertNull(resolved.detected())
    }

    @Test
    fun clearingTheOverrideFallsBackToTheDetection() {
        // The picker's "detect automatically" writes null over the override and nothing else.
        val cleared = ConversationLanguage.resolve("de", null)
        Assert.assertEquals(ConversationLanguage.Source.DETECTED, cleared.source())
        Assert.assertEquals("de", cleared.code())
    }

    @Test
    fun clearingTheOverrideWithNothingDetectedLeavesItUnknown() {
        val cleared = ConversationLanguage.resolve(null, null)
        Assert.assertEquals(ConversationLanguage.Source.UNKNOWN, cleared.source())
        Assert.assertNull(cleared.storedCode())
    }

    @Test
    fun surroundingSpaceAndCaseDoNotMakeALanguage() {
        Assert.assertEquals("de", ConversationLanguage.resolve(" DE ", null).code())
        Assert.assertEquals("de", ConversationLanguage.resolve(null, " de ").code())
    }

    @Test
    fun languageNamesAreEnglishWhateverTheDeviceLocaleIs() {
        Assert.assertEquals("Finnish", ConversationLanguage.languageName("fi"))
        Assert.assertEquals("German", ConversationLanguage.languageName("de"))
        Assert.assertEquals("Swedish", ConversationLanguage.languageName("sv"))
        Assert.assertEquals("unknown", ConversationLanguage.languageName(null))
        Assert.assertEquals("unknown", ConversationLanguage.languageName("und"))
        // A code the platform cannot name is shown as itself rather than as an empty string.
        Assert.assertEquals("zz", ConversationLanguage.languageName("zz"))
    }

    // -- what one received message says the conversation's language is -------------------------------

    private val GERMAN = "Gestern war es sehr schön."
    private val WEAK_ENGLISH = "See you tomorrow"
    private val FINNISH = "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    @Test
    fun aStrongLocalReadingBeatsAContradictingModelAnswer() {
        val reading =
                ConversationLanguage.read(GERMAN, "en", "fi", null, null)
        Assert.assertEquals("de", reading.language)
        Assert.assertEquals("de", reading.local.code)
        Assert.assertTrue("the two readings do disagree", reading.disagrees())
    }

    @Test
    fun theModelsAnswerIsUsedWhenTheDetectorHasNoOpinion() {
        val reading =
                ConversationLanguage.read("Ok", "en", "fi", null, null)
        Assert.assertTrue("the detector really has no opinion about this", reading.local.isUnknown())
        Assert.assertEquals("en", reading.language)
        Assert.assertFalse("an unknown reading is not a disagreement", reading.disagrees())
    }

    @Test
    fun aWeakLocalReadingDoesNotReplaceAnEstablishedLanguage() {
        Assert.assertTrue(
                "the fixture must really be a weak reading, got " + TextLanguage.detect(WEAK_ENGLISH),
                TextLanguage.detect(WEAK_ENGLISH).confidence
                        < TextLanguage.TRUSTWORTHY_CONFIDENCE)
        Assert.assertNull(ConversationLanguage.read(WEAK_ENGLISH, "en", "fi", "de", null).language)
    }

    @Test
    fun theModelsAnswerDoesNotReplaceAnEstablishedLanguageEither() {
        Assert.assertNull(ConversationLanguage.read("Ok", "en", "fi", "de", null).language)
    }

    @Test
    fun aStrongLocalReadingReplacesAStaleOne() {
        Assert.assertEquals("de", ConversationLanguage.read(GERMAN, "de", "fi", "en", null).language)
    }

    @Test
    fun aSingleConfidentTokenIsNotProseEnoughToNameTheConversation() {
        // Confidence alone would let a bare name through: the profiles read it as a language.
        Assert.assertEquals("mt", TextLanguage.detect("Matti").code)
        Assert.assertNull(ConversationLanguage.read("Matti", "de", "fi", null, null).language)
    }

    @Test
    fun theAppLanguageIsNeverRecordedAsTheRoomsLanguage() {
        Assert.assertNull(
                ConversationLanguage.read(FINNISH, "fi", "fi", null, null).language)
    }

    @Test
    fun noModelLanguageAtAllIsNotALanguage() {
        Assert.assertNull(ConversationLanguage.read("Ok", "und", "fi", null, null).language)
        Assert.assertNull(ConversationLanguage.read("Ok", null, "fi", null, null).language)
        Assert.assertNull(ConversationLanguage.read("Ok", "  ", "fi", null, null).language)
    }

    // -- was this message already in the app language? ---------------------------------------------

    @Test
    fun aMessageIsOnlyAlreadyInTheTargetWhenTheDetectorSaysSo() {
        // The model calls the German message Finnish; the detector is the one that counts.
        val reading =
                ConversationLanguage.read(GERMAN, "fi", "fi", null, null)
        Assert.assertEquals("de", reading.messageLanguage)
        Assert.assertFalse(reading.wasAlreadyIn("fi"))
    }

    @Test
    fun theModelsClaimStandsInWhenTheDetectorHasNoOpinion() {
        val reading =
                ConversationLanguage.read("Ok", "fi", "fi", null, null)
        Assert.assertTrue(reading.local.isUnknown())
        Assert.assertEquals("fi", reading.messageLanguage)
        Assert.assertTrue(reading.wasAlreadyIn("fi"))
    }

    @Test
    fun aWeakReadingIsNeverTreatedAsAlreadyInTheTarget() {
        val reading =
                ConversationLanguage.read(WEAK_ENGLISH, "fi", "fi", null, null)
        Assert.assertNull("a reading below the bar says nothing about the message", reading.messageLanguage)
        Assert.assertFalse("when it cannot be told, translate", reading.wasAlreadyIn("fi"))
    }

    @Test
    fun anEmptyOrUnknownTargetIsNeverSomethingAMessageIsAlreadyIn() {
        val reading =
                ConversationLanguage.read(GERMAN, null, "fi", null, null)
        Assert.assertFalse(reading.wasAlreadyIn(null))
        Assert.assertFalse(reading.wasAlreadyIn(""))
        Assert.assertFalse(reading.wasAlreadyIn("und"))
    }

    @Test
    fun theMessageLanguageIsKnownEvenWhenItIsTheAppLanguage() {
        // The conversation says nothing - the app language is never a room's language - but the
        // message itself is still Finnish, so nothing needs translating.
        val reading =
                ConversationLanguage.read(FINNISH, "en", "fi", null, null)
        Assert.assertEquals("fi", reading.messageLanguage)
        Assert.assertNull(reading.language)
        Assert.assertTrue(reading.wasAlreadyIn("fi"))
    }
}
