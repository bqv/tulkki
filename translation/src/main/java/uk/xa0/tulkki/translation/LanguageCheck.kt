package uk.xa0.tulkki.translation

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Did a translation actually happen?
 *
 * <p>The owner's report is that too often what they send is not translated and what arrives is not
 * either - and the app drew both as though they were fine. This is the check that refuses to: it
 * reads the text that came back and either confirms it is in the language that was asked for or says
 * which check failed, and a failure takes the outcome the app already has for a body it cannot trust
 * - the message is covered, the reason is named, and nothing is drawn as a translation that is not
 * one.
 *
 * <p><strong>It fails on evidence, never on doubt.</strong> Covering costs the owner a tap on a
 * bubble, so "the readers could not agree" is not a failure: it is drawn as the translation it
 * probably is. What does fail is an answer that *is* the input unchanged, an empty answer, and a
 * language that independent readers agree is not the target - or one reader that cannot be wrong,
 * which is [ScriptReading] speaking about a writing system no other language here uses.
 *
 * <p><strong>Three identifiers and one fact</strong>, which is what makes agreement worth anything:
 * the shipped n-gram detector ([TextLanguage]), Lingua's models ([LinguaReading]), OpenNLP's
 * maximum-entropy classifier ([OpenNlpReading]), and the writing system the text is physically
 * written in ([ScriptReading]). The first two are asked about the languages actually in play - the
 * target, the app language and the language the model said the input was in - so a reading is a
 * comparison rather than a lottery over seventy languages; OpenNLP answers about all 103 of its own,
 * which is exactly the independence the check wants from it.
 *
 * <p><strong>The report never contains the text.</strong> A reason is stored as a failure's detail,
 * listed on the failures screen and quoted in the composer, so it names languages and outcomes and
 * nothing else, and a test pins that.
 *
 * <p>Stated so it is not oversold: this catches the wrong language, the input echoed back and the
 * empty answer. "In the right language and wrong" is a quality judgement, and no on-device check
 * makes it.
 *
 * <p>Pure Kotlin, no Android types, and the judgement is separable from the readers ([judge]), so
 * both are exercised by JVM unit tests without a device.
 *
 * <p>Two members were package-private in the Java this replaces and are public here, because Kotlin
 * has no package-private and Java callers (the tests) reach them: [judge], [readers] and
 * [useReaders]. The widening is deliberate and changes no behaviour.
 */
object LanguageCheck {

    /**
     * How sure a statistical reader has to be before its reading counts as a vote. Below this a
     * reader is guessing at a short message, and a guess must not cover a good translation.
     */
    const val MIN_CONFIDENCE = 0.30

    /**
     * The shortest text an unchanged answer is a failure for. "Super!" is a real translation of
     * "Super!"; a sentence handed back word for word is not, and length is what tells them apart
     * without asking a detector to be certain about six characters.
     */
    private const val MIN_ECHO_LENGTH = 12

    /**
     * One reader's opinion of one text.
     *
     * <p>`confidence` is the reader's own certainty on a common 0..1 scale, not a probability that
     * means the same thing in each library: the profile detectors report theirs, OpenNLP reports how
     * far its winner stands above its runner-up (its probabilities are spread over 103 languages and
     * are never large), and the script reader reports certainty. The check only ever asks whether a
     * reading clears [MIN_CONFIDENCE].
     */
    class Reading(
            @JvmField val code: String?,
            @JvmField val confidence: Double,
            /** True for [ScriptReading], whose readings are certainties rather than estimates. */
            @JvmField val script: Boolean
    ) {

        /** A reading worth counting: a language, sure enough, from a reader that had an opinion. */
        internal fun counts(): Boolean =
                ScriptReading.normalize(code) != null && confidence >= MIN_CONFIDENCE

        override fun toString(): String =
                (code ?: TextLanguage.UNKNOWN) + if (script) "!" else "($confidence)"
    }

    /** A way of asking what language a text is in. */
    fun interface Reader {
        /**
         * @param text the text to read
         * @param candidates the languages in play, as a hint; a reader may ignore it
         * @return the language this reader sees, or `null` for no opinion
         */
        fun read(text: String, candidates: Set<String>): Reading?

        /**
         * What to call this reader in a diagnostic sentence ("Lingua said Finnish with confidence
         * 0.40"). The default names no library, which is all a reader that does not give itself a
         * name deserves; the four the app runs each give themselves their own
         * ([LanguageReadings]). It lives here so the diagnostic record can say which identifier said
         * what without this class keeping a second list of names that could fall out of order with
         * the readers themselves.
         */
        fun label(): String = "reader"
    }

    /** What the check concluded. */
    enum class Outcome {
        /** The answer is in the language that was asked for, as far as the readers can tell. */
        OK,
        /** Nothing came back, or nothing but whitespace. */
        NOTHING_CAME_BACK,
        /** The answer is the input, unchanged, so nothing was translated. */
        ECHOED,
        /** The answer is in a language that is not the one that was asked for. */
        WRONG_LANGUAGE
    }

    /**
     * What the owner's tap on a *held* outgoing message must do.
     *
     * <p>A type rather than a sentence because the two doubt kinds want **opposite** taps, and
     * flattening them into one "doubtful" verdict is the easy mistake: sending an answer that is not
     * a translation sends the original, which is the bug this whole check exists to stop.
     */
    enum class Tap {
        /**
         * Buy a fresh answer. Nothing was translated, so the held answer *is* the original.
         */
        RE_TRANSLATE,
        /**
         * Send the held answer as it stands. Something was translated, in a language no reader could
         * vouch for; the owner's tap is what accepts that risk.
         */
        SEND_AS_HELD
    }

    /**
     * The doubt a *send-path* answer was accepted on: the third kind beside [Outcome.OK] and the
     * failures (docs/MIGRATION.md item 16).
     *
     * <p>Only the send path consults it. For display `failed()` is false for both members, so a
     * received message is still drawn as the translation it probably is - "doubt is not evidence" is
     * unchanged where text is drawn, and this type exists so the send path can tell the two apart
     * without a sentence someone may tidy away.
     */
    enum class Doubt(val tap: Tap, val because: String) {
        /**
         * The answer is the input handed back, too short for [echo] to be allowed to call it one:
         * "Super!" really is the translation of "Super!". Nothing was translated.
         */
        NOTHING_TRANSLATED(
                Tap.RE_TRANSLATE,
                "the answer is the text that was sent, unchanged: nothing was translated"),
        /**
         * Nothing but a single reader's dissent stands between this answer and the target, and one
         * voice against the target is doubt rather than evidence.
         */
        DOUBTFUL_LANGUAGE(
                Tap.SEND_AS_HELD,
                "no reader could vouch for the language the answer came back in")
    }

    /** The verdict, and the sentence that goes with it. */
    class Report internal constructor(
            @JvmField val outcome: Outcome,
            /** The language that was read instead, when one was. For the reason's own wording. */
            @JvmField val language: String?,
            /** Why this failed, in words that name languages and never the text. Empty when it did not. */
            @JvmField val because: String,
            /** The doubt this answer was accepted on, or `null`. Only the send path reads it. */
            @JvmField val doubt: Doubt? = null
    ) {

        /** True for a failure, and false for an accepted-on-doubt answer as well as for a plain OK. */
        fun failed(): Boolean = outcome != Outcome.OK

        /** True when this answer is held on doubt rather than refused: the send path's third kind. */
        fun acceptedOnDoubt(): Boolean = doubt != null

        /**
         * What the owner's tap does with a *held* message, or `null` when this verdict holds none.
         *
         * <p>`ECHOED` and `NOTHING_CAME_BACK` are not "probably fine": nothing was translated, so
         * their tap buys a fresh answer exactly as [Doubt.NOTHING_TRANSLATED]'s does. A refusal
         * ([Outcome.WRONG_LANGUAGE]) is not a doubt either, and its own tap is its own business.
         */
        fun tap(): Tap? =
                when (outcome) {
                    Outcome.ECHOED, Outcome.NOTHING_CAME_BACK -> Tap.RE_TRANSLATE
                    Outcome.OK -> doubt?.tap
                    else -> null
                }

        override fun toString(): String =
                when {
                    failed() -> "$outcome: $because"
                    doubt != null -> "OK on doubt ($doubt): $because"
                    else -> "OK"
                }
    }

    private val OK = Report(Outcome.OK, null, "")

    /**
     * One call's verdict together with every reader's own reading, so a diagnostic can name the
     * readers and their confidences instead of reducing the attempt to a single word.
     *
     * <p>[readings] and [readers] are aligned and the same length: a reader that had no opinion - or
     * could not run - is a `null` reading in its own position rather than an omission, because "the
     * script reader said nothing" is a fact worth showing. Both are empty when the check answered
     * before asking any reader ([Outcome.ECHOED], [Outcome.NOTHING_CAME_BACK], and the short echo
     * that is [Doubt.NOTHING_TRANSLATED]); a diagnostic says so rather than inventing readers that
     * were never asked.
     *
     * <p>Read through [LanguageCheck.inspect], which is what [of] is built on: the two are one code
     * path, so the recorded facts can never disagree with the verdict.
     */
    class Inspection
    internal constructor(
            @JvmField val report: Report,
            @JvmField val readings: List<Reading?>,
            @JvmField val readers: List<String>
    )

    @Volatile private var readersInstance: List<Reader>? = null

    /**
     * The readers, built once. Reading the second and third detectors' models is not free, so this
     * happens on whichever background thread asks first and never on the main one.
     *
     * <p>The list is wrapped in `Collections.unmodifiableList` exactly as the Java did: Kotlin's
     * `listOf` gives a fixed-size list that a Java caller could still `set`, and the contract here is
     * that nobody mutates the readers.
     */
    @JvmStatic
    fun readers(): List<Reader> {
        readersInstance?.let {
            return it
        }
        synchronized(this) {
            readersInstance?.let {
                return it
            }
            val built =
                    Collections.unmodifiableList(
                            listOf<Reader>(
                                    LanguageReadings.shipped(),
                                    LanguageReadings.lingua(),
                                    LanguageReadings.openNlp(),
                                    LanguageReadings.script()))
            readersInstance = built
            return built
        }
    }

    /** For tests that pin the judgement, and for a reader that needs no models at all. */
    @JvmStatic
    fun useReaders(replacement: List<Reader>?) {
        readersInstance = if (replacement == null) null else Collections.unmodifiableList(replacement)
    }

    /**
     * The check itself.
     *
     * @param input the text that was sent to be translated
     * @param answer what came back
     * @param targetLanguage the language the answer was asked for in
     * @param appLanguage the app language, one of the languages in play
     * @param modelLanguage the language the model said the input was in, or `null`
     */
    @JvmStatic
    fun of(
            input: String?,
            answer: String?,
            targetLanguage: String?,
            appLanguage: String?,
            modelLanguage: String?
    ): Report = inspect(input, answer, targetLanguage, appLanguage, modelLanguage).report

    /**
     * The same judgement as [of], with every reader's own reading kept beside it.
     *
     * <p>One code path, not two: [of] is this method's verdict and nothing else, so the diagnostic
     * record built from an [Inspection] can never disagree with what the check actually decided. A
     * reader that has no opinion is a `null` reading in its own place, and a reader that cannot run
     * counts as one; [readAll] is where both are turned into "nothing", exactly as the private
     * reading loop [of] used to hold did.
     */
    @JvmStatic
    fun inspect(
            input: String?,
            answer: String?,
            targetLanguage: String?,
            appLanguage: String?,
            modelLanguage: String?
    ): Inspection {
        val target = ScriptReading.normalize(targetLanguage) ?: return Inspection(OK, emptyList(), emptyList())
        if (answer == null || answer.javaTrim().isEmpty()) {
            return Inspection(
                    Report(Outcome.NOTHING_CAME_BACK, null, "the answer came back empty"),
                    emptyList(),
                    emptyList())
        }
        if (echo(input, answer, target, modelLanguage)) {
            return Inspection(
                    Report(
                            Outcome.ECHOED,
                            null,
                            "the answer is the text that was sent, unchanged: nothing was translated"),
                    emptyList(),
                    emptyList())
        }
        if (shortEcho(input, answer, target, modelLanguage)) {
            // The same fact [echo] refuses to call an echo, because short texts are exempt there so
            // that "Super!" is not failed as its own translation. That exemption is right for what
            // is drawn and wrong for what is sent, so it is a doubt here rather than a pass.
            return Inspection(Report(Outcome.OK, null, "", Doubt.NOTHING_TRANSLATED), emptyList(), emptyList())
        }
        val asked = readers()
        val readings = readAll(answer, candidates(target, appLanguage, modelLanguage, input), asked)
        return Inspection(judge(target, readings), readings, asked.map { it.label() })
    }

    /**
     * The judgement, separable from the readers so it can be pinned without loading any models: what
     * the readings say about this answer.
     *
     * <p>`reading` is nullable in the element type because a Java caller can hand in a list holding
     * one, and the Java this replaces skipped it rather than failing.
     */
    @JvmStatic
    fun judge(targetLanguage: String?, readings: List<Reading?>): Report {
        val target = ScriptReading.normalize(targetLanguage) ?: return OK
        val votes = LinkedHashMap<String, Int>()
        var targetSeen = false
        var scriptLanguage: String? = null
        for (reading in readings) {
            if (reading == null || !reading.counts()) {
                continue
            }
            val code = ScriptReading.normalize(reading.code) ?: continue
            if (code == target) {
                targetSeen = true
            } else if (reading.script) {
                // A writing system only one of the languages in play uses. That is not a vote, it is
                // a fact about the text, and no other reading can outvote it.
                scriptLanguage = code
            } else {
                votes[code] = (votes[code] ?: 0) + 1
            }
        }
        scriptLanguage?.let {
            return wrong(it, target)
        }
        if (targetSeen) {
            // One reader recognising the target outranks the others' dissent: a reader with a small
            // candidate set is asked "could this be the target?", and when it answers yes, that is
            // the question the check actually cares about.
            return OK
        }
        var against = 0
        var chosen: String? = null
        var best = 0
        for ((code, count) in votes) {
            against += count
            if (count > best) {
                best = count
                chosen = code
            }
        }
        // `chosen` is null only when there are no votes at all, and then `against` is zero too, so
        // the two conditions below are one decision rather than a null check bolted on.
        if (against < 2 || chosen == null) {
            // One reader against the target, and no reader for it. The shipped detector is wrong
            // about short chat messages often enough that a single dissent is doubt, and doubt is
            // not evidence: this is drawn as the translation it probably is, and only the send path
            // is told which kind of doubt it is.
            return Report(Outcome.OK, null, "", Doubt.DOUBTFUL_LANGUAGE)
        }
        // Two readers, neither of them the target. They do not have to agree on what the language
        // *is* - the second reader is asked about a handful of languages in play, so its answer is
        // "not the target, and closer to this one" - they agree about the thing that matters.
        return wrong(chosen, target)
    }

    /**
     * Whether the answer is the thing that was sent, in the tolerant form the words are compared in
     * ([ComposerGate.comparisonForm]: case folded, whitespace collapsed, trailing punctuation
     * dropped). That is the right question here and not the gate's - a model that hands back
     * "Matti: hello." for "Matti: hello" has echoed the input, which is this check's failure, while
     * the composer's gate asks something stricter and deliberately different (was the owner's draft
     * already the app language, character for character).
     *
     * <p>Two conditions keep this from failing honest work: short texts are exempt, because "Super!"
     * really is the translation of "Super!", and the input has to look foreign - the model's own
     * reading of it, or the shipped detector's - because an echo of a text that was already in the
     * target is not a failure but the correct answer.
     */
    private fun echo(input: String?, answer: String, target: String, modelLanguage: String?): Boolean {
        val sent = ComposerGate.comparisonForm(input)
        if (sent.length < MIN_ECHO_LENGTH) {
            return false
        }
        if (sent != ComposerGate.comparisonForm(answer)) {
            return false
        }
        return inputLooksForeign(input, target, modelLanguage)
    }

    /**
     * The same comparison as [echo], for the texts [echo] deliberately exempts: under
     * [MIN_ECHO_LENGTH] an echo is not called one, because "Super!" really is the translation of
     * "Super!". That exemption is exactly right for display and wrong for sending, so the same fact
     * is reported here as the doubt the send path holds on ([Doubt.NOTHING_TRANSLATED]).
     */
    private fun shortEcho(input: String?, answer: String, target: String, modelLanguage: String?): Boolean {
        val sent = ComposerGate.comparisonForm(input)
        if (sent.isEmpty() || sent.length >= MIN_ECHO_LENGTH) {
            return false
        }
        if (sent != ComposerGate.comparisonForm(answer)) {
            return false
        }
        return inputLooksForeign(input, target, modelLanguage)
    }

    /** Whether the text that was sent was in something other than the target language. */
    private fun inputLooksForeign(input: String?, target: String, modelLanguage: String?): Boolean {
        val claimed = ScriptReading.normalize(modelLanguage)
        if (claimed != null && claimed != target) {
            return true
        }
        val guess = TextLanguage.detect(input)
        return !guess.isUnknown() &&
                guess.confidence >= MIN_CONFIDENCE &&
                guess.code != target
    }

    /** The failure's sentence. Languages only, never the text: this is stored and shown. */
    private fun wrong(language: String, target: String): Report =
            Report(
                    Outcome.WRONG_LANGUAGE,
                    language,
                    "the answer is in " +
                            name(language) +
                            ", not " +
                            name(target) +
                            ": the translation did not come back in the language it was asked for")

    /**
     * A language's name for the reason's own wording. Not limited to the languages the app offers: a
     * reading of a language outside the shared list is still worth naming properly, and the platform
     * knows how.
     */
    private fun name(code: String): String = ConversationLanguage.languageName(code)

    /**
     * Every reader's reading of the answer, in the readers' own order, with a `null` where a reader
     * had no opinion or could not run.
     *
     * <p>The `null`s are kept rather than dropped, which is the one difference from the loop this
     * replaces: the judgement skips them either way ([judge] already ignored a null element, because
     * a Java caller could hand one in), and a diagnostic can say which identifier abstained.
     */
    private fun readAll(text: String, candidates: Set<String>, readers: List<Reader>): List<Reading?> =
            readers.map { reader ->
                try {
                    reader.read(text, candidates)
                } catch (e: RuntimeException) {
                    // A reader that cannot run has no opinion - never a failed translation, and never
                    // a crash in the middle of somebody's message.
                    null
                }
            }

    /** The languages the readers should be asked about: those in play, and no more. */
    private fun candidates(
            target: String,
            appLanguage: String?,
            modelLanguage: String?,
            input: String?
    ): Set<String> {
        val codes = LinkedHashSet<String>()
        codes.add(target)
        val app = ScriptReading.normalize(appLanguage)
        if (app != null) {
            codes.add(app)
        }
        codes.add("en")
        val claimed = ScriptReading.normalize(modelLanguage)
        if (claimed != null) {
            codes.add(claimed)
        }
        val script = ScriptReading.language(input)
        if (TextLanguage.UNKNOWN != script) {
            codes.add(script)
        }
        return Collections.unmodifiableSet(codes)
    }

    /**
     * A lower-cased code for a comparison, or `null`. Kept here so callers need one import.
     *
     * <p>Package-private and unused in the Java this replaces; kept as `internal` so the translation
     * does not also become a deletion, and so a Kotlin caller inside the module can still reach it.
     */
    internal fun code(language: String?): String? =
            ScriptReading.normalize(language?.lowercase(Locale.ROOT))
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead. Here that is the difference between an
 * answer of one non-breaking space being "empty" and being a one-character answer, so the Java
 * reading - which the Java this replaces used - is the one kept.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
