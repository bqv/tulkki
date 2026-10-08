package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/**
 * What is held, what may go out, and what is never bought twice.
 *
 * <p>The row model these tests pin down is: while held, {@code body} is the owner's draft; after the
 * swap, {@code body} is the wire text and {@code translated_body} is the owner's draft again.
 *
 * <p>Every case but the off ones runs with the interpreter on, through the two local shorthand
 * helpers below - test conveniences, never production defaults: {@code HeldSend} requires the
 * interpreter as its last argument so a real call site cannot compile without deciding.
 */
class HeldSendTest {

    private val FINNISH = "Moi! Mennäänkö huomenna kahville?"
    private val GERMAN = "Hallo, treffen wir uns morgen zum Kaffee?"
    private val LINK = "https://example.org/some/page"

    /** The interpreter on: app Finnish, study German, so neither side names English or NONE. */
    private val ON = Interpreter.of("fi", "de")

    /** The interpreter off: app Finnish with study Finnish, one language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    /** {@link HeldSend#needsTranslation} while the interpreter is on. */
    private fun needsTranslation(
            draft: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?): Boolean {
        return HeldSend.needsTranslation(
                draft, appLanguage, conversationLanguage, conversationName, ON)
    }

    /** {@link OutgoingTranslation#sendVerdict} while the interpreter is on. */
    private fun sendVerdict(
            draft: String?,
            translationState: Int,
            translatedBody: String?,
            storedTarget: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?): OutgoingTranslation.SendVerdict {
        return OutgoingTranslation.sendVerdict(
                draft,
                translationState,
                translatedBody,
                storedTarget,
                appLanguage,
                conversationLanguage,
                conversationName,
                ON)
    }

    // --- what needs translating -------------------------------------------------------------

    @Test
    fun appLanguageInAForeignRoomNeedsTranslating() {
        Assert.assertTrue(needsTranslation(FINNISH, "fi", "de", null))
    }

    @Test
    fun appLanguageInAnAppLanguageRoomNeedsNothing() {
        Assert.assertFalse(needsTranslation(FINNISH, "fi", "fi", null))
    }

    @Test
    fun aLinkNeedsNothingEvenInAForeignRoom() {
        Assert.assertFalse(needsTranslation(LINK, "fi", "de", null))
    }

    @Test
    fun anUnknownConversationLanguageHoldsLanguageButNotSignal() {
        Assert.assertTrue(needsTranslation(FINNISH, "fi", null, null))
        Assert.assertTrue(needsTranslation(FINNISH, "fi", TextLanguage.UNKNOWN, null))
        Assert.assertFalse(needsTranslation(LINK, "fi", null, null))
        Assert.assertFalse(needsTranslation("482913", "fi", null, null))
    }

    @Test
    fun aShortWordIsStillHeldRatherThanSentRaw() {
        // The detector has no opinion about "ok", but the room may still not read it, so it is
        // translated like anything else. It is never refused, though - see ComposerGateTest.
        Assert.assertTrue(needsTranslation("ok", "fi", "de", null))
        Assert.assertFalse(needsTranslation("ok", "fi", "fi", null))
    }

    // --- may it go out, as the send path's own decision answers it ---------------------------------
    //
    // These cells used to call `HeldSend.maySend`, a pure forwarding seam with no production caller;
    // S4-16 deleted it and re-expressed what was worth keeping against `OutgoingTranslation
    // .sendVerdict`, which is the decision the send path actually makes. The plainest case - a draft
    // the on-mode rule holds for translation - now lives only in `OutgoingTranslationHoldTest`,
    // because nothing here could say it better. What stays is the row shapes that test does not walk:
    // a failed translation that still names a stored target, a room that already speaks the app
    // language with and without the same-language mark, that mark going stale when the room moves,
    // and the two swapped rows whose draft has to be read back out of `translated_body`.

    @Test
    fun aFailedTranslationStaysHeld() {
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.HOLD,
                sendVerdict(FINNISH, Message.TRANSLATION_FAILED, null, "de", "fi", "de", null))
    }

    @Test
    fun noLanguageNeedsNoTranslationSoItGoesOut() {
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                sendVerdict(LINK, Message.TRANSLATION_NONE, null, null, "fi", "de", null))
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                sendVerdict("482913", Message.TRANSLATION_NONE, null, null, "fi", "de", null))
    }

    @Test
    fun anAppLanguageRoomSendsWithoutTranslation() {
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                sendVerdict(FINNISH, Message.TRANSLATION_NONE, null, "fi", "fi", "fi", null))
        // And a row already marked as needing none is sent outright: the mark was decided here, so
        // it is not marked a second time.
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.SEND,
                sendVerdict(FINNISH, Message.TRANSLATION_SAME_LANGUAGE, null, "fi", "fi", "fi", null))
    }

    @Test
    fun aRoomThatChangedLanguageInvalidatesTheSameLanguageMark() {
        // The conversation was marked as speaking Finnish; the owner has since set it to German, so
        // the mark is worthless, the draft has to be translated after all, and the row stays held.
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.HOLD,
                sendVerdict(FINNISH, Message.TRANSLATION_SAME_LANGUAGE, null, "fi", "fi", "de", null))
    }

    // --- never buy the same sentence twice --------------------------------------------------

    @Test
    fun aSuccessfulTranslationIsSentAgainWithoutBuyingIt() {
        // After the swap: the body is the German wire text, translated_body is the Finnish draft.
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.SEND,
                sendVerdict(GERMAN, Message.TRANSLATION_DONE, FINNISH, "de", "fi", "de", null))
        Assert.assertTrue(
                HeldSend.mayReuse(FINNISH, Message.TRANSLATION_DONE, "de", "de"))
    }

    @Test
    fun aStoredTranslationForAnotherLanguageIsNotReused() {
        // The conversation moved to English: the stored German translation would send the wrong
        // language, so it has to be bought again - and judged on the draft the swap left behind, not
        // on the wire text sitting in the body.
        Assert.assertFalse(HeldSend.mayReuse(FINNISH, Message.TRANSLATION_DONE, "de", "en"))
        Assert.assertEquals(
                OutgoingTranslation.SendVerdict.HOLD,
                sendVerdict(GERMAN, Message.TRANSLATION_DONE, FINNISH, "de", "fi", "en", null))
        Assert.assertEquals(
                FINNISH, HeldSend.draftOf(GERMAN, Message.TRANSLATION_DONE, FINNISH))
    }

    @Test
    fun anEmptyStoredTranslationIsNotATranslation() {
        Assert.assertFalse(HeldSend.mayReuse("", Message.TRANSLATION_DONE, "de", "de"))
        Assert.assertFalse(HeldSend.mayReuse(null, Message.TRANSLATION_DONE, "de", "de"))
        Assert.assertFalse(HeldSend.mayReuse("  ", Message.TRANSLATION_DONE, "de", "de"))
    }

    @Test
    fun aStoredTranslationWithoutATargetIsNotReused() {
        Assert.assertFalse(HeldSend.mayReuse(FINNISH, Message.TRANSLATION_DONE, null, "de"))
        Assert.assertFalse(
                HeldSend.mayReuse(FINNISH, Message.TRANSLATION_DONE, TextLanguage.UNKNOWN, "de"))
    }

    @Test
    fun onlyTheDoneStateCountsAsTranslated() {
        Assert.assertFalse(HeldSend.mayReuse(FINNISH, Message.TRANSLATION_NONE, "de", "de"))
        Assert.assertFalse(HeldSend.mayReuse(FINNISH, Message.TRANSLATION_FAILED, "de", "de"))
        Assert.assertFalse(HeldSend.mayReuse(FINNISH, Message.TRANSLATION_SAME_LANGUAGE, "de", "de"))
    }

    @Test
    fun theDraftIsTheBodyUntilTheSwapAndTheStoredTranslationAfterIt() {
        Assert.assertEquals(FINNISH, HeldSend.draftOf(FINNISH, Message.TRANSLATION_NONE, null))
        Assert.assertEquals(FINNISH, HeldSend.draftOf(FINNISH, Message.TRANSLATION_FAILED, null))
        Assert.assertEquals(GERMAN, HeldSend.draftOf(GERMAN, Message.TRANSLATION_NONE, null))
        Assert.assertEquals(FINNISH, HeldSend.draftOf(GERMAN, Message.TRANSLATION_DONE, FINNISH))
    }

    // --- the cheap check the send path itself makes -----------------------------------------

    @Test
    fun aTranslatedRowIsAlreadyDecidedAndGoesOut() {
        Assert.assertTrue(HeldSend.alreadyDecided(FINNISH, Message.TRANSLATION_DONE, "de", "de"))
    }

    @Test
    fun aRowFoundToNeedNoTranslationIsAlreadyDecided() {
        Assert.assertTrue(
                HeldSend.alreadyDecided(null, Message.TRANSLATION_SAME_LANGUAGE, "fi", "fi"))
        // Even with no conversation language at all: a link was let through once, let it through
        // again rather than holding it forever.
        Assert.assertTrue(
                HeldSend.alreadyDecided(null, Message.TRANSLATION_SAME_LANGUAGE, null, null))
    }

    @Test
    fun anUndecidedRowIsNotAlreadyDecided() {
        Assert.assertFalse(HeldSend.alreadyDecided(null, Message.TRANSLATION_NONE, null, "de"))
        Assert.assertFalse(
                HeldSend.alreadyDecided(null, Message.TRANSLATION_FAILED, null, "de"))
    }

    @Test
    fun aChangedConversationLanguageUndoesAnEarlierDecision() {
        Assert.assertFalse(
                HeldSend.alreadyDecided(null, Message.TRANSLATION_SAME_LANGUAGE, "fi", "de"))
        Assert.assertFalse(
                HeldSend.alreadyDecided(FINNISH, Message.TRANSLATION_DONE, "de", "en"))
    }

    // --- what the composer says -------------------------------------------------------------

    @Test
    fun noKeyIsNamedBeforeAnythingElse() {
        Assert.assertEquals(
                HeldSend.HoldReason.NO_KEY, HeldSend.localReason(false, true, "de"))
        Assert.assertEquals(
                HeldSend.HoldReason.NO_KEY, HeldSend.localReason(false, false, null))
    }

    @Test
    fun anUnknownLanguageIsNamedBeforeTheCap() {
        Assert.assertEquals(
                HeldSend.HoldReason.UNKNOWN_LANGUAGE, HeldSend.localReason(true, true, null))
    }

    @Test
    fun aReachedCapIsNamedWhenTheLanguageIsKnown() {
        Assert.assertEquals(
                HeldSend.HoldReason.CAP_REACHED, HeldSend.localReason(true, true, "de"))
    }

    @Test
    fun nothingToSayWhenTheCallMayBeMade() {
        Assert.assertNull(HeldSend.localReason(true, false, "de"))
    }

    @Test
    fun aRetryableFailureIsTheNetwork() {
        Assert.assertEquals(
                HeldSend.HoldReason.UNREACHABLE,
                HeldSend.failureReason(true, "deepseek is unreachable: timeout"))
    }

    @Test
    fun aRejectedBalanceIsNotRetryable() {
        Assert.assertEquals(
                HeldSend.HoldReason.NO_CREDIT,
                HeldSend.failureReason(
                        false, "deepseek returned 402: {\"error\":{\"message\":\"Insufficient Balance\"}}"))
    }

    @Test
    fun aRejectedKeyIsNotTheSameAsAnUnreadableAnswer() {
        // 401 and 403 are the key itself, and the owner can do something about that; retrying will
        // not help, and "DeepSeek answered something we did not like" would hide the one useful fact.
        Assert.assertEquals(
                HeldSend.HoldReason.REJECTED_KEY,
                HeldSend.failureReason(false, "deepseek returned 401: invalid api key"))
        Assert.assertEquals(
                HeldSend.HoldReason.REJECTED_KEY,
                HeldSend.failureReason(false, "deepseek returned 403: forbidden"))
    }

    @Test
    fun anythingElseIsJustAFailure() {
        Assert.assertEquals(
                HeldSend.HoldReason.FAILED,
                HeldSend.failureReason(false, "the answer was not JSON: {"))
        Assert.assertEquals(
                HeldSend.HoldReason.FAILED,
                HeldSend.failureReason(false, "deepseek returned 400: bad request"))
        Assert.assertEquals(
                HeldSend.HoldReason.FAILED, HeldSend.failureReason(false, null))
    }

    // --- the interpreter off: nothing is held and nothing is refused -------------------------

    /**
     * Off, the outbound decision is {@code SEND} for a draft the on-mode rule holds for translation,
     * and for a confidently foreign one too - the two shapes the gate distinguishes are one answer to
     * a plain client.
     */
    @Test
    fun theOffInterpreterHoldsNoDraft() {
        Assert.assertTrue(
                "the premise: the on-mode rule holds this for translation",
                needsTranslation(FINNISH, "fi", "de", null))
        Assert.assertFalse(
                "off: nothing needs translating",
                HeldSend.needsTranslation(FINNISH, "fi", "de", null, OFF))
        Assert.assertEquals(
                "off: a draft in the app language sends",
                HeldSend.Action.SEND,
                HeldSend.decide(FINNISH, "fi", "de", null, HeldSend.Source.ELSEWHERE, OFF))
        Assert.assertEquals(
                "off: and so does one confidently in another language",
                HeldSend.Action.SEND,
                HeldSend.decide(GERMAN, "fi", "de", null, HeldSend.Source.ELSEWHERE, OFF))
    }

    /**
     * The quick reply is the one path with no composer in front of it, and off it is not refused
     * either: the reply sends as the owner typed it, so nothing is put into the conversation's draft.
     * That is {@code OutgoingTranslation.refuseQuickReply} answering false before it calls
     * {@code keepAsDraft} - the pure half of it is the {@code SEND} pinned here, and the other half is
     * that {@link HeldSend#mergeDraft} is never reached in that state.
     */
    @Test
    fun theOffInterpreterSendsAForeignQuickReplyAsTyped() {
        Assert.assertEquals(
                "the premise: on, this is refused and drafted",
                HeldSend.Action.DRAFT_NOT_SENT,
                HeldSend.decide(GERMAN, "fi", "de", null, HeldSend.Source.QUICK_REPLY, ON))
        Assert.assertEquals(
                "off: the same quick reply sends as typed",
                HeldSend.Action.SEND,
                HeldSend.decide(GERMAN, "fi", "de", null, HeldSend.Source.QUICK_REPLY, OFF))
        Assert.assertEquals(
                "off: an English quick reply too",
                HeldSend.Action.SEND,
                HeldSend.decide(
                        "Hey, are we still meeting tomorrow?",
                        "fi",
                        "fi",
                        null,
                        HeldSend.Source.QUICK_REPLY,
                        OFF))
    }
}
