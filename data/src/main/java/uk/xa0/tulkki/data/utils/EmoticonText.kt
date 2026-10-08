package uk.xa0.tulkki.data.utils

import com.google.common.collect.ImmutableSet
import net.fellbaum.jemoji.EmojiManager

/**
 * The emoji predicates the data layer needs, moved down from `uk.xa0.tulkki.ui.utils.Emoticons` to kill the
 * forbidden `:data` -> `:ui` edge (D9). `Emoticons` keeps its public methods and now delegates here, so no
 * `:ui` call site moved.
 *
 * Three things were carried down because `Message` and `Reaction` ask them while rendering and storing a
 * message: whether a whole body is emoji (`isOnlyEmoji`), whether a body is an emoji at all (`isEmoji`), and
 * the TEXT-default-to-emoji-presentation rewrite (`normalizeToVS16`).
 *
 * `existingVariant` stayed behind in pair 8a because its only two callers were in `:xmpp` — and that is
 * exactly why pair 11 moved it here: the variation-selector rewrite is pure text handling (a `Set` of strings
 * and one suffix test), the caller is an island that may not name `:ui`, and the one thing it shared with the
 * `:ui` facade was the two variation-selector constants, which live here already. `Emoticons.existingVariant`
 * still exists and now delegates, so no `:ui` call site moved.
 *
 * Everything here is pure text handling over a third-party emoji table - no resource, no `Context`, no
 * `View` - which is what makes it movable at all.
 */
object EmoticonText {

    private val VARIATION_16_STRING = "\uFE0F"
    private val VARIATION_15_STRING = "\uFE0E"

    private val TEXT_DEFAULT_TO_VS16: Set<String> =
        ImmutableSet.of(
            "❤",
            "✔",
            "✖",
            "➕",
            "➖",
            "➗",
            "⭐",
            "⚡",
            "\uD83C\uDF96",
            "\uD83C\uDFC6",
            "\uD83E\uDD47",
            "\uD83E\uDD48",
            "\uD83E\uDD49",
            "\uD83D\uDC51",
            "⚓",
            "⛵",
            "✈",
            "⚖",
            "⛑",
            "⚒",
            "⛏",
            "☎",
            "⛄",
            "⛅",
            "⚠",
            "⚛",
            "✡",
            "☮",
            "☯",
            "☀",
            "⬅",
            "➡",
            "⬆",
            "⬇",
        )

    /** Give a TEXT-default emoji its emoji presentation, unless it already asked for the text one. */
    @JvmStatic
    fun normalizeToVS16(input: String?): String {
        // Java short-circuited its way to a null return for a null input, and Kotlin's check on Reaction's
        // non-null declaration threw an NPE at the call; the check is named here instead (Reaction.kt's own
        // note 5 records the moved frame).
        val value = input ?: throw NullPointerException()
        return if (TEXT_DEFAULT_TO_VS16.contains(value) && !value.endsWith(VARIATION_15_STRING)) {
            value + VARIATION_16_STRING
        } else {
            value
        }
    }

    @JvmStatic
    fun isEmoji(input: String): Boolean = EmojiManager.isEmoji(input)

    @JvmStatic
    fun isOnlyEmoji(input: String): Boolean {
        // Vacuous but not useful.
        if (input.trim { it <= ' ' }.isEmpty()) return false

        return EmojiManager.removeAllEmojis(input).replace("\uFE0F", "").trim { it <= ' ' }.isEmpty()
    }

    /**
     * The variation-selector form of `original` that `existing` already holds, or `original` unchanged. Moved
     * down from `uk.xa0.tulkki.ui.utils.Emoticons` by pair 11: its callers are the island's reaction path,
     * which may not name `:ui`, and the rewrite itself is pure text handling.
     */
    @JvmStatic
    fun existingVariant(original: String, existing: Set<String>): String {
        if (existing.contains(original) || original.endsWith(VARIATION_15_STRING)) {
            return original
        }
        val variant =
            if (original.endsWith(VARIATION_16_STRING)) {
                original.substring(0, original.length - 1)
            } else {
                original + VARIATION_16_STRING
            }
        return if (existing.contains(variant)) variant else original
    }
}
