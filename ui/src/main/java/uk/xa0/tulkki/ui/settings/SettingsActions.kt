package uk.xa0.tulkki.ui.settings

/**
 * What [SettingsScreen] asks its host to do, so the screen emits and the host owns the effects.
 *
 * <p>It is an interface rather than a dozen `Consumer` parameters because the host is one object -
 * `TulkkiSettingsFragment` - and it already holds the reader and the writer; the screen then has one
 * collaborator instead of a parameter list that changes whenever a row gains an editor.
 *
 * <p>**The host implements this in Java**, which is why every method is a plain call with no default
 * and no Kotlin-only shape. The split is the ledger's: a Composable takes a state and emits ids, and
 * the fragment reads and writes.
 */
interface SettingsActions {

    /** The page moved: [SettingsRoute.PAGE] or [SettingsRoute.PROMPTS]. */
    fun onRoute(route: SettingsRoute)

    /** A switch row was toggled; [key] is the raw preference key the row writes. */
    fun onToggle(key: String, value: Boolean)

    /** A row that opens an editor was tapped; the host decides which editor its key means. */
    fun onEdit(row: SettingsRow)

    /** A row that opens a screen of its own was tapped: the ledger, the failures list, the prompts, top-up. */
    fun onOpenScreen(key: String)

    /** The open editor was dismissed without committing. */
    fun onDismissEditor()

    /** One of the string editors committed: the key (masked), the base URL, or an instruction. */
    fun commitText(key: String, value: String)

    /** The cap committed; a value that did not parse never reaches here. */
    fun commitNumber(key: String, value: Int)

    /** A language was chosen; the host writes it through the store, so the on-to-off clear runs. */
    fun commitLanguage(key: String, code: String)
}
