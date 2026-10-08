package uk.xa0.tulkki.ui.accounts

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.conversationlist.AvatarShape

/**
 * The account list the three "pick an account" screens draw, and the row `item_account.xml` drew.
 *
 * <p>**The layout is gone from the screens.** `activity_manage_accounts.xml` held a toolbar, an
 * `account_list` `RecyclerView`, the phone-accounts row and the settings icon inside it;
 * `item_account.xml` held one account row and `adapter/AccountAdapter.kt` inflated it. The three
 * hosts - `ManageAccountActivity`, `ShareViaAccountActivity`,
 * `ChooseAccountForProfilePictureActivity` - now compose this screen inside the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]), and `AccountAdapter` is deleted. The toolbar, its
 * overflow and the row's own `item_account` inflation went with the views they belonged to.
 *
 * <p>**What this file decides and what the host decides.** The screen draws
 * [ManageAccountsState] and emits taps and moves: no registry, no service and no preference is read
 * here, so the whole screen is reachable from a JVM cell with literal rows. The avatar, the account
 * colour and the verification shield's drawable are resolved by the host, because the avatar
 * service, the theme and the account model are the host's, exactly as the deleted adapter resolved
 * them in the host's `Activity`.
 *
 * <p>**The context menu is the host's list.** Only `ManageAccountActivity` wires one; the other two
 * hosts pass `null` and their rows have no long-press menu, which is what they passed the adapter.
 * The host builds the visible items ([AccountContextMenu]) because the visibility rules read the
 * account's enabled state and `Config.supportOpenPgp()`.
 */

/** What a row's status text is drawn in, as the deleted adapter chose its `MaterialColors` role. */
enum class AccountStatusTone {
    PRIMARY,
    NEUTRAL,
    ERROR,
}

/**
 * One account row, exactly the facts the deleted `AccountAdapter.onBindViewHolder` read.
 *
 * @param jid the bare address, which is also the row's stable identity.
 * @param avatar the resolved avatar, or `null` for the plate the tree drew until one landed.
 * @param statusText the readable status string (`Account.State.getReadableId`).
 * @param tone which theme role the status is drawn in: `colorPrimary` for online,
 *     `colorOnSurfaceVariant` for a disabled, logged-out or connecting account, and `colorError` for
 *     every other state.
 * @param statusMessage the presence status message, or `null`.
 * @param verificationIcon the shield's drawable, or `null` when the account is not online and
 *     connected - the three-way `resolverAuthenticated`/`daneVerified` choice the adapter made.
 * @param enabled the switch's position: `!account.isEnabled()` was the adapter's `!isDisabled`.
 * @param color the frame's colour, or `null` when the tree left the row uncoloured (no service, or a
 *     single account).
 */
data class AccountRow(
    val jid: String,
    val avatar: Drawable?,
    val statusText: String,
    val tone: AccountStatusTone,
    val statusMessage: String?,
    @DrawableRes val verificationIcon: Int?,
    val enabled: Boolean,
    val color: Int?,
)

/** The whole screen, read here and written by the host. */
data class ManageAccountsState(
    val rows: List<AccountRow> = emptyList(),
    /** The Android dialler row, shown only while an account carries a `pstn` gateway. */
    val showPhoneAccounts: Boolean = false,
    /** `true` only for `ManageAccountActivity`, the one host that passed a drag listener. */
    val reorderable: Boolean = false,
)

/** The long-press menu for one row: the header the old `setHeaderTitle` drew and its items. */
data class AccountContextMenu(
    val title: String,
    val items: List<ChromeMenuItem>,
)

/**
 * One account, as a row - the deleted adapter's `onBindViewHolder`, minus the views.
 *
 * @param context any context, for the status string the row's `TextView` was set to.
 * @param color the row's frame colour, or `null` when the host left it uncoloured.
 */
fun accountRow(
    context: Context,
    account: Account,
    avatar: Drawable?,
    color: Int?,
): AccountRow {
    val state = account.getStatus()
    return AccountRow(
        jid = account.getJid().asBareJid().toString(),
        avatar = avatar,
        statusText = context.getString(state.getReadableId()),
        tone =
            when (state) {
                Account.State.ONLINE -> AccountStatusTone.PRIMARY
                Account.State.DISABLED, Account.State.LOGGED_OUT, Account.State.CONNECTING ->
                    AccountStatusTone.NEUTRAL
                else -> AccountStatusTone.ERROR
            },
        statusMessage = account.getPresenceStatusMessage(),
        verificationIcon = verificationIcon(account),
        enabled = account.isEnabled(),
        color = color,
    )
}

/** The adapter's three-way shield: hidden unless online and connected. */
@DrawableRes
private fun verificationIcon(account: Account): Int? {
    if (!account.isOnlineAndConnected()) {
        return null
    }
    val connection = account.getXmppConnection()
    return if (connection != null && connection.resolverAuthenticated()) {
        if (connection.daneVerified()) R.drawable.shield_verified else R.drawable.shield
    } else {
        R.drawable.shield_question
    }
}

/**
 * The screen.
 *
 * @param state the rows and the two visibility facts.
 * @param avatarShape the owner's `avatar_shape` preference, which the row's avatar is clipped to.
 * @param onRow a row tap.
 * @param onToggle the row switch, with its new position.
 * @param onMove a reorder in progress, as the two positions the deleted `onItemMove` took.
 * @param onMoveFinished the deleted `clearView`: the drag ended and the order should be committed.
 * @param contextMenu the row's long-press menu, or `null` for a host that wired none.
 * @param onPhoneAccounts a tap on the dialler row.
 * @param onPhoneAccountsSettings a tap on the dialler row's settings icon.
 */
@Composable
fun ManageAccountsScreen(
    state: ManageAccountsState,
    avatarShape: AvatarShape,
    onRow: (AccountRow) -> Unit,
    onToggle: (AccountRow, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onMoveFinished: () -> Unit = {},
    contextMenu: ((AccountRow) -> AccountContextMenu?)? = null,
    onPhoneAccounts: () -> Unit = {},
    onPhoneAccountsSettings: () -> Unit = {},
) {
    val currentRows by rememberUpdatedState(state.rows)
    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items = state.rows, key = { it.jid }) { row ->
            val dragHandle =
                if (state.reorderable) {
                    Modifier.pointerInput(row.jid) {
                        detectDragGestures(
                            onDragStart = {
                                draggingKey = row.jid
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                if (draggingKey != null) {
                                    onMoveFinished()
                                }
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                if (draggingKey != null) {
                                    onMoveFinished()
                                }
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val key = draggingKey
                                if (key != null) {
                                    val rows = currentRows
                                    val current = rows.indexOfFirst { it.jid == key }
                                    val height =
                                        listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == current }
                                            ?.size
                                    if (current >= 0 && height != null && height > 0) {
                                        dragOffset += amount.y
                                        var target = current
                                        while (dragOffset > height / 2f &&
                                            target < rows.lastIndex
                                        ) {
                                            dragOffset -= height
                                            target++
                                        }
                                        while (dragOffset < -height / 2f && target > 0) {
                                            dragOffset += height
                                            target--
                                        }
                                        if (target != current) {
                                            onMove(current, target)
                                        }
                                    }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                }
            AccountRowItem(
                row = row,
                shape = avatarShape,
                dragHandle = dragHandle,
                contextMenu = contextMenu,
                onRow = { onRow(row) },
                onToggle = { onToggle(row, it) },
            )
        }
        if (state.showPhoneAccounts) {
            item(key = "phone_accounts") {
                PhoneAccountsRow(
                    onClick = onPhoneAccounts,
                    onSettings = onPhoneAccountsSettings,
                )
            }
        }
    }
}

/** The row `item_account.xml` drew: the avatar, the three texts, the handle and the switch. */
@Composable
private fun AccountRowItem(
    row: AccountRow,
    shape: AvatarShape,
    dragHandle: Modifier,
    contextMenu: ((AccountRow) -> AccountContextMenu?)?,
    onRow: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    // The menu is built on the long press, never during composition: the host's builder tells the
    // activity which account was selected, and that side effect happens once per long press.
    var menu by remember { mutableStateOf<AccountContextMenu?>(null) }
    val builder = contextMenu
    val avatarDescription = stringResource(R.string.your_avatar)
    val dragDescription = stringResource(R.string.drag_handle)
    Box {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(row.color?.let { Color(it) } ?: Color.Transparent)
                    .then(
                        if (builder == null) {
                            Modifier.clickable(onClick = onRow)
                        } else {
                            Modifier.combinedClickable(
                                onClick = onRow,
                                onLongClick = { menu = builder.invoke(row) },
                            )
                        }
                    )
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(row.avatar, shape, avatarDescription)
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(start = dimensionResource(R.dimen.avatar_item_distance))
            ) {
                Text(
                    text = row.jid,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(row.tone),
                    )
                    row.verificationIcon?.let { icon ->
                        Image(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = row.statusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(R.drawable.rounded_drag_handle_24),
                contentDescription = dragDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandle.padding(horizontal = 4.dp),
            )
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            )
        }
        val open = menu
        if (open != null) {
            DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                Text(
                    text = open.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                for (item in open.items) {
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menu = null
                            item.onSelected()
                        },
                    )
                }
            }
        }
    }
}

/** The Android dialler row inside the deleted layout's `phone_accounts` `RelativeLayout`. */
@Composable
private fun PhoneAccountsRow(onClick: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(dimensionResource(R.dimen.avatar))
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.avatar_radius)))
                    .background(colorResource(R.color.yeller)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_call_24dp),
                contentDescription = null,
                tint = Color.White,
            )
        }
        Column(
            modifier =
                Modifier.weight(1f).padding(start = dimensionResource(R.dimen.avatar_item_distance))
        ) {
            Text(
                text = stringResource(R.string.manage_phone_accounts),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.dialler_integration),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_24dp),
                contentDescription = null,
            )
        }
    }
}

/** The row's avatar: the tree's `AvatarView`, clipped by the owner's shape preference. */
@Composable
private fun AccountAvatar(avatar: Drawable?, shape: AvatarShape, description: String) {
    val size = dimensionResource(R.dimen.avatar)
    val clip = shape.toShape()
    if (avatar == null) {
        Box(
            modifier =
                Modifier.size(size)
                    .clip(clip)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    Canvas(modifier = Modifier.size(size).clip(clip).semantics { contentDescription = description }) {
        drawIntoCanvas { canvas ->
            avatar.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt())
            avatar.draw(canvas.nativeCanvas)
        }
    }
}

/** The owner's `avatar_shape`, as the clip the row draws with. */
@Composable
private fun AvatarShape.toShape(): Shape =
    when (this) {
        AvatarShape.OVAL -> CircleShape
        AvatarShape.ROUNDED_SQUARE -> RoundedCornerShape(dimensionResource(R.dimen.avatar_corners_radius))
        AvatarShape.SQUARE -> RectangleShape
    }

/** The three roles the deleted adapter coloured the status text with. */
@Composable
private fun statusColor(tone: AccountStatusTone): Color =
    when (tone) {
        AccountStatusTone.PRIMARY -> MaterialTheme.colorScheme.primary
        AccountStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        AccountStatusTone.ERROR -> MaterialTheme.colorScheme.error
    }

/**
 * One account's avatar, on the calling thread - the body of the deleted
 * `AvatarWorkerTask.loadAvatar`, which resolved synchronously before it fell back to `AsyncTask`.
 */
fun XmppActivity.accountAvatar(account: Account): Drawable? =
    try {
        avatarService().get(account, resources.getDimension(R.dimen.avatar).toInt(), true)
    } catch (e: RuntimeException) {
        // An avatar is decoration: a row whose image cannot be resolved is drawn without one.
        null
    }

/** The row's frame colour, or `null` - the deleted adapter's two guards, unchanged. */
fun XmppActivity.accountFrameColor(account: Account): Int? =
    if (xmppConnectionService != null && AccountRegistry.get().getAccounts().size > 1) {
        account.getColor(isDark())
    } else {
        null
    }
