package uk.xa0.tulkki.ui.details

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.extras.TagEditorField
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.MucDetailsAction
import uk.xa0.tulkki.ui.util.MucUserDropdownMenu
import uk.xa0.tulkki.ui.util.MucUserMenu
import uk.xa0.tulkki.ui.widget.AvatarView

/**
 * The group-chat / channel details body: the deleted `activity_muc_details.xml`, section for
 * section.
 *
 * <p>**What moved and what stayed.** The layout held one `ScrollView` over `muc_main_layout`: the
 * header (the room avatar, the title and subject over their tag chips, the "edit name and topic"
 * field group, the leave/add/destroy buttons, the server-info row, the browse button and the two
 * JID lines), then three ruled blocks - the participants, the owner's own settings (nick, role,
 * notifications, ephemeral messages) and the media block - plus the recent threads. The header, the
 * blocks, the tag chips and the thread rows are Compose now, and the screen's own layout file is
 * deleted. Three surfaces stay views, hosted through [HostView] for the same reasons the one-to-one
 * screen keeps them:
 *
 *  * the room avatar, an `AvatarView` loaded by `AvatarWorkerTask` (its click publishes the group
 *    avatar, its long press opens the photo menu);
 *  * the subject line, a `TextView`: it is handed a `SpannableStringBuilder` that
 *    `StylingHelper.format` decorates and `MyLinkify` fills with its own `FixedURLSpan`s, and it
 *    switches between two `TextAppearance`s by length - all of which a `TextView` does and a
 *    `Text` would have to re-derive;
 *  * the media grid, `MediaAdapter` - "the RecyclerView bridge those two screens still need".
 *
 * The participants grid is Compose now ([UserPreviewItem]): `item_user_preview.xml` and
 * `UserPreviewAdapter` are deleted, the row is one square `media_size` cell, and its long press
 * opens the same participant menu in the row ([MucUserDropdownMenu]) - the shape `MucUsersActivity`
 * uses too.
 *
 * The tag editor is Compose now (`TagEditorState`), drawn inside the editor group where the deleted
 * `edit_tags` sat: `item_tag.xml` and `TagEditorView` are deleted.
 *
 * @param state every value the deleted layout was given, already resolved.
 * @param events what the deleted views' listeners ran.
 * @param avatar the `AvatarView` the host built and loaded.
 * @param subjectLine the `TextView` the host decorates.
 * @param tagEditor the `TagEditorState` the host fills, or `null` in a cell.
 * @param users the participants, in the order the host sorted them; the screen keeps the one row
 *     `MucOptions.sub` allows for the width it was given.
 * @param userRow one participant's row facts, resolved by the host.
 * @param onUserOpen a row's tap: the host's `highlightInMuc` and left-conference toast.
 * @param onUserMenu a row's long press, which opens the host's [MucUserMenu].
 * @param userMenu the participant menu the host resolved, or `null` while none is open.
 * @param onUserMenuDismiss the menu's own dismissal.
 * @param onUserMenuSelected the verb chosen from the menu.
 * @param mediaGrid the `RecyclerView` of `MediaAdapter` rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConferenceDetailsScreen(
    state: ConferenceDetailsState,
    events: ConferenceDetailsEvents,
    avatar: View?,
    subjectLine: View?,
    tagEditor: TagEditorState?,
    users: List<MucOptions.User>,
    userRow: (MucOptions.User) -> ConferenceUserRow,
    onUserOpen: (MucOptions.User) -> Unit,
    onUserMenu: (MucOptions.User) -> Unit,
    userMenu: MucUserMenu?,
    onUserMenuDismiss: () -> Unit,
    onUserMenuSelected: (MucDetailsAction) -> Unit,
    mediaGrid: View?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(vertical = 8.dp).padding(16.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                HostView(avatar, modifier = Modifier.size(96.dp))
                // The deleted `conference_photo.xml`, anchored where its `PopupMenu` was: the photo
                // menu the avatar's long press opens, with the same two items.
                DropdownMenu(
                    expanded = state.photoMenuOpen,
                    onDismissRequest = events.onPhotoMenuDismiss,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_avatar)) },
                        onClick = events.onShowAvatar,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_avatar)) },
                        onClick = events.onBlockAvatar,
                    )
                }
            }

            // The deleted `edit_muc_name_button` sat at the top end of the header, and the whole
            // title/subject block was laid out below it (`android:layout_below`), so the button is
            // drawn first and the block follows - in both the display and the editor state.
            if (state.editButtonVisible) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = events.onEditNameOrTopic) {
                        Icon(
                            painter = painterResource(state.editorButtonIconRes),
                            contentDescription =
                                stringResource(state.editorButtonDescriptionRes),
                        )
                    }
                }
            }

            if (state.editorVisible) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (state.editorNameVisible) {
                        OutlinedTextField(
                            value = state.editName,
                            onValueChange = events.onEditNameChanged,
                            singleLine = true,
                            enabled = state.editorNameEnabled,
                            label = { Text(stringResource(R.string.group_chat_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = state.editSubject,
                        onValueChange = events.onEditSubjectChanged,
                        singleLine = true,
                        enabled = state.editorSubjectEnabled,
                        label = { Text(stringResource(R.string.topic)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.editorTagsVisible) {
                        tagEditor?.let {
                            TagEditorField(
                                state = it,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        )
                    }
                    if (state.subject != null) {
                        HostView(subjectLine, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.tags.isNotEmpty()) {
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
                }
            }

            state.leaveLabelRes?.let { label ->
                ElevatedButton(
                    onClick = events.onLeave,
                    colors =
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(state.leaveContainerColor),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp),
                ) {
                    Text(stringResource(label))
                }
            }

            state.addLabelRes?.let { label ->
                ElevatedButton(
                    onClick = events.onAddToContacts,
                    colors =
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(state.addContainerColor),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp),
                ) {
                    Text(stringResource(label))
                }
            }

            state.destroyLabelRes?.let { label ->
                ElevatedButton(
                    onClick = events.onDestroy,
                    colors =
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = Color(state.destroyContainerColor),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp),
                ) {
                    Icon(painter = painterResource(R.drawable.delete_black_24dp), contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(label))
                }
            }

            if (state.settingsVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Text(
                        text = state.conferenceType ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.changeConferenceVisible) {
                        IconButton(onClick = events.onChangeConference) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_24dp),
                                contentDescription = stringResource(R.string.edit_configuration),
                            )
                        }
                    }
                }
            }

            if (state.infoMoreVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.server_info_mam),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = state.mamTextRes?.let { stringResource(it) } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            TextButton(
                onClick = events.onBrowseSpace,
                modifier = Modifier.align(Alignment.End).padding(top = 32.dp),
            ) {
                Text(stringResource(R.string.browse_space))
            }

            state.jid?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                )
            }
            state.trueJid?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            DetailsDivider()
            if (state.usersVisible) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    state.noUsersHintRes?.let {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        // The deleted grid's span count, from the width the RecyclerView measured:
                        // `GridManager.calculateColumnCount` rounded the available width by
                        // `media_size`, and `MucOptions.sub` is the one-row preview it showed.
                        val mediaSize = dimensionResource(R.dimen.media_size)
                        val columns = maxOf(1, Math.round(maxWidth / mediaSize))
                        FlowRow {
                            for (user in MucOptions.sub(users, columns)) {
                                val row = userRow(user)
                                UserPreviewItem(
                                    row = row,
                                    // The menu is drawn in the row it belongs to, which is where
                                    // the deleted `PopupMenu` should have pointed.
                                    menu = userMenu?.takeIf { it.key == row.key },
                                    onOpen = { onUserOpen(user) },
                                    onMenuOpen = { onUserMenu(user) },
                                    onMenuDismiss = onUserMenuDismiss,
                                    onMenuSelected = onUserMenuSelected,
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        if (state.inviteVisible) {
                            TextButton(onClick = events.onInvite) {
                                Text(stringResource(R.string.invite))
                            }
                        }
                        if (state.showUsersVisible) {
                            TextButton(onClick = events.onShowUsers) {
                                Text(state.showUsersLabel)
                            }
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            DetailsDivider()
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.nick,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.role?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = events.onEditNick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit_24dp),
                            contentDescription = stringResource(R.string.edit_nick),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(state.notificationTextRes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = events.onNotifications) {
                        Icon(
                            painter = painterResource(state.notificationIconRes),
                            contentDescription =
                                stringResource(R.string.change_notification_settings),
                        )
                    }
                }

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
    }
}

/**
 * One value the deleted `activity_muc_details.xml` drew, with `null` standing for the `GONE` its
 * view carried. Built by this screen's host (`ConferenceDetailsActivity.updateView`).
 *
 * <p>`role`, `conferenceType` and `mamTextRes` are `null` when the deleted code had no assignment
 * for them on this pass; the views it left alone kept their last text, so the host carries the
 * previous value across rather than clearing it.
 */
data class ConferenceDetailsState(
    val titleRes: Int = R.string.action_muc_details,
    val editButtonVisible: Boolean = false,
    val editorVisible: Boolean = false,
    val editorButtonIconRes: Int = R.drawable.ic_edit_24dp,
    val editorButtonDescriptionRes: Int = R.string.edit_name_and_topic,
    val editorNameVisible: Boolean = true,
    val editorNameEnabled: Boolean = true,
    val editName: String = "",
    val editorSubjectEnabled: Boolean = true,
    val editSubject: String = "",
    val editorTagsVisible: Boolean = false,
    val photoMenuOpen: Boolean = false,
    val title: String? = null,
    val subject: String? = null,
    val tags: List<TagChip> = emptyList(),
    val leaveLabelRes: Int? = null,
    val leaveContainerColor: Int = 0,
    val addLabelRes: Int? = null,
    val addContainerColor: Int = 0,
    val destroyLabelRes: Int? = null,
    val destroyContainerColor: Int = 0,
    val settingsVisible: Boolean = false,
    val changeConferenceVisible: Boolean = false,
    val conferenceType: String? = null,
    val infoMoreVisible: Boolean = false,
    val mamTextRes: Int? = null,
    val jid: String? = null,
    val trueJid: String? = null,
    val usersVisible: Boolean = false,
    val noUsersHintRes: Int? = null,
    val inviteVisible: Boolean = false,
    val showUsersVisible: Boolean = false,
    val showUsersLabel: String = "",
    val nick: String = "",
    val role: String? = null,
    val notificationTextRes: Int = R.string.notify_on_all_messages,
    val notificationIconRes: Int = R.drawable.ic_notifications_24dp,
    val ephemeral: EphemeralRow = EphemeralRow(),
    val accountLine: String = "",
    val threads: List<ThreadRow> = emptyList(),
    val mediaVisible: Boolean = false,
    val storeSecurely: Boolean = false,
    val showMediaVisible: Boolean = false,
)

/** The listener bodies the deleted views ran, one for one. */
data class ConferenceDetailsEvents(
    val onEditNameOrTopic: () -> Unit,
    val onLeave: () -> Unit,
    val onAddToContacts: () -> Unit,
    val onDestroy: () -> Unit,
    val onChangeConference: () -> Unit,
    val onBrowseSpace: () -> Unit,
    val onInvite: () -> Unit,
    val onShowUsers: () -> Unit,
    val onEditNick: () -> Unit,
    val onNotifications: () -> Unit,
    val onEphemeralToggled: (Boolean) -> Unit,
    val onEphemeralDurationSelected: (Int) -> Unit,
    val onThread: (ThreadRow) -> Unit,
    val onStoreSecurelyChanged: (Boolean) -> Unit,
    val onShowMedia: () -> Unit,
    val onEditNameChanged: (String) -> Unit,
    val onEditSubjectChanged: (String) -> Unit,
    val onShowAvatar: () -> Unit,
    val onBlockAvatar: () -> Unit,
    val onPhotoMenuDismiss: () -> Unit,
)

/**
 * One participant's row facts: what `UserPreviewAdapter.onBindViewHolder` loaded into its view.
 *
 * @param key the row's identity in the grid.
 * @param presenceColor the dot's colour, or `null` for a row that draws no dot - the old
 *     `PresenceIndicator.setStatus` arm, with its `show_contact_status` preference and online check.
 * @param avatarable what the avatar service loads, or `null` in a screenshot cell.
 */
data class ConferenceUserRow(val key: String, val presenceColor: Int?, val avatarable: Avatarable?)

/**
 * One cell of the participants grid: the deleted `item_user_preview.xml` - a square `media_size`
 * frame inset by the layout's 2 dp, the `AvatarView` filling it and the presence dot on its foot -
 * with the adapter's tap (`highlightInMuc`) and long press (the `muc_details_context` menu).
 *
 * <p>The row's facts are [ConferenceUserRow]; the avatar is a hosted `AvatarView` because
 * `AvatarWorkerTask` loads into the `ImageView` itself, the same bridge `MucUsersActivity` uses.
 */
@Composable
private fun UserPreviewItem(
    row: ConferenceUserRow,
    menu: MucUserMenu?,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onMenuSelected: (MucDetailsAction) -> Unit,
) {
    val size = dimensionResource(R.dimen.media_size)
    Box(
        modifier =
            Modifier.size(size)
                .padding(2.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onMenuOpen)
    ) {
        val avatarable = row.avatarable
        if (avatarable == null) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        } else {
            AndroidView(
                factory = { context ->
                    AvatarView(context).also {
                        AvatarWorkerTask.loadAvatar(avatarable, it, R.dimen.media_size)
                    }
                },
                update = { view -> AvatarWorkerTask.loadAvatar(avatarable, view, R.dimen.media_size) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val dot = row.presenceColor
        if (dot != null) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(
                            end = dimensionResource(R.dimen.presence_indicator_offset),
                            bottom = dimensionResource(R.dimen.presence_indicator_offset),
                        )
                        .size(dimensionResource(R.dimen.presence_indicator_size))
                        .clip(CircleShape)
                        .background(Color(dot))
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
        if (menu != null) {
            MucUserDropdownMenu(
                menu = menu,
                onDismiss = onMenuDismiss,
                onSelected = onMenuSelected,
            )
        }
    }
}
