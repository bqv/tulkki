package uk.xa0.tulkki.ui

import uk.xa0.tulkki.translation.ComposerGate
import uk.xa0.tulkki.translation.Interpreter

import java.util.Locale

/**
 * The composer's language chip as a value: whether it is drawn at all, and what each of its two tiers
 * holds.
 *
 * <p>The chip names the two ends of the pair at a glance. The invariant is that the two are
 * <em>ordered and named for what they hold</em>, never for a direction: the top tier is the app
 * language - the fixed end, a setting - and the bottom tier is the conversation's own language, which
 * is what the owner is learning and what an outgoing message is translated into. A reading that
 * swapped them would be invisible in a screenshot where both happen to be the same language, which is
 * why the two names live here rather than as two local variables in the composer.
 *
 * <p><strong>With the interpreter off the chip is not drawn at all.</strong> [isDrawn] is the rule,
 * and the fragment reads it - through its own `interpreting()` - before it hands the composer a pair:
 * off, `UiComposer.language` is `null` and no chip is composed. The tap and the long press are the
 * drawn chip's own gestures and neither is a plain XMPP client's affordance; the settings entry is
 * the way to those screens in both modes, so hiding the chip strands nobody.
 *
 * <p>`ConversationFragment` is a Fragment and this module hosts no Activity, no Fragment and no
 * Robolectric, so the chip <em>as drawn</em> - its colours, its two sizes, its alpha, its two
 * gestures - is a device look. What is pure is here, and it is exercised by JVM unit tests.
 */
object LanguageChip {

    /**
     * What a tier carries when there is no language to name: **three question marks**. A marker rather
     * than a language name, never `null`, and never a lone `?` - one question mark reads as punctuation
     * beside two-letter codes, while `???` is unmistakably "unknown" and is still only three glyphs
     * wide. The word a reader gets for this state comes from the fragment's own string, so the chip
     * cannot invent a language for it either.
     *
     * <p>**The sentinel that decides "unknown" is not this drawing.** `TextLanguage.UNKNOWN` (`und`)
     * and [ComposerGate.isUnknownLanguage] are unchanged and are what [code] consults; only the glyphs
     * the chip writes changed, and the sentinel itself is never drawn.
     */
    const val UNKNOWN = "???"

    /**
     * The two tiers, each named for what it holds and never for a direction.
     *
     * The constructor is `internal` rather than `private` because Kotlin gives an enclosing object no
     * access to a nested class's private members; Java's `private` was never callable from outside
     * either, and `tiers` below is the only builder.
     */
    class Tiers internal constructor(
            /** The top tier: the app language, the fixed end of the pair. */
            @JvmField val app: String,
            /** The bottom tier: the conversation's own language, or [UNKNOWN]. */
            @JvmField val conversation: String)

    /**
     * Whether the chip is drawn at all. Off - one language on both sides, the sentinel on either, or
     * no interpreter at all - it is not: a plain client has no language affordance, and the pair it
     * names is a fact about interpreting.
     */
    @JvmStatic
    fun isDrawn(interpreter: Interpreter?): Boolean {
        return interpreter != null && interpreter.enabled()
    }

    /**
     * The two tiers for one conversation.
     *
     * @param appLanguage the app language - always present when the chip is drawn, because the
     *     sentinel on this side is one of the ways the interpreter is off
     * @param conversationLanguage the conversation's resolved code; read only when known
     * @param conversationKnown whether the conversation's language is established or overridden, as
     *     opposed to still unknown
     */
    @JvmStatic
    fun tiers(
            appLanguage: String,
            conversationLanguage: String?,
            conversationKnown: Boolean): Tiers {
        return Tiers(
                code(appLanguage), if (conversationKnown) code(conversationLanguage) else UNKNOWN)
    }

    /**
     * One language as the chip writes it: the code, upper case, or [UNKNOWN] when there is no
     * language at all. "No language" is the same question [ComposerGate.isUnknownLanguage] answers
     * everywhere else, so `und` takes [UNKNOWN] here rather than becoming a third code.
     */
    @JvmStatic
    fun code(language: String?): String {
        val normalized = ComposerGate.normalize(language)
        return if (ComposerGate.isUnknownLanguage(normalized)) {
            UNKNOWN
        } else {
            normalized.uppercase(Locale.ROOT)
        }
    }
}
