package uk.xa0.tulkki.ui.omemo

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R

/**
 * One OMEMO (or OpenPGP) key row: the shared `contact_key.xml` in Compose, and the shape every host
 * that used to inflate it now draws instead.
 *
 * The old row was a `RelativeLayout` with the prettified key and its type line on the start and a
 * `key_action_width` x 48 dp action slot on the end, holding exactly one of the verified mark (with
 * the two alphas the shared view set: 1.0 for an active key, 0.4368 for an inactive one), the
 * enable-device button, or the trust switch. Long-pressing the row opened the
 * `omemo_key_context.xml` context menu; the host resolved its visibility and built it as [actions].
 *
 * @param state every value the old view was given, already resolved by the host.
 */
@Composable
fun ContactKeyRow(state: ContactKeyRowState, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    // The old enable-device listener swapped the two views itself: the button went `GONE` and the
    // switch `VISIBLE`, without a refresh. This local bit is that swap.
    var deviceEnabled by remember { mutableStateOf(false) }
    val labelColor =
        if (state.active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val showSwitch = state.switchVisible || (state.enableDeviceVisible && deviceEnabled)
    Box {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { state.onTap?.invoke() },
                        onLongClick = if (state.actions.isEmpty()) null else ({ menuOpen = true }),
                    )
                    .padding(dimensionResource(R.dimen.list_padding)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = labelColor,
                )
                val typeLabel = state.typeLabel
                if (typeLabel != null) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (state.typeHighlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                labelColor
                            },
                    )
                }
            }
            Box(
                modifier =
                    Modifier.width(dimensionResource(R.dimen.key_action_width))
                        .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.verified ->
                        Icon(
                            painter = painterResource(R.drawable.ic_verified_user_24dp),
                            contentDescription = null,
                            tint = colorResource(R.color.light_green_600),
                            modifier =
                                Modifier.size(40.dp)
                                    .alpha(state.verifiedAlpha)
                                    .combinedClickable(
                                        onClick = { state.onTap?.invoke() },
                                        onLongClick =
                                            if (state.actions.isEmpty()) null
                                            else ({ menuOpen = true }),
                                    ),
                        )
                    state.enableDeviceVisible && !deviceEnabled ->
                        IconButton(
                            onClick = {
                                deviceEnabled = true
                                state.onEnableDevice()
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_new_releases_24dp),
                                contentDescription = null,
                            )
                        }
                    showSwitch ->
                        Switch(
                            checked = state.switchChecked,
                            onCheckedChange = state.onSwitchChanged,
                            enabled = state.switchEnabled,
                        )
                }
            }
        }
        if (state.actions.isNotEmpty()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (action in state.actions) {
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            menuOpen = false
                            action.onSelected()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One entry of the `omemo_key_context.xml` menu: the label the menu item carried, and what
 * `OmemoActivity.onContextItemSelected` ran for it. The host resolves which entries are visible, so
 * this list is drawn in the order the menu declared them.
 */
data class ContactKeyAction(val label: String, val onSelected: () -> Unit)

/**
 * Everything one `contact_key.xml` row drew, resolved by its host.
 *
 * @param key the prettified fingerprint (or the OpenPGP key id), drawn monospace.
 * @param typeLabel the key's type line, or `null` for the `GONE` line `showTag = false` left behind.
 * @param typeHighlighted the `colorPrimaryVariant` the `highlight` arm put on that line.
 * @param active the key is in use: `onSurface` text and a usable switch, against `onSurfaceVariant`
 *     and a dead one.
 * @param verified the green `ic_verified_user_24dp` mark replaces the action slot entirely.
 * @param verifiedAlpha 1.0 on an active key, 0.4368 on an inactive one.
 * @param switchVisible the trust switch is the action slot's content.
 * @param switchChecked `tglTrust.isChecked`, which the shared view set from `status.isTrusted()`.
 * @param switchEnabled the `tglTrust.isEnabled = false` an inactive, unverified key got.
 * @param enableDeviceVisible the `button_enable_device` arm for an undecided key that needs enabling.
 * @param actions the context menu's visible items.
 * @param onTap what the row, the key and the type line ran on a tap - the shared view's toast.
 * @param onSwitchChanged `tglTrust`'s `OnCheckedChangeListener`.
 * @param onEnableDevice `button_enable_device`'s listener.
 */
data class ContactKeyRowState(
    val key: String,
    val typeLabel: String? = null,
    val typeHighlighted: Boolean = false,
    val active: Boolean = true,
    val verified: Boolean = false,
    val verifiedAlpha: Float = 1f,
    val switchVisible: Boolean = false,
    val switchChecked: Boolean = false,
    val switchEnabled: Boolean = true,
    val enableDeviceVisible: Boolean = false,
    val actions: List<ContactKeyAction> = emptyList(),
    val onTap: (() -> Unit)? = null,
    val onSwitchChanged: (Boolean) -> Unit = {},
    val onEnableDevice: () -> Unit = {},
)
