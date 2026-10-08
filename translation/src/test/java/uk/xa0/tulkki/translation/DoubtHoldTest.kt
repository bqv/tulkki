package uk.xa0.tulkki.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The per-conversation doubt-hold switch as the rules read it: one derived setting, the shipped
 * default hardcoded on, and the display side untouched by either reading.
 *
 * <p>The stored value is a tri-state and this class is where it is resolved
 * ({@code ConversationDoubtHoldTest} pins that the column and the model resolve nothing):
 * {@code NULL} is "this conversation never chose" and follows {@link DoubtHold#SHIPPED_DEFAULT},
 * which is <em>on</em>; {@code 0} is the owner's off for that room; {@code 1} is the owner's explicit
 * on. The default is on because a switch the owner has to remember to set leaves the bug - a doubtful
 * send going out untranslated - in every room they forgot.
 *
 * <p><strong>What the switch is not.</strong> It is not an input to what is drawn, and the last cell
 * here is that claim made executable: a doubtful answer is not a failure, so a received message still
 * draws it as the translation it probably is, and neither {@link LanguageCheck} nor
 * {@link DisplayedBody} takes a per-conversation answer at all - the decision has no such parameter,
 * which is what makes "the hold's alone" a property of the code rather than of a sentence. And it is
 * not an override of the echo either: an answer that is the draft handed back is held whatever the
 * owner stored, because sending it would send the original (item 16's own sentence, and the reason
 * the narrowing lives inside {@link DoubtHold#holds} rather than at the call site).
 */
class DoubtHoldTest {

    /** App Finnish, study German: the interpreter on, so the display rules apply. */
    private val ON = Interpreter.of("fi", "de")

    @Test
    fun aConversationThatNeverChoseFollowsTheShippedDefaultWhichIsOn() {
        assertTrue("a fresh install must hold doubtful answers", DoubtHold.SHIPPED_DEFAULT)
        assertTrue(DoubtHold.inForce(null))
    }

    @Test
    fun theOwnersOffTurnsTheHoldOff() {
        assertFalse(DoubtHold.inForce(java.lang.Boolean.FALSE))
    }

    @Test
    fun theOwnersOnKeepsItOn() {
        assertTrue(DoubtHold.inForce(java.lang.Boolean.TRUE))
        // The three readings are three answers, not two spellings of one: the third is what makes the
        // default reachable without writing a value into every row.
        assertNotEquals(DoubtHold.inForce(java.lang.Boolean.FALSE), DoubtHold.inForce(null))
    }

    @Test
    fun theSwitchDecidesNothingAboutWhatIsDrawn() {
        // The send path's other doubt kind, made concrete: one reader against the target and no
        // reader for it is doubt, and doubt is not evidence - for display it is the translation it
        // probably is, and for the send path it is held only while this conversation's switch is on.
        val doubt: LanguageCheck.Report =
                LanguageCheck.judge("fi", listOf(LanguageCheck.Reading("de", 0.61, false)))
        assertTrue(doubt.acceptedOnDoubt())
        assertFalse("display must not cover a probable translation", doubt.failed())

        for (stored in arrayOf<Boolean?>(null, java.lang.Boolean.FALSE, java.lang.Boolean.TRUE)) {
            val held = DoubtHold.inForce(stored)
            // `held` is the only thing the switch decides; the line below is what a received message
            // draws, and it is the same answer for all three readings because the switch is not among
            // its inputs.
            assertTrue(
                    DisplayedBody.of("Hallo", "Hallo!", Message.TRANSLATION_DONE, true, ON)
                            .isTranslation())
            assertEquals(if (stored == null) DoubtHold.SHIPPED_DEFAULT else stored, held)
        }
    }

    /**
     * What the override reaches, and the one thing it cannot. Item 16 in as many words: an echo
     * "(nothing was translated) must re-translate on the tap, because sending the held answer would
     * send the original, which is the bug" - so the switch's scope, a room "where a stray untranslated
     * line is harmless", cannot cover it. With the hold off a probable translation goes as held, and
     * the echo still does not go anywhere.
     */
    @Test
    fun theOwnersOffSendsAProbableTranslationAndStillHoldsAnEcho() {
        assertFalse(
                "a probable translation may go as held where the owner says so",
                DoubtHold.holds(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE, java.lang.Boolean.FALSE))
        assertTrue(
                "an echo is never sent, switch or no switch: sending it would send the original",
                DoubtHold.holds(LanguageCheck.Doubt.NOTHING_TRANSLATED, java.lang.Boolean.FALSE))
    }

    @Test
    fun withTheHoldOnBothKindsHold() {
        for (stored in arrayOf<Boolean?>(null, java.lang.Boolean.TRUE)) {
            assertTrue(DoubtHold.holds(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE, stored))
            assertTrue(DoubtHold.holds(LanguageCheck.Doubt.NOTHING_TRANSLATED, stored))
        }
    }
}
