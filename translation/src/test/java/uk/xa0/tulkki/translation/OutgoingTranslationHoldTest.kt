package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * {@link OutgoingTranslation#sendVerdict} and {@link OutgoingTranslation#isHeldForTranslation} - the
 * send path's refusal, and the question the unsent bubble's tap asks.
 *
 * <p>The owner's rule is that the retry is theirs: a held message is not re-attempted because a socket
 * came back, because the conversation was reopened or because the cap was raised. What made that
 * true in the code is that {@code holdBack} - the one choke point upstream's own sends pass, and so
 * the reconnect's way in - has no answer that buys anything, and this is that answer written as a
 * value: {@link OutgoingTranslation.SendVerdict} has three cases and none of them is "translate it
 * now". The cases below pin which of the three a reconnect gets for each shape of row, and pin the
 * other half of the rule with it: a row that needs no translation is still not held (a quick reply
 * into a room that already speaks the app language sends straight away), and a row whose translation
 * is already there is still sent, without being bought again.
 *
 * <p>The decision is a pure function of strings and constants on purpose. {@code Message.getBody()}
 * cannot run off a device - it goes through {@code android.util.Pair}, which the unit-test runtime
 * stubs out - so the caller's own text is a parameter here, exactly as it is in
 * {@code TranslationHooks.candidate}, and a decision a test cannot call is a decision a test cannot
 * pin. {@link HeldSendTest} covers the reuse rules ({@code alreadyDecided}, {@code mayReuse}) that
 * {@code sendVerdict} delegates to.
 */
class OutgoingTranslationHoldTest {

    /** The app language: what the owner writes in, and what everything received is rendered into. */
    private val APP = "fi"

    /** The conversation's language: what the owner's message has to be translated into. */
    private val ROOM = "en"

    private val FINNISH =
            "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    /** The interpreter on: app Finnish, study German, so neither side names English or NONE. */
    private val ON = Interpreter.of("fi", "de")

    /**
     * The interpreter off: app Finnish with study Finnish, the same language on both sides. This is
     * the mode the last case here is about.
     */
    private val OFF = Interpreter.of("fi", "fi")

    /**
     * The send path's answer for a row, with the facts that are the message's own.
     *
     * @param draft the owner's own text, a reply's quote already removed
     * @param state one of the {@code Message.TRANSLATION_*} constants
     * @param translatedBody the stored translation, if there is one
     * @param storedTarget the language that stored translation is for ({@code translation_lang})
     * @param room the conversation's language, which is the send target
     */
    private fun verdict(
            draft: String?,
            state: Int,
            translatedBody: String?,
            storedTarget: String?,
            room: String?): OutgoingTranslation.SendVerdict {
        return OutgoingTranslation.sendVerdict(
                draft, state, translatedBody, storedTarget, APP, room, null, ON)
    }

    private fun tapOffered(
            status: Int,
            draft: String?,
            state: Int,
            translatedBody: String?,
            storedTarget: String?,
            room: String?): Boolean {
        return OutgoingTranslation.isHeldForTranslation(
                status, draft, state, translatedBody, storedTarget, APP, room, null, ON)
    }

    /**
     * The regression this whole change exists for. Upstream re-offers every queued
     * {@code STATUS_WAITING} row to the send path when the account reconnects, and that path now ends
     * at {@link OutgoingTranslation.SendVerdict#HOLD}: it is written into the conversation and left
     * waiting, and no request is made. The old behaviour - translate it and send it - is not reachable
     * from this answer at all, because the enum has no case for it.
     */
    @Test
    fun aReconnectOfferingAMessageWithNoTranslationHoldsItAndBuysNothing() {
        Assert.assertEquals(
                "a held row must be refused, not translated-and-sent, on the reconnect path",
                OutgoingTranslation.SendVerdict.HOLD,
                verdict(FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))
        Assert.assertTrue(
                "and it is exactly the row the bubble offers its tap for",
                tapOffered(
                        Message.STATUS_WAITING,
                        FINNISH,
                        Message.TRANSLATION_NONE,
                        null,
                        null,
                        ROOM))
    }

    /**
     * The other half of "nothing is bought again": a row whose translation already landed for this
     * very language goes out, and {@code HeldSend.alreadyDecided} is why it costs nothing. This is
     * also the shape a message has when the translation succeeded and the send did not - it must
     * still leave.
     */
    @Test
    fun aMessageWhoseTranslationAlreadyLandedIsSentAndNotBoughtAgain() {
        Assert.assertEquals(
                "the translation is there for this language: send it, do not buy a second one",
                OutgoingTranslation.SendVerdict.SEND,
                verdict(FINNISH, Message.TRANSLATION_DONE, FINNISH, ROOM, ROOM))
        Assert.assertFalse(
                "there is nothing left to ask the owner to retry",
                tapOffered(
                        Message.STATUS_WAITING,
                        FINNISH,
                        Message.TRANSLATION_DONE,
                        FINNISH,
                        ROOM,
                        ROOM))
    }

    /**
     * A stored translation for a language the conversation no longer speaks is not a translation of
     * this message any more, so it is held rather than put on the wire in the wrong language. Pinned
     * because the "reuse" check has to compare the target, not merely find a stored pair.
     */
    @Test
    fun aTranslationForAnotherLanguageIsHeldRatherThanSentStale() {
        Assert.assertEquals(
                "a translation for German cannot go to an English room",
                OutgoingTranslation.SendVerdict.HOLD,
                verdict(FINNISH, Message.TRANSLATION_DONE, FINNISH, "de", ROOM))
    }

    /**
     * "Only a message that needs translating is held." Nothing needs translating in a room that
     * already speaks the app language - the case a notification quick reply makes - so the hold path
     * lets it out and records that it needed none, rather than freezing it until the owner taps.
     */
    @Test
    fun aRoomThatAlreadySpeaksTheAppLanguageIsNotHeld() {
        Assert.assertEquals(
                "there is nothing to translate from, so the message is not held",
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                verdict(FINNISH, Message.TRANSLATION_NONE, null, null, APP))
        Assert.assertFalse(
                "and a message that is not held is not offered a retry",
                tapOffered(Message.STATUS_WAITING, FINNISH, Message.TRANSLATION_NONE, null, null, APP))
    }

    /**
     * Text with no language at all is not "another language" and must never be a reason to hold a
     * send: a link, and the room's own bare name. The name is passed as the conversation's name, so
     * this is the whole-body rule and not "one capitalised word is a name".
     */
    @Test
    fun aBodyWithNoLanguageInItIsNotHeld() {
        Assert.assertEquals(
                "a link has no language to translate",
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                verdict("https://example.org/some/page?a=1", Message.TRANSLATION_NONE, null, null, ROOM))
        Assert.assertEquals(
                "a body that is the room's own name is a bare name, not prose",
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                OutgoingTranslation.sendVerdict(
                        "Matti", Message.TRANSLATION_NONE, null, null, APP, ROOM, "Matti", ON))
        Assert.assertEquals(
                "and a ping is the same kind of thing",
                OutgoingTranslation.SendVerdict.SEND_AND_MARK,
                OutgoingTranslation.sendVerdict(
                        "Matti: ", Message.TRANSLATION_NONE, null, null, APP, ROOM, "Matti", ON))
    }

    /**
     * A conversation with no language yet is held: there is nothing to translate into, and the
     * composer's bar names it. Pinned here because the reconnect must not decide to send it "because
     * it cannot translate it anyway" - that would be the untranslated send the whole rule forbids.
     */
    @Test
    fun anUnknownConversationLanguageIsHeld() {
        Assert.assertEquals(
                "no target language means nothing to translate into, so it waits",
                OutgoingTranslation.SendVerdict.HOLD,
                verdict(FINNISH, Message.TRANSLATION_NONE, null, null, null))
        Assert.assertTrue(
                "and the tap is how the owner gets it out once the language is known",
                tapOffered(Message.STATUS_WAITING, FINNISH, Message.TRANSLATION_NONE, null, null, null))
    }

    /**
     * The tap is only for a message that is still waiting. A message the owner has already sent is
     * history: it must not be re-sent by a stray tap, and it has no translation to wait for. The
     * unsent status is what tells the two apart, so both non-waiting outgoing statuses are pinned.
     */
    @Test
    fun anAlreadySentMessageIsNotOfferedTheTap() {
        Assert.assertFalse(
                "a sent message is not waiting on anything",
                tapOffered(Message.STATUS_SEND, FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))
        Assert.assertFalse(
                "nor is one whose send failed: upstream's own resend puts it back in the queue, and"
                        + " the hold path re-holds it -- the tap appears then, not before",
                tapOffered(
                        Message.STATUS_SEND_FAILED, FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))
        Assert.assertFalse(
                "and a received message is never the owner's to send",
                tapOffered(
                        Message.STATUS_RECEIVED, FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))
    }

    /**
     * The premise the adapter's cover guard stands on, pinned so that guard cannot be deleted as
     * redundant. {@code DisplayedBody}'s FAILED branch is decisive and never asks whose message it is,
     * so an outgoing message whose translation failed comes back blurred like anybody else's. The
     * adapter is the half that knows the difference: it leaves the owner's own unsent words readable -
     * they are not an original to conceal, and they are the bubble the retry tap lives on - and puts
     * the reason in the composer's bar instead.
     */
    @Test
    fun aFailedTranslationIsBlurredEvenWhenTheBodyIsTheOwnersOwn() {
        Assert.assertTrue(
                "direction-blind by design: the adapter is what keeps an outgoing body off the cover",
                DisplayedBody.of(
                                "Moi! Mennäänkö huomenna kahville?",
                                null,
                                Message.TRANSLATION_FAILED,
                                false,
                                ON)
                        .isBlurred())
    }

    /**
     * A reply is judged on the owner's own text. The quote it carries is somebody else's message: it
     * stays on the wire verbatim, in the language it arrived in, and it is never billed for. The
     * caller hands this decision {@code composed.translatable()} and not the composed body, so the
     * pin is that the text it is handed is exactly the owner's own - the German quote is not in it.
     */
    @Test
    fun aReplyIsJudgedOnTheOwnersOwnTextNotOnTheQuoteItCarries() {
        val quote = "> Hallo, treffen wir uns morgen zum Kaffee?\n\n"
        val composed =
                ComposedBody.of(quote + FINNISH, "0", quote.length.toString())
        Assert.assertTrue("the premise: the quote is carried, not translated", composed.hasCarried())
        Assert.assertEquals(
                "the caller is handed the owner's own text and nothing of the quote",
                FINNISH,
                composed.translatable())
        Assert.assertEquals(
                "and it is that text which has to be translated for the English room",
                OutgoingTranslation.SendVerdict.HOLD,
                verdict(composed.translatable(), Message.TRANSLATION_NONE, null, null, ROOM))
    }

    // --- the interpreter off: the send path refuses nothing ---------------------------------------

    /**
     * Off, {@link OutgoingTranslation#sendVerdict} answers {@code SEND} for every row that is
     * otherwise {@code HOLD} - including a row already in the database at
     * {@code STATUS_WAITING}, which is <em>released as the owner typed it</em> rather than deleted
     * when upstream re-offers it, and the tap is not offered because nothing is waiting.
     *
     * <p>{@code SEND} and not {@code SEND_AND_MARK}: the mark is state written for a rule that is not
     * running, and nothing may be written while off.
     */
    @Test
    fun theOffInterpreterSendsEveryRowItWouldOtherwiseHold() {
        // The premise, with the interpreter on: a Finnish draft into a German room is held and its
        // unsent bubble is offered the owner's tap.
        Assert.assertEquals(
                "the premise: on, this row waits for its translation",
                OutgoingTranslation.SendVerdict.HOLD,
                verdict(FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))
        Assert.assertTrue(
                "the premise: on, the tap is offered",
                tapOffered(Message.STATUS_WAITING, FINNISH, Message.TRANSLATION_NONE, null, null, ROOM))

        Assert.assertEquals(
                "off: the same row sends as typed",
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_NONE, null, null, APP, ROOM, null, OFF))
        Assert.assertEquals(
                "off: a translation stored for another language is not held either - the row goes out",
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_DONE, FINNISH, "de", APP, ROOM, null, OFF))
        Assert.assertEquals(
                "off: a row whose translation failed is not held",
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_FAILED, null, "de", APP, ROOM, null, OFF))
        Assert.assertEquals(
                "off: not even with no conversation language to translate into",
                OutgoingTranslation.SendVerdict.SEND,
                OutgoingTranslation.sendVerdict(
                        FINNISH, Message.TRANSLATION_NONE, null, null, APP, null, null, OFF))
        Assert.assertFalse(
                "off: no row is held, so no tap is ever installed",
                OutgoingTranslation.isHeldForTranslation(
                        Message.STATUS_WAITING,
                        FINNISH,
                        Message.TRANSLATION_NONE,
                        null,
                        null,
                        APP,
                        ROOM,
                        null,
                        OFF))
        Assert.assertFalse(
                "off: and not with no language known either",
                OutgoingTranslation.isHeldForTranslation(
                        Message.STATUS_WAITING,
                        FINNISH,
                        Message.TRANSLATION_NONE,
                        null,
                        null,
                        APP,
                        null,
                        null,
                        OFF))
    }
}
