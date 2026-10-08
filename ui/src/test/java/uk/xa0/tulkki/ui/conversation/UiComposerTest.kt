package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.LanguageCheck
import uk.xa0.tulkki.ui.projection.AttachmentKind

/**
 * The composer's own three answers: whether there is anything to send, that an empty composer means
 * *no chip* rather than a chip that says nothing, and that item 16's switch follows the same rule -
 * off, it is not drawn at all rather than drawn in a position that does nothing.
 *
 * <p>The interpreter-off cell is §2.12's shape and the reason it is worth a cell: the interpreter-off
 * screen has no conversation chip at all, so a host that assembles the composer with no language must
 * get a composer with no language - not an invented default and not the app language, which would name
 * a pair for a conversation that has none.
 */
class UiComposerTest {

    @Test
    fun aBlankDraftHasNothingToSend() {
        Assert.assertFalse(UiComposer().canSend)
        Assert.assertFalse("and whitespace is not words", UiComposer(draft = TextFieldValue("   ")).canSend)
        Assert.assertFalse(UiComposer(draft = TextFieldValue("\n\t ")).canSend)
    }

    @Test
    fun aWrittenDraftCanBeSent() {
        Assert.assertTrue(UiComposer(draft = TextFieldValue("Nähdään huomenna.")).canSend)
    }

    /**
     * The draft is a `TextFieldValue` and not a `String` for the draft verbs' sake: a marker's
     * position *is* a selection. So the caret survives into the composer and back out of it, which is
     * the whole of what the lift bought - a field that carried only text could not place `/me` at the
     * head or wrap a selected run in `*`.
     */
    @Test
    fun theDraftCarriesTheCaretAndNotOnlyTheWords() {
        val draft = TextFieldValue("Nähdään huomenna.", selection = TextRange(3, 8))
        val composer = UiComposer(draft = draft)
        Assert.assertEquals(
            "the composer holds the field's own value, selection and all",
            draft,
            composer.draft,
        )
        Assert.assertEquals(3, composer.draft.selection.start)
        Assert.assertEquals(8, composer.draft.selection.end)
        Assert.assertTrue("and words with a selection are still words", composer.canSend)
        Assert.assertFalse(
            "a selection inside whitespace is still not words to send",
            UiComposer(draft = TextFieldValue("   ", selection = TextRange(0, 3))).canSend,
        )
    }

    /**
     * A staged file is something to send on its own: the Java composer refuses only when the body is
     * empty *and* there is nothing staged (`body.length() == 0 && !hasAttachments`), and an attachment
     * travels with the message's own optional caption.
     */
    @Test
    fun aStagedAttachmentCanBeSentWithoutADraft() {
        val staged = listOf(UiPendingAttachment(id = "a-uuid", kind = AttachmentKind.FILE))
        Assert.assertTrue(UiComposer(attachments = staged).canSend)
        Assert.assertFalse("and removing the last one takes the send with it", UiComposer().canSend)
    }

    @Test
    fun anEmptyComposerNamesNoLanguageAndHoldsNothing() {
        val composer = UiComposer()
        Assert.assertNull("no chip at all, which is the interpreter-off shape", composer.language)
        Assert.assertNull(composer.held)
        Assert.assertNull("and no doubt-hold switch either", composer.doubtHold)
        Assert.assertNull(composer.reply)
    }

    /**
     * A held send carries *which* kind it is, because the two kinds want opposite taps - a doubtful
     * language may be sent as it stands and an echo may not, since sending it would send the original.
     * A surface handed one sentence would have to guess, so the type does not offer one.
     */
    @Test
    fun aHeldSendCarriesItsKindAndNotASentence() {
        val reason = UiHold.Reason(HeldSend.HoldReason.NO_KEY)
        Assert.assertEquals("an ordinary hold is the send path's own reason", reason, UiComposer(held = reason).held)
        val doubt = UiHold.Doubt(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE)
        Assert.assertEquals("item 16's doubt is carried as its kind", doubt, UiComposer(held = doubt).held)
    }
}
