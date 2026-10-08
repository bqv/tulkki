package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * The identity of a review, and the rule that decides which bubbles may show one.
 *
 * <p>Two things are pinned here. The key is a function of the owner's own words and the language the
 * notes are in, and it is in a namespace of its own, so a review can share the cache table with a
 * translation and a gloss without any of them being mistaken for another. And a review can only
 * belong to the owner's <em>own</em>, actually translated message: a received row is refused on its
 * status before its text is even looked at, which is the input-side rule made structural.
 *
 * <p>The off state is here too, because this is the closest gate to the store that is pure: a plain
 * client has no notes, so {@code forBubble} answers {@code null} for the very shape that carries a key
 * when the interpreter is on, and nothing downstream - the cache read, the row in the message menu -
 * is reachable. The two store seams that are not pure are exercised beside it: {@code ReviewStore.of}
 * with a real {@link Message} and an off interpreter, and {@code ReviewStore.record} against a
 * {@link TranslationDoubles.MemoryCacheStore}, which is where {@code docs/MIGRATION.md}'s §2.6 guard
 * on the write side is measured rather than asserted.
 */
class ReviewKeyTest {

    private val DRAFT = "Mina opin suomea ja haluan puhua sinulle"

    /** The off mode: app {@code fi} with study {@code fi}, one language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    /** Interpreting, so the on-state cells beside every off cell are not vacuous. */
    private val ON = Interpreter.of("fi", "de")

    /**
     * Leaves a fresh install behind for the next class: {@code TranslationSettings.inMemory()} installs
     * a process-wide instance, and the cells below deliberately move the two languages on it.
     */
    @After
    fun tearDown() {
        TranslationSettings.inMemory()
    }

    @Test
    fun theSameWordsInTheSameLanguageAreTheSameReview() {
        Assert.assertEquals(ReviewKey.of(DRAFT, "en"), ReviewKey.of(DRAFT, "en"))
    }

    @Test
    fun differentWordsOrADifferentStudyLanguageAreDifferentReviews() {
        Assert.assertNotEquals(ReviewKey.of(DRAFT, "en"), ReviewKey.of(DRAFT + "!", "en"))
        Assert.assertNotEquals(ReviewKey.of(DRAFT, "en"), ReviewKey.of(DRAFT, "fi"))
    }

    @Test
    fun aReviewNeverSharesAKeyWithATranslationOrAGloss() {
        Assert.assertNotEquals(CacheKey.of(DRAFT, "en"), ReviewKey.of(DRAFT, "en"))
        Assert.assertNotEquals(GlossKey.of(DRAFT, "en"), ReviewKey.of(DRAFT, "en"))
    }

    @Test
    fun theNamespaceCarriesTheContractsVersion() {
        // The review prompt changed - each note now carries the stretch of the owner's text it is
        // about - so the namespace moved, the way the gloss namespace moved when its prompt started
        // insisting that no field comes back empty. Without the move, a bubble would read back a bare
        // remark bought under the old prompt and have nothing to underline, and the new prompt would
        // look like it had not worked. The precedent is 8fb4de7ff9; the cost, stated rather than
        // hidden, is that the sends already reviewed are reviewed once more.
        Assert.assertEquals("review-v3", ReviewKey.NAMESPACE)
    }

    @Test
    fun theOwnersTranslatedMessageHasAReviewKey() {
        val key =
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_DONE, Message.STATUS_SEND, "en", ON)

        Assert.assertEquals(ReviewKey.of(DRAFT, "en"), key)
    }

    @Test
    fun aReceivedMessageHasNoReviewWhateverItsText() {
        // The rule that must never bend: the notes are about what the owner typed, and a received
        // row's translated_body is the translation of somebody else's words.
        Assert.assertNull(
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_DONE, Message.STATUS_RECEIVED, "en", ON))
        // Even with the text and the state of a perfectly reviewable message.
        Assert.assertNull(
                ReviewKey.forBubble(
                        "some translated received body",
                        Message.TRANSLATION_DONE,
                        Message.STATUS_RECEIVED,
                        "en",
                        ON))
    }

    @Test
    fun aMessageThatNeededNoTranslationHasNoReview() {
        // No call was made, so no notes came back and none ever will: the notes ride on the call.
        Assert.assertNull(
                ReviewKey.forBubble(
                        null, Message.TRANSLATION_SAME_LANGUAGE, Message.STATUS_SEND, "en", ON))
        Assert.assertNull(
                ReviewKey.forBubble("", Message.TRANSLATION_DONE, Message.STATUS_SEND, "en", ON))
        Assert.assertNull(
                ReviewKey.forBubble("   ", Message.TRANSLATION_DONE, Message.STATUS_SEND, "en", ON))
    }

    @Test
    fun aTranslationThatDidNotFinishHasNoReview() {
        Assert.assertNull(
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_FAILED, Message.STATUS_SEND, "en", ON))
        Assert.assertNull(
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_NONE, Message.STATUS_WAITING, "en", ON))
    }

    // ---- the off state: the same shapes, with the interpreter off ----

    /**
     * The gate's first cell. The shape is the one the on-state cell above uses - the owner's own
     * message, translated, with words and a study language - so the only thing that can make it
     * {@code null} is the mode. "Off" is asked three ways because the truth table has three ways to
     * be off and this rule may not be reading a different one.
     */
    @Test
    fun anOffInterpreterHasNoReviewForTheOwnersTranslatedMessage() {
        Assert.assertNull(
                "fi x fi: one language on both sides",
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_DONE, Message.STATUS_SEND, "en", OFF))
        Assert.assertNull(
                "either side none is off too, and it is not a language",
                ReviewKey.forBubble(
                        DRAFT,
                        Message.TRANSLATION_DONE,
                        Message.STATUS_SEND,
                        "en",
                        Interpreter.of("fi", Interpreter.NONE)))
        Assert.assertNull(
                "a caller with no mode has nothing to say",
                ReviewKey.forBubble(
                        DRAFT, Message.TRANSLATION_DONE, Message.STATUS_SEND, "en", null))
    }

    /**
     * {@code ReviewStore.of}'s off clause, over a real {@link Message}: the answer is an absent review
     * for a row that carries a key when the interpreter is on - the same row, the same text, the same
     * state - so the mode is what makes the difference. The context is null on purpose: with the
     * interpreter off {@code of} answers before it dereferences anything, which is the behaviour a
     * plain client gets. What a JVM test cannot show is the other half of §2.6's clause, that the
     * <em>cache</em> is not read: the read lives behind a {@code TranslationStore}, which needs an
     * encrypted database and a device. The chain is stated instead - the guard is above the
     * {@code forBubble} call, and that call is what returns the key the read would use.
     */
    @Test
    fun anOffInterpreterReadsBackAnAbsentReview() {
        val settings = TranslationSettings.inMemory()
        settings.setAppLanguage("fi")
        settings.setStudyLanguage("fi")
        val message =
                Message(
                        BareConversation(),
                        DRAFT,
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_SEND)
        message.setTranslatedBody(DRAFT)
        message.setTranslationState(Message.TRANSLATION_DONE)

        val absent = ReviewStore.of(null, message)

        Assert.assertFalse("nobody asked, so there is no review", absent.isPresent())
        Assert.assertTrue(absent.items().isEmpty())

        Assert.assertNotNull(
                "the same row has a key while the interpreter is on, so off is the mode",
                ReviewKey.forBubble(
                        message.getTranslatedBody(),
                        message.getTranslationState(),
                        message.getStatus(),
                        "en",
                        Interpreter.of("fi", "de")))
    }

    /**
     * {@code ReviewStore.record}'s off clause, against a {@code MemoryCacheStore}: nothing is written.
     * The on cell beside it writes the same review under the same key, so "nothing written" is the
     * mode's answer and not the review's - a review with nothing to keep would satisfy the off
     * assertion too, which is why the positive is here rather than assumed.
     */
    @Test
    fun anOffInterpreterRecordsNothing() {
        val cache =
                TranslationDoubles.MemoryCacheStore()
        val review =
                Review.of(
                        listOf(
                                Review.Note.of(
                                        "the ending is wrong",
                                        listOf("opin"))))

        ReviewStore.record(TranslationCache(cache), DRAFT, "en", review, OFF)

        Assert.assertTrue("a plain client keeps no notes at all", cache.entries.isEmpty())

        ReviewStore.record(TranslationCache(cache), DRAFT, "en", review, ON)

        Assert.assertEquals(
                "the same call writes once while the interpreter is on", 1, cache.entries.size)
        Assert.assertNotNull(
                "and under the key the bubble will look in", cache.entries.get(ReviewKey.of(DRAFT, "en")))
    }

    /**
     * A conversation for the store cell, built the way the engine's own tests build one: the real
     * type, so nothing is doubled, with the one member the unit-test runtime's stub {@code org.json}
     * cannot answer overridden.
     */
    private class BareConversation : Conversation(
            java.util.UUID.randomUUID().toString(),
            "tulkki",
            null,
            "account",
            // No address: this is about a message's own translation columns, and the test may
            // not name an island type to build a Jid.
            null,
            0L,
            Conversation.STATUS_AVAILABLE,
            Conversation.MODE_SINGLE,
            "") {

        override fun getEphemeralTimer(): Int {
            return 0
        }
    }
}
