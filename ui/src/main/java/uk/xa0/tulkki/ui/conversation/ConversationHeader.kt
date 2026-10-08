package uk.xa0.tulkki.ui.conversation

import android.util.Patterns
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The conversation's three at-a-glance lines above the messages: the room's subject (or, in a
 * one-to-one, the contact's own status message), the tune the peer is listening to, and the
 * ephemeral-messages hint. They are the deleted `muc_subject`, `tune_subject` and `ephemeral_hint`
 * `LinearLayout`s, drawn from state rather than filled into views.
 *
 * <p>**Each line is a cell of its own, and an absent line draws nothing at all.** The Java kept the
 * three rows in the tree and moved their `visibility`; here `null` is the same fact and the row is
 * not composed, so a one-to-one with no status message is pixel-for-pixel what it was - which is why
 * the screen's existing screenshot references do not move when this surface joins them.
 *
 * <p>**The concealment rules are untouched, because no line here draws a message.** The subject, the
 * status text and the tune line are the peer's own metadata - none of them is a body, a quote or an
 * original - and the ephemeral hint is a sentence about the timer. Nothing here reads a setting, so
 * no rule that governs the second half or a cover is consulted, let alone weakened.
 *
 * <p>**The icon's tint is the caller's.** The Java shaded the subject, status and tune icons with
 * `SendButtonTool.getSendButtonColor(...)` - the peer's presence - on every `updateSendButton`, and
 * re-writing that decision here would be a second copy of it. [iconTint] is that colour, already
 * resolved by the host; the ephemeral line's icon is the theme's own `?colorOnSurface`, which the
 * deleted view's `app:tint` named.
 *
 * <p>**The subject text's `autoLink="web"` is kept.** A room's subject is often a link, and the
 * deleted `TextView` made every web address in it tappable; the taps are
 * [androidx.compose.ui.text.LinkAnnotation.Url]s resolved by the platform's own handler, which is
 * what `Linkify.WEB_URLS` did.
 *
 * @param header the three lines, each of which may be absent
 * @param onSubjectOpen a tap on the subject line, or `null` when it opens nothing
 */
@Composable
fun ConversationHeader(
    header: UiConversationHeader,
    onSubjectOpen: (() -> Unit)?,
    onSubjectHide: () -> Unit,
    onTuneOpen: (() -> Unit)?,
    onTuneHide: () -> Unit,
    onEphemeralHide: () -> Unit,
) {
    val links = MaterialTheme.colorScheme.primary
    val iconTint =
        if (header.iconTint.isSpecified) header.iconTint else MaterialTheme.colorScheme.onSurface
    header.subject?.let { line ->
        HeaderLine(
            icon = if (line.room) R.drawable.subject else R.drawable.rounded_info_24,
            iconTint = iconTint,
            text = remember(line.text, links) { webLinks(line.text, links) },
            onOpen = onSubjectOpen,
            onHide = onSubjectHide,
        )
    }
    header.tune?.let { line ->
        HeaderLine(
            icon = R.drawable.ic_play_circle_24dp,
            iconTint = iconTint,
            text = AnnotatedString(line.line),
            onOpen = onTuneOpen,
            onHide = onTuneHide,
        )
    }
    header.ephemeral?.let { line ->
        HeaderLine(
            icon = R.drawable.ic_auto_delete_24dp,
            iconTint = MaterialTheme.colorScheme.onSurface,
            text = AnnotatedString(line.line),
            onOpen = null,
            onHide = onEphemeralHide,
        )
    }
}

/**
 * The three lines [ConversationHeader] draws, as one value.
 *
 * <p>The empty value is "this conversation has nothing to say above its messages", which is the
 * ordinary one-to-one with no status message, no tune and no ephemeral timer - and it composes
 * nothing at all, exactly as the three `GONE` `LinearLayout`s drew nothing.
 *
 * <p>[iconTint] is the presence colour the caller resolved; [Color.Unspecified] means "no reading
 * was taken" and the surface falls back to the theme's `onSurface`, which is the offline colour the
 * deleted `getSendButtonColor` answered with. It is an ARGB `Color` rather than a resource because
 * the reading is a rule of its own (`SendButtonTool`), and a second copy of that rule here would
 * drift from the send affordance that still reads it.
 */
data class UiConversationHeader(
    val subject: UiSubjectLine? = null,
    val tune: UiTuneLine? = null,
    val ephemeral: UiEphemeralLine? = null,
    val iconTint: Color = Color.Unspecified,
)

/**
 * One line of [ConversationHeader]: a small icon, the sentence, and the control that puts the line
 * away. The three rows are the same row, so they are one composable and not three.
 *
 * <p>The geometry is the deleted `LinearLayout`'s own: 24 dp icon, 8 dp to the text, 8 dp to the
 * close control, 28 dp close control, 16 dp gutters and 8 dp above and below the text, on the
 * surface with its 4 dp lift. The close control is an `ImageView` in the XML and carries no
 * description there; here it keeps its own node but names itself - the same "Close" the rest of the
 * app uses - so a screen reader has something to say.
 */
@Composable
private fun HeaderLine(
    @DrawableRes icon: Int,
    iconTint: Color,
    text: AnnotatedString,
    onOpen: (() -> Unit)?,
    onHide: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .then(if (onOpen == null) Modifier else Modifier.clickable(onClick = onOpen))
                    .padding(
                        start = TulkkiSpacing.lg,
                        end = TulkkiSpacing.lg,
                        top = TulkkiSpacing.sm,
                        bottom = TulkkiSpacing.sm,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.weight(1f)
                        .padding(start = TulkkiSpacing.sm, end = TulkkiSpacing.sm),
            )
            Icon(
                painter = painterResource(R.drawable.rounded_close_24),
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp).clickable(onClick = onHide),
            )
        }
    }
}

/**
 * The room's subject, or a one-to-one contact's status line: the two facts that share the first of
 * [ConversationHeader]'s three rows, told apart by [room] because the rows differ in their icon and
 * in what a tap opens (the room's details versus the contact's).
 */
data class UiSubjectLine(
    /** The line itself, already resolved by the host (`getMucOptions().getSubject()`, or the last processed status text). */
    val text: String,
    /** Whether this is a room's subject (`true`) or a contact's status message (`false`). */
    val room: Boolean,
)

/** The peer's `UserTune`: the sentence the host built from its title and artist. */
data class UiTuneLine(val line: String)

/** The ephemeral-messages hint: the timer in force, already worded by the host. */
data class UiEphemeralLine(val line: String)

/**
 * The string with every web address in it a [androidx.compose.ui.text.LinkAnnotation.Url], which is
 * what the deleted `TextView`'s `android:autoLink="web"` (`Linkify.WEB_URLS`, reading
 * [Patterns.WEB_URL]) did to it - the same rule `AboutActivity`'s own copy of this function keeps,
 * including the trim of the sentence punctuation that follows an address.
 */
private fun webLinks(text: String, linkColor: Color): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
    val matcher = Patterns.WEB_URL.matcher(text)
    return buildAnnotatedString {
        var last = 0
        while (matcher.find()) {
            val start = matcher.start()
            val url = matcher.group().trimEnd(')', '.', ',', ';')
            append(text, last, start)
            withLink(LinkAnnotation.Url(url, styles)) { append(url) }
            last = start + url.length
        }
        append(text, last, text.length)
    }
}
