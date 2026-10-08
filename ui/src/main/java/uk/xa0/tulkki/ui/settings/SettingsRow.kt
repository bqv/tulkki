package uk.xa0.tulkki.ui.settings

import androidx.annotation.StringRes

/**
 * One row of Tulkki's settings page, as a value.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.4 item 4: "A `SettingRow` is **two fields**, title
 * and summary - never one string with `\n`, so the row and the destination screen's title cannot
 * disagree." The key is the third thing a row has to carry and it is the important one: a Compose
 * screen must keep writing the **raw preference keys** the Java readers are bound to, and
 * `SettingsPageTest` audits every row's key against the keys `TranslationSettingsStore` defines and
 * routes, and every row's title against `strings_tulkki.xml`.
 *
 * <p>[enabled] and [enabledWhy] are one decision with two halves, so the constructor refuses the two
 * shapes that are lies: a row that cannot act without saying why (`IDEA-SCAN §5.7` - it must not look
 * broken), and a row that can act while carrying a reason.
 */
data class SettingsRow(
    /**
     * The raw preference key the row reads and writes, or `null` for a row that stores nothing - a
     * heading, or the one informative line the off state adds.
     */
    val key: String?,
    /** The row's title, `@StringRes`: the string `strings_tulkki.xml` gives it. */
    @param:StringRes val title: Int,
    /** The row's second field, or `null` for a heading. */
    val summary: SettingSummary? = null,
    /** Whether the row can do anything in this state. */
    val enabled: Boolean = true,
    /** Why it cannot, as the sentence drawn under it; present exactly when [enabled] is false. */
    val enabledWhy: SettingSummary? = null,
    /** What the row is: a value, a door, a heading, or a line that stores nothing. */
    val kind: RowKind = RowKind.SETTING,
) {

    init {
        require(enabled || enabledWhy != null) { "a row that cannot act must say why: $key" }
        require(enabledWhy == null || !enabled) { "a row that can act carries no reason: $key" }
    }

    enum class RowKind {
        /** A row that stores a value: its key is one the settings store already handles. */
        SETTING,

        /** A row that opens a screen of its own: its key is in the tree, and the store does not know it. */
        SCREEN,

        /** A category heading. */
        HEADER,

        /** A row that stores nothing at all - the off state's one line. */
        LINE,
    }
}

/**
 * A row's second field, as the resource to draw it from rather than a string already built.
 *
 * <p>The distinction is what keeps the model pure: a [Formatted] summary's arguments are values a
 * Composable formats with the resource's own placeholders (`stringResource(id, *args.toTypedArray())`),
 * so nothing here needs a `Context`, and a JVM test can read the whole row set without one.
 *
 * <p>An argument may itself be a [SettingSummary], which the Composable resolves to text first - the
 * prompts' summaries embed one of the hint resources, and an id passed straight into `String.format`
 * would print the number rather than the sentence.
 */
sealed interface SettingSummary {

    /** A sentence with no placeholders. */
    data class Plain(@param:StringRes val id: Int) : SettingSummary

    /** A sentence with placeholders, in the resource's own order. */
    data class Formatted(@param:StringRes val id: Int, val args: List<Any>) : SettingSummary
}