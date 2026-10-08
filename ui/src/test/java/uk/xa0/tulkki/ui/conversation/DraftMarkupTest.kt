package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert
import org.junit.Test

/**
 * `insertFormatting`'s rule, as a JVM cell: what a marker does to a draft, and where the caret lands.
 *
 * <p>These are the Java's own two branches, pinned from `ConversationFragment.insertFormatting` - a
 * selection becomes `marker + selected + marker`, and **nothing selected becomes one marker** at the
 * caret, the toggle a second tap closes. The rule is text and offsets, so nothing here needs a Compose
 * runtime; the boundaries are chosen where the Java clamped (`0..length`) and where the caret is easy
 * to get wrong (the draft's own end).
 */
class DraftMarkupTest {

    @Test
    fun nothingSelectedInsertsASingleMarker() {
        val edit = DraftMarkup.format("", 0, 0, MarkupKind.BOLD)
        Assert.assertEquals("*", edit.text)
        Assert.assertEquals("the caret follows the marker it just wrote", 1, edit.cursor)
    }

    @Test
    fun aCollapsedCaretSplicesOneMarkerAtItsOffset() {
        val edit = DraftMarkup.format("hi", 1, 1, MarkupKind.ITALIC)
        Assert.assertEquals("h_i", edit.text)
        Assert.assertEquals(2, edit.cursor)
    }

    @Test
    fun aSelectionIsWrappedInTheSameCharacter() {
        val edit = DraftMarkup.format("hello", 0, 5, MarkupKind.BOLD)
        Assert.assertEquals("*hello*", edit.text)
        Assert.assertEquals("the caret is past the closing marker", 7, edit.cursor)
    }

    @Test
    fun aBackwardsSelectionIsTheSameEditAsTheForwardsOne() {
        Assert.assertEquals(
            DraftMarkup.format("hello", 0, 5, MarkupKind.MONOSPACE),
            DraftMarkup.format("hello", 5, 0, MarkupKind.MONOSPACE),
        )
    }

    @Test
    fun aSelectionAtTheEndOfTheDraftWrapsWithoutAnIndexError() {
        val edit = DraftMarkup.format("ab", 2, 2, MarkupKind.STRIKETHROUGH)
        Assert.assertEquals("ab~", edit.text)
        Assert.assertEquals(3, edit.cursor)
    }

    @Test
    fun aStaleSelectionIsClampedRatherThanThrown() {
        // The Java clamped into `0..length` before it touched the Editable; a stale offset must not
        // become an exception here either.
        Assert.assertEquals("ab_", DraftMarkup.format("ab", 2, 9, MarkupKind.ITALIC).text)
        Assert.assertEquals("_ab", DraftMarkup.format("ab", -3, 0, MarkupKind.ITALIC).text)
    }

    @Test
    fun eachKindCarriesTheJavasOwnCharacter() {
        Assert.assertEquals('*', MarkupKind.BOLD.marker)
        Assert.assertEquals('_', MarkupKind.ITALIC.marker)
        Assert.assertEquals('`', MarkupKind.MONOSPACE.marker)
        Assert.assertEquals('~', MarkupKind.STRIKETHROUGH.marker)
    }

    /**
     * `/me` is `meCommand`'s own head insert, and **its double space is preserved** rather than
     * tidied: the Java spliced `Message.ME_COMMAND + " "` and `hasMeCommand()` trims before it reads,
     * so the extra space is inert on the wire. A port that "fixed" it would be changing what the
     * owner's message body has always said.
     *
     * The caret sits **after the whole run the Java inserted**, not between the command and its
     * second space: `insert(0, Message.ME_COMMAND + " ")` is one insertion of five characters, and an
     * `Editable.insert` leaves the caret at the end of the text it wrote - the platform says so in as
     * many words (`BaseInputConnection.replaceTextInternal`: "Replace (or insert) to the cursor ...
     * will position the cursor to the end of the new replaced/inserted text"), and this app relies on
     * it (the emoji picker inserts repeatedly at `getSelectionStart()`; `highlightInConference` reads
     * `getSelectionStart() + 1` straight after an insert). Offset 4 is `Message.ME_COMMAND.length`,
     * the boundary between the command and that extra space, and no `insert` can leave the caret
     * inside the run it wrote.
     */
    @Test
    fun theMeVerbGoesToTheHeadAndKeepsItsOwnSpacing() {
        val value = DraftMarkup.me(textField(""))
        Assert.assertEquals("/me  ", value.text)
        Assert.assertEquals("the caret is at the end of the run the Java inserted", 5, value.selection.start)
    }

    /** A non-empty draft is prefixed too: the button is disabled then, but the rule is total. */
    @Test
    fun theMeVerbPrefixedANonEmptyDraft() {
        Assert.assertEquals("/me  Hei!", DraftMarkup.me(textField("Hei!")).text)
    }

    /**
     * `insertQuote`'s two branches: at the head of an empty-ish draft the marker opens the draft, and
     * anywhere else it opens a line of its own at the caret. The Java read `getSelectionStart()` only
     * when the selection was collapsed and quoted at 0 otherwise, which is what the second case pins.
     */
    @Test
    fun theQuoteVerbOpensALineAtTheCaretOrAtTheHead() {
        val atHead = DraftMarkup.quoteLine(textField("", 0))
        Assert.assertEquals("> ", atHead.text)
        Assert.assertEquals(2, atHead.selection.start)

        val collapsedMidText = DraftMarkup.quoteLine(textField("abc", 1))
        Assert.assertEquals("a\n> bc", collapsedMidText.text)
        Assert.assertEquals("the caret follows the marker it just wrote", 4, collapsedMidText.selection.start)

        val selected = DraftMarkup.quoteLine(textField("abc", 1, 3))
        Assert.assertEquals("a selection quotes at the head, as the Java's pos = 0 branch did", "> abc", selected.text)
    }

    private fun textField(
        text: String,
        start: Int = text.length,
        end: Int = start,
    ) = TextFieldValue(text = text, selection = TextRange(start, end))
}
