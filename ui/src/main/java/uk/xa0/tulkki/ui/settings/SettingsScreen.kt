package uk.xa0.tulkki.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * Tulkki's own settings, docs/MIGRATION.md "Design: the Compose UI" §3.1 - the rows §3.4 asks for,
 * drawn from the page [SettingsPage] computes and nothing else.
 *
 * <p>**The live screen is this one.** `TulkkiSettingsFragment` hosts it through [SettingsHost], reads
 * its state from `TranslationSettings` and writes through `TranslationSettingsStore`, and
 * `preferences_tulkki.xml` is deleted with that swap - so the rows, the switch values and the off state
 * are readings rather than a tree that is built once and goes stale.
 *
 * <p>§7.4's "**Composables stay dumb**" holds: everything drawn is a reading of [state] or of a row, and
 * every interaction is a callback. The screen does not read a setting, does not know a raw key's
 * meaning beyond [SettingsEditors]' one table, and does not show an editor: the value in force that a
 * dialog opens holding is the host's reading, so the editors are [SettingsTextEditorDialog],
 * [SettingsNumberEditorDialog] and [SettingsLanguagePickerDialog], shown by [SettingsHost] around this
 * screen.
 *
 * <p>The prompts are the one nested route. [SettingsNavigation] splits the page's flat row list by
 * route, using the container's own children, so the `tulkki_prompts` row keeps something to do and the
 * three instructions keep the nested shape the owner has today rather than being flattened into the
 * list.
 */
@Composable
fun SettingsScreen(
    state: TulkkiSettingsState,
    route: SettingsRoute,
    onRoute: (SettingsRoute) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (SettingsRow) -> Unit,
    onOpenScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen paints the theme's own background, exactly as the ledger does: without a Surface the
    // content colour falls back to black on whatever the host drew behind it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TulkkiSpacing.lg, vertical = TulkkiSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
        ) {
            if (route == SettingsRoute.PROMPTS) {
                PromptsHeader(onRoute)
            }
            for (row in SettingsNavigation.rowsFor(route, SettingsPage.rows(state))) {
                SettingsRowView(state, row, onToggle, onEdit, onOpenScreen)
            }
        }
    }
}

/** The prompts sub-screen's own head: the container's title, and the way back to the page. */
@Composable
private fun PromptsHeader(onRoute: (SettingsRoute) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.md),
    ) {
        Text(
            stringResource(R.string.back),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { onRoute(SettingsRoute.PAGE) },
        )
        Text(stringResource(R.string.tulkki_prompts), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * One row. The shape is the row's [SettingsRow.kind] and, for a setting, the one editor
 * [SettingsEditors.editorFor] names - so a switch is drawn as a switch and every other setting as a
 * tappable row, and the screen holds no list of keys of its own.
 */
@Composable
private fun SettingsRowView(
    state: TulkkiSettingsState,
    row: SettingsRow,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (SettingsRow) -> Unit,
    onOpenScreen: (String) -> Unit,
) {
    when (row.kind) {
        SettingsRow.RowKind.HEADER ->
            Text(
                stringResource(row.title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.md),
            )
        SettingsRow.RowKind.LINE ->
            Text(
                stringResource(row.title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.xs),
            )
        SettingsRow.RowKind.SCREEN -> SettingsValueRow(row) { row.key?.let(onOpenScreen) }
        SettingsRow.RowKind.SETTING ->
            if (SettingsEditors.editorFor(row) == RowEditor.TOGGLE) {
                SettingsToggleRow(row, SettingsEditors.checked(state, row.key) == true, onToggle)
            } else {
                SettingsValueRow(row) { onEdit(row) }
            }
    }
}

/** A title and its summary, tappable unless the state has taken the row's ability to act away. */
@Composable
private fun SettingsValueRow(row: SettingsRow, onClick: () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = row.enabled, onClick = onClick)
                .padding(vertical = TulkkiSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
    ) {
        Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge)
        row.summary?.let { Text(summaryText(it), style = MaterialTheme.typography.bodySmall) }
    }
}

/** A switch row: the same two fields, with the value on the trailing edge. */
@Composable
private fun SettingsToggleRow(
    row: SettingsRow,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
        ) {
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge)
            row.summary?.let { Text(summaryText(it), style = MaterialTheme.typography.bodySmall) }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle(row.key.orEmpty(), it) },
            enabled = row.enabled,
        )
    }
}

/**
 * A summary's own sentence. An argument that is itself a [SettingSummary] is resolved first - the
 * prompts' summaries embed one of the hint resources, and an id passed straight into the format would
 * print the number rather than the sentence.
 */
@Composable
private fun summaryText(summary: SettingSummary): String =
    when (summary) {
        is SettingSummary.Plain -> stringResource(summary.id)
        is SettingSummary.Formatted -> {
            val resolved = ArrayList<Any>(summary.args.size)
            for (arg in summary.args) {
                resolved.add(if (arg is SettingSummary) summaryText(arg) else arg)
            }
            stringResource(summary.id, *resolved.toTypedArray())
        }
    }
