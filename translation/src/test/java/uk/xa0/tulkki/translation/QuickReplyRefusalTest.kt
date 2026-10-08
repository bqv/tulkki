package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The notification quick reply's refusal - {@link HeldSend#decide} and {@link HeldSend#mergeDraft}.
 *
 * <p>"Writing in anything but the app language does not send" had one hole: the quick reply, which
 * reaches the send path with no composer in front of it, and {@code holdBack}, which cannot refuse it
 * because "confidently another language" is not "needs translating". The owner's decision is to refuse
 * it and keep the words as the conversation's draft, so this class pins three things:
 *
 * <ul>
 *   <li>the refusal is the quick reply's alone - the very same draft through any other source keeps
 *       the meaning it always had and is sent as typed, which is what leaves shares alone;
 *   <li>it is a <em>refusal</em>, not a translation: nothing goes down the translation path for it,
 *       so no call is ever made and no second copy of the text exists;
 *   <li>the words are kept - {@link HeldSend#mergeDraft} puts them with any draft already waiting
 *       rather than replacing it.
 * </ul>
 *
 * <p>The pure decision is what is tested here. The notification and service plumbing around it -
 * {@code OutgoingTranslation.refuseQuickReply}, {@code XmppConnectionService.directReply},
 * {@code Conversation.setNextMessage} - is Android- and service-shaped and is <strong>not</strong>
 * covered by these tests; its evidence is the code and a green build, not a unit test.
 */
class QuickReplyRefusalTest {

    /** The app language: what the owner writes in, and what everything received is rendered into. */
    private val APP = "fi"

    private val FINNISH =
            "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."
    private val GERMAN =
            "Hallo, treffen wir uns morgen zum Kaffee? Ich habe Neuigkeiten."
    private val ENGLISH =
            "Hey, are we still meeting tomorrow for coffee? I have news."
    private val LINK = "https://example.org/some/page?a=1"

    /** The interpreter on: app Finnish, study German, so neither side names English or NONE. */
    private val ON = Interpreter.of("fi", "de")

    /** The quick reply's answer: the owner's words, typed on the notification. */
    private fun quickReply(draft: String, room: String?): HeldSend.Action {
        return HeldSend.decide(draft, APP, room, null, HeldSend.Source.QUICK_REPLY, ON)
    }

    /** Anywhere else the pipeline is reached: a share, a retry, a reconnect. */
    private fun elsewhere(draft: String, room: String?): HeldSend.Action {
        return HeldSend.decide(draft, APP, room, null, HeldSend.Source.ELSEWHERE, ON)
    }

    // --- what still sends ---------------------------------------------------------------------

    @Test
    fun theAppLanguageInAnAppLanguageRoomSends() {
        Assert.assertEquals(HeldSend.Action.SEND, quickReply(FINNISH, APP))
    }

    @Test
    fun theAppLanguageInAForeignRoomGoesDownTheTranslationPath() {
        // A Finnish quick reply to a German room is not refused: it is the app language, so it is
        // held and translated like any composer send. Refusing it would be refusing the owner's own
        // language.
        Assert.assertEquals(HeldSend.Action.TRANSLATE, quickReply(FINNISH, "de"))
    }

    @Test
    fun aPingSendsInEitherDirection() {
        Assert.assertEquals(HeldSend.Action.SEND, quickReply("Matti: ", "de"))
        Assert.assertEquals(HeldSend.Action.SEND, quickReply("Matti:", "de"))
    }

    @Test
    fun aLinkSends() {
        Assert.assertEquals(HeldSend.Action.SEND, quickReply(LINK, "de"))
    }

    @Test
    fun aNumberAndAnEmojiSend() {
        Assert.assertEquals(HeldSend.Action.SEND, quickReply("482913", "de"))
        Assert.assertEquals(HeldSend.Action.SEND, quickReply("\uD83C\uDF89\uD83D\uDC4D", "de"))
    }

    @Test
    fun theConversationsOwnBareNameSends() {
        // The whole body, and only this conversation's name: the detector reads "Matti" as Maltese at
        // 0.96, so refusing it would make a bare name unsendable.
        Assert.assertEquals(
                HeldSend.Action.SEND,
                HeldSend.decide("Matti", APP, "de", "Matti", HeldSend.Source.QUICK_REPLY, ON))
        Assert.assertEquals(
                "case-insensitively, as the whole body",
                HeldSend.Action.SEND,
                HeldSend.decide("matti", APP, "de", "Matti", HeldSend.Source.QUICK_REPLY, ON))
        Assert.assertEquals(
                "but a sentence that merely contains the name is prose",
                HeldSend.Action.TRANSLATE,
                HeldSend.decide(
                        "Matti tulee huomenna",
                        APP,
                        "de",
                        "Matti",
                        HeldSend.Source.QUICK_REPLY,
                        ON))
    }

    @Test
    fun aBareNameIsNotADraftWhenTheNameIsNotKnown() {
        // With no conversation name to compare against, "Matti" is a one-word message: translated,
        // not refused (see ComposerGateTest) and so not drafted either.
        Assert.assertEquals(HeldSend.Action.TRANSLATE, quickReply("Matti", "de"))
    }

    // --- what is refused, and only here -------------------------------------------------------

    @Test
    fun aForeignDraftInItsOwnRoomIsRefusedAndDrafted() {
        // The regression this change exists for: a German quick reply into a German room used to go
        // out as typed, because "not the app language" reads as "needs no translation".
        Assert.assertEquals(HeldSend.Action.DRAFT_NOT_SENT, quickReply(GERMAN, "de"))
    }

    @Test
    fun aForeignDraftIsRefusedEvenInAnAppLanguageRoom() {
        Assert.assertEquals(HeldSend.Action.DRAFT_NOT_SENT, quickReply(ENGLISH, APP))
    }

    @Test
    fun aRefusalIsNeverATranslation() {
        // The two mechanisms must not fight: a refused quick reply must not also be a held send, or
        // the same words would exist twice and a translation could be bought for text that is only a
        // draft. There is no Action that is both, and the German draft is not TRANSLATE either.
        Assert.assertNotEquals(HeldSend.Action.TRANSLATE, quickReply(GERMAN, "de"))
        Assert.assertNotEquals(HeldSend.Action.SEND, quickReply(GERMAN, "de"))
    }

    @Test
    fun anUnknownConversationLanguageStillTakesTheTranslationPath() {
        // Not a refusal: the draft is the app language and there is simply nothing to translate it
        // into yet, so this is the held send's own "unknown language" case.
        Assert.assertEquals(HeldSend.Action.TRANSLATE, quickReply(FINNISH, null))
        Assert.assertEquals(
                HeldSend.Action.TRANSLATE,
                HeldSend.decide(
                        FINNISH, APP, TextLanguage.UNKNOWN, null, HeldSend.Source.QUICK_REPLY, ON))
    }

    @Test
    fun aForeignDraftOutranksAnUnknownRoomLanguage() {
        // The draft is what the owner can fix right now; the room's language is the next step.
        Assert.assertEquals(HeldSend.Action.DRAFT_NOT_SENT, quickReply(GERMAN, null))
    }

    // --- what a share is ----------------------------------------------------------------------

    @Test
    fun theSameForeignDraftFromAnyOtherSourceIsNotRefused() {
        // A share carries somebody else's text and is put into the composer by upstream, where the
        // composer's own gate sees it before anything is sent. Held here as the statement of the
        // boundary: the refusal is the quick reply's alone, so nothing about shares changes.
        Assert.assertEquals(HeldSend.Action.SEND, elsewhere(GERMAN, "de"))
        Assert.assertEquals(HeldSend.Action.SEND, elsewhere(ENGLISH, APP))
        Assert.assertEquals(HeldSend.Action.TRANSLATE, elsewhere(FINNISH, "de"))
    }

    @Test
    fun needsTranslationIsTheElsewhereCaseOfTheSameDecision() {
        // The send path's existing question is spelled as this decision, so the two cannot drift: a
        // refusal is never "needs translating" there either.
        Assert.assertTrue(HeldSend.needsTranslation(FINNISH, APP, "de", null, ON))
        Assert.assertFalse(HeldSend.needsTranslation(GERMAN, APP, "de", null, ON))
        Assert.assertFalse(HeldSend.needsTranslation(LINK, APP, "de", null, ON))
    }

    // --- the words do not vanish --------------------------------------------------------------

    @Test
    fun refusedWordsBecomeTheDraftWhenThereIsNone() {
        Assert.assertEquals(GERMAN, HeldSend.mergeDraft(null, GERMAN))
        Assert.assertEquals(GERMAN, HeldSend.mergeDraft("", GERMAN))
        Assert.assertEquals(GERMAN, HeldSend.mergeDraft("   ", GERMAN))
    }

    @Test
    fun anExistingDraftIsKeptAlongsideTheRefusedWords() {
        Assert.assertEquals("half a sentence\n\n" + GERMAN, HeldSend.mergeDraft("half a sentence", GERMAN))
    }

    @Test
    fun anEmptyRefusalDoesNotDisturbTheDraft() {
        Assert.assertEquals("half a sentence", HeldSend.mergeDraft("half a sentence", ""))
        Assert.assertEquals("half a sentence", HeldSend.mergeDraft("half a sentence", "   "))
        Assert.assertEquals("half a sentence", HeldSend.mergeDraft("half a sentence", null))
        Assert.assertEquals("", HeldSend.mergeDraft(null, null))
    }
}
