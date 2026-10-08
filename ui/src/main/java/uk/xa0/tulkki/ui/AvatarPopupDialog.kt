package uk.xa0.tulkki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.chrisbanes.photoview.PhotoView
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.ui.util.AvatarWorkerTask

/**
 * The avatar popup: `avatar_dialog.xml`, deleted with it.
 *
 * <p>The layout was a `RelativeLayout` around one third-party `PhotoView`, `match_parent` with
 * `adjustViewBounds`; the popup itself was a plain `Dialog` whose window background was made
 * transparent, whose image was loaded by [AvatarWorkerTask.loadAvatar] at `R.dimen.avatar_big`, and
 * whose tap dismissed it. All four facts are here: a transparent [Dialog] that fills the window, the
 * same `PhotoView` in an [AndroidView] so the pinch-zoom the third-party view owns survives, the same
 * worker at the same size with the same content description, and a tap - on the image or on the space
 * around it - that dismisses.
 *
 * <p>The dialog fills the window (`usePlatformDefaultWidth = false`) and centres the photo in it.
 * The deleted popup was a floating window whose width was the photo's own, but a wrap-content
 * Compose dialog would hold an [AndroidView] with no intrinsic size to measure until the worker
 * lands, so the window is the screen and the photo is what centres. The window background needs no
 * call of its own - the Compose dialog already sets `android.R.color.transparent` - which is why the
 * deleted `setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))` has no counterpart here.
 *
 * @param avatarable the avatar to draw - an account, a contact, a conversation or a MUC user.
 * @param onDismiss the outside tap, the back gesture or the tap on the image.
 */
@Composable
fun AvatarPopupBody(avatarable: Avatarable, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    PhotoView(context).apply {
                        // The XML's own two attributes, and nothing else: `adjustViewBounds` with no
                        // `scaleType`, which leaves the third-party view's own fit-centre.
                        adjustViewBounds = true
                        // The deleted `imageView.setOnClickListener { dialog.dismiss() }`.
                        setOnClickListener { onDismiss() }
                        AvatarWorkerTask.loadAvatar(
                            avatarable,
                            this,
                            R.dimen.avatar_big,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
