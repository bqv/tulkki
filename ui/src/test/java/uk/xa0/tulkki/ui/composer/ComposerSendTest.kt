package uk.xa0.tulkki.ui.composer

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.ComposerGate

/**
 * The composer's send decision, as four answers.
 *
 * <p>The order is the behaviour: a draft too long to draw is refused before the field's emptiness is
 * even looked at, an empty composer with nothing staged is not a send at all, and only then does the
 * gate get to turn a draft into a prompt. A staged attachment or a threaded subject alone is still a
 * send, which is the Java composer's own rule and not a new one.
 *
 * <p>The verdict is an input rather than a call, so [ComposerSend] stays Android-free and this class
 * can pin the four branches on the JVM - the same reason `ComposerGate` is a pure object.
 */
class ComposerSendTest {

    private fun carriage(
        draft: CharSequence?,
        conversationPresent: Boolean = true,
        hasAttachments: Boolean = false,
        hasSubject: Boolean = false,
        hasThread: Boolean = false,
        verdict: ComposerGate.Verdict? = null,
        suggestionIsTheDraft: Boolean = false,
        maxDisplayChars: Int = 1000,
    ): ComposerSend.Carriage =
        ComposerSend.carriage(
            draft,
            conversationPresent,
            hasAttachments,
            hasSubject,
            hasThread,
            maxDisplayChars,
            verdict,
            suggestionIsTheDraft,
        )

    @Test
    fun aDraftLongerThanTheAppDrawsIsTooLong() {
        Assert.assertEquals(ComposerSend.Carriage.TOO_LONG, carriage("x".repeat(1001)))
        Assert.assertEquals(ComposerSend.Carriage.TOO_LONG, carriage("x".repeat(1001), hasAttachments = true))
    }

    @Test
    fun aDraftAtTheLimitIsNotTooLong() {
        Assert.assertEquals(
            ComposerSend.Carriage.SEND,
            carriage("x".repeat(1000), verdict = ComposerGate.Verdict.TRANSLATE),
        )
    }

    @Test
    fun anEmptyComposerWithNothingStagedHasNothingToSend() {
        Assert.assertEquals(ComposerSend.Carriage.NOTHING, carriage(""))
        Assert.assertEquals(ComposerSend.Carriage.NOTHING, carriage(null))
        Assert.assertEquals(ComposerSend.Carriage.NOTHING, carriage("   ".trim()))
    }

    @Test
    fun noConversationHasNothingToSend() {
        Assert.assertEquals(
            ComposerSend.Carriage.NOTHING,
            carriage("moi", conversationPresent = false, hasAttachments = true),
        )
    }

    @Test
    fun aStagedAttachmentIsSomethingToSendWithoutADraft() {
        Assert.assertEquals(ComposerSend.Carriage.SEND, carriage("", hasAttachments = true))
    }

    @Test
    fun aSubjectAloneTravelsOnlyWithAThread() {
        Assert.assertEquals(
            ComposerSend.Carriage.NOTHING,
            carriage("", hasSubject = true),
        )
        Assert.assertEquals(
            ComposerSend.Carriage.SEND,
            carriage("", hasSubject = true, hasThread = true),
        )
    }

    @Test
    fun aRefusedDraftBecomesThePrompt() {
        Assert.assertEquals(
            ComposerSend.Carriage.PROMPT,
            carriage("hello there", verdict = ComposerGate.Verdict.NOT_APP_LANGUAGE),
        )
    }

    @Test
    fun aRefusedDraftThatTheModelEchoedIsSent() {
        Assert.assertEquals(
            ComposerSend.Carriage.SEND,
            carriage(
                "hello there",
                verdict = ComposerGate.Verdict.NOT_APP_LANGUAGE,
                suggestionIsTheDraft = true,
            ),
        )
    }

    @Test
    fun aDraftTheGateWouldTranslateIsSent() {
        Assert.assertEquals(
            ComposerSend.Carriage.SEND,
            carriage("nähdään", verdict = ComposerGate.Verdict.TRANSLATE),
        )
    }

    @Test
    fun anUnaskedVerdictIsSent() {
        Assert.assertEquals(ComposerSend.Carriage.SEND, carriage("nähdään", verdict = null))
    }
}
