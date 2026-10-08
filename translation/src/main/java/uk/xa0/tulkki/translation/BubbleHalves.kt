package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

/**
 * The two sides of one bubble, and which of them may be read.
 *
 * <p>A translated message has two language sides, and the bubble shows both of them, one above the
 * other, separated by a rule:
 *
 * <ul>
 *   <li>the <strong>top</strong> is always the <strong>app language</strong> - the language the
 *       interface is in. Received, that is what the translation produced; sent, it is what the owner
 *       typed;
 *   <li>the <strong>bottom</strong> is always the <strong>conversation's language</strong> - the
 *       language the others write in. Received, that is the original somebody sent; sent, it is the
 *       text that actually went on the wire.
 * </ul>
 *
 * <p>That is the invariant, and it is not "the translation on top": the <em>direction</em> of the
 * translation flips between the two cases while the sides do not. Both sides are therefore read out of
 * the stored row, where the swap already put them - the app language's side in `translated_body`,
 * the conversation's side in `body` - rather than from a direction test.
 *
 * <p><strong>What may be read follows from whose message it is, not from which language sits
 * below.</strong> A received message's bottom half is never readable: a contact's original is not
 * the owner's to inspect, and this is the one place it could leak, so it is rendered as a strip with
 * no text in it at all rather than as text under a blur.
 *
 * <p>Pure Kotlin apart from the `Message` constants, so it is exercised by JVM unit tests. `Bottom`
 * stays nested, `of` is `@JvmStatic`, and the three `is…()` accessors stay methods because the Kotlin
 * callers call them as calls (`halves.isBottomReadable()`, `halves.isDivided()`).
 */
class BubbleHalves private constructor(
        private val topText: String,
        private val bottomValue: Bottom,
        private val bottomTextView: String
) {

    /** What is below the divider, if anything. */
    enum class Bottom {
        /** No second half: there is only one side to show. */
        NONE,
        /** The conversation's language, present and never readable - a place-holder strip alone. */
        CONCEALED,
        /** The conversation's language, readable: the text that was sent. */
        READABLE
    }

    companion object {

        /** Dead in the Java this replaces too: kept faithful rather than silently dropped. */
        private val NOTHING_TO_DIVIDE = BubbleHalves("", Bottom.NONE, "")

        /**
         * The decision.
         *
         * @param body the conversation's-language side of the row: the original of a received message,
         *     the wire text of a sent one; may be `null`
         * @param translatedBody the app-language side, as stored; may be `null`
         * @param translationState one of the `Message.TRANSLATION_*` constants
         * @param messageStatus the row's status, which is what says whose message this is
         * @param conversationName the name the interface shows for this row's conversation
         *     ([ConversationName]), or `null` when the caller has none - in which case only
         *     the ping shape can tell it that the body has no language
         * @param interpreter the interpreter's off switch. Required, and read first: off there is never a
         *     second side to divide, whatever the row holds.
         */
        @JvmStatic
        fun of(
                body: String?,
                translatedBody: String?,
                translationState: Int,
                messageStatus: Int,
                conversationName: String?,
                interpreter: Interpreter
        ): BubbleHalves {
            if (!interpreter.enabled()) {
                // The explicit outer gate, deliberately kept even though DisplayedBody.of already
                // answers ORIGINAL when off and `!displayed.isTranslation()` below would collapse
                // this too: the one-half answer must not depend on another class's internal
                // ordering. A plain client divides nothing.
                return BubbleHalves(body ?: "", Bottom.NONE, "")
            }
            val displayed =
                    DisplayedBody.of(
                            body,
                            translatedBody,
                            translationState,
                            DisplayedBody.needsTranslation(
                                    messageStatus, body, conversationName, interpreter),
                            interpreter)
            if (!displayed.isTranslation()) {
                // Nothing was translated, so there is no second side: the one body is the bubble,
                // and a body that needed translating and did not get one is covered as it always was.
                return BubbleHalves(displayed.text(), Bottom.NONE, "")
            }
            // A translation exists, so the top is the app language's side - exactly what
            // DisplayedBody chose - and the bottom is the conversation's language's side, `body`.
            if (messageStatus == Message.STATUS_RECEIVED) {
                // Somebody else's original: it never becomes readable, so no text reaches the view.
                return BubbleHalves(displayed.text(), Bottom.CONCEALED, "")
            }
            val conversationSide = body ?: ""
            // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
            if (conversationSide.javaTrim().isEmpty()) {
                // A translation with nothing on the other side is not a second half.
                return BubbleHalves(displayed.text(), Bottom.NONE, "")
            }
            return BubbleHalves(displayed.text(), Bottom.READABLE, conversationSide)
        }
    }

    /** What the top half shows: always the app language's side. Never `null`. */
    fun top(): String = topText

    fun bottom(): Bottom = bottomValue

    /** The bottom half's text; empty unless the bottom half is [Bottom.READABLE]. */
    fun bottomText(): String = bottomTextView

    /** True when the bubble is divided at all. */
    fun isDivided(): Boolean = bottomValue != Bottom.NONE

    /** True when the second half is there and may be read. */
    fun isBottomReadable(): Boolean = bottomValue == Bottom.READABLE

    /** True when the second half is there and must never become readable. */
    fun isBottomConcealed(): Boolean = bottomValue == Bottom.CONCEALED

    override fun toString(): String =
            topText +
                    " / " +
                    bottomValue +
                    (if (bottomTextView.isEmpty()) "" else "(" + bottomTextView + ")")
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
