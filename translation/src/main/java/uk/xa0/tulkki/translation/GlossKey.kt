package uk.xa0.tulkki.translation

import java.util.Locale

/**
 * The cache identity of one gloss lookup.
 *
 * <p>Lookups are cached per surface form and study language, because a learner taps far more words
 * than they send messages: the second tap on the same word must not be a second purchase. The key
 * goes through [CacheKey] in its own namespace, so a word and a message can share the table
 * without ever sharing an answer.
 *
 * <p>The surface form is lower-cased and trimmed, because a word does not become a different word by
 * starting a sentence: "Talo" and "talo" are one lookup and one price. Pure Kotlin, so it is
 * exercised by JVM unit tests.
 *
 * <p><strong>Package-private in the Java this replaces; public here, with `@JvmStatic`, because
 * Kotlin has no package-private and the Java `GlossLookup` and `GlossKeyTest` call it across the
 * package.</strong> The widening is the one deliberate API change in this file and it changes no
 * behaviour.
 */
object GlossKey {

    /**
     * The namespace that keeps glosses apart from translations in the shared cache table.
     *
     * <p>It carries the gloss contract's version, because a cached answer is only as good as the
     * prompt that asked for it. It went from "gloss" to "gloss-v2" when the prompt started insisting
     * that no field comes back empty: an entry bought under the old prompt is the old answer - a word
     * in its basic form cached as nothing but a meaning - and a tap on that word would have gone on
     * showing one row while the fix looked like it had not worked. It moved again, to `gloss-v3`,
     * when the prompt started being given the sentence the word was tapped in: the answer is now a
     * function of the word *in its sentence*, which is what this class's own
     * [of] puts in the key, and an entry bought without that context
     * is the answer to a different question. The cost is stated rather than hidden: those words are
     * bought once more, a few hundred tokens each, and their old rows stay in the table unread.
     */
    const val NAMESPACE = "gloss-v3"

    /**
     * The word on its own - a caller with no sentence to give, and what the tests of the word's own
     * folding use.
     *
     * @param surface the word as it was tapped; may be `null`
     * @param studyLanguage the language the gloss is in; may be `null`
     * @return 64 lowercase hex characters
     */
    @JvmStatic
    fun of(surface: String?, studyLanguage: String?): String = of(surface, studyLanguage, null)

    /**
     * The word in the sentence it was tapped in, which is what a gloss is now a function of: the
     * sentence is bought with the request, so the same word in another sentence is another question
     * and cannot be answered with the first one's gloss. Re-reading one message is still free -
     * identical text folds to the identical key.
     *
     * @param surface the word as it was tapped; may be `null`
     * @param studyLanguage the language the gloss is in; may be `null`
     * @param sentence the sentence the word sits in, or `null` when there is none
     * @return 64 lowercase hex characters
     */
    @JvmStatic
    fun of(surface: String?, studyLanguage: String?, sentence: String?): String {
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        val word = if (surface == null) "" else surface.javaTrim().lowercase(Locale.ROOT)
        // A newline cannot occur in a word, so the two fields cannot be confused for one another -
        // and a word with no sentence is hashed as the word alone, which is the shape its key has
        // always had.
        val context = folded(sentence)
        val question = if (context.isEmpty()) word else word + "\n" + context
        return CacheKey.of(
                NAMESPACE + PromptBook.suffix(PromptBook.Kind.GLOSS), question, studyLanguage)
    }

    /** Whitespace folded to single spaces, so the same sentence typed two ways is one question. */
    private fun folded(sentence: String?): String =
            if (sentence == null) "" else sentence.javaTrim().replace(Regex("\\s+"), " ")
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
