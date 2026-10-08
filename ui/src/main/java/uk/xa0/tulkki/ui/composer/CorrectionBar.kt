package uk.xa0.tulkki.ui.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * Tulkki: the composer's correction bar, in Compose.
 *
 * <p>It replaces the Java `correction_container` - a caption, a cancel button and the owner's own
 * words - and the two Java handlers that moved it: `mCancelCorrectionListener` and the send button's
 * `CANCEL` branch, which were the same eleven lines twice. They are one `cancelCorrection()` in the
 * fragment now, and this bar asks for it through [CorrectionBarHost.install]'s `onCancel`.
 *
 * <p>**The bar draws the owner's words, never the wire text.** The Java filled it with
 * `HeldSend.draftOf(...)`, the same rule the editor uses, so the text handed in here is already the
 * app-language draft; this file does not read a message or decide which text that is.
 *
 * <p>Redesign rather than reproduction: the old bar carried a decorative edit icon that did nothing
 * and a second caption above the row; the row itself is the surface, and the caption it keeps is the
 * one that says what the bar is for.
 */
object CorrectionBarHost {

    /**
     * Sets the bar's content once and answers the controller the fragment keeps.
     *
     * @param onCancel what the cancel button does, owned by the caller - the fragment, which holds
     *     the draft the correction set aside
     */
    @JvmStatic
    fun install(view: ComposeView, darkTheme: Boolean, onCancel: Runnable): CorrectionBarController {
        val controller = CorrectionBarController()
        view.setTulkkiContent(darkTheme) {
            CorrectionBarContent(controller, onCancel)
        }
        return controller
    }
}

/**
 * The bar's content, for a host that composes it directly rather than setting it on a `ComposeView`
 * of its own: [CorrectionBarHost.install]'s body without the view. See
 * [uk.xa0.tulkki.ui.pinnedmessage.PinnedBarContent] for why the two doors exist and why they must draw
 * the same bar.
 */
@Composable
fun CorrectionBarContent(controller: CorrectionBarController, onCancel: Runnable) {
    CorrectionBar(controller, onCancel)
}

/**
 * The bar's one input: the words the owner is correcting. `null` is "no correction", which is a
 * zero-height `ComposeView` and nothing drawn.
 */
class CorrectionBarController internal constructor() {

    internal val text: MutableState<String?> = mutableStateOf(null)

    fun isShowing(): Boolean = text.value != null

    fun show(ownText: String) {
        text.value = ownText
    }

    fun hide() {
        text.value = null
    }
}

@Composable
private fun CorrectionBar(controller: CorrectionBarController, onCancel: Runnable) {
    val ownText = controller.text.value ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.correct_message),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_cancel_24dp),
                contentDescription = stringResource(R.string.action_cancel),
                modifier = Modifier.size(28.dp).clickable { onCancel.run() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = ownText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
