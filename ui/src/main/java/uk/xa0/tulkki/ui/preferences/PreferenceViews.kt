package uk.xa0.tulkki.ui.preferences

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import com.rarepebble.colorpicker.ColorPickerView
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * A settings screen drawn from [PreferenceItem]s - the Compose replacement for the `PreferenceScreen`
 * that `PreferenceFragmentCompat` inflated from `res/xml/preferences_*.xml`.
 *
 * <p>**Composables stay dumb** (docs/MIGRATION.md "Design: the Compose UI" §7.4): every value is a
 * reading of [items], every interaction is a callback, and the screen reads no setting, no resource and
 * no database of its own. The host builds the items from `SharedPreferences` and performs what a tap
 * means - so the raw keys and the side effects live in one place, exactly as the old fragment's
 * `onSharedPreferenceChanged` was that place.
 *
 * <p>A dialog is a value and not internal state: [dialog] is the row whose editor is open, and the
 * host holds it across a re-render. That is what keeps a dialog open when a write behind it re-reads
 * the screen, which is the one thing `PreferenceFragmentCompat`'s `PreferenceDialogFragment` gave for
 * free.
 *
 * <p>Every row reads from the theme's own tokens (the spacing scale is [TulkkiSpacing]) so light and
 * dark both work and no size is invented here; the colours are `MaterialTheme.colorScheme`'s, exactly
 * as [uk.xa0.tulkki.ui.settings.SettingsScreen] draws its own rows.
 *
 * @param items the rows, in the order the screen draws them.
 * @param dialog the row whose editor is open, or `null`.
 * @param onToggle a switch row's new value; the host writes the key.
 * @param onClick a non-switch row's own tap; the host decides what it opens.
 * @param onValue a list or text dialog's committed value, `null` for a cleared text field.
 * @param onColour the colour picker's committed ARGB value.
 * @param onDismiss the dialog was closed without a value.
 */
@Composable
fun PreferenceListView(
    items: List<PreferenceItem>,
    dialog: PreferenceItem?,
    onToggle: (String, Boolean) -> Unit,
    onClick: (PreferenceItem) -> Unit,
    onCopy: (PreferenceItem) -> Unit,
    onValue: (String, String?) -> Unit,
    onColour: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen paints the theme's own background, exactly as the ledger, the failures and the Tulkki
    // settings screens do: without a Surface the content colour falls back to black on whatever the
    // host drew behind it.
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
            for (item in items) {
                if (!item.visible) {
                    continue
                }
                when (item.kind) {
                    PreferenceItem.Kind.HEADER -> PreferenceHeading(item)
                    PreferenceItem.Kind.LINE -> PreferenceLine(item)
                    PreferenceItem.Kind.SWITCH -> PreferenceSwitch(item, onToggle)
                    else -> PreferenceValue(item, onClick, onCopy)
                }
            }
        }
    }
    if (dialog != null) {
        when (dialog.kind) {
            PreferenceItem.Kind.LIST -> ListDialog(dialog, onValue, onDismiss)
            PreferenceItem.Kind.TEXT -> TextDialog(dialog, onValue, onDismiss)
            PreferenceItem.Kind.COLOUR -> ColourDialog(dialog, onColour, onDismiss)
            else -> Unit
        }
    }
}

/** A `PreferenceCategory`'s title, drawn as the heading it was. */
@Composable
private fun PreferenceHeading(item: PreferenceItem) {
    Text(
        text = item.title?.toString().orEmpty(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.md),
    )
}

/** The bare `android:summary` line: informative, never tappable, and its own two rules above it. */
@Composable
private fun PreferenceLine(item: PreferenceItem) {
    Text(
        text = item.summary?.toString().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.sm),
    )
}

/** A switch row: the same two fields, with the value on the trailing edge and the whole row tappable. */
@Composable
private fun PreferenceSwitch(item: PreferenceItem, onToggle: (String, Boolean) -> Unit) {
    val key = item.key.orEmpty()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = item.enabled) { onToggle(key, !item.checked) }
                .padding(vertical = TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.md),
    ) {
        PreferenceIcon(item)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
        ) {
            Text(item.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge)
            PreferenceSummary(item)
        }
        Switch(
            checked = item.checked,
            onCheckedChange = { onToggle(key, it) },
            enabled = item.enabled,
        )
    }
}

/** A row that opens something: a dialog, a picker, another screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreferenceValue(
    item: PreferenceItem,
    onClick: (PreferenceItem) -> Unit,
    onCopy: (PreferenceItem) -> Unit,
) {
    val touch =
        if (item.copyable) {
            Modifier.fillMaxWidth()
                .combinedClickable(
                    enabled = item.enabled,
                    onLongClick = { onCopy(item) },
                    onClick = { onClick(item) },
                )
        } else {
            Modifier.fillMaxWidth().clickable(enabled = item.enabled) { onClick(item) }
        }
    Row(
        modifier = touch.padding(vertical = TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.md),
    ) {
        PreferenceIcon(item)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs),
        ) {
            Text(item.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge)
            PreferenceSummary(item)
        }
    }
}

/** The leading `android:icon`, drawn untinted so a coloured drawable keeps the colour it had. */
@Composable
private fun PreferenceIcon(item: PreferenceItem) {
    val icon = item.icon ?: return
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(TulkkiSpacing.xl),
    )
}

/** The row's second field, or nothing at all - a row with no summary draws no second line. */
@Composable
private fun PreferenceSummary(item: PreferenceItem) {
    val summary = item.summary ?: return
    Text(summary.toString(), style = MaterialTheme.typography.bodySmall)
}

/**
 * A `ListPreference`'s dialog: a single-choice list, applied on the tap and closed, with Cancel as the
 * only button - the same shape `ListPreference`'s own `AlertDialog` had.
 */
@Composable
private fun ListDialog(
    item: PreferenceItem,
    onValue: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((item.dialogTitle ?: item.title)?.toString().orEmpty()) },
        text = {
            Column {
                for (index in item.labels.indices) {
                    val value = item.values[index]
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = value == item.value,
                                    onClick = { onValue(item.key.orEmpty(), value) },
                                )
                                .padding(vertical = TulkkiSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == item.value, onClick = { onValue(item.key.orEmpty(), value) })
                        Text(item.labels[index].toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * An `EditTextPreference`'s dialog: the value in force in an editable field, written on OK and left
 * alone on Cancel. A cleared field persists an empty string, which is what `setText(value)` did; the
 * one site that means "forget it" removes the key itself (`setText(null)` was that).
 */
@Composable
private fun TextDialog(
    item: PreferenceItem,
    onValue: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(item.key) { mutableStateOf(item.value.orEmpty()) }
    val hint: CharSequence? = item.textHint
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((item.dialogTitle ?: item.title)?.toString().orEmpty()) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = if (hint != null) ({ Text(hint.toString()) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onValue(item.key.orEmpty(), text) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * The theme's `ColorPreference` dialog: the library's own [ColorPickerView], which is the same view
 * that preference drew - `chrome`'s rule that a converted screen keeps the widget it had where the
 * widget is a third party's - written on OK and left alone on Cancel.
 */
@Composable
private fun ColourDialog(
    item: PreferenceItem,
    onColour: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = remember(item.key) { mutableStateOf<ColorPickerView?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((item.dialogTitle ?: item.title)?.toString().orEmpty()) },
        text = {
            AndroidView(
                factory = { context ->
                    ColorPickerView(context)
                        .apply {
                            showAlpha(false)
                            setColor(item.colour)
                        }
                        .also { picker.value = it }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = picker.value?.color ?: item.defaultColour
                    onColour(item.key.orEmpty(), value)
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
