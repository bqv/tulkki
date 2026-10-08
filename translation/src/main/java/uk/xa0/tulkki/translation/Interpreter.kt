package uk.xa0.tulkki.translation

/**
 * The interpreter's off-switch: is Tulkki interpreting, or is it a plain XMPP client?
 *
 * <p>The interpreter is a feature, not the app. With both language settings naming the same language
 * every one of its surfaces - the second half, the composer gate, the gloss, the notes, the covers -
 * is off, and Tulkki is an ordinary client. This one derived value is the answer. It is deliberately
 * not a stored setting and not a second code path: one truth table, consulted by every rule that has
 * an off state, so the two modes cannot drift apart.
 *
 * <p>It is pure Kotlin - no Android, no `Context`, no `Message`, no store read - so it sits
 * in `:translation`'s pure layer beside [TranslationLanguages] and is exercised by JVM
 * unit tests. The settings are filled in by the store, `TranslationSettings.interpreter()`, and
 * a consumer is handed the value as a required argument; that is what turns a forgotten guard into a
 * compile error instead of a second default.
 *
 * <p>What decides it, in the owner's words: <em>interpretation happens if the app language is not the
 * study language</em>. The two settings must name <strong>different</strong> languages, and neither
 * may be [NONE] - a sentinel is not a language to read or to study. Nothing else enters it:
 * English has no special role any more, so app Finnish with study English is <em>on</em> (reading
 * Finnish, studying English) and a same-language pair is off. Comparison goes through
 * [ComposerGate.normalize], so `"FI"`, `" fi "` and `"fi"` are the same
 * answer - the sharpest failure this class has is a stored spelling that reads as a language when it
 * means the sentinel.
 *
 * <p>Both settings default to the device's current locale (`TranslationSettings
 * .defaultLanguage()`), which is the same value on both sides and therefore the off pair: a fresh
 * install is a plain client on any device, and it starts interpreting when the owner sets one of the
 * two to a different language. Nothing is stored differently, so nothing has to be migrated, and the
 * off state is reachable without a hidden default.
 *
 * <p>Settings that must <em>not</em> enter this predicate, each because it would turn one derived
 * mode into a per-message or per-capability question: the API key (a missing key is a loud,
 * recoverable state, not a mode), the daily cap and the API base URL (spend controls), the three
 * prompts (they only matter once a call is made), the six display switches (they live <em>inside</em>
 * the interpreter), and a conversation's own detected or overridden language (the predicate is
 * app-wide; a room that happens to be English must not flip the mode for that room).
 *
 * <p>`enabled()` stays a method, not a property: it is not a Java getter, so Kotlin never
 * synthesised a property over it, and the twelve code dependents (Java and Kotlin both) already call
 * it as `enabled()`.
 */
class Interpreter private constructor(private val isEnabled: Boolean) {

    companion object {

        /**
         * "No language": a legal stored value in the two language settings, and never a language.
         *
         * <p>It lives here, in the settings layer beside the settings that hold it, and not in
         * [TranslationLanguages]: that list is the pinned intersection of the three detectors,
         * computed by `TranslationLanguagesTest`, and a sentinel is not something any detector can
         * name. [TranslationLanguages.isKnown] stays `false` for it.
         *
         * <p>It is not `""`. Blank already means "unset, use the default" in the settings class, so
         * clearing a field is not how the interpreter is turned off; `"none"` is non-blank and
         * round-trips through both getters unchanged.
         */
        const val NONE = "none"

        /**
         * The one rule: two settings in, one answer out. Pure, so the same two strings always give
         * the same [enabled] - there is nothing else to consult.
         */
        @JvmStatic
        fun of(appLanguage: String?, studyLanguage: String?): Interpreter {
            val app = ComposerGate.normalize(appLanguage)
            val study = ComposerGate.normalize(studyLanguage)
            return Interpreter(NONE != app && NONE != study && app != study)
        }
    }

    /**
     * True when the app is interpreting: two different languages, and neither is [NONE].
     */
    fun enabled(): Boolean = isEnabled
}
