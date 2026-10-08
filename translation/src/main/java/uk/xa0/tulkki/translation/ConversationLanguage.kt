package uk.xa0.tulkki.translation

import java.util.Locale

/**
 * What language this conversation is in, and how that is known.
 *
 * <p>Each conversation carries its own language - read from what the others write, before
 * translation - which is the target everything the owner sends is translated into. It is two stored
 * values rather than one: a detection and an override, the override winning when it is set, which is
 * the same resolution the send path makes. Which of the two won is what tells the owner whether
 * clearing it means anything: a detected language is the app's guess and can be replaced or cleared
 * back to guessing, an override is the owner's own answer and clearing it returns to detection.
 *
 * <p>Unknown means unknown. When neither is set the conversation has no send target at all, the send
 * path holds the message and says so, and the picker is how the owner resolves it - this class never
 * invents a language from the app language or from anything else.
 *
 * <p>The worded copy is the interface's: the bar builds it from [Resolved.source] and
 * [languageName], so the rules here stay pure Kotlin and are exercised by JVM unit tests.
 */
object ConversationLanguage {

    /** Where a conversation's language came from. */
    enum class Source {
        /** Nothing is known about it yet. */
        UNKNOWN,
        /** Read from the others' own messages, before they were translated. */
        DETECTED,
        /** The owner's own answer, which beats the guess. */
        SET
    }

    /**
     * The language to send in, and how it was arrived at.
     *
     * <p>The three accessors are methods and not Kotlin properties because Java reads them that way
     * (`resolved.code()`), and the constructor is internal because only this object makes one.
     */
    class Resolved
    internal constructor(
            private val codeValue: String,
            private val sourceValue: Source,
            private val detectedValue: String?
    ) {

        /** The language code, or [TextLanguage.UNKNOWN] when there is none. */
        fun code(): String = codeValue

        fun source(): Source = sourceValue

        /** The detected language, whether it won or not; `null` when nothing was detected. */
        fun detected(): String? = detectedValue

        /** True when this came from detection rather than from the owner. */
        val isAutomatic: Boolean
            get() = sourceValue == Source.DETECTED

        /** True when the owner has set this language themselves. */
        val isSet: Boolean
            get() = sourceValue == Source.SET

        /** True when there is no language at all, so nothing can be translated into it. */
        val isUnknown: Boolean
            get() = sourceValue == Source.UNKNOWN

        /** True when there is a language to translate into. */
        val isKnown: Boolean
            get() = !isUnknown

        /** The code as a store would hold it: `null` rather than "und" or blank. */
        fun storedCode(): String? = if (isUnknown) null else codeValue
    }

    /**
     * What one received message says this conversation's language is.
     *
     * <p>This is the design's rule made concrete. Detection is [TextLanguage], local and offline, and
     * it runs on the message *as it arrived* - the original text, before anything was translated. The
     * model's `lang` is a fallback for the one case where the offline detector has no opinion at all,
     * and it never overrules a local reading.
     *
     * <p>The four fields are `@JvmField`s because Java reads them as fields (`reading.language`), and
     * the constructor is internal because only [read] makes one.
     */
    class Reading
    internal constructor(
            /**
             * The language to store as the conversation's own, or `null` when the conversation's
             * stored language is to be left exactly as it is.
             */
            @JvmField val language: String?,
            /**
             * The language this one message is in, as far as the offline detector can say, or `null`
             * when it cannot be told. The model's answer is used here only as the fallback it is -
             * see [wasAlreadyIn].
             */
            @JvmField val messageLanguage: String?,
            /** The offline detector's reading of the original text; never `null`. */
            @JvmField val local: TextLanguage.Guess,
            /** The model's `lang` field as it was received, so a disagreement can name it. */
            @JvmField val modelLanguage: String?
    ) {

        /**
         * Whether this message was already in `targetLanguage`, so nothing needed to be bought and
         * the original is what belongs on the screen.
         *
         * <p>Offline first: the detector's reading of the original text is the verdict, and the
         * model's `lang` is consulted only when the detector has no opinion at all. A reading that
         * is not worth acting on is not a "yes" - when it cannot be told, the message is translated.
         * A needless call costs tokens; the other direction puts an untranslated message in front of
         * the person who cannot read it, which is the one thing the design forbids.
         */
        fun wasAlreadyIn(targetLanguage: String?): Boolean {
            val target = clean(targetLanguage)
            return target != null && target == messageLanguage
        }

        /**
         * True when the offline detector had an opinion and the model named a different language.
         * Worth a log line: one of the two is wrong, and this is where the choice is made.
         *
         * <p>`local` is non-null here, so the Java's own null check on it has nowhere left to fire.
         */
        fun disagrees(): Boolean {
            if (local.isUnknown()) {
                return false
            }
            val model = clean(modelLanguage)
            return model != null && !local.code.equals(model, ignoreCase = true)
        }
    }

    /**
     * The one decision about what a received message says the conversation's language is. Pure, and
     * deliberately not made anywhere near a request: this runs whether or not the message was ever
     * sent to DeepSeek.
     *
     * <p>The rule, in order:
     *
     * <ul>
     *   <li>a local reading is worth acting on only when the detector is at least
     *       [TextLanguage.TRUSTWORTHY_CONFIDENCE] sure *and* the message is prose. Confidence alone
     *       is not enough - the profiles read a bare name as a language ("Matti" comes back Maltese
     *       at 0.96), and a conversation's language decides what language the owner's next message is
     *       sent in;
     *   <li>when the detector has no opinion at all ([TextLanguage.UNKNOWN]), the model's `lang` is
     *       used instead - but only to fill a gap, never to replace a language the conversation
     *       already has;
     *   <li>a reading that is not worth acting on changes nothing. A weak guess never replaces an
     *       established language, and never establishes one either: "unknown" is a state the owner can
     *       resolve with the picker, and a wrong guess is not;
     *   <li>the app language is never recorded as a conversation's language: the room is not speaking
     *       Finnish just because that is what we translated into.
     * </ul>
     *
     * <p>[Reading.messageLanguage] is the same evidence asked the other question - is this message
     * itself already in the app language - and [Reading.wasAlreadyIn] is where the model's answer is
     * allowed to stand in, only when the detector has nothing to say.
     *
     * @param originalBody the message as it arrived, before translation
     * @param modelLanguage the `lang` field of the model's answer, or `null`
     * @param appLanguage the language this message was being translated into, or `null`
     * @param established the conversation's detected language as stored now, or `null`
     * @param conversationName the name the interface shows for this conversation
     *     ([ConversationName]), or `null` when the caller has none
     */
    @JvmStatic
    fun read(
            originalBody: String?,
            modelLanguage: String?,
            appLanguage: String?,
            established: String?,
            conversationName: String?
    ): Reading {
        val local = TextLanguage.detect(originalBody)
        if (Ping.isPing(originalBody, conversationName)) {
            // A ping, or the conversation's own bare name, is never a reading: "Matti" and "Matti:"
            // are precisely the bare-name shape the offline detector calls Maltese at 0.96, and a
            // nudge or a room's own name must not name a conversation. The detector's own answer is
            // discarded here rather than reported.
            return Reading(null, null, local, modelLanguage)
        }
        val app = clean(appLanguage)
        val current = clean(established)
        val model = clean(modelLanguage)

        // The one bar a local reading must clear (TextLanguage.TRUSTWORTHY_CONFIDENCE). What is this
        // site's own is the prose question beside it: a conversation's language is the target
        // everything the owner sends is translated into, so a reading that only just wins must not
        // silently send their next message in a language the other person cannot read.
        //
        // The `body != null` is Kotlin's, not the rule's: `isProse` takes a non-null `String`, and
        // the Java this replaces reached it with a body that could be null only because the chain
        // short-circuited first - `detect(null)` is always unknown, so `!local.isUnknown()` is
        // already false. Written first, it decides the same thing and lets the compiler see it.
        val body = originalBody
        val trustworthy =
                body != null &&
                        !local.isUnknown() &&
                        local.confidence >= TextLanguage.TRUSTWORTHY_CONFIDENCE &&
                        ComposerGate.isProse(body)

        // What this message itself is in. The model's answer is a fallback for the one case the
        // detector has no opinion about; a weak reading leaves this unknown, and unknown means the
        // message is translated rather than treated as already readable.
        val messageLanguage: String? =
                if (trustworthy) {
                    ComposerGate.normalize(local.code)
                } else {
                    if (local.isUnknown()) model else null
                }

        val conversation: String? =
                if (messageLanguage == null || messageLanguage == app) {
                    // Nothing to say, or the app language, which is never a room's language.
                    null
                } else if (trustworthy || current == null) {
                    // The offline detector always wins; the model's fallback only fills a gap.
                    messageLanguage
                } else {
                    null
                }
        return Reading(conversation, messageLanguage, local, modelLanguage)
    }

    /**
     * Resolves a stored pair. The override beats the detection, exactly as
     * `OutgoingTranslation.languageOf` does for the send path - the two must agree, or the bar would
     * claim a language the send does not use.
     */
    @JvmStatic
    fun resolve(detectedLanguage: String?, languageOverride: String?): Resolved {
        val detected = clean(detectedLanguage)
        val override = clean(languageOverride)
        if (override != null) {
            return Resolved(override, Source.SET, detected)
        }
        if (detected != null) {
            return Resolved(detected, Source.DETECTED, detected)
        }
        return Resolved(TextLanguage.UNKNOWN, Source.UNKNOWN, null)
    }

    /**
     * What the language is, for a sentence a person reads: "German", "Finnish", or "unknown". The
     * name is always English, like the rest of the interface.
     *
     * <p>The blank test is taken on the trimmed code, as the Java took it, so the unknown answer is
     * the same `"unknown"` for `null`, `""`, `"   "` and `"und"`.
     */
    @JvmStatic
    fun languageName(code: String?): String {
        val trimmed = code?.javaTrim() ?: ""
        if (ComposerGate.isUnknownLanguage(trimmed)) {
            return "unknown"
        }
        val name = Locale.forLanguageTag(trimmed).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isNullOrEmpty()) trimmed else name
    }

    /**
     * A language code as stored, with "no language" (`null`, blank, `und`) as null. Case and padding
     * are folded by [ComposerGate.normalize] - the one way Tulkki writes a language code down - so a
     * code written " DE " is the same language as "de" rather than a second, nameless one.
     */
    private fun clean(code: String?): String? {
        val normalized = ComposerGate.normalize(code)
        if (ComposerGate.isUnknownLanguage(normalized)) {
            return null
        }
        return normalized
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and [ConversationLanguage.languageName]
 * decides "unknown" on the trimmed form, so a code padded with a non-breaking space is unknown to
 * Kotlin and a real (if odd) code to Java. The Java this replaces used `String.trim()`, so this does
 * too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
