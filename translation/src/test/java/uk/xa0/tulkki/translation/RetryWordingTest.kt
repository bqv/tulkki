package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The retry action's wording: the owner may edit it, a blank field is the shipped wording, and the
 * edit is UI copy rather than a prompt.
 *
 * <p>The last claim is the load-bearing one and is not asserted here by inspecting the text: it is
 * pinned where it can actually fail, in {@code PromptBookTest}'s "the key is unmoved" cell, because a
 * future edit that folded this wording into {@link PromptBook} would have to pass that cell. This
 * class pins the setting's own contract - the default byte for byte, and the blank rule.
 */
class RetryWordingTest {

    @Test
    fun anUntouchedFieldIsTheShippedWording() {
        Assert.assertEquals("Translate again", RetryWording.SHIPPED)
        Assert.assertEquals(RetryWording.SHIPPED, RetryWording.inForce(null))
        Assert.assertEquals("an absent setting is the default", RetryWording.SHIPPED, RetryWording.inForce(""))
        // Blank, not merely empty: a field holding a newline is a field the owner emptied.
        Assert.assertEquals(RetryWording.SHIPPED, RetryWording.inForce("  \n "))
        Assert.assertFalse(RetryWording.edited(null))
        Assert.assertFalse(RetryWording.edited("  "))
    }

    @Test
    fun anEditedFieldIsTheWordingInForce() {
        Assert.assertEquals("Ask again", RetryWording.inForce("Ask again"))
        Assert.assertTrue(RetryWording.edited("Ask again"))
    }

    @Test
    fun theSettingReadsTheSameAsThePureRule() {
        val store = TranslationSettings.MapPrefs()
        val settings = TranslationSettings(store, store)
        Assert.assertEquals(
                "an untouched install draws exactly the shipped wording",
                RetryWording.SHIPPED,
                settings.retryWording())

        settings.setRetryWording("Ask DeepSeek again")
        Assert.assertEquals(
                "and a stored wording is what the button will carry",
                "Ask DeepSeek again",
                TranslationSettings(store, store).retryWording())

        settings.setRetryWording("  ")
        Assert.assertEquals(
                "clearing it puts the shipped wording back, with no reset row to drift",
                RetryWording.SHIPPED,
                settings.retryWording())
    }
}
