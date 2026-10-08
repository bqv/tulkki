package uk.xa0.tulkki.ui.list

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.details.TagPill
import uk.xa0.tulkki.ui.details.annotatedJid
import uk.xa0.tulkki.ui.projection.UiAccountLine
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.utils.XEP0392Helper
import uk.xa0.tulkki.ui.widget.AvatarView
import uk.xa0.tulkki.xmpp.utils.IrregularUnicodeDetector

/**
 * The row the contact pickers draw, and the list they draw it in. `item_contact.xml` and
 * `ListItemAdapter` are deleted: this file is their body, decided the same way.
 *
 * <p>**One row, resolved by its host.** [pickerRow] makes every decision the adapter made in
 * `getView` - the display name, the JID with its `IrregularUnicodeDetector` highlights, the dynamic
 * tags and the two meta tags, the presence dot's colour, the account line and the account colour -
 * so [PickerRowItem] is a pure drawing of facts. The activity owns the model and re-resolves the
 * rows when a filter moves it, exactly as `notifyDataSetChanged` did.
 *
 * <p>**The two lists that drew it stay separate screens.** `AbstractSearchableListItemActivity`'s
 * three pickers and `StartConversationActivity`'s two pages both hold [PickerRow]s now; the list is
 * a [LazyColumn], so nothing is inflated and no adapter exists. The row's own drawing is the deleted
 * layout's: the avatar over its presence dot, the display name, the JID line, then the tag pills.
 *
 * <p>No new string or colour is named here: the pills are the tree's [TagPill], the account line is
 * [UiAccountLine]'s rule, and the presence dot's colours are `UIHelper.getColorForStatus`'s.
 */

/**
 * One picker row, every adapter decision already made.
 *
 * @param key the row's identity for the `LazyColumn`, unique within one page.
 * @param jidValue the JID as `getJid().toString()`, which is what a choice-mode selection holds.
 * @param displayName the first line, `ListItem.getDisplayName()`.
 * @param jid the JID line, its mixed-script runs highlighted, or `null` for the `GONE` branch the
 *     adapter kept.
 * @param tags the dynamic tags, in the adapter's order, each already harmonised with the theme's
 *     primary and each the one thing on the row that opens the search.
 * @param metaTag the single `blocked` or presence tag an adapter appended for a contact, or `null`.
 * @param accountLine the `show_own_accounts` line, or `null` for a row that draws none.
 * @param presenceColor the presence dot's colour, or `null` for a row that draws no dot.
 * @param avatarable what the avatar service loads.
 * @param containerColor the account-colour coding, or `null` when the host does not colour accounts.
 */
data class PickerRow(
    val key: String,
    val jidValue: String,
    val displayName: String,
    val jid: AnnotatedString?,
    val tags: List<TagChip>,
    val metaTag: TagChip?,
    val accountLine: String?,
    val presenceColor: Int?,
    val avatarable: Avatarable?,
    val containerColor: Int?,
)

/**
 * Resolve one [ListItem] into the row `ListItemAdapter.getView` drew: its two lines, its tags, its
 * presence dot, its account line and its account colour.
 *
 * @param activity the host, which owns `colorCodeAccounts()`, `show_own_accounts` and `isDark()`.
 * @param item the model, a `Contact`, a `Bookmark` or a `RawBlockable`.
 * @param index the row's place in the filtered list, which keeps the `LazyColumn` key unique.
 * @param showDynamicTags the `show_dynamic_tags` preference: when false the row draws no tag at all,
 *     meta ones included, exactly as the adapter's `visibility = GONE` did.
 */
fun pickerRow(
    activity: XmppActivity,
    item: ListItem,
    index: Int,
    showDynamicTags: Boolean,
): PickerRow {
    val context: Context = activity
    val jid: Jid? = item.getJid()
    val jidValue = jid?.toString() ?: ""
    return PickerRow(
        key = "$jidValue@$index",
        jidValue = jidValue,
        displayName = item.getDisplayName(),
        jid = jid?.let { annotatedJid(IrregularUnicodeDetector.style(context, it)) },
        tags = dynamicTags(context, item, showDynamicTags),
        metaTag = metaTag(context, item, showDynamicTags),
        accountLine =
            UiAccountLine.of(
                activity.xmppConnectionService != null &&
                    activity.getBooleanPreference("show_own_accounts", R.bool.show_own_accounts),
                item.getAccount().getJid().asBareJid().toString(),
            ),
        presenceColor = presenceColor(activity, item),
        avatarable = item,
        containerColor =
            if (activity.colorCodeAccounts()) {
                item.getAccount().getColor(activity.isDark())
            } else {
                null
            },
    )
}

/** The dynamic tags, tinted by the adapter's own `XEP0392Helper.rgbFromNick` call. */
private fun dynamicTags(
    context: Context,
    item: ListItem,
    showDynamicTags: Boolean,
): List<TagChip> {
    if (!showDynamicTags) {
        return emptyList()
    }
    val tags = ArrayList<TagChip>()
    for (tag in item.getTags(context)) {
        tags.add(
            TagChip(
                tag.name,
                harmonised(context, XEP0392Helper.rgbFromNick(tag.name)),
            ),
        )
    }
    return tags
}

/**
 * The one meta tag the adapter appended for a contact: `blocked`, or the presence word its own
 * `UIHelper.setStatus` would have written, in the same colour. A row with dynamic tags hidden draws
 * neither, and a contact whose presence is offline draws none.
 */
private fun metaTag(context: Context, item: ListItem, showDynamicTags: Boolean): TagChip? {
    if (!showDynamicTags || item !is Contact) {
        return null
    }
    if (item.isBlocked()) {
        return TagChip(
            context.getString(uk.xa0.tulkki.data.R.string.blocked),
            harmonised(context, ContextCompat.getColor(context, R.color.gray_800)),
        )
    }
    val status = item.shownStatus
    if (status == Presence.Status.OFFLINE) {
        return null
    }
    val (text, color) =
        when (status) {
            Presence.Status.CHAT -> R.string.presence_chat to R.color.green_800
            Presence.Status.ONLINE -> R.string.presence_online to R.color.green_800
            Presence.Status.AWAY -> R.string.presence_away to R.color.amber_800
            Presence.Status.XA -> R.string.presence_xa to R.color.orange_800
            Presence.Status.DND -> R.string.presence_dnd to R.color.red_800
            else -> throw IllegalStateException()
        }
    return TagChip(context.getString(text), harmonised(context, ContextCompat.getColor(context, color)))
}

private fun harmonised(context: Context, color: Int): Int =
    MaterialColors.harmonizeWithPrimary(context, color)

/**
 * The presence dot's colour, the two facts `PresenceIndicator` read: the `show_contact_status`
 * preference, and an account that is online and connected. A non-contact row draws no dot, which is
 * what the adapter's `setStatus(null)` meant.
 */
private fun presenceColor(activity: XmppActivity, item: ListItem): Int? {
    if (item !is Contact) {
        return null
    }
    val enabled =
        activity.getPreferences().getBoolean(
            "show_contact_status",
            activity.resources.getBoolean(R.bool.show_contact_status),
        ) && item.account?.isOnlineAndConnected() == true
    return if (enabled) UIHelper.getColorForStatus(item.shownStatus) else null
}

/**
 * The picker list: one [PickerRowItem] per row, in the order the host filtered them.
 *
 * <p>`ListView`'s choice mode is the caller's now: [checkedKeys] holds the JIDs a choice-mode picker
 * has checked, so a checked row draws the `background_selectable_list_item` shade
 * (`?colorSurfaceContainerHighest`, which the deleted `state_activated` selector resolved to) and the
 * gestures stay the host's.
 *
 * @param rows the filtered rows.
 * @param onRowClick a row's tap, with its place in [rows]: the old `setOnItemClickListener`.
 * @param onRowLongClick a row's long press, with the view a `PopupMenu` is anchored to: the old
 *     `setOnItemLongClickListener`, and the way a multi-choice picker enters its action mode.
 * @param onTagClick a dynamic tag's tap, with its word: the old `OnTagClickedListener`.
 * @param checkedKeys the JIDs drawn as checked; empty for a list with no choice mode.
 */
@Composable
fun PickerList(
    rows: List<PickerRow>,
    onRowClick: (Int) -> Unit,
    onRowLongClick: (Int, View) -> Unit,
    onTagClick: (String) -> Unit,
    checkedKeys: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            PickerRowItem(
                row = row,
                checked = row.jidValue in checkedKeys,
                onClick = { onRowClick(index) },
                onLongClick = { anchor -> onRowLongClick(index, anchor) },
                onTagClick = onTagClick,
            )
        }
    }
}

/**
 * One row: the deleted `item_contact.xml`, laid out as it was. The padding is `list_padding`, the
 * avatar `avatar`, the gap after it `avatar_item_distance`, and the dot `presence_indicator_size`
 * inset by `presence_indicator_offset` on the avatar's foot.
 *
 * <p>The account colour is the row's background when the host codes accounts, and the checked shade
 * only when it does not - the deleted inner `RelativeLayout` covered the outer `FrameLayout`'s
 * activated drawable exactly the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRowItem(
    row: PickerRow,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (View) -> Unit,
    onTagClick: (String) -> Unit,
) {
    val anchor = LocalView.current
    val background =
        row.containerColor
            ?: if (checked) {
                MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
            } else {
                Color.Transparent.toArgb()
            }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(Color(background))
                .combinedClickable(onClick = onClick, onLongClick = { onLongClick(anchor) })
                .padding(dimensionResource(R.dimen.list_padding)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(dimensionResource(R.dimen.avatar))) {
            val avatarable = row.avatarable
            if (avatarable == null) {
                // A screenshot cell's row: the plate the avatar will cover, so the cell holds no
                // `AndroidView`.
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                AndroidView(
                    factory = { context ->
                        AvatarView(context).also {
                            AvatarWorkerTask.loadAvatar(avatarable, it, R.dimen.avatar)
                        }
                    },
                    update = { view ->
                        AvatarWorkerTask.loadAvatar(avatarable, view, R.dimen.avatar)
                    },
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
                )
            }
        }
        Column(
            modifier =
                Modifier.padding(start = dimensionResource(R.dimen.avatar_item_distance))
        ) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.jid?.let { jid ->
                Text(
                    text = jid,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The tags sat in a `Flow` under the JID line, 4sp below it, and each dynamic tag was
            // the one clickable pill: the meta tag the adapter appended never took the listener.
            if (row.tags.isNotEmpty() || row.metaTag != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    for (tag in row.tags) {
                        TagPill(
                            chip = tag,
                            modifier = Modifier.clickable { onTagClick(tag.text) },
                        )
                    }
                    row.metaTag?.let { meta -> TagPill(chip = meta) }
                }
            }
            row.accountLine?.let { line ->
                Text(text = line, fontSize = 11.sp)
            }
        }
    }
}
