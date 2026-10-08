package uk.xa0.tulkki.translation

/**
 * The readers [LanguageCheck] asks, in one place so that "how many opinions is this check built
 * from" has a single answer.
 *
 * <p>Three identifiers and one fact, and the three are deliberately different methods rather than
 * three spellings of one: n-gram profiles trained on text ([TextLanguage]), a second library
 * with its own models ([LinguaReading]), a maximum-entropy classifier over 103 languages
 * ([OpenNlpReading]), and the writing system the text is physically written in
 * ([ScriptReading]). Agreement between methods that fail differently is the only kind of
 * agreement worth acting on.
 *
 * <p><strong>This is also the one place a reader is given the name a diagnostic calls it by.</strong>
 * The four names live beside the four factories so the order of the list and the order of the names
 * cannot drift apart, and [LanguageCheck.Reader.label] is what a diagnostic record reads.
 * Nothing here decides anything; a name is a word for the screen and the log, never evidence.
 *
 * <p>Pure Kotlin, no Android types.
 *
 * <p><strong>Package-private in the Java this replaces; public here, with `@JvmStatic`, because
 * Kotlin has no package-private and both [LanguageCheck] and the Java `LanguageCheckTest` call it
 * across the package.</strong> The widening is the one deliberate API change in this file and it
 * changes no behaviour.
 */
object LanguageReadings {

    /** The detector that already ships, and that chose the target in the first place. */
    @JvmStatic
    fun shipped(): LanguageCheck.Reader =
            named(
                    "the shipped detector",
                    LanguageCheck.Reader { text, _ ->
                        val guess = TextLanguage.detect(text)
                        if (guess.isUnknown()) {
                            null
                        } else {
                            LanguageCheck.Reading(guess.code, guess.confidence, false)
                        }
                    })

    /** The second library: different models, different training data. */
    @JvmStatic fun lingua(): LanguageCheck.Reader = named("Lingua", LinguaReading.reader())

    /** The third: a classifier rather than a profile comparison, over its own 103 languages. */
    @JvmStatic fun openNlp(): LanguageCheck.Reader = named("OpenNLP", OpenNlpReading.reader())

    /** The writing system, which is a fact rather than a guess, so its reading is certain. */
    @JvmStatic
    fun script(): LanguageCheck.Reader =
            named(
                    "the script reader",
                    LanguageCheck.Reader { text, _ ->
                        val code = ScriptReading.language(text)
                        if (TextLanguage.UNKNOWN == code) {
                            null
                        } else {
                            LanguageCheck.Reading(code, 1.0, true)
                        }
                    })

    /**
     * A reader and the name a diagnostic calls it by, as one value.
     *
     * <p>The delegate keeps each factory's own shape - [LinguaReading.reader] and
     * [OpenNlpReading.reader] stay the lambdas they are - and this is the one adapter that
     * gives them a name, so the labelling is visible in one file rather than scattered through four.
     */
    private fun named(label: String, delegate: LanguageCheck.Reader): LanguageCheck.Reader =
            object : LanguageCheck.Reader {
                override fun read(text: String, candidates: Set<String>): LanguageCheck.Reading? =
                        delegate.read(text, candidates)

                override fun label(): String = label
            }
}
