package uk.xa0.tulkki.ui.utils

import uk.xa0.tulkki.data.utils.EmoticonText

/**
 * The `:ui` entry point for the emoji predicates. The implementation moved down to [EmoticonText] in
 * `:data` to kill the forbidden `:data` -> `:ui` edge (D9); every method below delegates, so no `:ui`
 * caller moved.
 *
 * `existingVariant` was the last implementation still living here, because pair 8a read its two
 * callers as `:xmpp`'s. Pair 11 closes D4, and the island may not name `:ui`: so the rewrite and its
 * two variation-selector constants moved down to [EmoticonText] in the same commit, and this method is
 * now a one-line delegation like the others.
 */
object Emoticons {

    @JvmStatic
    fun normalizeToVS16(input: String?): String = EmoticonText.normalizeToVS16(input)

    @JvmStatic
    fun existingVariant(original: String, existing: Set<String>): String =
        EmoticonText.existingVariant(original, existing)

    @JvmStatic
    fun isEmoji(input: String): Boolean = EmoticonText.isEmoji(input)

    @JvmStatic
    fun isOnlyEmoji(input: String): Boolean = EmoticonText.isOnlyEmoji(input)
}
