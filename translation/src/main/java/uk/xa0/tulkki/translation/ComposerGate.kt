package uk.xa0.tulkki.translation

import java.util.Locale

/**
 * The composer gate: may this draft go out at all?
 *
 * <p>Everything Tulkki sends is translated *from* the app language, so a draft in any other language
 * has to be refused rather than translated: the draft becomes the prompt, and the owner produces the
 * app language themselves. There is deliberately no per-draft bypass, no length threshold and no
 * escape hatch the owner can reach - they asked to be forced - which makes the only thing that has to
 * be right the line between "another language" and "no language at all". A link, a verification code,
 * a number, an emoji or a bare name has no language and must pass, or those messages become
 * unsendable. That line is not drawn here: [TranslationDecision.hasLanguage] draws it for this path
 * and the receive path both, and [verdict] is where the composer asks it.
 *
 * <p>Above all of that sits the [Interpreter] itself. With it off Tulkki is a plain XMPP client, so
 * [verdict] answers [Verdict.SEND] before it looks at the draft and a confidently foreign one goes
 * out as the owner typed it. That is not the escape hatch this class refuses to offer - it is
 * app-wide, derived from the two language settings, and it switches every surface off together
 * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
 *
 * <p>Detection is [TextLanguage], which is local and free, and it happens before any request is even
 * considered. A weak guess is never final: only a draft the detector is
 * [TextLanguage.TRUSTWORTHY_CONFIDENCE] sure about is refused, so a short word that merely looks
 * Estonian cannot lock the composer.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object ComposerGate {

    /**
     * Whether the model's version of the draft in the app language *is* the draft.
     *
     * <p>This is the owner's rule: if the suggested app-language text equals what they typed, the
     * gate was a false refusal and the message sends normally - no prompt, no retyping, nothing
     * bought for a translation that was never needed. Equality here means trimmed equality: leading
     * and trailing whitespace is not a language and is ignored, and nothing else is. Case, internal
     * spacing and punctuation have to match exactly.
     *
     * <p>The strictness is the point, and it is a correction the owner asked for. Tolerant equality
     * - case folded, internal whitespace collapsed, trailing punctuation dropped - was a loophole in
     * "no escape hatch": a draft that differed from the model's answer only in those ways was read
     * as "this was the app language all along", the gate stood down, and "show me the Finnish" did
     * not translate. A draft that differs from its answer by so much as a capital letter has not
     * been shown to be the app language, so the refusal stands and the draft goes through the normal
     * path. Either way the message is still translated into the conversation's language: this
     * decides only whether the gate stands down, never whether a translation is skipped.
     *
     * <p>It is deliberately *this* comparison and not [comparisonForm]: that form is tolerant of
     * case, spacing and trailing punctuation, which is the right answer to the one question still
     * asked with it - [LanguageCheck] asks whether an answer is the input handed back unchanged,
     * where "Matti: hello" and "matti: hello." really are the same text - and the wrong answer to
     * this one, where the whole point is that the model's answer has to be the owner's words exactly.
     *
     * <p>It overrides the local detector rather than being corroborated by it, and the reason is in
     * this class's own history: the detector answers a bare name with a language, "Matti" coming
     * back Maltese at 0.96. A heuristic with a demonstrated confident failure mode must not be able
     * to force the owner to retype their own language when the model says the text is already it.
     *
     * <p>The residual risk is real and accepted: a model that echoes a genuinely foreign draft makes
     * the gate stand down for it. The cost is bounded - the owner is not made to retype, and the
     * send path still translates the draft into the conversation's language, so nothing goes out
     * untranslated - and it is written down in the state notes.
     */
    @JvmStatic
    fun suggestionIsTheDraft(draft: String?, suggestion: String?): Boolean {
        if (draft == null || suggestion == null) {
            return false
        }
        val typed = draft.javaTrim()
        return typed.isNotEmpty() && typed == suggestion.javaTrim()
    }

    /**
     * The words of a text: case folded, internal whitespace collapsed, and trailing punctuation
     * dropped. Everything that is not a word should not decide whether two texts are the same text.
     *
     * <p><strong>Not what [suggestionIsTheDraft] uses</strong>, and the one caller left is
     * [LanguageCheck]'s echo check, where "the answer is the input handed back unchanged" is
     * genuinely a question about the words rather than about the characters: a model that returns
     * "Matti: hello." for "Matti: hello" has echoed the input, which is what that check is looking
     * for. The gate's own question - was the owner's draft already the app language - is strict, and
     * the owner asked for it to be.
     *
     * <p>The two predicates are Java's, spelled out rather than taken from Kotlin's `Char`
     * extensions: `Char.isWhitespace()` is `Character.isWhitespace` **or**
     * `Character.isSpaceChar`, so it would collapse a non-breaking space that Java does not, and
     * the Java this replaces used `Character.isWhitespace` here. `Character.toLowerCase` is the
     * same function in both languages, and is written the Java way for the same reason.
     */
    @JvmStatic
    fun comparisonForm(text: String?): String {
        if (text == null) {
            return ""
        }
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (i in 0 until text.length) {
            val c = text[i]
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length > 0
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(Character.toLowerCase(c))
        }
        var end = out.length
        while (end > 0 && !Character.isLetterOrDigit(out[end - 1])) {
            end--
        }
        return out.substring(0, end)
    }

    /** What the composer should do with the draft it is holding. */
    enum class Verdict {
        /** Send it as it stands: no language in it, or the conversation already speaks the app language. */
        SEND,
        /** Hold it: translate the draft into the conversation's language, then send. */
        TRANSLATE,
        /** Hold it: nothing can be translated until the conversation has a language. */
        UNKNOWN_LANGUAGE,
        /** Refuse it: the draft is confidently not the app language, and never becomes a message. */
        NOT_APP_LANGUAGE
    }

    /**
     * The one decision, from four strings, the [Interpreter] and no other state.
     *
     * @param conversationName the name the interface shows for the conversation the draft is going
     *     to ([ConversationName]), or `null` when the caller has none - in which case only the ping
     *     shape can tell it that the draft has no language
     * @param interpreter whether Tulkki is interpreting at all, required and last so that a call site
     *     cannot compile without deciding - the anti-drift property of the off-switch
     */
    @JvmStatic
    fun verdict(
            draft: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?,
            interpreter: Interpreter
    ): Verdict {
        if (!interpreter.enabled()) {
            // The interpreter is off, so Tulkki is a plain XMPP client and the gate answers what a
            // plain client answers: send the draft as the owner typed it. A confidently foreign draft
            // passes here rather than becoming a prompt - nothing is refused, held or bought while
            // off (MIGRATION.md "Design: the interpreter off-switch" §2.4).
            return Verdict.SEND
        }
        // Read into a local so a null draft is refused on the first line and the rest of the method
        // works with a `String` rather than scattering `!!` after a check the compiler cannot see
        // through. `hasLanguage(null, …)` is false, so the two spellings decide the same thing.
        val text = draft
        if (text == null || !TranslationDecision.hasLanguage(text, conversationName)) {
            // A link, a number, an emoji, a code, a ping or the room's own bare name: nothing to
            // translate and nothing to force.
            return Verdict.SEND
        }
        val guess = TextLanguage.detect(text)
        if (isRefusable(text, guess, appLanguage)) {
            return Verdict.NOT_APP_LANGUAGE
        }
        if (isUnknownLanguage(conversationLanguage)) {
            return Verdict.UNKNOWN_LANGUAGE
        }
        if (normalize(conversationLanguage).equals(normalize(appLanguage), ignoreCase = true)) {
            // The room already speaks the app language, so there is nothing to translate from.
            return Verdict.SEND
        }
        return Verdict.TRANSLATE
    }

    /**
     * Whether the draft is confidently a language other than the app language *and* the detector can
     * be trusted about it.
     *
     * <p>The second half is not a kindness and not a length threshold: the profiles read a single
     * token as whatever they recognise in its shape, and they are confidently wrong - "Matti" comes
     * back Maltese at 0.96, "Joo" Somali at 0.90. Refusing on that would make a bare name
     * unsendable, which is exactly what the design forbids. So a refusal needs a piece of prose to
     * stand on. A foreign one-word message is not let through by this: it is still translated before
     * it goes out (see [verdict]), it just is not turned into a prompt.
     */
    private fun isRefusable(draft: String, guess: TextLanguage.Guess, appLanguage: String?): Boolean {
        if (guess.isUnknown() || !isProse(draft)) {
            return false
        }
        if (guess.code.equals(normalize(appLanguage), ignoreCase = true)) {
            return false
        }
        // The same bar the receive path acts on and the one a conversation's own language is read
        // with (TextLanguage.TRUSTWORTHY_CONFIDENCE); what is this site's own is the prose question
        // above, which the bar is not a substitute for.
        return guess.confidence >= TextLanguage.TRUSTWORTHY_CONFIDENCE
    }

    /**
     * A run of letters or digits; the detector's word n-grams have nothing to work with below two.
     *
     * <p>Package-private in the Java this replaces, and public here because Kotlin has no
     * package-private: [ConversationLanguage.read] asks the same question for the same reason before
     * letting one message name a conversation's language, and `TrustBarTest` (Java) asks it too. The
     * widening is the one deliberate API change in this file and it changes no behaviour.
     */
    @JvmStatic
    fun isProse(text: String): Boolean {
        var tokens = 0
        var inToken = false
        for (i in 0 until text.length) {
            val tokenChar = Character.isLetterOrDigit(text[i])
            if (tokenChar && !inToken && ++tokens >= 2) {
                return true
            }
            inToken = tokenChar
        }
        return false
    }

    /** True when no language is known: null, blank, or [TextLanguage.UNKNOWN]. */
    @JvmStatic
    fun isUnknownLanguage(code: String?): Boolean {
        val normalized = normalize(code)
        return normalized.isEmpty() || TextLanguage.UNKNOWN == normalized
    }

    /** "fi" becomes "Finnish", for copy that has to name the app language. */
    @JvmStatic
    fun languageName(code: String?): String {
        if (code == null || code.javaTrim().isEmpty()) {
            return code ?: ""
        }
        val name = Locale.forLanguageTag(code.javaTrim()).getDisplayLanguage(Locale.ENGLISH)
        return if (name == null || name.isEmpty()) code else name
    }

    /**
     * Tulkki's one way to write a language code down. Public because the conversation screen
     * normalises the codes it shows and stores: two spellings of the same language would be two
     * different conversations' languages.
     */
    @JvmStatic
    fun normalize(code: String?): String = code?.javaTrim()?.lowercase(Locale.ROOT) ?: ""
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace, so a non-breaking space at the edge of a draft or
 * a code would be stripped by it and kept by the Java this replaces. At [ComposerGate.suggestionIsTheDraft]
 * that is the difference between "the model handed the draft back" and "the draft is another
 * language", so the Java reading is the one kept. The Java used `String.trim()`, so this does too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
