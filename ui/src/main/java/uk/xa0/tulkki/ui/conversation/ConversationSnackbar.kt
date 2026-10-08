package uk.xa0.tulkki.ui.conversation

import android.view.View
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The conversation's resting bar: the account's state, a blocked contact, a pending subscription, a
 * room that fell over, a pending decryption, an unverified OTR session, a stranger - one sentence and
 * the one action that fixes it.
 *
 * <p>It is the deleted `snackbar` `RelativeLayout` and its two `TextView`s: the same sentence, the
 * same action word, the same inverse-surface plate with its 8 dp corners and 48 dp floor. The
 * drawing is Compose now; the *decisions* are not here - [UiSnackbar] is handed the sentence and the
 * action already resolved by `ConversationFragment.updateSnackBar`, and this surface names no
 * condition of its own.
 *
 * <p>**The listeners stay Java `View` listeners, and the bar hands them its own host view.** Every
 * action `updateSnackBar` produces is a `View.OnClickListener` (or a long-click listener) whose body
 * already existed: one marks itself invisible, and several anchor a `PopupMenu` at the view they were
 * handed - so they are carried across unchanged and invoked with [LocalView], exactly as
 * `ComposerBar` invokes its own with `null` where the listener does not read it. A bar that
 * re-invented those bodies would be a second answer to a question the fragment already owns.
 *
 * <p>**The action's "I was used" state is the deleted `v.setVisibility(INVISIBLE)`.** That listener
 * hid the button it was handed, so [UiSnackbar.actionUsed] draws the button where it is - the same
 * reserved space - with no pixels and no touch target, and the next reading clears it. The bar is a
 * surface; a view it can mark is the one thing it does not have.
 *
 * <p>The concealment rules are untouched: this bar draws the account's and the contact's own state,
 * never a message body, so nothing here can make a received original readable.
 *
 * @param state the bar to draw, or `null` for no bar at all - which is the deleted `View.GONE`
 */
@Composable
fun ConversationSnackbar(state: UiSnackbar?) {
    if (state == null) {
        return
    }
    val anchor = LocalView.current
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(state.words),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(start = 24.dp),
            )
            val action = state.onAction
            if (action != null) {
                SnackbarActionButton(state = state, action = action, anchor = anchor)
            }
        }
    }
}

/**
 * The bar's action word: the deleted `snackbar_action` `TextView`, with its all-caps, its bold and
 * its 24 dp by 16 dp box. It is one [Text] because it was one `TextView`, and both gestures are its
 * own - the tap and the block submenu's long press do not merge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnackbarActionButton(state: UiSnackbar, action: View.OnClickListener, anchor: View) {
    val longPress = state.onActionLongPress
    val words = if (state.actionLabel == 0) "" else stringResource(state.actionLabel)
    Text(
        text = words.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        modifier =
            Modifier.alpha(if (state.actionUsed) 0f else 1f)
                .combinedClickable(
                    enabled = !state.actionUsed,
                    onClick = { action.onClick(anchor) },
                    onLongClick = { longPress?.onLongClick(anchor) },
                )
                .padding(
                    start = TulkkiSpacing.xl,
                    end = TulkkiSpacing.xl,
                    top = TulkkiSpacing.lg,
                    bottom = TulkkiSpacing.lg,
                ),
    )
}

/**
 * The bar's own state: the sentence, the action word and the listeners, already resolved by the host.
 *
 * <p>[onAction] `null` is no action button at all - the deleted
 * `snackbarAction.setVisibility(if (clickListener == null) GONE else VISIBLE)` - and it carries both
 * listeners because the long press is the same menu as the tap where a bar has one.
 *
 * <p>[actionLabel] is a resource id and `0` is the deleted `if (action != 0) setText(action)`: a bar
 * that names no new word leaves the button's own words alone, so `0` draws the empty button the
 * `TextView` would have been, not a crash on `stringResource(0)`.
 */
data class UiSnackbar(
    /** The sentence. */
    @StringRes val words: Int,
    /** The action word, or `0` for the button's previous (empty) words. */
    @StringRes val actionLabel: Int = 0,
    /** What the action does, or `null` when this bar offers none. */
    val onAction: View.OnClickListener? = null,
    /** What a long press does, or `null` when it does nothing. */
    val onActionLongPress: View.OnLongClickListener? = null,
    /** Whether the action has already been used: drawn in place, with no pixels and no touch target. */
    val actionUsed: Boolean = false,
)
