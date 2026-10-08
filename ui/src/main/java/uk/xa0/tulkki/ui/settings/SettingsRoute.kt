package uk.xa0.tulkki.ui.settings

import uk.xa0.tulkki.ui.TulkkiSettingsRows

/**
 * Which page of Tulkki's settings is drawn.
 *
 * <p>One nesting level, so a route is a value and not a stack: the prompts are the one nested
 * `PreferenceScreen` the owner has today, and the screen's back gesture is [PAGE] from [PROMPTS]. A
 * second level would need a real back stack, and there is nothing to put in it.
 */
enum class SettingsRoute {
    /** The page itself: the languages, the account rows, the received and sent sections. */
    PAGE,

    /** The three instructions, the way the nested `PreferenceScreen` opens them today. */
    PROMPTS,
}

/**
 * The page's rows, split by route.
 *
 * <p>It reads `SettingsPage`'s one flat list and does not change it: the three prompt rows follow the
 * `tulkki_prompts` container row, exactly as the tree nests them, and they are the container's
 * children. Drawing the container row and its children on the same page would leave the container with
 * nothing to do, which is what "a row that does nothing" forbids; drawing the container on [PAGE] and
 * the children on [PROMPTS] is the tree's own shape.
 *
 * <p>The children are taken as the run of `SETTING` rows immediately after the container, so the
 * partition is structural - a row added inside the container joins it without an edit here, and a new
 * row after the run stays on the page.
 */
object SettingsNavigation {

    fun rowsFor(route: SettingsRoute, rows: List<SettingsRow>): List<SettingsRow> {
        val children = promptRange(rows)
        return when (route) {
            SettingsRoute.PAGE -> rows.filterIndexed { at, _ -> at !in children }
            SettingsRoute.PROMPTS -> rows.slice(children)
        }
    }

    /** The indices of the `tulkki_prompts` container's children, empty when it is not in the list. */
    private fun promptRange(rows: List<SettingsRow>): IntRange {
        val container = rows.indexOfFirst { it.key == TulkkiSettingsRows.KEY_PROMPTS }
        if (container < 0) {
            return IntRange.EMPTY
        }
        var last = container
        while (last + 1 < rows.size && rows[last + 1].kind == SettingsRow.RowKind.SETTING) {
            last++
        }
        // Empty when the container carries nothing, so `slice` cannot include the container itself.
        return if (last == container) IntRange.EMPTY else (container + 1)..last
    }
}
