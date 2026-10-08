package uk.xa0.tulkki.ui.conversationlist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.CallKind
import uk.xa0.tulkki.ui.projection.EncryptionKind
import uk.xa0.tulkki.ui.projection.UiConversation
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiNotification
import uk.xa0.tulkki.ui.projection.UiPresence
import uk.xa0.tulkki.ui.projection.UiPreview
import uk.xa0.tulkki.ui.projection.UiTranslationMode
import uk.xa0.tulkki.ui.theme.TulkkiColors
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The conversation list, docs/MIGRATION.md "Design: the Compose UI" §3.5 - the second Compose screen and
 * the first one with rows, drawn from [ConversationListState] and nothing else.
 *
 * <p>The four states §3.5 names, each its own drawing and never a fake row:
 * * **loading** - the first emission has not landed, so three skeleton rows are drawn, "not a spinner,
 *   so the screen does not jump when rows land";
 * * **empty** - the read landed and there is nothing to show: one explainer card, and no invented row;
 * * **error** - the connection is a status line above the rows, "never an empty list - 'no
 *   conversations' and 'not connected' are different facts";
 * * **interpreter-off** - a row names no language and its line is what the projection drew, because
 *   nothing was ever covered ([MessagePreview] with the interpreter off answers `Visible`).
 *
 * <p>**Must never show**: a body column, a snippet or a cached last-message string. Every line is the
 * projection's [UiPreview] - the pointer's own row read at projection time - so there is no second copy
 * to leak, and a `Covered` row draws the cover's own vocabulary rather than a bitmap (§3.5: "one line,
 * not a bitmap").
 *
 * <p>The screen reads nothing and decides nothing about a row's actions: a tap, a long press and a swipe
 * each emit into [ConversationListEvents], and the long press draws exactly the entries
 * [ConversationMenu] says the row offers. **Both gestures are touch behaviour, which §7.3 puts among the
 * things a JVM test cannot reach** - "gestures, IME, haptics, scroll physics, animation timing are
 * device questions" - so what this file's cells hold is that the gestures exist and are wired to the
 * vocabulary, and the owner's device look is what says they feel right.
 *
 * <p>[listState] is hoisted for the one caller that keeps the scroll as saved data:
 * `ConversationListFragment` restores `ScrollState(position, offset)` and is Java, so it cannot remember
 * a list state of its own and builds one instead. A caller with nothing to restore leaves the argument
 * out and gets the screen's own.
 */
@Composable
fun ConversationListScreen(
    state: ConversationListState,
    events: ConversationListEvents,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    swipeEnabled: Boolean = true,
    menuEnabled: Boolean = true,
    avatar: ConversationAvatar? = null,
) {
    // The screen paints the theme's own background, exactly as the ledger, the settings page, the
    // failures list and top-up do: without a Surface the content colour falls back to black on
    // whatever the host drew behind it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FilterRow(state.filter, events)
            if (state.connection != UiConnection.CONNECTED) {
                StatusLine(state.connection)
            }
            when {
                state.loading -> Skeleton()
                state.empty -> Explainer()
                else ->
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.visible, key = { it.id.uuid }) { conversation ->
                            ConversationRow(conversation, events, swipeEnabled, menuEnabled, avatar)
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

/**
 * The three filters, drawn as the predicates they are. Material 3's own control for "one of a small set,
 * and the chosen one is visible": a row of chips, the current one selected rather than merely bold.
 */
@Composable
private fun FilterRow(filter: ConversationFilter, events: ConversationListEvents) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = TulkkiSpacing.md, vertical = TulkkiSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (candidate in ConversationFilter.entries) {
            FilterChip(
                selected = candidate == filter,
                onClick = { events.onFilter(candidate) },
                label = { Text(stringResource(filterLabel(candidate))) },
            )
        }
    }
}

/**
 * §3.5's "**error**: a banner or a status line", as a **strip** rather than a sentence: one slim bar in the
 * surface's own step, a dot for the state and the words beside it. It sits above the rows, because the list
 * behind it is still the last thing that was read - emptying it would claim there are no conversations.
 */
@Composable
private fun StatusLine(connection: UiConnection) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = TulkkiSpacing.lg, vertical = TulkkiSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(StatusDotSize)
                        .clip(CircleShape)
                        .background(
                            if (connection == UiConnection.CONNECTING) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
            )
            Text(
                stringResource(
                    if (connection == UiConnection.CONNECTING) {
                        R.string.tulkki_list_connecting
                    } else {
                        R.string.tulkki_list_disconnected
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = TulkkiSpacing.sm),
            )
        }
    }
}

/** §3.5's explainer: one card, no fake row - and centred, because it is the whole screen's content. */
@Composable
private fun Explainer() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.lg)) {
            Column(
                modifier = Modifier.padding(TulkkiSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
            ) {
                Text(stringResource(R.string.tulkki_list_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tulkki_list_empty_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Three rows' worth of shape while the first emission is in flight, centred like every other state. */
@Composable
private fun Skeleton() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.lg),
        ) {
            for (at in 0 until SKELETON_ROWS) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
                ) {
                    SkeletonBar(SKELETON_NAME)
                    SkeletonBar(if (at % 2 == 0) SKELETON_LINE_LONG else SKELETON_LINE_SHORT)
                }
            }
        }
    }
}

/** One placeholder bar: a share of the width and the theme's own surface step, no literal size. */
@Composable
private fun SkeletonBar(width: Float) {
    Box(
        modifier =
            Modifier.fillMaxWidth(width)
                .height(TulkkiSpacing.md)
                .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/**
 * One conversation. The name, the preview line, a badge and a muting mark; the language pair only while
 * the interpreter is on, and only when there is a pair to name - §3.5's off state "names no language on
 * a row", and an unread conversation's own language is not something to invent.
 *
 * <p>The three touches: a tap opens ([ConversationListEvents.onOpen]), a long press opens the row's own
 * menu ([ConversationMenu.entries] over the row and its live call fact), and a swipe leaves by the entry
 * the menu's last line offers ([ConversationMenu.archive]). The leaving action is the screen's to name
 * and the host's to perform: the row archives nothing itself, and the host's undo is what makes a swipe
 * reversible.
 *
 * <p>**The swipe is drawn only while [swipeEnabled].** It is the owner's `swipe_to_archive`, and the
 * tree attached its touch helper only when that preference was true and never during onboarding. A
 * disabled swipe is not drawn at all rather than refused after the drag: a row that slides and springs
 * back says the gesture exists, which is the one thing this state must not say.
 *
 * <p>**And the menu only while [menuEnabled].** The list offers the twelve entries the tree's context
 * menu offered; `ShareWithActivity` is a picker whose one gesture is the tap that picks a row, so a long
 * press there opens nothing rather than a menu of effects a picker cannot perform.
 */
@Composable
private fun ConversationRow(
    conversation: UiConversation,
    events: ConversationListEvents,
    swipeEnabled: Boolean,
    menuEnabled: Boolean,
    avatar: ConversationAvatar?,
) {
    if (!swipeEnabled) {
        ConversationRowContent(conversation, events, menuEnabled, avatar)
        return
    }
    val dismissed =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.Settled) {
                    false
                } else {
                    events.onAction(ConversationMenu.archive(conversation.kind), conversation.id.uuid)
                    true
                }
            }
        )
    SwipeToDismissBox(
        state = dismissed,
        backgroundContent = {
            Box(
                modifier =
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            )
        },
    ) {
        ConversationRowContent(conversation, events, menuEnabled, avatar)
    }
}

/**
 * One row's own drawing, with no gesture of its leaving: the swipe's wrapper is the caller's.
 *
 * <p>The shape is the deleted row's: the avatar at the start, the name with the row's own clock at the
 * end of that line, the preview under it, and the presence dot at the avatar's foot. The dot is drawn
 * only while the projection names a state - [UiPresence.UNKNOWN] is the seam saying nobody read one, and a
 * row draws nothing for it rather than an empty ring.
 */
@Composable
private fun ConversationRowContent(
    conversation: UiConversation,
    events: ConversationListEvents,
    menuEnabled: Boolean,
    avatar: ConversationAvatar?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    // The row's resting surface is the screen's own background, so the swipe's
                    // background shows only while the row is moving; without this the whole list
                    // would wear the swipe's colour.
                    .background(MaterialTheme.colorScheme.background)
                    .combinedClickable(
                        onClick = { events.onOpen(conversation.id.uuid) },
                        onLongClick = { menuOpen = true },
                    )
                    // The tree's own `android:padding="8dp"`: one step, both axes. It was 16dp across and
                    // 12dp down, which is where the extra eight dp of row height came from.
                    .padding(TulkkiSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            RowAvatar(conversation, avatar)
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(start = dimensionResource(R.dimen.avatar_item_distance)),
                // The tree's `layout_marginTop="4dp"` between the name and the second line, and the same
                // gap under the account line, which is a third line and not a step of its own.
                verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xs),
            ) {
                // The first line: the name, and the row's own clock at the end of it.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.time.isNotEmpty()) {
                        Text(
                            conversation.time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = TulkkiSpacing.sm),
                        )
                    }
                }
                // The second line: who is talking, what kind of thing they sent, the words, and the marks.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    conversation.sender?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = TulkkiSpacing.xs),
                        )
                    }
                    val line = previewLine(conversation.preview)
                    if (line != null) {
                        RowMark(
                            icon = (conversation.preview as? UiPreview.Visible)?.icon,
                            contentDescription = line,
                        )
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (conversation.muted) {
                        Text(stringResource(R.string.tulkki_list_muted), style = MaterialTheme.typography.bodySmall)
                    }
                    RowMark(icon = conversation.tick, contentDescription = null)
                    RowMark(
                        icon = notificationMark(conversation.notification),
                        contentDescription = null,
                    )
                    if (conversation.pinned) {
                        RowMark(icon = R.drawable.ic_star_24dp, contentDescription = null)
                    }
                    if (conversation.unread > 0) {
                        UnreadBadge(conversation.unread)
                    }
                }
                conversation.account?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                languageLine(conversation)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (menuEnabled) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (action in ConversationMenu.entries(conversation, conversation.ongoingCall)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(ConversationMenu.label(action))) },
                        onClick = {
                            menuOpen = false
                            events.onAction(action, conversation.id.uuid)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One row's avatar, with the presence dot at its foot - or, until the host has the image, the plate the
 * avatar will cover. The size is the tree's own `avatar_on_conversation_overview`, which the host resolves
 * at too, so the two never disagree about how big a row's avatar is.
 */
@Composable
private fun RowAvatar(conversation: UiConversation, avatar: ConversationAvatar?) {
    val shape = avatarShape(avatar)
    Box(modifier = Modifier.size(dimensionResource(R.dimen.avatar_on_conversation_overview))) {
        val image = avatar?.of(conversation.id.uuid)
        if (image != null) {
            Canvas(modifier = Modifier.fillMaxSize().clip(shape)) {
                drawIntoCanvas { canvas ->
                    image.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    image.draw(canvas.nativeCanvas)
                }
            }
        } else {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        // The tree drew no dot at all for a row it could not colour - `getColorForStatus` answers `null`
        // for `OFFLINE` and the old `PresenceIndicator` then drew nothing - so the row's own drawing is
        // the same: a state with no colour is a state with no dot. An offline row is not an unknown one
        // (the row still carries it, and another surface may say it), but on this row they draw alike.
        val dot = presenceColour(conversation.presence)
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
                        .background(dot)
                        .border(PresenceDotBorder, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

/** The owner's `avatar_shape`, as the clip the row draws with; a host with no avatar names the oval. */
@Composable
private fun avatarShape(avatar: ConversationAvatar?): Shape =
    when (avatar?.shape() ?: AvatarShape.OVAL) {
        AvatarShape.OVAL -> CircleShape
        AvatarShape.ROUNDED_SQUARE -> RoundedCornerShape(dimensionResource(R.dimen.avatar_corners_radius))
        AvatarShape.SQUARE -> RectangleShape
    }

/**
 * What each presence state is drawn in, or `null` when the row draws no dot at all.
 *
 * <p>The three colours are the tree's own fixed values, named in [TulkkiColors] because §1.8 rule 1
 * allows a literal in a Composable nowhere - not scheme roles, which would change hue with the theme and
 * stop matching the indicator the owner knew. `OFFLINE` and `UNKNOWN` are the two states the tree drew
 * nothing for: `UIHelper.getColorForStatus` answers `null` for offline, and an unknown presence is a
 * status nobody could read. Keeping them distinct on the row and identical in the drawing is the point -
 * the state is a fact, the dot is a decision.
 *
 * <p>It is `internal` and not `@Composable`, so a JVM cell reaches it: the rule is three constants and a
 * `null`, and the one thing that must never drift is which states get a dot.
 */
internal fun presenceColour(presence: UiPresence): Color? =
    when (presence) {
        UiPresence.ONLINE -> TulkkiColors.presenceOnline
        UiPresence.AWAY -> TulkkiColors.presenceAway
        UiPresence.DND -> TulkkiColors.presenceDnd
        UiPresence.OFFLINE, UiPresence.UNKNOWN -> null
    }

/** One 18sp mark of the trailing strip, or nothing at all when the row has no such fact. */
@Composable
private fun RowMark(@DrawableRes icon: Int?, contentDescription: String?) {
    if (icon == null) {
        return
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = RowMarkGap).size(RowMarkSize),
    )
}

/**
 * The unread count as the loudest thing in the row. The tree's badge was an 18sp `UnreadCountCustomView`
 * filled with `colorPrimary` and written in `colorOnPrimary`; this is the same idea in Material 3, and it is
 * a badge rather than a number because that is the one row fact meant to pull the eye.
 */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier =
            Modifier.padding(start = RowMarkGap)
                .defaultMinSize(minWidth = RowMarkSize, minHeight = RowMarkSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = BadgePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The mark each notification state draws; [UiNotification.NONE] is the state the tree drew nothing for. */
@DrawableRes
private fun notificationMark(notification: UiNotification): Int? =
    when (notification) {
        UiNotification.CALL -> R.drawable.ic_phone_in_talk_24dp
        UiNotification.MUTED -> R.drawable.ic_notifications_off_24dp
        UiNotification.MUTED_UNTIL -> R.drawable.ic_notifications_paused_24dp
        UiNotification.SILENT -> R.drawable.ic_notifications_none_24dp
        UiNotification.NONE -> null
    }

/**
 * The row's line, from the projection and never from a second read. `Absent` draws nothing; `Covered`
 * draws the cover's own one-line vocabulary; the two typed cases draw their own labels, which is what
 * the `EncryptionKind`/`CallKind` vocabularies exist for. A `Visible` the projection marked as a draft is
 * the owner's own text and says so: the tree drew it in the preview slot with a "Draft" label beside it,
 * and a row has one line, so the label is the line's own prefix.
 */
@Composable
private fun previewLine(preview: UiPreview): String? =
    when (preview) {
        is UiPreview.Visible ->
            if (preview.draft) {
                stringResource(R.string.draft) + " " + preview.text
            } else {
                preview.text
            }
        is UiPreview.Covered -> stringResource(R.string.tulkki_untranslated)
        is UiPreview.Encryption -> stringResource(encryptionLabel(preview.kind))
        is UiPreview.Call -> stringResource(callLabel(preview.kind))
        is UiPreview.Absent -> null
    }

/**
 * The pair, as every surface that names a language must name it (AGENTS.md): the conversation's own
 * language and the app language, codes because a row has one line. Nothing is drawn while the
 * interpreter is off, and nothing while the conversation's language is unknown - there is no pair.
 */
@Composable
private fun languageLine(conversation: UiConversation): String? {
    if (conversation.translation != UiTranslationMode.ON) {
        return null
    }
    val pair: UiLanguagePair = conversation.language
    val own = pair.conversationLanguage ?: return null
    return stringResource(R.string.tulkki_language_pair, own, pair.appLanguage)
}

/** The five encryption kinds' own words, the tree's strings (§2.2.1 #7). */
@StringRes
internal fun encryptionLabel(kind: EncryptionKind): Int =
    when (kind) {
        EncryptionKind.PGP -> R.string.pgp_message
        EncryptionKind.OTR -> R.string.otr_message
        EncryptionKind.PGP_DECRYPTION_FAILED -> R.string.decryption_failed
        EncryptionKind.OMEMO_NOT_FOR_THIS_DEVICE -> R.string.not_encrypted_for_this_device
        EncryptionKind.OMEMO_DECRYPTION_FAILED -> R.string.omemo_decryption_failed
    }

/** The three call kinds' own words (§2.2.1 #8). */
@StringRes
internal fun callLabel(kind: CallKind): Int =
    when (kind) {
        CallKind.MISSED -> R.string.missed_call
        CallKind.INCOMING -> R.string.incoming_call
        CallKind.OUTGOING -> R.string.outgoing_call
    }

/** The filter's own label; `all_chats` is the string the tree's list already used. */
@StringRes
internal fun filterLabel(filter: ConversationFilter): Int =
    when (filter) {
        ConversationFilter.ALL -> R.string.all_chats
        ConversationFilter.UNREAD -> R.string.tulkki_list_unread
        ConversationFilter.GROUPS -> R.string.tulkki_list_groups
    }

/** §3.5 says three skeleton rows; the shape constants are this file's, the widths are proportions. */
private const val SKELETON_ROWS = 3
private const val SKELETON_NAME = 0.35f
private const val SKELETON_LINE_LONG = 0.75f
private const val SKELETON_LINE_SHORT = 0.5f

/** The ring the presence dot wears, so it reads as a badge on the avatar rather than a hole in it. */
private val PresenceDotBorder = 1.dp

/** The trailing strip's own metrics: the deleted row's 18sp marks, 4dp apart. */
private val RowMarkSize = 18.dp
private val RowMarkGap = 4.dp

/** And the badge's inside padding, which is what makes a two-digit count a pill rather than a circle. */
private val BadgePadding = 6.dp

/** The status strip's own dot: a state, not a decoration. */
private val StatusDotSize = 8.dp
