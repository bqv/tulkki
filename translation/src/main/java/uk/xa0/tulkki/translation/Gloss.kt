package uk.xa0.tulkki.translation

/**
 * One word explained: the dictionary form, what it is doing in this sentence, and what it means.
 *
 * <p>A gloss is not a translation. The text being tapped is already in the app language, so asking
 * what it means *in* the app language would be circular; the meaning is in the separate study
 * language, and the useful part for an agglutinative language like Finnish is how the surface form
 * was built - the dictionary form plus the ending and the case - rather than a synonym.
 *
 * <p><strong>The caveat this class exists to keep visible:</strong> the gloss explains the text the
 * interface is showing, and for a received message that text is a machine translation. So the
 * dictionary form is the dictionary form of the <em>translated</em> word. That is right whenever the
 * translation is right, and it is wrong in exactly the cases the translation is - which is why the
 * gloss is a reading aid and not a source of truth about what the other person typed.
 *
 * <p>No field is ever `null`: an answer that did not fill one says nothing there, and the sheet
 * decides whether an empty part is worth a line. Pure Kotlin, so it is exercised by JVM unit tests.
 *
 * <p>The five accessors stay methods, not properties: their Java names are the API the Java callers
 * (`GlossContent`, `GlossLookup`, `GlossParser`, `DeepSeekClient`) already call.
 */
class Gloss private constructor(
        surface: String?,
        dictionary: String?,
        ending: String?,
        grammaticalCase: String?,
        meaning: String?
) {

    private val surfaceText: String = text(surface)
    private val dictionaryText: String = text(dictionary)
    private val endingText: String = text(ending)
    private val caseText: String = text(grammaticalCase)
    private val meaningText: String = text(meaning)

    companion object {

        @JvmStatic
        fun of(
                surface: String?,
                dictionary: String?,
                ending: String?,
                grammaticalCase: String?,
                meaning: String?
        ): Gloss = Gloss(surface, dictionary, ending, grammaticalCase, meaning)
    }

    /** The word as it appeared in the message. Never `null`; the empty string if unknown. */
    fun surface(): String = surfaceText

    /** The form a dictionary lists it under - the nominative singular, for Finnish. */
    fun dictionary(): String = dictionaryText

    /** The ending the surface form carries, or empty when there is none to name. */
    fun ending(): String = endingText

    /** The case the ending is, or empty when it is not a case (a tense, a plural, nothing). */
    fun grammaticalCase(): String = caseText

    /** What the word means, in the study language. */
    fun meaning(): String = meaningText

    /**
     * Whether there is anything worth showing: a dictionary form or a meaning. An answer with neither
     * is a failed lookup wearing a gloss's clothes, and the caller turns it into the failure it is.
     */
    fun isUsable(): Boolean = dictionaryText.isNotEmpty() || meaningText.isNotEmpty()

    /** True when the surface form is the dictionary form, so the sheet need not repeat it. */
    fun isDictionaryForm(): Boolean =
            dictionaryText.isNotEmpty() && dictionaryText.equals(surfaceText, ignoreCase = true)

    override fun toString(): String =
            surfaceText +
                    " -> " +
                    dictionaryText +
                    endingText +
                    "/" +
                    caseText +
                    " (" +
                    meaningText +
                    ")"
}

/**
 * A field as the Java this replaces read it: `null` is the empty string, and `trim()` is Java's own
 * (`<= ' '`), not Kotlin's Unicode one.
 */
private fun text(value: String?): String = value?.javaTrim() ?: ""

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
