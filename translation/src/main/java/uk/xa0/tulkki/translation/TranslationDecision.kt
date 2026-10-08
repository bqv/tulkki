package uk.xa0.tulkki.translation

import java.util.regex.Pattern
import uk.xa0.tulkki.data.model.Message

/**
 * The two decisions that stand between a message and money.
 *
 * <p>The first is structural: may Tulkki spend anything on this message at all? There are two ways
 * in, and the difference between them is *automatic* versus *asked for*:
 *
 * <ul>
 *   <li>[isEligible] is the automatic pass, and it is the strict one. The archive is the expensive
 *       mistake here - translating fetched history means years of messages in one burst - so only
 *       messages that arrived live and carry text Tulkki can read qualify ([isReadableBody]).
 *   <li>[isRequestable] is one message the owner pointed at by tapping it. The archive rules are the
 *       one thing that does not apply: "it came from history" is not a reason to refuse a message
 *       someone deliberately asked for. Everything else still is, because pointing at a ciphertext
 *       blob or a reaction cannot make it translatable.
 * </ul>
 *
 * <p>Both spend against the same daily cap and the same token counter.
 *
 * <p>All three answers take the [Interpreter] as a required argument, and with it off none of them
 * can name a translation: the two spend gates answer `false` and [classify] answers
 * [Verdict.NO_LANGUAGE]. That is the off state of the interpreter expressed as the one derived
 * setting these rules consult, rather than as a second set of decisions made somewhere else
 * (MIGRATION.md "Design: the interpreter off-switch" §2.1).
 *
 * <p>The second is the free one: [TextLanguage] runs on the device before any request, and a message
 * that is confidently already in the app language is marked as needing no translation and never sent
 * anywhere.
 *
 * <p>Pure Kotlin apart from compile-time `Message` constants, so it is exercised by JVM unit tests
 * without a device.
 */
object TranslationDecision {

    /**
     * A body that is nothing but a URI has no language; translating one is paying for nothing.
     *
     * <p>`\S*` and not `\S+`, so the scheme alone is enough: `"https://"` is a URI with nothing after
     * it, not prose that happens to contain letters. That is the half the composer's own copy of this
     * rule got right, and it was one of the two shapes the two copies disagreed about once [Ping] had
     * answered for the schemes that are a colon and nothing else.
     *
     * <p>Kept as a `java.util.regex.Pattern` rather than a Kotlin `Regex` on purpose: the two differ
     * in what `IGNORE_CASE` means, and `Pattern.CASE_INSENSITIVE` is the flag the Java this replaces
     * passed.
     */
    private val LINK_ONLY =
            Pattern.compile("^(?:https?://|xmpp:|mailto:|geo:)\\S*$", Pattern.CASE_INSENSITIVE)

    /** How many letters a body needs before it can plausibly be language rather than a code. */
    private const val MIN_LETTERS = 2

    /** What to do with a body, locally and for free. */
    enum class Verdict {
        /** Worth a request: it is language and it is not confidently the target language. */
        TRANSLATE,
        /** Confidently the app language already: no request, and no blur. */
        SAME_LANGUAGE,
        /** A link, a number, a code, emoji: nothing to translate and nothing to buy. */
        NO_LANGUAGE
    }

    /**
     * Everything about an arriving message that decides whether Tulkki may spend on it. Fields
     * default to the safe answer, so anything the caller forgets to set means "do not translate".
     */
    class Candidate {
        /** The message was received, not sent by us. */
        @JvmField var received: Boolean = false

        /** It came out of a Message Archive Management query. */
        @JvmField var fromArchive: Boolean = false

        /** It carried a XEP-0203 delay stamp: it was delivered late, not now. */
        @JvmField var delayed: Boolean = false

        @JvmField var deleted: Boolean = false

        /**
         * The message carries a file or an image, so the body is that file's caption or URL and not
         * something to buy a translation of.
         *
         * <p>Ask a `Message` for this with `!message.getFileParams().isEmpty()` - never with
         * `getFileParams() != null`. That getter manufactures an empty `FileParams` for a message
         * with nothing attached, so a null check answers "yes" for every message there is, and a
         * gate that believes it refuses all of them.
         */
        @JvmField var hasFileParams: Boolean = false

        /** An edit, correction or retraction of an earlier message. */
        @JvmField var hasReplacement: Boolean = false

        @JvmField var hasReactions: Boolean = false

        @JvmField var encryption: Int = Message.ENCRYPTION_PGP

        @JvmField var body: String? = null

        /**
         * The name the interface shows for this message's conversation ([ConversationName]), or
         * `null` when the caller has none.
         *
         * <p>It is what [Ping] matches a whole-body message against, and its default is the safe
         * direction: with no name nothing is exempted, so a short message is translated rather than
         * silently skipped.
         */
        @JvmField var conversationName: String? = null

        /** Fluent setter, so the call site reads like the list of conditions it is. */
        fun live(received: Boolean, fromArchive: Boolean, delayed: Boolean): Candidate {
            this.received = received
            this.fromArchive = fromArchive
            this.delayed = delayed
            return this
        }
    }

    /**
     * May this message be queued by the *automatic* pass?
     *
     * <p>The [Interpreter] is a required argument, and it is the first thing consulted: with it off
     * the answer is `false` before the candidate is even read, so the off state is the one derived
     * setting this rule consults rather than a second decision every call site has to remember.
     * Required and not optional on purpose - a caller cannot compile without deciding which
     * interpreter it is asking for, which is what keeps "off" from being a thing people forget.
     *
     * <p>The archive checks are deliberately redundant: a MAM result carries both a query result
     * element and a delay stamp, and either one alone is disqualifying. History must never be
     * translated in bulk, so this refuses anything that looks like it rather than trusting one
     * signal. An explicit tap is a different question: see [isRequestable].
     */
    @JvmStatic
    fun isEligible(candidate: Candidate?, interpreter: Interpreter): Boolean {
        if (!interpreter.enabled() ||
                candidate == null ||
                candidate.fromArchive ||
                candidate.delayed) {
            return false
        }
        return isRequestable(candidate, interpreter)
    }

    /**
     * May this one message be translated because the owner *asked for it*, by tapping it?
     *
     * <p>This is [isEligible] without the archive rule, and with nothing else relaxed: who asked
     * changes what may be queued, never what may be bought. A body that is still ciphertext, a file,
     * an edit, a reaction or a body with no language at all cannot be translated by pointing at it
     * either, and asking would only pay for a blob or for nothing.
     *
     * <p>The [Interpreter] is required here too, and for the same reason it is required on
     * [isEligible]: with it off nothing may be bought, not even one message the owner pointed at -
     * an off interpreter has no covered bubble to tap, so there is nothing to ask for.
     */
    @JvmStatic
    fun isRequestable(candidate: Candidate?, interpreter: Interpreter): Boolean {
        if (!interpreter.enabled() || candidate == null || !candidate.received) {
            return false
        }
        if (candidate.deleted ||
                candidate.hasFileParams ||
                candidate.hasReplacement ||
                candidate.hasReactions) {
            return false
        }
        if (!isReadableBody(candidate.encryption)) {
            return false
        }
        return hasLanguage(candidate.body, candidate.conversationName)
    }

    /**
     * Whether the stored body is text Tulkki can read - because it is plain, because OTR or OMEMO
     * left it in the clear client-side, or because the PGP decryption service has replaced the
     * ciphertext and marked the row [Message.ENCRYPTION_DECRYPTED].
     *
     * <p>A body that is still ciphertext is not readable: PGP is stored encrypted and decrypted
     * afterwards, asynchronously, and `ENCRYPTION_DECRYPTION_FAILED` never became text at all.
     * Neither may be bought. And accepting the decrypted value does not open a bulk purchase:
     * nothing re-offers a decrypted row to the automatic pass (the decryption service is deliberately
     * not hooked into Tulkki), so it means exactly one thing - the owner's tap on that covered bubble
     * may buy that one message, counted against the daily cap like any other tap.
     *
     * <p>Public because the receive path names the condition that refused a message when it logs the
     * refusal, and a second copy of this list would be a second place for it to be wrong.
     */
    @JvmStatic
    fun isReadableBody(encryption: Int): Boolean =
            encryption == Message.ENCRYPTION_NONE ||
                    encryption == Message.ENCRYPTION_OTR ||
                    encryption == Message.ENCRYPTION_AXOLOTL ||
                    encryption == Message.ENCRYPTION_DECRYPTED

    /**
     * Whether there is language in here at all. A link, a verification code, a number or a row of
     * emoji is not "another language" - it is nothing to translate, and it must not be blurred as if
     * translation had failed either. Neither is a ping or the conversation's own bare name ([Ping]):
     * a name, with or without a colon, has no language, so nothing is sent to DeepSeek for it and
     * nothing is covered.
     *
     * <p><strong>One rule, both directions.</strong> This used to have a deliberate twin in
     * [ComposerGate] - the same question asked for an outgoing draft - and the two answered
     * differently about every link shape that was not a complete URI with no whitespace in it. This
     * copy called a bare `"https://"` language, so the receive path bought a translation of it, the
     * model handed the text back, [LanguageCheck] refused the echo and the bubble was left covered
     * with nothing to fix; the twin sent a scheme followed by a newline as typed. They were one
     * concept written twice, so [ComposerGate.verdict] asks this method now and the twin, its own
     * `MIN_LETTERS` and its prefix test are gone.
     *
     * <p>What the one rule says: a URI with no whitespace in it is nothing to translate, whether it
     * is a whole URL or a bare `"https://"`, and anything with whitespace inside is text that happens
     * to contain a URI and is translated like any other body. Whitespace means Java's `\s`, not a
     * literal space, which is the other half the twin got wrong. The schemes that are a scheme and a
     * colon and nothing more never reach the link test at all - [Ping] has already answered for them,
     * on both paths.
     *
     * @param body the body to ask about
     * @param conversationName the name the interface shows for the conversation it is in
     *     ([ConversationName]), or `null` when the caller has none - in which case only the ping
     *     shape can match
     */
    @JvmStatic
    fun hasLanguage(body: String?, conversationName: String?): Boolean {
        if (body == null) {
            return false
        }
        if (Ping.isPing(body, conversationName)) {
            return false
        }
        val text = body.javaTrim()
        if (text.isEmpty()) {
            return false
        }
        if (LINK_ONLY.matcher(text).matches()) {
            return false
        }
        var letters = 0
        for (i in 0 until text.length) {
            if (Character.isLetter(text[i]) && ++letters >= MIN_LETTERS) {
                return true
            }
        }
        return false
    }

    /**
     * The local decision, before anything is sent anywhere. `targetLanguage` is the app language.
     *
     * <p>`conversationName` is the name the interface shows for the conversation the body came from
     * ([ConversationName]), or `null` where the caller has none - the persistent queue carries no
     * name, and a body that reached it has already passed the name-aware check at the door, so a
     * null here only means the redundant second look cannot apply the name rule.
     *
     * <p>The [Interpreter] is required, and with it off the answer is [Verdict.NO_LANGUAGE] before
     * the body is read: it is the free verdict, so nothing downstream can see a translate verdict,
     * cover a bubble or queue a request for a message the plain client is simply showing as it
     * arrived.
     */
    @JvmStatic
    fun classify(
            body: String?,
            targetLanguage: String?,
            conversationName: String?,
            interpreter: Interpreter
    ): Verdict {
        if (!interpreter.enabled()) {
            return Verdict.NO_LANGUAGE
        }
        if (!hasLanguage(body, conversationName)) {
            return Verdict.NO_LANGUAGE
        }
        val guess = TextLanguage.detect(body)
        if (targetLanguage != null &&
                guess.isProbably(
                        ComposerGate.normalize(targetLanguage), TextLanguage.TRUSTWORTHY_CONFIDENCE)) {
            return Verdict.SAME_LANGUAGE
        }
        return Verdict.TRANSLATE
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and at [TranslationDecision.hasLanguage]
 * that changes the answer: `"https:// \u00A0"` still contains a space to Java - so it is a text that
 * happens to contain a URI - and is a bare link to Kotlin. The Java this replaces used
 * `String.trim()`, so this does too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
