package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

import org.junit.Assert
import org.junit.Test

/**
 * The rule that decides what the interface renders. It is the only place that knows about it, so
 * these tests are the contract every display site relies on: the translation, the original because
 * nothing was needed, or a blur because translation was needed and did not happen.
 */
class DisplayedBodyTest {

    /** App Finnish, study German: the interpreter is on and every rule below applies. */
    private val ON = Interpreter.of("fi", "de")
    /** The off pair, app Finnish with study Finnish: one language, and Tulkki is a plain client. */
    private val OFF = Interpreter.of("fi", "fi")

    private fun of(body: String?, translated: String?, state: Int): DisplayedBody {
        // The overwhelmingly common case: somebody else's prose that was not already classified.
        return DisplayedBody.of(body, translated, state, true, ON)
    }

    private fun ofNothingNeeded(
            body: String?, translated: String?, state: Int): DisplayedBody {
        return DisplayedBody.of(body, translated, state, false, ON)
    }

    /** The same decision with the interpreter off, for any state and either answer. */
    private fun off(
            body: String?, translated: String?, state: Int, needed: Boolean): DisplayedBody {
        return DisplayedBody.of(body, translated, state, needed, OFF)
    }

    @Test
    fun doneShowsTranslation() {
        val displayed = of("Hello world", "Hei maailma", Message.TRANSLATION_DONE)
        Assert.assertEquals(DisplayedBody.Kind.TRANSLATION, displayed.kind())
        Assert.assertEquals("Hei maailma", displayed.text())
        Assert.assertTrue(displayed.isTranslation())
    }

    @Test
    fun doneWithEmptyTranslationBlursWhenTranslationWasNeeded() {
        // A DONE state that stored nothing is a translation that did not happen, so it is covered.
        val displayed = of("Hello world", "", Message.TRANSLATION_DONE)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
    }

    @Test
    fun doneWithoutStoredTranslationBlursWhenTranslationWasNeeded() {
        val displayed = of("Hello world", null, Message.TRANSLATION_DONE)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
    }

    @Test
    fun doneWithEmptyTranslationShowsOriginalWhenNothingWasNeeded() {
        val displayed = ofNothingNeeded("Hello world", "", Message.TRANSLATION_DONE)
        Assert.assertEquals(DisplayedBody.Kind.ORIGINAL, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
    }

    @Test
    fun noneBlursWhenTranslationWasNeeded() {
        // Still queued, no key configured, or the cap is reached: all of them are "needed and did
        // not happen", and all of them look like TRANSLATION_NONE from here.
        val displayed = of("Hello world", null, Message.TRANSLATION_NONE)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
    }

    @Test
    fun noneShowsOriginalWhenNothingWasNeeded() {
        // A link, a code, a number, emoji or a bare name: there was never anything to translate.
        val displayed = ofNothingNeeded("https://example.com/x", null, Message.TRANSLATION_NONE)
        Assert.assertEquals(DisplayedBody.Kind.ORIGINAL, displayed.kind())
        Assert.assertEquals("https://example.com/x", displayed.text())
    }

    @Test
    fun sameLanguageShowsOriginal() {
        val displayed =
                of("Hei maailma", null, Message.TRANSLATION_SAME_LANGUAGE)
        Assert.assertEquals(DisplayedBody.Kind.ORIGINAL, displayed.kind())
        Assert.assertEquals("Hei maailma", displayed.text())
        Assert.assertTrue(displayed.isOriginal())
    }

    @Test
    fun sameLanguageIsDecisiveEvenWhenTheCallerSaysNeeded() {
        // The state is a classification: someone already established there was nothing to do.
        val displayed =
                DisplayedBody.of("Hei maailma", null, Message.TRANSLATION_SAME_LANGUAGE, true, ON)
        Assert.assertEquals(DisplayedBody.Kind.ORIGINAL, displayed.kind())
    }

    @Test
    fun failedBlurs() {
        val displayed = of("Hello world", null, Message.TRANSLATION_FAILED)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
        Assert.assertTrue(displayed.isBlurred())
    }

    @Test
    fun failedIsDecisiveEvenWhenTheCallerSaysNothingWasNeeded() {
        // FAILED means a translation was attempted and lost, so there is something to cover.
        val displayed =
                DisplayedBody.of("Hello world", null, Message.TRANSLATION_FAILED, false, ON)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
    }

    @Test
    fun storedTranslationOnlyShownInDoneState() {
        // A leftover translation in any other state is not current, so it is not shown.
        Assert.assertEquals(
                DisplayedBody.Kind.BLURRED,
                of("Hello world", "Hei maailma", Message.TRANSLATION_NONE).kind())
        Assert.assertEquals(
                DisplayedBody.Kind.ORIGINAL,
                of("Hello world", "Hei maailma", Message.TRANSLATION_SAME_LANGUAGE).kind())
        Assert.assertEquals(
                DisplayedBody.Kind.BLURRED,
                of("Hello world", "Hei maailma", Message.TRANSLATION_FAILED).kind())
    }

    @Test
    fun nullOriginalBecomesEmptyString() {
        Assert.assertEquals("", of(null, null, Message.TRANSLATION_NONE).text())
        Assert.assertEquals("", of(null, null, Message.TRANSLATION_DONE).text())
        Assert.assertEquals("", of(null, null, Message.TRANSLATION_FAILED).text())
        Assert.assertEquals("", of(null, null, Message.TRANSLATION_SAME_LANGUAGE).text())
    }

    @Test
    fun nullOriginalStillShowsATranslation() {
        val displayed = of(null, "Hei maailma", Message.TRANSLATION_DONE)
        Assert.assertEquals(DisplayedBody.Kind.TRANSLATION, displayed.kind())
        Assert.assertEquals("Hei maailma", displayed.text())
    }

    @Test
    fun everyStateHasANonNullKindAndText() {
        val states = intArrayOf(
            Message.TRANSLATION_NONE,
            Message.TRANSLATION_DONE,
            Message.TRANSLATION_SAME_LANGUAGE,
            Message.TRANSLATION_FAILED,
        )
        for (state in states) {
            for (needed in booleanArrayOf(true, false)) {
                for (translated in arrayOf<String?>(null, "", "Hei maailma")) {
                    val displayed =
                            DisplayedBody.of("Hello world", translated, state, needed, ON)
                    Assert.assertNotNull(displayed.kind())
                    Assert.assertNotNull(displayed.text())
                    Assert.assertNotNull(displayed.toString())
                }
                Assert.assertNotNull(DisplayedBody.of(null, null, state, needed, ON).text())
            }
        }
    }

    @Test
    fun translationIsStillShownWhenTheCallerSaysNothingWasNeeded() {
        // Nothing to relitigate: a stored translation in DONE wins.
        val displayed =
                DisplayedBody.of("Hello world", "Hei maailma", Message.TRANSLATION_DONE, false, ON)
        Assert.assertEquals(DisplayedBody.Kind.TRANSLATION, displayed.kind())
        Assert.assertEquals("Hei maailma", displayed.text())
    }

    // -- the interpreter off: a plain XMPP client --------------------------------------------------

    @Test
    fun offWithAStoredTranslationAndDoneRendersTheOriginal() {
        // The decisive case, and the one that was wrong before this clause existed: the row was
        // translated while the interpreter was on, and off it must not render that paid-for
        // rendering as the message. The clause is deliberately above the DONE check.
        val displayed =
                off("Hello world", "Hei maailma", Message.TRANSLATION_DONE, true)
        Assert.assertEquals(DisplayedBody.Kind.ORIGINAL, displayed.kind())
        Assert.assertEquals("Hello world", displayed.text())
        Assert.assertTrue(displayed.isOriginal())
        Assert.assertFalse(displayed.isTranslation())
        Assert.assertFalse(displayed.isBlurred())
    }

    @Test
    fun offNeverCoversEvenWhenTranslationWasNeededAndDidNotHappen() {
        Assert.assertEquals(
                DisplayedBody.Kind.ORIGINAL, off("Hello world", null, Message.TRANSLATION_NONE, true).kind())
        Assert.assertEquals(
                DisplayedBody.Kind.ORIGINAL, off("Hello world", null, Message.TRANSLATION_FAILED, true).kind())
        Assert.assertEquals(
                DisplayedBody.Kind.ORIGINAL, off("Hello world", "", Message.TRANSLATION_DONE, true).kind())
    }

    @Test
    fun offShowsTheOriginalInEveryState() {
        val states = intArrayOf(
            Message.TRANSLATION_NONE,
            Message.TRANSLATION_DONE,
            Message.TRANSLATION_SAME_LANGUAGE,
            Message.TRANSLATION_FAILED,
        )
        for (state in states) {
            for (needed in booleanArrayOf(true, false)) {
                for (translated in arrayOf<String?>(null, "", "Hei maailma")) {
                    val displayed = off("Hello world", translated, state, needed)
                    Assert.assertEquals("state " + state, DisplayedBody.Kind.ORIGINAL, displayed.kind())
                    Assert.assertEquals("state " + state, "Hello world", displayed.text())
                }
            }
        }
    }

    @Test
    fun offStillTurnsANullBodyIntoTheEmptyString() {
        Assert.assertEquals("", off(null, "Hei maailma", Message.TRANSLATION_DONE, true).text())
        Assert.assertEquals("", off(null, null, Message.TRANSLATION_NONE, true).text())
    }

    @Test
    fun offNeedsNoTranslation() {
        Assert.assertFalse(
                DisplayedBody.needsTranslation(
                        Message.STATUS_RECEIVED, "Hallo, wie geht es dir?", null, OFF))
        Assert.assertFalse(
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "Hallo", "Tampere", OFF))
    }

    @Test
    fun offIsNotTheSentinelPairOnly() {
        // The predicate is about either language: "none" on the study side is the same answer as
        // English on it, and the display half must not read the settings itself to find out.
        val none = Interpreter.of("fi", Interpreter.NONE)
        Assert.assertEquals(
                DisplayedBody.Kind.ORIGINAL,
                DisplayedBody.of("Hello world", "Hei maailma", Message.TRANSLATION_DONE, true, none).kind())
        Assert.assertFalse(
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "Hallo", null, none))
    }

    @Test
    fun needsTranslationIsTrueForReceivedProse() {
        Assert.assertTrue(
                DisplayedBody.needsTranslation(
                        Message.STATUS_RECEIVED, "Hallo, wie geht es dir?", null, ON))
    }

    @Test
    fun needsTranslationIsFalseForOurOwnMessages() {
        // Our own messages are never translated, so they must never be covered either.
        Assert.assertFalse(
                DisplayedBody.needsTranslation(
                        Message.STATUS_SEND, "Hallo, wie geht es dir?", null, ON))
        Assert.assertFalse(DisplayedBody.needsTranslation(Message.STATUS_UNSEND, "Hallo", null, ON))
        Assert.assertFalse(
                DisplayedBody.needsTranslation(
                        Message.STATUS_SEND_RECEIVED, "Hallo, wie geht's?", null, ON))
    }

    @Test
    fun needsTranslationIsFalseForBodiesWithNoLanguage() {
        Assert.assertFalse(
                DisplayedBody.needsTranslation(
                        Message.STATUS_RECEIVED, "https://example.com/x", null, ON))
        Assert.assertFalse(
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "+1 555 0100", null, ON))
        Assert.assertFalse(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "42", null, ON))
        Assert.assertFalse(
                DisplayedBody.needsTranslation(
                        Message.STATUS_RECEIVED, "\uD83D\uDC4D\uD83D\uDC4D", null, ON))
        Assert.assertFalse(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "", null, ON))
        Assert.assertFalse(DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, null, null, ON))
    }

    // -- what a covered body says for itself -------------------------------------------------------

    @Test
    fun aCoveredBodyWithNothingElseToSayOffersTheTap() {
        // The plain cover: the owner's way out of it is a tap, and the cover says so.
        Assert.assertEquals(DisplayedBody.Cover.TAP, DisplayedBody.cover(false, null, false))
    }

    @Test
    fun historyTheAutomaticPassRefusedStaysCoveredAndOffersTheTap() {
        // This is the case that used to be a dead end: a message the automatic pass refused is left
        // at TRANSLATION_NONE, exactly like one that is merely waiting, so it is covered - and the
        // tap, which may translate the one message the automatic pass refused, is what resolves it.
        // It is never shown raw: the display cannot tell "refused by design" from "still pending",
        // and the original is not a thing the interface shows either way.
        val displayed = of("Hello from last year", null, Message.TRANSLATION_NONE)
        Assert.assertEquals(DisplayedBody.Kind.BLURRED, displayed.kind())
        Assert.assertEquals("Hello from last year", displayed.text())
        Assert.assertEquals(DisplayedBody.Cover.TAP, DisplayedBody.cover(false, null, false))
    }

    @Test
    fun askingForAMessageShowsTranslatingRatherThanAReason() {
        // The tap is answered with "in flight", whatever failed before it.
        Assert.assertEquals(
                DisplayedBody.Cover.TRANSLATING,
                DisplayedBody.cover(true, HeldSend.HoldReason.UNREACHABLE, true))
        Assert.assertEquals(
                DisplayedBody.Cover.TRANSLATING,
                DisplayedBody.cover(true, null, false))
    }

    @Test
    fun aFailureIsOnlyShownOnTheMessageItHappenedTo() {
        // A reason is not a property of every covered bubble in the conversation.
        Assert.assertEquals(
                DisplayedBody.Cover.TAP,
                DisplayedBody.cover(false, HeldSend.HoldReason.UNREACHABLE, false))
    }

    @Test
    fun everyReasonKeepsItsOwnMeaning() {
        Assert.assertEquals(
                DisplayedBody.Cover.NO_KEY,
                DisplayedBody.cover(false, HeldSend.HoldReason.NO_KEY, true))
        Assert.assertEquals(
                DisplayedBody.Cover.CAP_REACHED,
                DisplayedBody.cover(false, HeldSend.HoldReason.CAP_REACHED, true))
        Assert.assertEquals(
                DisplayedBody.Cover.NO_CREDIT,
                DisplayedBody.cover(false, HeldSend.HoldReason.NO_CREDIT, true))
        Assert.assertEquals(
                DisplayedBody.Cover.UNREACHABLE,
                DisplayedBody.cover(false, HeldSend.HoldReason.UNREACHABLE, true))
        Assert.assertEquals(
                DisplayedBody.Cover.REJECTED_KEY,
                DisplayedBody.cover(false, HeldSend.HoldReason.REJECTED_KEY, true))
        Assert.assertEquals(
                DisplayedBody.Cover.FAILED,
                DisplayedBody.cover(false, HeldSend.HoldReason.FAILED, true))
        // An unknown conversation language is a reason a *send* is held; on a received body it can
        // only be a failure, and it must still render as something.
        Assert.assertEquals(
                DisplayedBody.Cover.FAILED,
                DisplayedBody.cover(false, HeldSend.HoldReason.UNKNOWN_LANGUAGE, true))
    }

    @Test
    fun everyCoverHasAValue() {
        for (asking in booleanArrayOf(true, false)) {
            for (mine in booleanArrayOf(true, false)) {
                for (reason in HeldSend.HoldReason.values()) {
                    Assert.assertNotNull(DisplayedBody.cover(asking, reason, mine))
                }
                Assert.assertNotNull(DisplayedBody.cover(asking, null, mine))
            }
        }
    }

    /**
     * The notification's own line, and the whole of docs/MIGRATION.md item 17's first decision: a message
     * whose translation did not happen says so there, in the cover's own words, and never in the
     * body's - so the decision must be reachable without a word of the message, and must refuse to
     * borrow a reason that belongs to another message or to a message that already has an answer.
     */
    @Test
    fun aNotificationShowsItsOwnMessagesReasonAndNothingElse() {
        // A received row whose attempt was refused: its own reason, in the cover's own words.
        Assert.assertEquals(
                DisplayedBody.Cover.FAILED,
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_FAILED,
                        Message.STATUS_RECEIVED,
                        HeldSend.HoldReason.FAILED,
                        true))
        // A message the owner tapped while there was no key: the reason the tap recorded.
        Assert.assertEquals(
                DisplayedBody.Cover.NO_KEY,
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_NONE,
                        Message.STATUS_RECEIVED,
                        HeldSend.HoldReason.NO_KEY,
                        true))
        // Somebody else's failure is not this message's reason to show.
        Assert.assertNull(
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_FAILED,
                        Message.STATUS_RECEIVED,
                        HeldSend.HoldReason.NO_KEY,
                        false))
        // A message that has an answer, or was already in the app language, is not covered at all:
        // its own line is the translation or the original.
        Assert.assertNull(
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_DONE,
                        Message.STATUS_RECEIVED,
                        HeldSend.HoldReason.FAILED,
                        true))
        Assert.assertNull(
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_SAME_LANGUAGE,
                        Message.STATUS_RECEIVED,
                        HeldSend.HoldReason.FAILED,
                        true))
        // Our own row is never the check's business.
        Assert.assertNull(
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_FAILED,
                        Message.STATUS_SEND,
                        HeldSend.HoldReason.FAILED,
                        true))
        // Nothing recorded yet: the renderer's plain cover stands, and this adds no second wording
        // for the same state.
        Assert.assertNull(
                DisplayedBody.notificationCover(
                        Message.TRANSLATION_NONE,
                        Message.STATUS_RECEIVED,
                        null,
                        false))
    }
}
