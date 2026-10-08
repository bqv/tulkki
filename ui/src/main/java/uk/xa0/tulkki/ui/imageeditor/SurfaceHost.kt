package uk.xa0.tulkki.ui.imageeditor

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * The plain ViewGroup the editor's third-party surfaces sit in under Compose's `AndroidView`.
 *
 * <p>The cropper is a legacy `ViewGroup` that rewrites its own LayoutParams from `onLayout`
 * (`CropImageView.onLayout` writes `mLayoutWidth`/`mLayoutHeight` into `layoutParams` and calls
 * `setLayoutParams`, which is an unconditional `requestLayout`). A normal Android parent swallows
 * that request - the parent is already laid out, so it propagates no further - which is why the
 * vendored XML host (`activity_edit.xml`'s `RelativeLayout`) was fine. Compose's interop host is
 * not a normal parent: `AndroidViewHolder` is measured and laid out from its own `LayoutNode`, so
 * the request escapes to `AndroidComposeView` and the ViewRootImpl, schedules another traversal,
 * and the cropper's `onLayout` requests layout again - a measure/layout cycle that never settles.
 * On a phone it is an ANR with the main thread `Runnable` inside
 * `CropImageView.onLayout -> applyImageMatrix -> Matrix.mapRect`.
 *
 * <p>This host restores the guard the ordinary View hierarchy gives for free, and only that guard:
 * a `requestLayout()` raised *while this group is laying its children out* is dropped, exactly as a
 * normal parent drops it, and every other request passes through unchanged. So the cropper's own
 * later steps (`setAspectRatio`, `rotateImage`, `setImageUriAsync`, ...) still reach Compose.
 */
class SurfaceHost
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {

        private var layingOut = false

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            layingOut = true
            try {
                super.onLayout(changed, left, top, right, bottom)
            } finally {
                layingOut = false
            }
        }

        override fun requestLayout() {
            if (layingOut) {
                return
            }
            super.requestLayout()
        }
    }
