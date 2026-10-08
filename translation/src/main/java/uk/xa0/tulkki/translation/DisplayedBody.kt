package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

/**
 * What the interface shows for a message body.
 *
 * <p>The translation <em>is</em> the message as far as the UI is concerned: there is no "show
 * original" affordance, so every display site has to make the same choice, and it has to make it the
 * same way. This is that one choice, isolated as a pure value so it can be JVM-tested without a
 * device.
 *
 * <p>The original always stays in the database in `body`; this only decides what to render, and
 * the answer is three-way rather than two:
 *
 * <ul>
 *   <li>[Kind.TRANSLATION] - the translation, when one was stored in state
 *       [Message.TRANSLATION_DONE].
 *   <li>[Kind.ORIGINAL] - the original, because nothing needed translating: the message was
 *       already in the app language, or a deliberate caller said this body has no language to
 *       translate (a link, a code, a number, emoji, a bare name).
 *   <li>[Kind.BLURRED] - the original is still the fallback, but translation was <em>needed
 *       and did not happen</em> (still pending, failed, capped, no key configured, or history the
 *       automatic pass refuses), so it must not be shown raw. The renderer covers it, and a
 *       deliberate tap does not reveal it: it asks for a translation of that one message.
 * </ul>
 *
 * <p>A covered body also has something to say for itself, and [cover] is that decision: the
 * plain cover ("not translated yet"), "translating now" after a tap, or the reason it cannot be
 * translated. One place, so the bubble and anything else that renders the same state agree.
 *
 * <p>`translation_state` alone cannot tell "still pending because there is no key" from "was
 * already in the app language and never needed anything" - the second case is only known to whoever
 * classified the message. So whether translation was needed is an explicit parameter, not something
 * this class guesses; see [needsTranslation] for the usual
 * answer.
 *
 * <p><strong>The interpreter has the last word.</strong> With it off Tulkki is a plain XMPP client,
 * and the answer is always [Kind.ORIGINAL] with the body as it arrived - whatever state the row
 * is in, including [Message.TRANSLATION_DONE] with a stored translation. That clause is the
 * first statement of [of], above the `DONE` check, because a row translated while the
 * interpreter was on would otherwise keep rendering its paid-for translation to an owner who never
 * saw the original. [needsTranslation] answers `false` off for the same reason: nothing
 * was owed a translation, so nothing is covered.
 */
class DisplayedBody private constructor(
        private val kindValue: Kind,
        private val textValue: String
) {

    /** Which of the three things the interface does with a body. */
    enum class Kind {
        /** The translation is shown; it is the message as far as the UI is concerned. */
        TRANSLATION,
        /** The original is shown, because nothing needed translating. */
        ORIGINAL,
        /** Translation was needed and did not happen: the original must be covered, not shown. */
        BLURRED
    }

    /**
     * What a covered body says for itself. The cover never becomes the original: a tap asks for a
     * translation of that one message, so these are the only captions a covered bubble ever shows.
     */
    enum class Cover {
        /** Nothing known: "not translated yet", and a tap asks for it. */
        TAP,
        /** The owner asked for this one and the request is in flight. */
        TRANSLATING,
        /** Pointing at this body cannot help: it is ciphertext, a file, an edit, a reaction. */
        CANNOT_TRANSLATE,
        /** No DeepSeek key is configured. */
        NO_KEY,
        /** The day's token cap is used up. */
        CAP_REACHED,
        /** The DeepSeek account has no balance left. */
        NO_CREDIT,
        /** DeepSeek could not be reached; worth trying again later. */
        UNREACHABLE,
        /** DeepSeek refused to authorise the key itself. */
        REJECTED_KEY,
        /** DeepSeek answered, and the answer was not usable. */
        FAILED
    }

    companion object {

        /**
         * The display decision.
         *
         * @param body the original message body; may be `null`
         * @param translatedBody the stored translation; may be `null`
         * @param translationState one of the `Message.TRANSLATION_*` constants
         * @param translationNeeded whether this body needed translating at all - true for received text
         *     in another language, false for our own messages and for bodies with no language in them.
         *     Consulted only for [Message.TRANSLATION_NONE] and for a `DONE` state that
         *     stored nothing usable, because that is where the state alone is silent.
         * @param interpreter the interpreter's off switch. Required, and read first: off, Tulkki is a
         *     plain XMPP client and what it shows is the message as it arrived, in every state.
         * @return the translation when there is one to show, otherwise the original, marked as either
         *     ordinary or covered. [text] is never `null`: a `null` original becomes
         *     the empty string.
         */
        @JvmStatic
        fun of(
                body: String?,
                translatedBody: String?,
                translationState: Int,
                translationNeeded: Boolean,
                interpreter: Interpreter
        ): DisplayedBody {
            if (!interpreter.enabled()) {
                // Off: a plain client shows what was sent. This is deliberately ABOVE the DONE check.
                // A row translated while the interpreter was on still holds its translation, and
                // rendering it would show the owner a paid-for rendering of a message they never saw
                // in the original - the second mode in the UI, not a missing guard.
                return DisplayedBody(Kind.ORIGINAL, body ?: "")
            }
            val original = body ?: ""
            if (translationState == Message.TRANSLATION_DONE &&
                    translatedBody != null &&
                    translatedBody.isNotEmpty()) {
                return DisplayedBody(Kind.TRANSLATION, translatedBody)
            }
            if (translationState == Message.TRANSLATION_SAME_LANGUAGE) {
                // Decisive: the body was already in the app language, so there is nothing to cover.
                return DisplayedBody(Kind.ORIGINAL, original)
            }
            if (translationState == Message.TRANSLATION_FAILED) {
                // Decisive: a translation was attempted and did not happen.
                return DisplayedBody(Kind.BLURRED, original)
            }
            // TRANSLATION_NONE - or DONE with nothing usable stored, which is a translation that did
            // not happen just as much. Pending, no key, cap reached and "nothing here to translate"
            // all look the same from the state, so the caller's answer is what separates them.
            return if (translationNeeded) {
                DisplayedBody(Kind.BLURRED, original)
            } else {
                DisplayedBody(Kind.ORIGINAL, original)
            }
        }

        /**
         * Whether translation was needed for this body: it arrived from someone else, and it contains
         * language rather than only a link, a code, a number, emoji, a ping or the conversation's own
         * bare name. Our own messages are never translated, so they are never blurred.
         *
         * @param conversationName the name the interface shows for the conversation this body is in
         *     ([ConversationName]), or `null` when the caller has none - in which case only
         *     the ping shape can tell it that the body has no language
         * @param interpreter the interpreter's off switch. Required, and read first: off nothing was
         *     owed a translation, so nothing is covered. This is the one place that decides "this body
         *     needed translating and did not get one", so it is what turns every cover off at once.
         */
        @JvmStatic
        fun needsTranslation(
                messageStatus: Int,
                body: String?,
                conversationName: String?,
                interpreter: Interpreter
        ): Boolean {
            if (!interpreter.enabled()) {
                return false
            }
            return messageStatus == Message.STATUS_RECEIVED &&
                    TranslationDecision.hasLanguage(body, conversationName)
        }

        /**
         * What a covered body says, once [of] has said it is covered.
         *
         * @param asking the owner tapped this message and the request has not finished yet
         * @param failure the last failed translation attempt, or `null` when there was none
         * @param failureIsForThisMessage whether that attempt was this message's - a failure belongs to
         *     the message it happened to, and showing somebody else's reason on this bubble would be a
         *     lie. The caller knows the message uuid; this class deliberately does not.
         */
        @JvmStatic
        fun cover(
                asking: Boolean,
                failure: HeldSend.HoldReason?,
                failureIsForThisMessage: Boolean
        ): Cover {
            if (asking) {
                return Cover.TRANSLATING
            }
            if (failure == null || !failureIsForThisMessage) {
                return Cover.TAP
            }
            return when (failure) {
                HeldSend.HoldReason.NO_KEY -> Cover.NO_KEY
                HeldSend.HoldReason.CAP_REACHED -> Cover.CAP_REACHED
                HeldSend.HoldReason.NO_CREDIT -> Cover.NO_CREDIT
                HeldSend.HoldReason.UNREACHABLE -> Cover.UNREACHABLE
                HeldSend.HoldReason.REJECTED_KEY -> Cover.REJECTED_KEY
                // FAILED, and UNKNOWN_LANGUAGE, which cannot happen on a received body: the
                // conversation's language is only a question for what is sent.
                else -> Cover.FAILED
            }
        }

        /**
         * The cover a notification draws for one message, or `null` when the notification must
         * take its line from the renderer instead.
         *
         * <p>A notification cannot cover a body the way a bubble can - it has no tap, no blur and no
         * room - so a message whose translation did not happen has to <em>say so</em> there, in the
         * cover's own words, and never in the body's (docs/MIGRATION.md item 17, one). This is that
         * decision, and it is body-free: a reason the store recorded for this very message is a fact
         * about the message, so which cover it is needs no word of the text - [cover] remains the
         * one place that maps the reason to the cover.
         *
         * <p>It answers for the message the failure belongs to and for no other: [cover] returns
         * [Cover.TAP] for a failure that belongs to somebody else, and this returns `null` for
         * it, because borrowing another message's reason would be a lie about this one.
         *
         * @param translationState one of the `Message.TRANSLATION_*` constants
         * @param messageStatus the message's status; a sent row is never the check's business
         * @param failure the last failed translation attempt, or `null` when there was none
         * @param failureIsForThisMessage whether that attempt was this message's
         * @return the cover to say, or `null` to let the renderer's own preview stand
         */
        @JvmStatic
        fun notificationCover(
                translationState: Int,
                messageStatus: Int,
                failure: HeldSend.HoldReason?,
                failureIsForThisMessage: Boolean
        ): Cover? {
            if (messageStatus != Message.STATUS_RECEIVED) {
                return null
            }
            if (translationState == Message.TRANSLATION_DONE ||
                    translationState == Message.TRANSLATION_SAME_LANGUAGE) {
                // There is an answer, or there was nothing to answer: the line is not a cover.
                return null
            }
            if (failure == null || !failureIsForThisMessage) {
                // Nothing is known yet. The renderer's generic cover is the honest line, and the
                // bubble says the same thing, so this is not a second wording for one state.
                return null
            }
            // `asking` is false: a tap happens with the screen on and its request in flight, and a
            // notification has no way to know about either, so it may not claim TRANSLATING.
            return cover(false, failure, true)
        }
    }

    fun kind(): Kind = kindValue

    /** What to put in the view. Never `null`. */
    fun text(): String = textValue

    /** True when [text] is the translation and nothing else should be shown. */
    fun isTranslation(): Boolean = kindValue == Kind.TRANSLATION

    /** True when the original may be shown as it is. */
    fun isOriginal(): Boolean = kindValue == Kind.ORIGINAL

    /** True when the original is only a fallback and must be covered until deliberately revealed. */
    fun isBlurred(): Boolean = kindValue == Kind.BLURRED

    override fun toString(): String = "" + kindValue + "(" + textValue + ")"
}
