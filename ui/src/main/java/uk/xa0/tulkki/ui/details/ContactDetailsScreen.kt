package uk.xa0.tulkki.ui.details

import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.extras.TagEditorField
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.omemo.ContactKeyRow
import uk.xa0.tulkki.ui.omemo.ContactKeyRowState

/**
 * The one-to-one contact's details body: the deleted `activity_contact_details.xml`, section for
 * section.
 *
 * <p>**What moved and what stayed.** The layout held one `ScrollView` over `details_main_layout`:
 * a header (the avatar over its presence dot, the name, the JID, the tag chips, the last-seen line,
 * the status message, the clients list, the two action buttons and the five switches), then four
 * ruled sections - the vCard profile list, the recent threads, the media block and the OMEMO keys
 * block. The header, the section rules, the tag chips, the vCard profile rows and the thread rows are
 * Compose now, and the screen's own layout file is deleted. Two surfaces are deliberately still
 * views, hosted through [HostView]:
 *
 *  * the avatar and its presence dot, because `AvatarView`/`PresenceIndicator` and
 *    `AvatarWorkerTask` are shared with screens this lane does not own (the `PostsScreen` precedent
 *    for an avatar in an `AndroidView`);
 *  * the media grid, because `MediaAdapter` is explicitly "the RecyclerView bridge those two
 *    screens still need" and its item views are built inside the adapter.
 *
 * The vCard profile list is Compose rows ([ProfileRowItem], one per [ContactProfileRow] the host
 * resolves): `VcardAdapter` and the `command_row.xml` every row reused are deleted with it.
 *
 * The OMEMO key rows are [keyRows], the Compose shape [ContactKeyRow] draws: `contact_key.xml` and
 * its `omemo_key_context.xml` menu are deleted, and the host resolves each row's facts and menu
 * items instead of inflating a view.
 *
 * The tag editor is Compose now (`TagEditorState`), drawn where the deleted `edit_tags` sat, in the
 * header: `item_tag.xml` and `TagEditorView` are deleted.
 *
 * <p>**The editing field.** The old screen's "edit contact" item was a toolbar action view
 * (`actionview_edit.xml`) whose `EditText` the host read back. The chrome has no action view, so
 * the field is this screen's own `OutlinedTextField` at the top of the header; it is filled with
 * the same server name, its "done" IME action runs the same save, and it is only drawn while the
 * item is being edited. The field it replaced carried no hint, so this one carries none either.
 *
 * @param state every value the deleted layout was given, already resolved.
 * @param events what the deleted views' listeners ran.
 * @param avatar the `AvatarView` the host built and loaded.
 * @param presence the `PresenceIndicator` the host set.
 * @param tagEditor the `TagEditorState` the host fills, or `null` in a cell.
 * @param keyRows the OMEMO and OpenPGP key rows, already resolved by the host.
 * @param mediaGrid the `RecyclerView` of `MediaAdapter` rows.
 * @param onEditNameChanged the field's text, which the host keeps as the name to save.
 * @param onEditDone the field's IME action, which runs the save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactDetailsScreen(
    state: ContactDetailsState,
    events: ContactDetailsEvents,
    avatar: View?,
    presence: View?,
    tagEditor: TagEditorState?,
    keyRows: List<ContactKeyRowState>,
    mediaGrid: View?,
    onEditNameChanged: (String) -> Unit,
    onEditDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (state.editing) {
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    focus.requestFocus()
                    keyboard?.show()
                }
                OutlinedTextField(
                    value = state.editName,
                    onValueChange = onEditNameChanged,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onEditDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(96.dp)) {
                    HostView(avatar, modifier = Modifier.fillMaxSize())
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = 2.dp, bottom = 2.dp)
                                .size(12.dp)
                    ) {
                        HostView(presence, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Text(
                text = state.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            )
            Text(
                text = state.jid,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.editing) {
                if (state.tagsEditable) {
                    tagEditor?.let {
                        TagEditorField(
                            state = it,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            } else if (state.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    for (chip in state.tags) {
                        TagPill(chip, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }

            state.lastSeen?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style =
                        if (state.statusMessageEmojiOnly) {
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize * 2f
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }

            state.clients?.let { clients ->
                Text(
                    text = clients,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }

            state.archiveLabelRes?.let { label ->
                ElevatedButton(
                    onClick = events.onArchive,
                    colors =
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(state.archiveContainerColor),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp),
                ) {
                    Text(stringResource(label))
                }
            }

            state.extraLabelRes?.let { label ->
                ElevatedButton(
                    onClick = events.onExtraButton,
                    colors =
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(state.extraContainerColor),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                ) {
                    Text(stringResource(label))
                }
            }

            if (state.followFeedVisible) {
                SwitchRow(
                    label = stringResource(R.string.follow_feed),
                    checked = state.followFeedChecked,
                    enabled = state.followFeedEnabled,
                    onCheckedChange = events.onFollowFeedChanged,
                )
            }

            if (state.sendPresenceVisible) {
                SwitchRow(
                    label = stringResource(state.sendPresenceLabelRes),
                    checked = state.sendPresenceChecked,
                    enabled = state.presenceEnabled,
                    onCheckedChange = events.onSendPresenceChanged,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.receivePresenceVisible) {
                SwitchRow(
                    label = stringResource(state.receivePresenceLabelRes),
                    checked = state.receivePresenceChecked,
                    enabled = state.presenceEnabled,
                    onCheckedChange = events.onReceivePresenceChanged,
                )
            }

            SwitchRow(
                label = stringResource(R.string.disable_voice_and_video_calls),
                checked = state.callsDisabled,
                enabled = true,
                onCheckedChange = events.onCallsDisabledChanged,
                modifier = Modifier.padding(top = 8.dp),
            )

            EphemeralMessages(
                row = state.ephemeral,
                onToggle = events.onEphemeralToggled,
                onDurationSelected = events.onEphemeralDurationSelected,
                modifier = Modifier.padding(top = 8.dp),
            )

            Text(
                text = state.accountLine,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.End).padding(top = 32.dp),
            )
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            DetailsDivider()
            for (row in state.profile) {
                ProfileRowItem(
                    row = row,
                    onClick = { events.onProfileRowClick(row) },
                    onLongClick = { events.onProfileRowLongClick(row) },
                )
            }
        }

        if (state.threads.isNotEmpty()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DetailsDivider()
                for (thread in state.threads) {
                    ThreadRowItem(thread = thread, onClick = { events.onThread(thread) })
                }
            }
        }

        if (state.mediaVisible) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DetailsDivider()
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.store_media_only_in_cache),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.storeSecurely,
                            onCheckedChange = events.onStoreSecurelyChanged,
                        )
                    }
                    HostView(mediaGrid, modifier = Modifier.fillMaxWidth())
                    if (state.showMediaVisible) {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            OutlinedButton(onClick = events.onShowMedia) {
                                Text(stringResource(R.string.view_media))
                            }
                        }
                    }
                }
            }
        }

        if (state.keysVisible) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DetailsDivider()
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (row in keyRows) {
                        ContactKeyRow(row, modifier = Modifier.padding(8.dp))
                    }
                    if (state.unverifiedVisible) {
                        Text(
                            text = stringResource(R.string.contact_uses_unverified_keys),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        if (state.scanVisible) {
                            TextButton(onClick = events.onScan) {
                                Text(stringResource(R.string.scan_qr_code))
                            }
                        }
                        state.inactiveLabelRes?.let { label ->
                            TextButton(onClick = events.onToggleInactiveDevices) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One value the deleted `activity_contact_details.xml` drew, with `null` standing for the `GONE`
 * its view carried. Built by the screen's host (`ContactDetailsActivity.populateView`) and read once
 * per composition.
 *
 * <p>The two container colours are already resolved - the deleted code read
 * `R.color.md_theme_dark_error` and tinted those buttons' backgrounds with it, or
 * `R.color.md_theme_light_surface` for the "add contact" arm - so this file names no colour
 * resource of its own.
 */
data class ContactDetailsState(
    val titleRes: Int = R.string.action_contact_details,
    val editing: Boolean = false,
    val editName: String = "",
    val tagsEditable: Boolean = false,
    val editVisible: Boolean = false,
    val blockVisible: Boolean = false,
    val unblockVisible: Boolean = false,
    val customNotificationsVisible: Boolean = false,
    val tags: List<TagChip> = emptyList(),
    val name: String = "",
    val jid: AnnotatedString = AnnotatedString(""),
    val accountLine: String = "",
    val lastSeen: String? = null,
    val statusMessage: String? = null,
    val statusMessageEmojiOnly: Boolean = false,
    val clients: String? = null,
    val archiveLabelRes: Int? = null,
    val archiveContainerColor: Int = 0,
    val extraLabelRes: Int? = null,
    val extraContainerColor: Int = 0,
    val followFeedVisible: Boolean = false,
    val followFeedChecked: Boolean = false,
    val followFeedEnabled: Boolean = true,
    val sendPresenceVisible: Boolean = false,
    val sendPresenceLabelRes: Int = R.string.send_presence_updates,
    val sendPresenceChecked: Boolean = false,
    val receivePresenceVisible: Boolean = false,
    val receivePresenceLabelRes: Int = R.string.receive_presence_updates,
    val receivePresenceChecked: Boolean = false,
    val presenceEnabled: Boolean = false,
    val callsDisabled: Boolean = false,
    val ephemeral: EphemeralRow = EphemeralRow(),
    val threads: List<ThreadRow> = emptyList(),
    val profile: List<ContactProfileRow> = emptyList(),
    val mediaVisible: Boolean = false,
    val storeSecurely: Boolean = false,
    val showMediaVisible: Boolean = false,
    val keysVisible: Boolean = false,
    val unverifiedVisible: Boolean = false,
    val scanVisible: Boolean = false,
    val inactiveLabelRes: Int? = null,
)

/** The listener bodies the deleted views ran, one for one. */
data class ContactDetailsEvents(
    val onArchive: () -> Unit,
    val onExtraButton: () -> Unit,
    val onFollowFeedChanged: (Boolean) -> Unit,
    val onSendPresenceChanged: (Boolean) -> Unit,
    val onReceivePresenceChanged: (Boolean) -> Unit,
    val onCallsDisabledChanged: (Boolean) -> Unit,
    val onEphemeralToggled: (Boolean) -> Unit,
    val onEphemeralDurationSelected: (Int) -> Unit,
    val onThread: (ThreadRow) -> Unit,
    val onProfileRowClick: (ContactProfileRow) -> Unit,
    val onProfileRowLongClick: (ContactProfileRow) -> Unit,
    val onStoreSecurelyChanged: (Boolean) -> Unit,
    val onShowMedia: () -> Unit,
    val onScan: () -> Unit,
    val onToggleInactiveDevices: () -> Unit,
)

/**
 * One vCard profile row as the deleted `VcardAdapter` resolved it: the label its `getView` set, the
 * icon it drew beside it (already a `R.drawable` id rather than the `Drawable` it fetched), and the
 * URI and fallback text its click and long click consumed.
 *
 * @param label the field's text or the URI's scheme-specific part, exactly as `setText` took it.
 * @param iconRes the drawable id `setCompoundDrawablesRelativeWithIntrinsicBounds` was given, or
 *     `null` when the field had no icon of its own.
 * @param uri the `getUri` answer, which the row click opens, or `null`.
 * @param copyText what the long click copies: the URI when there is one, the `text` child otherwise.
 */
data class ContactProfileRow(
    val label: String?,
    @DrawableRes val iconRes: Int?,
    val uri: String?,
    val copyText: String?,
)

/**
 * One vCard profile row: the icon at the adapter's own 20 px gap and the label in `bodyMedium`,
 * over the small list-item height the deleted `command_row.xml` carried, with the row taking both
 * taps - a tap opens the URI, a long press copies [ContactProfileRow.copyText].
 */
@Composable
fun ProfileRowItem(
    row: ContactProfileRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { 20.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp),
    ) {
        row.iconRes?.let { iconRes ->
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.padding(end = gap).size(24.dp),
            )
        }
        Text(
            text = row.label.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
