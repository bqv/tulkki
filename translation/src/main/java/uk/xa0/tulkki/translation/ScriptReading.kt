package uk.xa0.tulkki.translation

import java.util.HashMap
import java.util.Locale

/**
 * What writing system a text is in, when that alone names the language.
 *
 * <p>The third opinion in [LanguageCheck], and the one that cannot be wrong about what it claims: a
 * text whose letters are Greek is Greek, whatever any statistical detector thinks. It is therefore
 * also the narrowest of the three - it speaks only when a script belongs to exactly one language the
 * app can name, and abstains everywhere else rather than guessing.
 *
 * <p><strong>It reads the dominant script, not the presence of a character.</strong> A Finnish
 * message quoting a Greek word is not a Greek message, so a script has to cover most of the text's
 * letters before it counts. That is the difference between evidence and an anecdote, and it is why
 * this reader needs no confidence figure of its own: when it speaks, it is sure.
 *
 * <p>The scripts that are deliberately *not* here are the interesting half. Cyrillic is Russian,
 * Ukrainian, Bulgarian, Serbian, Macedonian and Belarusian at once; Arabic is Arabic, Persian and
 * Urdu; Devanagari is Hindi, Marathi and Nepali; Hebrew is Hebrew and Yiddish; and Han characters
 * are Chinese and Japanese together. Each of those would be a coin toss dressed as evidence, so this
 * reader abstains and lets the two statistical detectors argue it out. The languages with no entry
 * at all - Georgian, Armenian, Amharic, Sinhala - are ones neither the app's language list nor the
 * local detectors can name, which is a limit stated rather than papered over.
 *
 * <p>The ranges are written out rather than taken from `Character.UnicodeScript`, which arrived on
 * Android in API 24 and would crash on the oldest devices this app still supports. Pure Kotlin
 * otherwise, and no Android types, so it is exercised by JVM unit tests.
 */
object ScriptReading {

    /** Latin letters are counted even though Latin names no language: it is the script to beat. */
    private const val SCRIPT_LATIN = 0
    private const val SCRIPT_HANGUL = 1
    private const val SCRIPT_KANA = 2
    private const val SCRIPT_GREEK = 3
    private const val SCRIPT_BENGALI = 4
    private const val SCRIPT_GURMUKHI = 5
    private const val SCRIPT_GUJARATI = 6
    private const val SCRIPT_TAMIL = 7
    private const val SCRIPT_TELUGU = 8
    private const val SCRIPT_KANNADA = 9
    private const val SCRIPT_MALAYALAM = 10
    private const val SCRIPT_THAI = 11
    private const val SCRIPT_KHMER = 12

    /** The language each script names, in the order above. `null` names none. */
    private val LANGUAGE_OF =
            arrayOf<String?>(
                    null, // Latin
                    "ko",
                    "ja",
                    "el",
                    "bn",
                    "pa",
                    "gu",
                    "ta",
                    "te",
                    "kn",
                    "ml",
                    "th",
                    "km")

    /**
     * How much of a text's letters one script has to cover before it is the text's script. Below
     * this the text is mixed, and a mixed text is not evidence about its language: the rule exists
     * so that a quotation cannot decide what language a message is in.
     */
    private const val DOMINANT_SHARE = 0.6

    /** Below this many letters there is no dominant script, only a word or two. */
    private const val MIN_LETTERS = 4

    /**
     * The language the text's dominant script names, or [TextLanguage.UNKNOWN] when no script has
     * it.
     */
    @JvmStatic
    fun language(text: String?): String {
        if (text == null || text.isEmpty()) {
            return TextLanguage.UNKNOWN
        }
        val counts = HashMap<Int, Int>()
        var letters = 0
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            i += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) {
                continue
            }
            letters++
            val script = scriptOf(codePoint)
            if (script >= 0) {
                counts[script] = (counts[script] ?: 0) + 1
            }
        }
        if (letters < MIN_LETTERS) {
            return TextLanguage.UNKNOWN
        }
        var best = -1
        var bestCount = 0
        for ((script, count) in counts) {
            if (count > bestCount) {
                bestCount = count
                best = script
            }
        }
        if (best < 0 || bestCount.toDouble() / letters < DOMINANT_SHARE) {
            return TextLanguage.UNKNOWN
        }
        return LANGUAGE_OF[best] ?: TextLanguage.UNKNOWN
    }

    /**
     * Which script a letter belongs to, or `-1` for a letter whose script names no language and for
     * everything that is not a letter at all - digits, punctuation and emoji carry no script
     * evidence, and counting them would change what "most of this text" means.
     */
    private fun scriptOf(codePoint: Int): Int {
        if (inRange(codePoint, 0x0041, 0x024F) ||
                inRange(codePoint, 0x0250, 0x02AF) ||
                inRange(codePoint, 0x1E00, 0x1EFF) ||
                inRange(codePoint, 0x2C60, 0x2C7F) ||
                inRange(codePoint, 0xA720, 0xA7FF) ||
                inRange(codePoint, 0xAB30, 0xAB6F) ||
                inRange(codePoint, 0xFF21, 0xFF5A)) {
            return SCRIPT_LATIN
        }
        if (inRange(codePoint, 0x1100, 0x11FF) ||
                inRange(codePoint, 0x3130, 0x318F) ||
                inRange(codePoint, 0xA960, 0xA97F) ||
                inRange(codePoint, 0xAC00, 0xD7FF)) {
            return SCRIPT_HANGUL
        }
        if (inRange(codePoint, 0x3040, 0x30FF) || inRange(codePoint, 0x31F0, 0x31FF)) {
            return SCRIPT_KANA
        }
        if (inRange(codePoint, 0x0370, 0x03FF) || inRange(codePoint, 0x1F00, 0x1FFF)) {
            return SCRIPT_GREEK
        }
        if (inRange(codePoint, 0x0980, 0x09FF)) {
            return SCRIPT_BENGALI
        }
        if (inRange(codePoint, 0x0A00, 0x0A7F)) {
            return SCRIPT_GURMUKHI
        }
        if (inRange(codePoint, 0x0A80, 0x0AFF)) {
            return SCRIPT_GUJARATI
        }
        if (inRange(codePoint, 0x0B80, 0x0BFF)) {
            return SCRIPT_TAMIL
        }
        if (inRange(codePoint, 0x0C00, 0x0C7F)) {
            return SCRIPT_TELUGU
        }
        if (inRange(codePoint, 0x0C80, 0x0CFF)) {
            return SCRIPT_KANNADA
        }
        if (inRange(codePoint, 0x0D00, 0x0D7F)) {
            return SCRIPT_MALAYALAM
        }
        if (inRange(codePoint, 0x0E00, 0x0E7F)) {
            return SCRIPT_THAI
        }
        if (inRange(codePoint, 0x1780, 0x17FF) || inRange(codePoint, 0x19E0, 0x19FF)) {
            return SCRIPT_KHMER
        }
        return -1
    }

    private fun inRange(codePoint: Int, from: Int, to: Int): Boolean =
            codePoint >= from && codePoint <= to

    /**
     * A lower-cased, trimmed code, or `null` when there is nothing usable.
     *
     * <p>Package-private in the Java this replaces; public here because Kotlin has no
     * package-private and both [LinguaReading] and [LanguageCheck] call it across the package.
     * Widening the visibility is the one deliberate API change in this file, and it changes no
     * behaviour.
     */
    @JvmStatic
    fun normalize(code: String?): String? {
        if (code == null) {
            return null
        }
        val trimmed = code.javaTrim().lowercase(Locale.ROOT)
        return if (trimmed.isEmpty() || TextLanguage.UNKNOWN == trimmed) null else trimmed
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and that is not academic here: a code
 * carrying a non-breaking space would be folded by Kotlin's `trim()` and kept by the Java this
 * replaces, which is the difference between a usable language code and a second, nameless one. The
 * Java used `String.trim()`, so this does too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
