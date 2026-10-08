package uk.xa0.tulkki.ui.conversation

import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The wall behind the conversation: the owner's own picture for this conversation, or nothing at all.
 *
 * <p>It is `background_image` - the first child of the conversation's root, drawn behind every other
 * surface - and it is an `AndroidView` holding an [ImageView] on purpose: the deleted view was an
 * `ImageView` whose whole job is `setImageURI`, and the tree has no image loader (no Coil, no
 * `AsyncImage`) to decode a `content://` or `file://` picture into a Compose painter. A Compose brush
 * here would need a second decoder and would still not be the same pixels; the surface is genuinely
 * the view it was.
 *
 * <p>The reload is guarded by the view's own tag: a recomposition with the same URI is not another
 * decode, and a changed one replaces the picture rather than stacking a second one.
 *
 * <p>`null` is no picture - which is the ordinary conversation, the one with a unicoloured background
 * or no image of its own - and composes nothing at all, exactly as the `GONE` view drew nothing.
 * Nothing here draws text, so no concealment rule is consulted.
 *
 * @param uri the conversation's background picture, or `null` for the theme's own surface
 */
@Composable
fun ConversationBackground(uri: String?, modifier: Modifier = Modifier) {
    if (uri == null) {
        return
    }
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { view ->
            if (view.tag != uri) {
                view.tag = uri
                view.setImageURI(Uri.parse(uri))
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
