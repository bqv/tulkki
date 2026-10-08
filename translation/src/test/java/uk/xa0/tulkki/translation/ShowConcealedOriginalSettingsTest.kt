package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * The received original's switch as the settings class reads it, the migration included.
 *
 * <p>The claim is not the code's shape but the owner's guarantee: the row is new, so no install has
 * ever stored a value for it, and an absent value must mean "whatever Show the second half says".
 * Anything else would change every existing install's conversations the moment this build arrived -
 * and the report that started this work was already about a strip nobody could switch off. Once the
 * owner touches the row the two switches are independent, which is the second test.
 *
 * <p>Pure JVM: {@link TranslationSettings#inMemory()} is the same class over a map instead of a
 * preferences file, so the read under test is the one the app performs.
 */
class ShowConcealedOriginalSettingsTest {

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
    }

    /** Leaves a fresh install behind for the next class: the instance is deliberately shared. */
    @After
    fun tearDown() {
        TranslationSettings.inMemory()
    }

    @Test
    fun anUnsetReceivedSwitchFollowsTheShowSecondHalfSetting() {
        Assert.assertTrue(
                "the shipped Show the second half is on, so an untouched install still draws the strip",
                settings.showConcealedOriginal())
        settings.setShowSecondHalf(false)
        Assert.assertFalse(
                "an install that had turned the old switch off keeps its single-half messages",
                settings.showConcealedOriginal())
        settings.setShowSecondHalf(true)
        Assert.assertTrue(
                "and it follows the old switch back, because nothing has been stored for it",
                settings.showConcealedOriginal())
    }

    @Test
    fun aChosenReceivedSwitchIsItsOwnValue() {
        settings.setShowSecondHalf(false)
        settings.setShowConcealedOriginal(true)
        Assert.assertTrue(
                "once the row has been touched it answers for itself, the older switch or not",
                settings.showConcealedOriginal())
        settings.setShowConcealedOriginal(false)
        settings.setShowSecondHalf(true)
        Assert.assertFalse(settings.showConcealedOriginal())
    }
}
