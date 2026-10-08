package uk.xa0.tulkki.translation

import com.github.pemistahl.lingua.api.IsoCode639_1
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import java.util.HashMap
import java.util.Locale
import java.util.TreeSet

/**
 * The second language identifier: Lingua's own models, asked about the languages in play.
 *
 * <p>The reason this exists at all is that the shipped detector is also the thing that <em>chose</em>
 * the target, so asking it alone whether the answer arrived in that target is asking the same
 * witness twice. Lingua is a different method on different training data, so its agreement means
 * something and its disagreement is worth covering a bubble for.
 *
 * <p><strong>It is asked only about the languages in play</strong> - the target, the app language,
 * the language the model claimed, and English - and that is a deliberate limitation as well as a
 * memory one. Lingua keeps every language model it loads in process-wide maps, and its models are
 * megabytes of JSON each: building a detector over all seventy-five languages would read a couple of
 * hundred megabytes of models into a phone's heap on the first translated message. A handful of
 * languages in play is a few megabytes, it is what the question actually needs ("is this the target,
 * or one of the few other languages that could plausibly be here?"), and breadth - naming an
 * unexpected language precisely - is [TextLanguage]'s job, with all seventy of its profiles.
 *
 * <p>The detector is rebuilt when the languages in play change, and the previous one's models are
 * unloaded as it goes, so a conversation in a new language does not quietly add to the heap for the
 * rest of the process's life. Everything here runs on whichever background thread is doing the
 * translation and holds one lock, because two concurrent readers sharing Lingua's static model maps
 * while one of them unloads is a race with no upside.
 *
 * <p>Failing is always allowed here: a model that cannot be read, a language Lingua does not know, an
 * input too short to say anything about - each answers "no opinion", and the check falls back to the
 * readers that do have one. A second opinion that cannot be given must never become a message that
 * cannot be read.
 *
 * <p><strong>Package-private in the Java this replaces; public here, with `@JvmStatic`, because
 * Kotlin has no package-private and the Java tests call it across the package</strong>
 * (`LinguaReadingTest`, `TranslationLanguagesTest`). The widening is the one deliberate API change in
 * this file and it changes no behaviour. Its two parameters stay nullable, because
 * `LinguaReadingTest.read(null, …)` is the no-opinion case and a non-null Kotlin parameter would turn
 * that cell into a runtime NPE.
 */
object LinguaReading {

    /** Below this many characters, a statistical reading is noise. */
    private const val MIN_LENGTH = 3

    private val LOCK = Any()

    /** The languages the detector in [detector] was built for, or `null`. */
    private var builtFor: Set<String>? = null

    private var detector: LanguageDetector? = null

    /** Every Lingua language by its ISO 639-1 code, which is how the app names languages. */
    private val BY_CODE: Map<String, Language> = byCode()

    /** This reader, in the shape [LanguageCheck] asks for. */
    @JvmStatic
    fun reader(): LanguageCheck.Reader =
            LanguageCheck.Reader { text, candidates -> read(text, candidates) }

    /**
     * Lingua's reading of `text`, over the languages in `candidates`, or `null` when it has nothing
     * to say.
     */
    @JvmStatic
    fun read(text: String?, candidates: Set<String>?): LanguageCheck.Reading? {
        if (text == null || text.javaTrim().length < MIN_LENGTH) {
            return null
        }
        val trimmed = text.javaTrim()
        synchronized(LOCK) {
            try {
                val local = detectorFor(candidates) ?: return null
                val values = local.computeLanguageConfidenceValues(trimmed)
                if (values == null || values.isEmpty()) {
                    return null
                }
                var best: Language? = null
                var confidence = 0.0
                for (entry in values.entries) {
                    val value = entry.value
                    if (value != null && (best == null || value > confidence)) {
                        best = entry.key
                        confidence = value
                    }
                }
                if (best == null) {
                    return null
                }
                val code = codeOf(best) ?: return null
                return LanguageCheck.Reading(code, confidence, false)
            } catch (e: RuntimeException) {
                // A detector that cannot be built or asked is a reader with no opinion. The check's
                // other two readers still answer, and a message is never covered because Lingua
                // could not speak.
                return null
            } catch (e: LinkageError) {
                return null
            }
        }
    }

    /**
     * The detector for these languages, built on first use and rebuilt only when the set changes.
     * Returns `null` when none of the candidates is a language Lingua knows.
     */
    private fun detectorFor(candidates: Set<String>?): LanguageDetector? {
        val wanted = known(candidates)
        if (wanted.isEmpty()) {
            return null
        }
        if (detector != null && wanted == builtFor) {
            return detector
        }
        val languages = ArrayList<Language>(wanted.size)
        for (code in wanted) {
            val language = BY_CODE[code]
            if (language != null) {
                languages.add(language)
            }
        }
        if (languages.isEmpty()) {
            return null
        }
        val built = LanguageDetectorBuilder.fromLanguages(*languages.toTypedArray()).build()
        if (detector != null) {
            // The models live in process-wide maps; dropping the detector alone would leave every
            // language it loaded in memory for the life of the process.
            detector?.unloadLanguageModels()
        }
        detector = built
        builtFor = wanted
        return detector
    }

    /** The candidates that Lingua has a model for. */
    private fun known(candidates: Set<String>?): Set<String> {
        val wanted = LinkedHashSet<String>()
        if (candidates != null) {
            for (candidate in candidates) {
                val code = ScriptReading.normalize(candidate)
                if (code != null && BY_CODE.containsKey(code)) {
                    wanted.add(code)
                }
            }
        }
        return wanted
    }

    /** Lingua's language for a code, as the app spells them. */
    private fun codeOf(language: Language): String? {
        try {
            val iso: IsoCode639_1? = language.isoCode639_1
            return if (iso == null) null else ScriptReading.normalize(iso.name)
        } catch (e: RuntimeException) {
            return null
        }
    }

    private fun byCode(): Map<String, Language> {
        val codes = HashMap<String, Language>()
        try {
            for (language in Language.values()) {
                // The app's spelling of the language, not Lingua's: Bokmål is the app's "no", and a
                // reading has to come back in the code everything else in the app compares against.
                val code = TranslationLanguages.appCode(codeOf(language))
                if (code != null) {
                    codes.putIfAbsent(code, language)
                }
            }
        } catch (e: RuntimeException) {
            // No Lingua at all: every read answers "no opinion" and the check runs on its other two
            // readers.
            codes.clear()
        } catch (e: LinkageError) {
            codes.clear()
        }
        return codes
    }

    /** The languages Lingua can speak about, for the docs and for a test to hold this to. */
    @JvmStatic
    fun languages(): Set<String> = LinkedHashSet(TreeSet(BY_CODE.keys))

    /** A code as the app writes it, for a caller that has one in another case. */
    @JvmStatic
    fun normalizeCode(code: String?): String? =
            ScriptReading.normalize(code?.lowercase(Locale.ROOT))
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and this class's Java read its input with
 * Java's `trim()`; the port keeps that, exactly as `ScriptReading` does.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
