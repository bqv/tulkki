package uk.xa0.tulkki.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The ephemeral-messages block both details screens draw, as the deleted layouts carried it.
 *
 * <p>`activity_contact_details.xml` and `activity_muc_details.xml` each held the same pair - a
 * `MaterialSwitch` labelled `@string/ephemeral_messages`, and a `GONE` column of the duration label
 * plus a `Spinner` over `R.array.ephemeral_durations` - so the two screens share one renderer rather
 * than two copies that could drift. The switch's text sits to its left and the switch at the end of
 * the row, which is what `MaterialSwitch android:text` drew.
 *
 * @param visible the deleted `ephemeral_messages_switch`'s own visibility: the group screen hides
 *     the whole block for a public channel, the one-to-one screen never does.
 * @param enabled the switch's checked state, which is "an ephemeral timer is set".
 * @param durationVisible the deleted `ephemeral_messages_duration_layout`'s visibility.
 * @param entries `R.array.ephemeral_durations`, already localised.
 * @param selectedIndex the index whose `R.array.ephemeral_duration_values` entry is the live timer.
 */
data class EphemeralRow(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val durationVisible: Boolean = false,
    val entries: List<String> = emptyList(),
    val selectedIndex: Int = 0,
)

/**
 * The switch and, when it is on, the duration picker.
 *
 * <p>The picker is an `OutlinedButton` that opens a `DropdownMenu`, not a `Spinner`: this is the
 * shape `CommandFormScreen`'s `SpinnerRow` already uses in this tree, and it keeps the choice in
 * Compose rather than hosting a view. The list is the same array in the same order, and a selection
 * runs the same `OnItemSelectedListener` body the `Spinner` ran (the host guards it on the timer
 * actually changing, exactly as the old listener did).
 */
@Composable
fun EphemeralMessages(
    row: EphemeralRow,
    onToggle: (Boolean) -> Unit,
    onDurationSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!row.visible) {
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ephemeral_messages),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }
        if (row.durationVisible) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                text = stringResource(R.string.ephemeral_messages_duration),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = TulkkiSpacing.xs),
            )
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.xs),
            ) {
                Text(
                    text = row.entries.getOrElse(row.selectedIndex) { "" },
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                row.entries.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onDurationSelected(index)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The warning the deleted `dialog_ephemeral_warning.xml` drew: the body sentence over a
 * "don't show again" check box, with `OK` and `cancel`.
 *
 * <p>It is an `AlertDialog` now, launched from the same place as before - the ephemeral switch's
 * "turn on" arm - and it carries the same two side effects: the confirm button hands back whether
 * the check box was ticked, so the host can write `AppSettings.HIDE_EPHEMERAL_WARNING`, and both the
 * cancel button and a dismissal run the host's "the switch goes back off" arm. The deleted layout's
 * own 24 dp / 8 dp padding is the dialog's, which is the stock Material padding for this slot.
 *
 * @param onDismiss the negative button and the cancel listener, which the host wires to one action.
 * @param onConfirm `OK`, carrying the check box's state.
 */
@Composable
fun EphemeralWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ephemeral_messages_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = TulkkiSpacing.sm),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                    )
                    Text(text = stringResource(R.string.dont_show_again))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShowAgain) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}
