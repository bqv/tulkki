package uk.xa0.tulkki.translation

import java.util.Collections

/**
 * The languages Tulkki lets the owner name: the app language, and a conversation's language.
 *
 * <p><strong>Every one of them is a language all three identifiers can name.</strong> That is not
 * decoration. Post-translation, three readers are asked whether an answer arrived in the language it
 * was asked for, and a language only one of them can name would make their agreement meaningless for
 * it - the third opinion would be silent exactly where it is needed. The app language is what the
 * composer gate compares a draft against and what the receive path compares a message against, and a
 * conversation's language is the send target, so a language no detector can name would make every
 * message look foreign and every draft look wrong.
 *
 * <p>So this is the **intersection** of the three: the 69 language subtags of
 * `com.optimaize.languagedetector`'s 70 bundled profiles, Lingua's 75 languages, and the 103
 * that OpenNLP's `langdetect` model knows, each mapped to the subtag
 * [TextLanguage.detect] would report. That is 56 languages, and the ones it leaves out
 * are left out for a stated reason rather than by accident: the thirteen (`an`, `ast`,
 * `br`, `gl`, `ht`, `km`, `kn`, `ml`, `mt`, `ne`,
 * `oc`, `tl`, `yi`) are languages one of the three has no model for at all.
 *
 * <p>Two languages are here only because the detectors spell them differently, and [appCode]
 * is that translation: opennlp's model calls Mandarin `cmn` where the app and the other two
 * call it `zh`, and both new detectors call Norwegian `nob`/`nb` (Bokmål, the
 * written standard the app means by `no`) where the app calls it `no`. A spelling is not
 * a coverage gap, so the aliases are applied wherever a detector's own code is read - in the readers
 * themselves and in the test below - rather than being papered over by hand in this list.
 * `TranslationLanguagesTest` computes the intersection from the three libraries themselves and
 * asserts this list equals it, so the list cannot drift from the detectors and a detector upgrade
 * announces itself as a test failure rather than as a language that quietly stopped being usable.
 *
 * <p>A code the list does not hold can still be <em>stored</em> - a language chosen by an older
 * build, or by something other than this screen - and it is kept and shown rather than silently
 * dropped; it is just not offered as a choice. Finnish is first, as the language this app was built
 * around - not as a default, which is now the device's locale. Every code
 * is lower case, because `ComposerGate.normalize` lower cases a code before comparing it and
 * [isKnown] compares the result against this list - an upper-case entry here would be
 * unreachable. Pure Kotlin, so it is exercised by JVM unit tests.
 */
object TranslationLanguages {

    /**
     * The languages the app language and a conversation's language may be set to: Finnish first,
     * then the rest in alphabetical order.
     */
    private val CODES: List<String> =
            Collections.unmodifiableList(
                    listOf(
                            "fi",
                            "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
                            "el", "en", "es", "et", "eu", "fa", "fr", "ga", "gu", "he",
                            "hi", "hr", "hu", "id", "is", "it", "ja", "ko", "lt", "lv",
                            "mk", "mr", "ms", "nl", "no", "pa", "pl", "pt", "ro", "ru",
                            "sk", "sl", "so", "sq", "sr", "sv", "sw", "ta", "te", "th",
                            "tr", "uk", "ur", "vi", "zh"))

    /**
     * The codes a detector answers with that are not the app's own spelling of the same language.
     *
     * <p>A spelling is not a coverage gap, so this is the one place that knows both sides of it. Each
     * entry says what the app calls the language the detector named: opennlp's model has Mandarin as
     * the individual language `cmn` where the platform's own table knows only the collective
     * `zho`, and both new detectors have Norwegian as Bokmål - `nob` at OpenNLP,
     * `nb` at Lingua, which the platform's table maps `nob` onto - which is the written
     * standard the app means by `no`. Nynorsk is deliberately absent: `nn` is a different
     * written language, it is not what this app offers, and mapping it onto `no` would make a
     * Nynorsk text read as the one Norwegian the app knows.
     */
    private val DETECTOR_ALIASES: Map<String, String> =
            mapOf(
                    "cmn" to "zh",
                    "zho" to "zh",
                    "nb" to "no",
                    "nob" to "no",
                    "nor" to "no")

    /**
     * The app's code for a code a detector answered with, aliases included, or `null` when
     * there is nothing to map. Both two-letter and three-letter detector codes are accepted, because
     * both kinds arrive: Lingua answers in ISO 639-1 and opennlp in ISO 639-3.
     *
     * <p>Package-private in the Java this replaces; Kotlin has no package-private, so it is a public
     * `@JvmStatic` member of the object - the `ScriptReading` precedent - which is the one deliberate
     * API change in this file and no behaviour change.
     */
    @JvmStatic
    fun appCode(detectorCode: String?): String? {
        val code = ComposerGate.normalize(detectorCode)
        if (code.isEmpty()) {
            return null
        }
        return DETECTOR_ALIASES[code] ?: code
    }

    /** Every selectable language, Finnish first. Immutable. */
    @JvmStatic fun codes(): List<String> = CODES

    /**
     * Whether `code` names one of them, however it is cased or padded. A language chosen
     * elsewhere may not be in the list - it is still kept and shown rather than silently dropped,
     * it is just not offered as a choice.
     */
    @JvmStatic
    fun isKnown(code: String?): Boolean =
            code != null && CODES.contains(ComposerGate.normalize(code))
}
