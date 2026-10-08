package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

/**
 * The quote block at the head of a reply: the message it answers, displayed like any other message.
 *
 * <p>A reply carries a copy of the message it answers inside its own body - upstream moves it out of
 * the bubble and into a small quote view - and that copy is the referenced message as it was written,
 * in the language the other person used. Rendered as it is, it is a second copy of an original on the
 * screen: the one part of a received message this design promises never to show.
 *
 * <p>The quote is a message being displayed, so it goes through the same two decisions as a bubble
 * and adds no third one: [DisplayedBody] on the <em>referenced row</em> settles the top half, and
 * [BubbleHalves] settles the two halves and which of them may be read. Its one status parameter is a
 * question about whose text is on the bottom, and the answer is <em>both</em> facts the quote
 * carries: the second half is readable only when the quoted row is the owner's own <em>and</em> the
 * reply is on its way out. Every other combination conceals it - including a received reply that
 * quotes the owner's own earlier words, because that is somebody else's bubble.
 *
 * <p><strong>Covered</strong> is the answer for a referenced row that [DisplayedBody] covers
 * because translation was needed and did not happen. There is a fallback string in the reply's body
 * that is the peer's original in the peer's language - exactly the thing that must not be shown - so
 * the cover, not the raw text, is the answer.
 *
 * <p><strong>The interpreter has the last word, and it has it over a reference that cannot be
 * resolved at all</strong> - `getInReplyTo()` null and no row in the conversation for the reply id,
 * which the caller reports through [unresolved]. On, that case is covered for the same reason as any
 * other; off, the app shows originals everywhere ([DisplayedBody.of] answers
 * [DisplayedBody.Kind.ORIGINAL] for every state, first statement), so the fallback is not a leak and
 * a concealment rule whose whole justification is "the original is not otherwise visible" cannot
 * survive a mode in which the original is visible by design. Off the quote is therefore the reply's
 * own fallback copy, the one half a plain client draws.
 *
 * <p>The reply is taken as its status rather than as a `Message` on purpose: this decision must
 * never read the reply's body. Only the referenced row's two text columns, its state and its status
 * are read, and the one exception proves the rule by being a value rather than a read: [unresolved]
 * is handed the reply's fallback text, read by the caller through `ReplyFallback`, and it neither
 * scans a body for it nor keeps it when the interpreter is on.
 *
 * <p>There is deliberately no app-language parameter, for the reason the Java's comment records: the
 * referenced row already holds both sides in fixed columns and its state already answers "this was
 * already the app language", while the current setting cannot decide anything about a row classified
 * under an older one.
 *
 * <p>Pure Kotlin, so it is exercised by JVM unit tests without a device. The one `!covered` accessor
 * reads its halves with `?: ""` rather than `!!`: `COVERED` is the only covered instance and a
 * non-covered one always holds its halves, but the null is in the type and is named, not asserted.
 */
class ReplyQuote private constructor(
        private val covered: Boolean,
        private val halves: BubbleHalves?
) {

    companion object {

        /** The one covered answer: show a cover, and put no text in the view. */
        private val COVERED = ReplyQuote(true, null)

        /**
         * The quote of a reply whose referenced message cannot be resolved locally: `getInReplyTo()`
         * was null and the conversation holds no row for the reply id.
         *
         * @param interpreter the interpreter's off switch; required, and read first
         * @param fallback the reply's own copy of the quoted text, read by the caller through
         *     `ReplyFallback` from the span the sender declared - never scanned out of a body by this
         *     class. It reaches a view only while the interpreter is off; on it is not stored at all,
         *     so a caller that ignores [isCovered] still cannot print it. May be `null`.
         */
        @JvmStatic
        fun unresolved(interpreter: Interpreter, fallback: String?): ReplyQuote {
            if (interpreter.enabled()) {
                return COVERED
            }
            // Off, nothing was owed a translation, so there is nothing to cover and nothing to
            // divide: the fallback copy is the quote, as the single half it arrived as.
            return ReplyQuote(
                    false,
                    BubbleHalves.of(
                            fallback,
                            null,
                            Message.TRANSLATION_NONE,
                            Message.STATUS_RECEIVED,
                            null,
                            interpreter))
        }

        /**
         * The decision for the quote block of one reply.
         *
         * @param replyStatus the reply's own status, which is what says whose bubble the quote sits in
         * @param referencedBody the referenced row's conversation-language side, with the nested reply
         *     fallback already removed (`Message#getBody(true)`); may be `null`
         * @param referencedTranslatedBody the referenced row's app-language side as stored; may be
         *     `null`
         * @param referencedTranslationState one of the `Message.TRANSLATION_*` constants
         * @param referencedStatus the referenced row's own status. The second half may be read only
         *     when this is not `Message.STATUS_RECEIVED` <em>and</em> `replyStatus` is not either;
         *     every other combination conceals it.
         * @param conversationName the name the interface shows for the conversation both rows are in
         *     ([ConversationName]), or `null` when the caller has none
         * @param interpreter the interpreter's off switch, required and the last argument. Off, the
         *     quote is the referenced row as it arrived: one half, no cover, and no divider.
         */
        @JvmStatic
        fun of(
                replyStatus: Int,
                referencedBody: String?,
                referencedTranslatedBody: String?,
                referencedTranslationState: Int,
                referencedStatus: Int,
                conversationName: String?,
                interpreter: Interpreter
        ): ReplyQuote {
            val displayed =
                    DisplayedBody.of(
                            referencedBody,
                            referencedTranslatedBody,
                            referencedTranslationState,
                            DisplayedBody.needsTranslation(
                                    referencedStatus, referencedBody, conversationName, interpreter),
                            interpreter)
            if (displayed.isBlurred()) {
                // The referenced message needed a translation and has none. Showing the fallback
                // would be showing that original; covering it is the answer.
                return COVERED
            }
            // It either has a translation or needed none, so the top half is settled. What is left
            // for BubbleHalves is the two sides and which of them may be read, and that is one
            // question - whose text is on the bottom.
            val bottomHalfIsTheOwnersOwn =
                    replyStatus != Message.STATUS_RECEIVED &&
                            referencedStatus != Message.STATUS_RECEIVED
            val bottomHalfOwner =
                    if (bottomHalfIsTheOwnersOwn) replyStatus else Message.STATUS_RECEIVED
            return ReplyQuote(
                    false,
                    BubbleHalves.of(
                            referencedBody,
                            referencedTranslatedBody,
                            referencedTranslationState,
                            bottomHalfOwner,
                            conversationName,
                            interpreter))
        }
    }

    /**
     * True when the quote has nothing that may be shown and must be covered instead: no text goes
     * into the view at all, only the cover and its caption.
     */
    fun isCovered(): Boolean = covered

    /**
     * What the quote's app-language half shows. Never `null`, and empty when the quote is
     * covered - so a caller that ignores [isCovered] still cannot print an original.
     */
    fun top(): String = if (covered) "" else (halves?.top() ?: "")

    /** True when the quote is divided at all: the app-language half above, the other below a rule. */
    fun isDivided(): Boolean = !covered && (halves?.isDivided() ?: false)

    /**
     * Which of the three things sits below the quote's divider, for a caller that has to hand the
     * question on - the display settings read it through `SecondHalf` rather than asking this class
     * about readability. [BubbleHalves.Bottom.NONE] when the quote is covered or has nothing to
     * divide.
     */
    fun bottom(): BubbleHalves.Bottom =
            if (covered) BubbleHalves.Bottom.NONE else (halves?.bottom() ?: BubbleHalves.Bottom.NONE)

    /** True when the quote's second half is the reply's own side and may be read. */
    fun isBottomReadable(): Boolean = !covered && (halves?.isBottomReadable() ?: false)

    /** True when the quote's second half is there and must never become readable. */
    fun isBottomConcealed(): Boolean = !covered && (halves?.isBottomConcealed() ?: false)

    /**
     * The second half's text; empty unless it is readable. A concealed half is a strip with no text
     * in the view, so nothing here ever reaches a screen reader, a screenshot or a dump.
     */
    fun bottomText(): String = if (covered) "" else (halves?.bottomText() ?: "")

    override fun toString(): String = if (covered) "COVERED" else (halves?.toString() ?: "COVERED")
}
