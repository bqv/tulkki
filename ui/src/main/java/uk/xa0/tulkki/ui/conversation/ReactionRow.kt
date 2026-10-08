package uk.xa0.tulkki.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import uk.xa0.tulkki.ui.projection.UiReaction
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The reaction row: the chips the deleted `BindingAdapters.setReactionsOnReceived`/`setReactionsOnSent`
 * drew under a bubble, rebuilt as Compose from the projection's own [UiReaction].
 *
 * <p>`UiReaction` was projected and never drawn; the projector's `MessageProjection.reactions` had
 * already decoded the document, grouped it and dropped the custom-emoji groups that need a
 * thumbnailer, so this file only renders what the read model carries and decides no reaction rule of
 * its own.
 *
 * <p>**What is drawn, and what the chips answer.** Each chip is the emoji, with the group's size
 * after it when there is more than one - the tree's own `setupEmojiChip` wording
 * (`BindingAdapters.java`, deleted in `93584ddd34`) - and the chip fills itself in the owner's own
 * colour when [UiReaction.mine]. The two gestures the tree's chips had are back: a tap emits
 * [onReaction] for that chip's emoji and a long press emits [onPicker]. Both default to null, and a
 * null pair draws the chip with no touch target at all - a drawn button that performs nothing is
 * worse than an absent one, so a caller with no host (a preview, a JVM cell) still gets the row and
 * no affordance. The **add** affordance is still not drawn: the tree's group went `GONE` with no
 * reactions and so does this row, which leaves adding the first reaction to `ui-10`'s menu.
 *
 * <p>A chip's text is [ReactionChips.label], a pure rule, so the one thing the row says is a JVM
 * cell rather than a screenshot's word.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionRow(
    reactions: List<UiReaction>,
    modifier: Modifier = Modifier,
    onReaction: ((UiReaction) -> Unit)? = null,
    onPicker: (() -> Unit)? = null,
) {
    if (reactions.isEmpty()) {
        return
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
        for (reaction in reactions) {
            ReactionChip(
                reaction = reaction,
                onReaction = onReaction,
                onPicker = onPicker,
            )
        }
    }
}

/**
 * One chip: the emoji, a count when the group is larger than one, and the owner's own fill.
 *
 * <p>The fill is the deleted adapter's own pair - `colorSurfaceContainerHighest` when one of the
 * reactions is not `received`, `colorSurfaceContainerLow` otherwise - read off the theme rather than
 * spelled as a literal, so light and dark both follow the scheme. The shape is a pill, as the tree's
 * 35 dp chip corner was.
 *
 * <p>Its two gestures are the row's callbacks, and a null pair leaves the chip untouchable rather
 * than answering nothing: the tree's chips were always answerable, but a preview or a JVM cell has
 * no host to hand the gesture to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionChip(
    reaction: UiReaction,
    onReaction: ((UiReaction) -> Unit)?,
    onPicker: (() -> Unit)?,
) {
    val answerable = onReaction != null || onPicker != null
    Text(
        text = ReactionChips.label(reaction),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.clip(CircleShape)
                .background(
                    if (reaction.mine) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                )
                .then(
                    if (answerable) {
                        Modifier.combinedClickable(
                            onClick = { onReaction?.invoke(reaction) },
                            onLongClick = onPicker,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xxs),
    )
}

/**
 * What a chip says, as a rule: the emoji alone for a group of one, the emoji and the group's size
 * otherwise.
 *
 * <p>It is `setupEmojiChip`'s branch (`BindingAdapters`: `count < 2` draws `emoji.unicode`, and
 * `count >= 2` draws `String.format(Locale.ENGLISH, "%s %d", emoji.unicode, count)`) narrowed to the
 * unicode chips the projector keeps. The count is a number and never a word: a chip is a badge, and
 * the tree's own chip carried no text beyond these two.
 */
object ReactionChips {

    /** The chip's text: the emoji, and the count when the group is larger than one. */
    @JvmStatic
    fun label(reaction: UiReaction): String =
        if (reaction.count > 1) reaction.emoji + " " + reaction.count else reaction.emoji
}
