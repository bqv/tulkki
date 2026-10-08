package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Which settings a bubble's drawing depends on, and which it does not.
 *
 * <p>The claim these tests exist for is the bug, not the feature: the bubbles on screen are decided
 * while a row is bound, so a display setting that changes what a row draws without changing this
 * value is a setting whose change the conversation never repaints for. Each test above that flips one
 * setting the decision classes read requires the value to move; the last one flips settings a bubble
 * never draws and requires it to stay put, because a repaint for a setting that changes no pixel is a
 * cost with no answer.
 */
class BubbleDisplaySettingsTest {

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
    }

    /**
     * Leaves the process-wide settings as a fresh install for whatever test class runs next: the
     * instance is deliberately shared, and an edit left behind here would be an edit in someone
     * else's test.
     */
    @After
    fun tearDown() {
        TranslationSettings.inMemory()
    }

    /** Two reads of an untouched store already agree, so resuming a conversation re-binds nothing. */
    @Test
    fun anUnchangedStoreGivesAnEqualValue() {
        val before = BubbleDisplaySettings.of(settings)
        val after = BubbleDisplaySettings.of(settings)
        assertEquals(before, after)
        assertEquals(
                "equal values must agree on the hash, or a future set would drop a repaint",
                before.hashCode(),
                after.hashCode())
        assertEquals(before, before)
    }

    @Test
    fun theShowSecondHalfSwitchChangesTheValue() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setShowSecondHalf(!settings.showSecondHalf())
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    @Test
    fun theConcealOwnSecondHalfSwitchChangesTheValue() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setConcealOwnSecondHalf(!settings.concealOwnSecondHalf())
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    /**
     * The received original's own switch: it decides whether a strip is drawn under a translated
     * received message, so a change to it has to repaint the bubbles already on screen. This is the
     * row the report was about - the strip had no control of its own - and a setting without this
     * test would leave the strip on screen until something else re-bound the list.
     */
    @Test
    fun theConcealedOriginalSwitchChangesTheValue() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setShowConcealedOriginal(!settings.showConcealedOriginal())
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    @Test
    fun theEnglishMasterSwitchChangesTheValue() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setShowBlurredEnglish(!settings.showBlurredEnglish())
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    @Test
    fun theSentEnglishSwitchChangesTheValue() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setShowEnglishRetranslation(!settings.showEnglishRetranslation())
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    /**
     * The app language is not a display switch, but it is drawn on a bubble all the same:
     * {@link EnglishRow#of} refuses the English row once the app language is English, so a change to
     * it can take a row off the screen - and a repaint that missed it would leave the row there.
     *
     * <p>The pair is set on both sides first, and to two different languages, so the interpreter is
     * on before and after: what moves here is the language the row reads, not the mode.
     */
    @Test
    fun theAppLanguageChangesTheValue() {
        settings.setStudyLanguage("de")
        settings.setAppLanguage("fi")
        val before = BubbleDisplaySettings.of(settings)
        settings.setAppLanguage(EnglishRow.ENGLISH)
        assertNotEquals(before, BubbleDisplaySettings.of(settings))
    }

    /**
     * The study language changes the value, and this test replaces an assertion that said the
     * opposite. That assertion was the bug: with the study language absent from the list, flipping it
     * from a real language to `en` or `none` turned the interpreter off - as the rule read then -
     * every second half and every English row on screen should go, and the resume comparison saw an
     * unchanged value and left them drawn. The class doc's claim that the study language "is drawn
     * nowhere on a bubble" was the reading behind it, and it is wrong twice over, so both halves are
     * asserted here:
     *
     * <ul>
     *   <li>it is half of {@link Interpreter}'s rule, where a value that names the sentinel is off.
     *       `de` -> `none` is therefore a mode change as well as a value change, and the last
     *       assertion below is that one;
     *   <li>it is an input to two decisions a bound row makes on its own - the reading aid's tap
     *       targets ({@link GlossText#words}) and the review the bubble reads back, because
     *       {@link ReviewKey} keys a review by the language its notes are in. So a change that leaves
     *       the interpreter's answer alone, `en` -> `de` or `de` -> `sv`, still changes what a bound
     *       row draws, and this is what pins that the <em>value</em> is carried rather than the
     *       derived on/off answer: carrying only `enabled()` would fail here.
     * </ul>
     */
    @Test
    fun theStudyLanguageChangesTheValue() {
        settings.setAppLanguage("fi")
        settings.setStudyLanguage("en")
        val english = BubbleDisplaySettings.of(settings)

        // Both of these pairs are on under the rule - the two languages differ - so what moves here is
        // the value a bound row reads, not the mode.
        settings.setStudyLanguage("de")
        val german = BubbleDisplaySettings.of(settings)
        assertNotEquals("en -> de changes the value the row's own decisions read", english, german)
        assertNotEquals(
                "equal values must agree on the hash, or a future set would drop a repaint",
                english.hashCode(),
                german.hashCode())

        settings.setStudyLanguage("sv")
        assertNotEquals(
                "de -> sv still changes the gloss targets and the review key",
                german,
                BubbleDisplaySettings.of(settings))

        settings.setStudyLanguage(Interpreter.NONE)
        assertNotEquals(
                "and a sentinel is a mode change as well as a value change",
                german,
                BubbleDisplaySettings.of(settings))
    }

    /**
     * The settings that decide something about a message without deciding a bubble's drawing. "Tap to
     * unblur" is the interesting one: it changes who buys the English, not what a bound row shows -
     * bare bar either way - so a repaint on it would redraw every visible row to the same pixels.
     *
     * <p>The study language used to be asserted here too, and that is exactly what this test got
     * wrong: it is not a setting a bubble never draws (see
     * {@link #theStudyLanguageChangesTheValue()}), and a case left in this list is a change nobody
     * repaints for. What stays in the list is the settings that reach no decision a bind makes.
     */
    @Test
    fun settingsThatDrawNothingOnABubbleLeaveTheValueAlone() {
        val before = BubbleDisplaySettings.of(settings)
        settings.setUnblurEnglishOnTap(!settings.unblurEnglishOnTap())
        assertEquals("tap to unblur buys, it does not draw", before, BubbleDisplaySettings.of(settings))
        settings.setRevealSuggestionFirst(!settings.revealSuggestionFirst())
        assertEquals(before, BubbleDisplaySettings.of(settings))
        settings.setDailyTokenCap(settings.dailyTokenCap() + 1)
        assertEquals(before, BubbleDisplaySettings.of(settings))
        settings.setTranslatePrompt("say it in Finnish")
        assertEquals(before, BubbleDisplaySettings.of(settings))
    }
}
