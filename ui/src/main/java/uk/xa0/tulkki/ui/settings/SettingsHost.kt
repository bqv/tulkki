package uk.xa0.tulkki.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsRows
import uk.xa0.tulkki.ui.TranslationSettingsStore
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The Java door to [SettingsScreen] and its editors, for the host that is still Java:
 * `TulkkiSettingsFragment`.
 *
 * <p>It exists for the reason [SettingsActions] does: a Composable cannot be called from Java, so the
 * fragment hands over the state, the route, the row whose editor is open and one callback object, and
 * this sets the content. The theme is a value passed in - "a screenshot test can render any theme
 * without an Activity" (§7.4) - read by the host off the Activity's own resources, which follow the
 * stored preference.
 *
 * <p>**Each call replaces the composition**, exactly as [uk.xa0.tulkki.ui.ledger.LedgerHost]'s does and
 * for the same reason: the Java host re-renders by re-setting the content after a write, and the screen
 * takes one immutable state, so nothing here can drift from the screenshots.
 */
object SettingsHost {

    @JvmStatic
    fun show(
        view: ComposeView,
        state: TulkkiSettingsState,
        route: SettingsRoute,
        editing: SettingsRow?,
        darkTheme: Boolean,
        actions: SettingsActions,
    ) {
        view.setContent {
            TulkkiTheme(darkTheme = darkTheme) {
                SettingsScreen(
                    state = state,
                    route = route,
                    onRoute = { actions.onRoute(it) },
                    onToggle = { key, value -> actions.onToggle(key, value) },
                    onEdit = { actions.onEdit(it) },
                    onOpenScreen = { actions.onOpenScreen(it) },
                )
                val row = editing
                if (row != null) {
                    SettingsEditor(row, state, actions)
                }
            }
        }
    }

    /**
     * The open row's editor. Which one it is comes from the row's own key through
     * [SettingsEditors.editorFor], and what it opens holding comes from [SettingsEditors.initialFor] -
     * the value in force, which is why the host must have read the state before it set [editing]: a
     * dialog that opened on a stale value would write the owner's edit over a newer one.
     */
    @Composable
    private fun SettingsEditor(row: SettingsRow, state: TulkkiSettingsState, actions: SettingsActions) {
        val title = SettingsEditors.dialogTitle(row)
        val initial = SettingsEditors.initialFor(state, row)
        val key = row.key.orEmpty()
        when (SettingsEditors.editorFor(row)) {
            RowEditor.TEXT ->
                SettingsTextEditorDialog(
                    title = title,
                    initial = initial,
                    onDismiss = { actions.onDismissEditor() },
                    onCommit = { actions.commitText(key, it) },
                    masked = key == TranslationSettingsStore.KEY_API_KEY,
                )
            RowEditor.PROMPT ->
                SettingsTextEditorDialog(
                    title = title,
                    initial = initial,
                    onDismiss = { actions.onDismissEditor() },
                    onCommit = { actions.commitText(key, it) },
                    multiline = true,
                    hint = stringResource(R.string.tulkki_prompt_clear_hint),
                )
            RowEditor.NUMBER ->
                SettingsNumberEditorDialog(
                    title = title,
                    initial = state.dailyTokenCap,
                    onDismiss = { actions.onDismissEditor() },
                    onCommit = { actions.commitNumber(key, it) },
                )
            RowEditor.LANGUAGE ->
                SettingsLanguagePickerDialog(
                    title = title,
                    codes = TulkkiSettingsRows.pickerValues(initial),
                    selected = initial,
                    onDismiss = { actions.onDismissEditor() },
                    onCommit = { actions.commitLanguage(key, it) },
                )
            // A row the host was asked to edit but that has no editor: nothing is shown, and
            // SettingsNavigationTest is what refuses that state rather than the screen guessing.
            else -> Unit
        }
    }
}
