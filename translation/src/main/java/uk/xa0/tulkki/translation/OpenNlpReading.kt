package uk.xa0.tulkki.translation

import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.TreeSet
import opennlp.tools.langdetect.Language
import opennlp.tools.langdetect.LanguageDetector
import opennlp.tools.langdetect.LanguageDetectorME
import opennlp.tools.langdetect.LanguageDetectorModel

/**
 * The third language identifier: Apache OpenNLP's maximum-entropy classifier, over its 103 languages.
 *
 * <p>It is here because three methods that fail differently are worth more than three spellings of
 * one: the shipped detector and Lingua are both n-gram profile comparisons on different training
 * data, and this is a classifier over character n-grams trained as a maximum-entropy model. When all
 * three name the same language as something other than the target, the check is not guessing.
 *
 * <p><strong>Its confidence is a margin, not a probability.</strong> OpenNLP spreads its probability
 * over 103 languages, so even a certain reading comes back around 0.05-0.09 where the other two
 * return 0.5-0.9; a shared threshold would make this reader abstain on every text there is. What is
 * reported here is therefore the reader's own certainty on the check's scale - how far the top
 * language stands above the runner-up, relative to the top - which is 1.0 when nothing else is close
 * and near zero for a text the model cannot place. That is what the check's floor is asking about, and
 * the [LanguageCheck.Reading] contract says so.
 *
 * <p><strong>Its codes are ISO 639-3 and the app's are ISO 639-1.</strong> The model answers `fin`,
 * `deu`, `ell`; the app names languages `fi`, `de`, `el`. The mapping is derived rather than typed
 * out, from the platform's own list of locales (`Locale#getISO3Language()`), so it cannot drift
 * from the JDK's table; a code it cannot map - OpenNLP's individual-language codes such as `cmn` for
 * Mandarin, which the platform knows only as `zho` - is one this reader declines to name, and the
 * other two readers still answer.
 *
 * <p>Everything that can go wrong ends in "no opinion": a model that cannot be read, a device where
 * the library does not load at all ([LinkageError], which is a real possibility on an old
 * Android and is caught here rather than allowed to break a message), an answer in a language this
 * reader cannot name. A third opinion that cannot be given must never become a message that cannot be
 * read.
 *
 * <p><strong>Package-private in the Java this replaces; public here, with `@JvmStatic`, because
 * Kotlin has no package-private and the Java tests call it across the package</strong>
 * (`OpenNlpReadingTest`, `TranslationLanguagesTest`). The widening is the one deliberate API change
 * in this file and it changes no behaviour. `read`, `iso1` and `certainty` keep their nullable
 * parameters (and `certainty` its nullable array), because the tests pass `null` to each and a
 * non-null Kotlin parameter would turn those cells into a runtime NPE.
 */
object OpenNlpReading {

    /** The bundled model, as a Java resource inside the APK. */
    private const val MODEL_RESOURCE = "/langdetect-183.bin"

    /** Below this many characters, a statistical reading is noise. */
    private const val MIN_LENGTH = 3

    private val LOCK = Any()

    private var detector: LanguageDetector? = null
    private var tried = false
    private var supported: Set<String>? = null

    /** ISO 639-3 to ISO 639-1, from the platform's own locales. */
    private val BY_ISO3: Map<String, String> = iso3ToIso1()

    /** This reader, in the shape [LanguageCheck] asks for. */
    @JvmStatic
    fun reader(): LanguageCheck.Reader =
            LanguageCheck.Reader { text, candidates -> read(text, candidates) }

    /**
     * OpenNLP's reading of `text`, or `null` when it has nothing to say. The candidates are ignored:
     * this model was trained over all 103 of its languages and answers about all of them, which is
     * exactly the independence the check wants from it.
     */
    @JvmStatic
    fun read(text: String?, candidates: Set<String>?): LanguageCheck.Reading? {
        if (text == null || text.javaTrim().length < MIN_LENGTH) {
            return null
        }
        val trimmed = text.javaTrim()
        synchronized(LOCK) {
            try {
                val local = detector() ?: return null
                val languages = local.predictLanguages(trimmed)
                if (languages == null || languages.isEmpty() || languages[0] == null) {
                    return null
                }
                val code = iso1(languages[0].lang) ?: return null
                return LanguageCheck.Reading(code, certainty(languages), false)
            } catch (e: RuntimeException) {
                // A reader that cannot run has no opinion. See the class comment: on a device where
                // this library does not load, the check runs on its other readers.
                return null
            } catch (e: LinkageError) {
                return null
            }
        }
    }

    /**
     * How far the winning language stands above the runner-up, relative to the winner: 1.0 when
     * nothing else is close, 0.0 when two languages are indistinguishable, which is what a text with
     * no signal looks like. The model's raw probabilities are over 103 languages and are never large,
     * so the margin is the thing that carries information.
     */
    @JvmStatic
    fun certainty(languages: Array<Language>?): Double {
        if (languages == null || languages.isEmpty() || languages[0] == null) {
            return 0.0
        }
        val top = languages[0].confidence
        if (top <= 0.0) {
            return 0.0
        }
        if (languages.size < 2 || languages[1] == null) {
            return 1.0
        }
        val margin = (top - languages[1].confidence) / top
        return maxOf(0.0, minOf(1.0, margin))
    }

    /** The languages this model can name, as the app spells them. */
    @JvmStatic
    fun languages(): Set<String> = TreeSet(BY_ISO3.values)

    /** The model's own supported codes, which are ISO 639-3. */
    @JvmStatic
    fun modelLanguages(): Set<String> = supportedCodes()

    /** The model's codes, read once: [iso1] is asked about them a great deal. */
    private fun supportedCodes(): Set<String> {
        val existing = supported
        if (existing != null) {
            return existing
        }
        synchronized(LOCK) {
            val again = supported
            if (again != null) {
                return again
            }
            val codes = TreeSet<String>()
            val local = detector()
            if (local != null) {
                val languages = local.supportedLanguages
                if (languages != null) {
                    Collections.addAll(codes, *languages)
                }
            }
            val unmodifiable: Set<String> = Collections.unmodifiableSet(codes)
            supported = unmodifiable
            return unmodifiable
        }
    }

    /**
     * The app's code for a model language, or `null` when there is nothing to map.
     *
     * <p>Two steps, because the model's codes are ISO 639-3 and the app speaks ISO 639-1: the
     * platform's own locale table does most of it (`deu` to `de`), and
     * [TranslationLanguages.appCode] covers the languages the two sides spell differently -
     * `cmn`, which the platform knows only as the collective `zho`, and `nob`,
     * which the platform maps to `nb` and the app calls `no`.
     */
    @JvmStatic
    fun iso1(iso3: String?): String? {
        if (iso3 == null) {
            return null
        }
        val lower = iso3.lowercase(Locale.ROOT)
        if (!supportedCodes().contains(lower)) {
            // Not a language this model knows at all - the model's own codes are the only ones that
            // mean anything here, so anything else has no reading to report rather than one spelled
            // out as a code nobody uses.
            return null
        }
        val twoLetter = BY_ISO3[lower]
        return TranslationLanguages.appCode(twoLetter ?: lower)
    }

    /** The detector, built once, or `null` when it cannot be built at all. */
    private fun detector(): LanguageDetector? {
        synchronized(LOCK) {
            if (tried) {
                return detector
            }
            tried = true
            try {
                val stream = OpenNlpReading::class.java.getResourceAsStream(MODEL_RESOURCE)
                if (stream != null) {
                    stream.use { detector = LanguageDetectorME(LanguageDetectorModel(it)) }
                }
            } catch (e: java.io.IOException) {
                detector = null
            } catch (e: RuntimeException) {
                detector = null
            } catch (e: LinkageError) {
                detector = null
            }
            return detector
        }
    }

    /**
     * The platform's own ISO 639-3 to ISO 639-1 table, read out of its locales rather than typed
     * here. First one wins, so a language whose regional variants share a three-letter code resolves
     * to whichever locale the platform lists first - and every one of them resolves to the same
     * two-letter code anyway.
     */
    private fun iso3ToIso1(): Map<String, String> {
        val codes = HashMap<String, String>()
        try {
            for (locale in Locale.getAvailableLocales()) {
                val language = locale.language
                if (language == null || language.isEmpty()) {
                    continue
                }
                try {
                    val iso3 = locale.getISO3Language()
                    if (iso3 != null && iso3.isNotEmpty()) {
                        codes.putIfAbsent(
                                iso3.lowercase(Locale.ROOT), language.lowercase(Locale.ROOT))
                    }
                } catch (e: RuntimeException) {
                    // A locale whose language has no three-letter code: nothing to map.
                    continue
                }
            }
        } catch (e: RuntimeException) {
            codes.clear()
        } catch (e: LinkageError) {
            codes.clear()
        }
        return codes
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and this class's Java read its input with
 * Java's `trim()`; the port keeps that, exactly as `ScriptReading` does.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
