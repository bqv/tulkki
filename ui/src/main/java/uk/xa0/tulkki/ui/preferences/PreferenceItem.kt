package uk.xa0.tulkki.ui.preferences

import androidx.annotation.DrawableRes

/**
 * One row of a settings screen that used to be `res/xml/preferences_*.xml`, as a value.
 *
 * <p>**Why the screen is a list of these and not a tree.** `PreferenceFragmentCompat` inflated an XML
 * resource into a live `Preference` tree whose rows were views, and a screen whose layout still exists
 * is not converted (`AGENTS.md`, "XML is for strings, values, configs and images"). The rows are now a
 * Compose list, and this value is the whole of a row: the key the rest of the app reads, the two
 * fields the old row drew, the kind of value it holds, and what a dialog needs to open holding the
 * value in force.
 *
 * <p>**The key is load-bearing.** `:data` and `:app` read these raw strings and some are Tulkki's
 * own, so a rename, a new default or a dropped row is a behaviour change and not a style choice. The
 * host builds this list from the same `SharedPreferences` the old tree was bound to, which is why a
 * key cannot drift between the two halves of a screen.
 *
 * <p>The two fields are separate on purpose: a title and a summary, never one string with `\n`, so
 * the row and the sentence under it cannot disagree. A [Kind.LINE] row is the one row with no title -
 * the old resource's bare `Preference android:summary="…"` - and therefore carries no key.
 *
 * @param key the raw preference key, or `null` for a row that stores nothing (a heading or an
 *     informative line). A row that stores a value but names no key is a list of nothing.
 * @param title the row's first field, already resolved by the host, or `null` for a bare line.
 * @param summary the row's second field, already resolved, or `null` when the row has none.
 * @param icon a drawable resource drawn untinted at the leading edge, exactly as the XML row's
 *     `android:icon` was, or `null` for a row with no icon.
 * @param kind what the row is and therefore how it is drawn and what a tap does.
 * @param checked the switch's value in force ([Kind.SWITCH] only).
 * @param labels the entries a dialog draws, in the resource's order ([Kind.LIST] only).
 * @param values the entry values a list row writes, one per label ([Kind.LIST] only).
 * @param value the value in force: the selected list value, the text, or `null` when unset.
 * @param dialogTitle the dialog's own title, or `null` to fall back to [title] - exactly the old
 *     `ListPreference`/`EditTextPreference` fallback.
 * @param textHint the hint the old `setOnBindEditTextListener` put on the field, or `null`.
 * @param defaultValue the value the screen persists when the key is absent. An old `ListPreference` or
 *     `EditTextPreference` wrote its `android:defaultValue` on bind, and the writers that read these
 *     keys depend on it (a recurring backup interval, a DNS server), so the default is written with
 *     the row rather than only shown.
 * @param defaultColour the colour's default ARGB, written when the key is absent, as the old
 *     `ColorPreference` persisted it.
 * @param colour the colour in force for a [Kind.COLOUR] row, the stored ARGB int.
 * @param copyable whether a long press copies the row's summary, as `android:copyingEnabled` did on
 *     the old about row.
 * @param visible whether the row is drawn. A row that is not drawn is still *in the list*, because an
 *     old `isVisible = false` row was still in the inflated tree and still wrote its
 *     `android:defaultValue` on bind - which matters wherever a reader asks `contains` instead of
 *     reading the key with a default, as the custom colour slots do.
 * @param enabled whether the row can act. A row that cannot act still draws, as the old
 *     `setEnabled(false)` did, and the host says why in [summary].
 */
data class PreferenceItem(
    val key: String? = null,
    val title: CharSequence? = null,
    val summary: CharSequence? = null,
    @DrawableRes val icon: Int? = null,
    val kind: Kind = Kind.PLAIN,
    val checked: Boolean = false,
    val labels: List<CharSequence> = emptyList(),
    val values: List<String> = emptyList(),
    val value: String? = null,
    val dialogTitle: CharSequence? = null,
    val textHint: CharSequence? = null,
    val defaultValue: String? = null,
    val defaultColour: Int = 0,
    val colour: Int = 0,
    val copyable: Boolean = false,
    val visible: Boolean = true,
    val enabled: Boolean = true,
) {
    init {
        require(kind != Kind.LIST || labels.size == values.size) {
            "a list row's labels and values are one list: $key"
        }
        require(kind == Kind.HEADER || kind == Kind.LINE || title != null || summary != null) {
            "a row must draw something: $key"
        }
    }

    /** What a row is: the old XML element's own distinction, kept in the model. */
    enum class Kind {
        /** A `PreferenceCategory` title: a heading, drawn in a different style, never tappable. */
        HEADER,

        /** A bare `Preference android:summary="…"`: one informative line and nothing else. */
        LINE,

        /** A `Preference` with a title: a row that opens a dialog, a picker or another screen. */
        PLAIN,

        /** A `SwitchPreferenceCompat`: the value on the trailing edge, the whole row tappable. */
        SWITCH,

        /** A `ListPreference`: a single-choice dialog, the selected entry as the summary. */
        LIST,

        /** An `EditTextPreference`: a text dialog, the value as the summary. */
        TEXT,

        /** The theme's `ColorPreference`: the library's colour picker, the value stored as an ARGB int. */
        COLOUR,
    }
}
