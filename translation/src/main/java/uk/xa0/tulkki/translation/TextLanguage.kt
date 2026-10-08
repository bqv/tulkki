package uk.xa0.tulkki.translation

import com.optimaize.langdetect.DetectedLanguage
import com.optimaize.langdetect.LanguageDetector
import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfile
import com.optimaize.langdetect.profiles.LanguageProfileReader
import java.io.IOException
import java.util.Locale

/**
 * What language is this text in?
 *
 * <p>Two things in Tulkki need this. Incoming: a message already in the target language must not be
 * sent to DeepSeek at all. Outgoing: the language to translate into is the language of the room,
 * which is the language the other people there are writing in.
 *
 * <p>Detection is local and offline on purpose - it must not cost a request, and it must work on
 * messages we are deliberately not sending anywhere. The profiles are trained text, and chat
 * messages are short, so [detect] also reports how sure it is: callers decide what to do with a weak
 * guess rather than the guess being silently final.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object TextLanguage {

    /** Below this many characters there is no signal worth acting on ("ok", "ja", "+1"). */
    private const val MIN_LENGTH = 3

    /**
     * Below this length the library switches to its short-text algorithm, which weights character
     * n-grams differently because word n-grams have nothing to work with.
     */
    private const val SHORT_TEXT_LIMIT = 100

    /** What [detect] returns when it has no opinion. */
    const val UNKNOWN = "und"

    /**
     * How sure the offline detector has to be before a local reading is acted on.
     *
     * <p>One name for one number, and it belongs here because this class owns [Guess.confidence]:
     * the receive path asks it for "already the app language" ([TranslationDecision.classify]), the
     * composer gate for "confidently another language" ([ComposerGate.verdict]), and
     * [ConversationLanguage.read] and [LanguageSample] for "may this message name the conversation".
     * All of those used to spell `0.90` in their own constant, each with a comment saying it was the
     * same bar as the others for the same reason - which is four places to re-tune one of them by
     * accident.
     *
     * <p>The bar's *reason* differs by direction, and that is why it is worth one name rather than
     * one call: a weak guess is never final (a false refusal costs the owner a retype), a strong one
     * is worth acting on, and a reading that only just wins must not silently send their next
     * message in a language the other person cannot read. What the bar is *not* is a length rule or
     * a prose rule: those are separate questions asked at each site, because the profiles read one
     * token as whatever it resembles - `"Matti"` comes back Maltese at 0.96.
     */
    const val TRUSTWORTHY_CONFIDENCE = 0.90

    /** A message this short can still be translated, it just says nothing about the room. */
    @Volatile private var detectorInstance: LanguageDetector? = null

    /** A language code, and how much to trust it. */
    class Guess internal constructor(@JvmField val code: String, @JvmField val confidence: Double) {

        fun isUnknown(): Boolean = UNKNOWN == code

        /** True when this is `code` and the detector is at least `min` sure. */
        fun isProbably(code: String, min: Double): Boolean = this.code == code && confidence >= min

        override fun toString(): String = String.format(Locale.ROOT, "%s(%.2f)", code, confidence)
    }

    /**
     * The best guess for `text`, or [UNKNOWN] when it is too short or gives no answer.
     *
     * <p>Building the detector reads every language profile, so the first call is expensive and
     * should not happen on the main thread.
     */
    @JvmStatic
    fun detect(text: String?): Guess {
        if (text == null) {
            return Guess(UNKNOWN, 0.0)
        }
        val trimmed = text.javaTrim()
        if (trimmed.length < MIN_LENGTH) {
            return Guess(UNKNOWN, 0.0)
        }
        val probabilities: List<DetectedLanguage>
        try {
            probabilities = detector().getProbabilities(trimmed)
        } catch (e: RuntimeException) {
            // A pathological message must never take the caller down with it.
            return Guess(UNKNOWN, 0.0)
        }
        if (probabilities.isEmpty()) {
            return Guess(UNKNOWN, 0.0)
        }
        val best = probabilities[0]
        return Guess(best.getLocale().getLanguage().lowercase(Locale.ROOT), best.getProbability())
    }

    private fun detector(): LanguageDetector {
        val existing = detectorInstance
        if (existing != null) {
            return existing
        }
        synchronized(this) {
            val again = detectorInstance
            if (again != null) {
                return again
            }
            val built = build()
            detectorInstance = built
            return built
        }
    }

    private fun build(): LanguageDetector {
        val profiles: List<LanguageProfile>
        try {
            profiles = LanguageProfileReader().readAllBuiltIn()
        } catch (e: IOException) {
            // The profiles are Java resources inside the APK; losing them is a packaging bug.
            throw IllegalStateException("language profiles are missing from the build", e)
        }
        return LanguageDetectorBuilder.create(NgramExtractors.standard())
                .withProfiles(profiles)
                .shortTextAlgorithm(SHORT_TEXT_LIMIT)
                .build()
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace, including the non-breaking space, and the Java
 * this replaces used `String.trim()`. Here the difference is a length decision - a body of one
 * non-breaking space is "too short" to Kotlin and one character long to Java - so the Java reading
 * is the one kept.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
