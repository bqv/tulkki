package uk.xa0.tulkki.ui.composer

import uk.xa0.tulkki.translation.ComposerGate

/**
 * The composer's send decision, and the seam the Compose composer shares with the Java one.
 *
 * <p>**It decides when the send affordance is used, and nothing else.** The gate's own verdict is
 * [ComposerGate]'s (`OutgoingTranslation.verdict` fills in the app's settings and is what a caller
 * passes here), the hold and the translation are `OutgoingTranslation`'s, and the trust discovery is
 * the transport's. What is left - and what this type owns - is the order the send path asks its
 * questions in, because that order is behaviour:
 *
 * <ol>
 *   <li>a draft the app cannot draw is refused before anything is looked at ([Carriage.TOO_LONG]);
 *   <li>a composer with nothing in it and nothing staged is not a send at all ([Carriage.NOTHING]) -
 *       a subject alone only travels while the conversation has a thread, and a staged attachment is
 *       something to send on its own;
 *   <li>a draft the gate refused becomes the prompt rather than a message ([Carriage.PROMPT]);
 *   <li>everything else is sent ([Carriage.SEND]), translated by the send path or not.
 * </ol>
 *
 * <p>**The gate stands down for the owner's own words, and that is [suggestionIsTheDraft].** When the
 * model's app-language version of the draft turned out to be the draft itself the verdict was wrong,
 * so the message goes out as typed - the app correcting a false refusal, never a bypass the owner can
 * reach. The caller computes it (`ComposerGate.suggestionIsTheDraft`) and passes the answer, so this
 * type stays free of the comparison and can be a JVM cell.
 *
 * <p>**It is Android-free on purpose.** The verdict is an input rather than a call, so the four
 * branches are decided in a plain JVM test and the two composers cannot disagree about them while
 * both exist. The Java field's own editability, the toast, the context menu and the prompt view all
 * stay where they belong - with the host that draws them.
 */
object ComposerSend {

    /** What the send affordance should do with the draft it is holding. */
    enum class Carriage {
        /** Send it: the send path translates it, or there is nothing to translate. */
        SEND,

        /** Nothing to send: the field is empty and nothing is staged. */
        NOTHING,

        /** The draft cannot be drawn: it is longer than the app displays. */
        TOO_LONG,

        /** The draft is confidently not the app language: it becomes the prompt, not a message. */
        PROMPT,
    }

    /**
     * The one decision, from the draft and the facts the host already read.
     *
     * @param draft the body in hand, or `null` when the field has none - treated as empty
     * @param conversationPresent whether there is a conversation to send into at all
     * @param hasAttachments whether the composer has staged something
     * @param hasSubject whether a subject was typed
     * @param hasThread whether the conversation carries a thread; a subject alone only travels with
     *     one, which is the Java composer's own rule and not a new one
     * @param maxDisplayChars the most the app draws, `Config.MAX_DISPLAY_MESSAGE_CHARS` at the call
     *     site so this type does not reach into the app module
     * @param verdict the gate's answer, or `null` when it was not asked (an empty draft, no activity)
     * @param suggestionIsTheDraft whether the gate's app-language answer turned out to be the draft
     */
    @JvmStatic
    fun carriage(
        draft: CharSequence?,
        conversationPresent: Boolean,
        hasAttachments: Boolean,
        hasSubject: Boolean,
        hasThread: Boolean,
        maxDisplayChars: Int,
        verdict: ComposerGate.Verdict?,
        suggestionIsTheDraft: Boolean,
    ): Carriage {
        val body = draft ?: ""
        if (body.length > maxDisplayChars) {
            return Carriage.TOO_LONG
        }
        if (!conversationPresent ||
            (body.isEmpty() && !hasAttachments && (!hasThread || !hasSubject))
        ) {
            return Carriage.NOTHING
        }
        return if (body.isNotEmpty() &&
            verdict == ComposerGate.Verdict.NOT_APP_LANGUAGE &&
            !suggestionIsTheDraft
        ) {
            Carriage.PROMPT
        } else {
            Carriage.SEND
        }
    }
}
