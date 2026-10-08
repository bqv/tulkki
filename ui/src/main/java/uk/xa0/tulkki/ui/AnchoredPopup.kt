package uk.xa0.tulkki.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * The anchored card: the one popup on a conversation, holding whatever content a caller brings.
 *
 * <p>Anchored rather than a sheet, because a sheet covers the bottom of the screen, which is where the
 * line being explained tends to sit. The card is placed beside its anchor and leaves that line
 * readable; [PopupPlacement] owns the arithmetic and this class only reads a screen and a measured
 * size into it.
 *
 * <p>It decides nothing about what it holds. Two content types reach it - the reading aid's
 * `GlossContent` and the review's title-plus-notes - and they stay two: this class carries the one
 * thing they share, which is the card. Tapping an underlined stretch or a word is what asks for the
 * content; a card that is already open is closed first, because there is one card and one [current],
 * so a second [show] closes the first before it opens.
 *
 * <p><strong>Why it is not touchable, which is a fix and not a detail - and not focusable
 * either.</strong> A [PopupWindow] that is still touchable owns its own rectangle: a touch inside it
 * is dispatched to this window and to nothing else, and this card is read-only, so every such touch
 * was a tap that went nowhere. The card is placed over the lines above its anchor, which is exactly
 * where the reader's next target usually is, so the second tap of a pair regularly landed on the card
 * and did nothing at all - the owner's "sometimes a gloss tap doesnt work, usually after another gloss
 * tap in the same message", verbatim. Not focusable was the first half of the same trap: a focusable
 * popup is <em>modal</em>, and a modal window's touchable region is the whole display, so it would eat
 * even a touch that lands outside it. But only not <em>touchable</em> takes the window out of input
 * dispatch altogether (`FLAG_NOT_TOUCHABLE`), and that is what lets a tap anywhere reach whatever is
 * underneath: the word behind the card glosses, and re-tapping the word the card is about dismisses it
 * and glosses it again, which is free because the lookup is cached.
 *
 * <p><strong>Dismissal: a touch anywhere outside the card.</strong> A touch <em>on</em> the card is
 * not one of those - the card holds text and nothing to press, so it has no touch to answer and must
 * keep passing one through. The popup watches outside touches (`FLAG_WATCH_OUTSIDE_TOUCH`, set by
 * [PopupWindow.setOutsideTouchable]), so the first down of a gesture that lands outside it is reported
 * to it as `ACTION_OUTSIDE` - even though the window no longer accepts touch - and the framework's own
 * decor view dismisses on that, while the same touch is delivered to the conversation. That is not the
 * only way out: the back button closes it through a callback that is enabled only while a card is
 * open, a scroll of the conversation closes it, and a second [show] closes the first before it opens.
 *
 * <p>One path is still the wrong half of that contract, and it is deliberately left for its own
 * change: because the window is out of input dispatch it cannot see the touch that lands on it, so the
 * only object that can tell "on the card" from "outside it" is `ConversationFragment.messagesView`'s
 * touch listener, which today dismisses on every `ACTION_DOWN`. The bounds test that fixes it - a
 * `covers(x, y)` on this class consulted by that one listener - is deferred by the owner, and the
 * listener's own comment names it as the one place it goes; until then a card still goes away under a
 * touch aimed at it.
 *
 * <p><strong>Back is one callback with one owner.</strong> The callback is armed while a card is open
 * and disabled rather than added and removed, so a disabled one lets the press through to whatever
 * handled it before. [close] is the only place that disarms it, and it asks first whether the card
 * going away is still [current]: a dismissal is reported by the window that is closing, and disarming
 * on behalf of a card that has already been replaced would take the card on screen's back button away
 * with it.
 */
class AnchoredPopup private constructor(
        private val window: PopupWindow,
        private val root: View,
        private val anchorBounds: Rect) {

    private var open = true

    /**
     * Draw again on this card and re-place it, so a longer answer still fits on the screen - unless
     * the card has gone, in which case the answer is dropped rather than drawn into a dead view. The
     * drawing belongs to the content, which is why it arrives as a runnable: the two content types
     * stay two.
     *
     * <p>Public where the Java was package-private: Kotlin has no package-private, and `internal`
     * would mangle the JVM name, so the method keeps its Java name. No caller names it today - the
     * gloss shell that did is deleted - but it is the card's redraw path and stays here.
     */
    fun update(render: Runnable) {
        if (!open) {
            return
        }
        render.run()
        replace()
    }

    /**
     * The window is gone, whoever closed it. The card stops being the current one and stops answering
     * its content, which is what keeps one show from leaving state behind for the next.
     *
     * <p>It disarms the back callback only when the card going away is still the one on screen. A
     * window's dismissal is reported by the window that is closing, and a second [show] may already
     * have made a new card current by then: disarming unconditionally would take the open card's back
     * button away with the closed one's.
     */
    private fun close() {
        open = false
        if (current !== this) {
            return
        }
        current = null
        disarmBack()
    }

    /** Re-place after the content changed size, so a longer answer still fits on the screen. */
    private fun replace() {
        if (!open) {
            return
        }
        val placed = place(window, root, anchorBounds)
        window.update(placed.left, placed.top, -1, -1)
    }

    companion object {

        private var current: AnchoredPopup? = null

        /**
         * The back button's callback, kept for the activity it was registered with and armed only
         * while a card is open, so a press of back with no card on screen goes exactly where it went
         * before.
         */
        private var backCallback: OnBackPressedCallback? = null

        private var backActivity: ComponentActivity? = null

        /**
         * Put one card on screen at the anchor's rectangle.
         *
         * <p>The content is built by the caller and this class only shows it: the card's size is not
         * known until the window has been laid out, which is why the placement is corrected once after
         * the show.
         *
         * @param anchor the view the content is about
         * @param anchorBounds the anchor's screen rectangle, which is where the card points
         * @param content the card's view, already inflated and already drawn once
         * @return the card, for content that draws again after its answer arrives
         */
        @JvmStatic
        fun show(anchor: View, anchorBounds: Rect, content: View): AnchoredPopup {
            // Whatever was open goes first, so this card starts from no state at all rather than from
            // the last one's - and so there is never a second card on screen.
            dismiss()

            val window =
                    PopupWindow(
                            content,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
            // Not focusable: a focusable popup is modal, and a modal window's touchable region is the
            // whole display, so an outside touch would be eaten by it too. See the class comment.
            window.isFocusable = false
            // Not touchable: this line is the fix for a tap that lands on the card going nowhere. The
            // card is read-only, so there is nothing it could want a touch for: the window steps out
            // of input dispatch and the tap is delivered to the conversation underneath.
            window.isTouchable = false
            // Watches outside touches (FLAG_WATCH_OUTSIDE_TOUCH), which is what keeps "a touch outside
            // the card closes it" true now that the window no longer receives the touch itself.
            window.isOutsideTouchable = true
            // A transparent background leaves the popup's own card as the only thing drawn. On this
            // API level it is not what registers an outside touch - the decor view dismisses on
            // ACTION_OUTSIDE, and the window would be translucent-format with or without it - so it is
            // left as it was rather than change what the window draws for nothing.
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            val popup = AnchoredPopup(window, content, anchorBounds)
            // Whatever closes the window - this class, the back button, the framework taking it away -
            // the state follows, so nothing is ever retained across shows.
            window.setOnDismissListener { popup.close() }

            current = popup
            armBack(anchor.context)
            window.showAtLocation(
                    anchor,
                    Gravity.NO_GRAVITY,
                    anchorBounds.left,
                    anchorBounds.bottom + gap(content))
            // The window has a size only after it is shown, so the placement that depends on that size
            // happens here rather than above.
            content.post {
                if (current === popup) {
                    popup.replace()
                }
            }
            return popup
        }

        /**
         * Close the card, if one is open. Called by anything that would otherwise strand it.
         *
         * <p>It does not clear [current] and it does not touch the back callback itself: asking the
         * window to dismiss is what makes [close] run, and that is the one place that knows whether
         * the card going away is the card that armed the callback. Clearing the state here would
         * disarm a callback a newer card had just armed. The one exception is a window with nothing
         * left to dismiss, whose `close()` would otherwise never arrive: that one is closed here, so
         * the state cannot be stranded.
         */
        @JvmStatic
        fun dismiss() {
            val card = current ?: return
            card.open = false
            card.window.dismiss()
            if (current === card && !card.window.isShowing) {
                // The window had nothing to dismiss - it was never shown, or its dismissal was
                // reported with the call - so no close() will follow and the state must not be left
                // behind with the back button armed for a card that is gone.
                card.close()
            }
        }

        /**
         * Arm the back button for as long as a card is on screen. It is armed, not added and removed:
         * a disabled callback lets the press through to whatever handled it before, and the dispatcher
         * keeps one per activity rather than one per card.
         */
        private fun armBack(context: Context) {
            val activity = activityOf(context)
            if (activity == null) {
                // Nowhere for back to be taken from (a test, a preview): leaving it alone is right.
                return
            }
            if (backCallback == null || backActivity !== activity) {
                val callback =
                        object : OnBackPressedCallback(false) {
                            override fun handleOnBackPressed() {
                                dismiss()
                            }
                        }
                backCallback = callback
                backActivity = activity
                activity.onBackPressedDispatcher.addCallback(callback)
            }
            backCallback?.isEnabled = true
        }

        private fun disarmBack() {
            backCallback?.isEnabled = false
        }

        /**
         * The activity the content is drawn in. The context a list adapter hands over is usually a
         * theme wrapper rather than the activity itself, so the wrappers are walked.
         */
        private fun activityOf(context: Context?): ComponentActivity? {
            var current: Context? = context
            while (current != null) {
                if (current is ComponentActivity) {
                    return current
                }
                if (current !is ContextWrapper) {
                    return null
                }
                current = current.baseContext
            }
            return null
        }

        /**
         * Where the card goes on this screen, from the measurements only a view can give: the card's
         * size, the screen's and the density the margin scales with. The rule itself is
         * [PopupPlacement.place].
         */
        private fun place(window: PopupWindow, content: View, anchorBounds: Rect): Rect {
            val metrics = content.resources.displayMetrics
            val width =
                    if (window.width > 0) window.width else content.measuredWidth
            val height =
                    if (window.height > 0) window.height else content.measuredHeight
            val placed =
                    PopupPlacement.place(
                            intArrayOf(
                                    anchorBounds.left,
                                    anchorBounds.top,
                                    anchorBounds.right,
                                    anchorBounds.bottom),
                            width,
                            height,
                            metrics.widthPixels,
                            metrics.heightPixels,
                            metrics.density,
                            gap(content))
            return Rect(placed[0], placed[1], placed[0] + width, placed[1] + height)
        }

        private fun gap(content: View): Int {
            return Math.round(PopupPlacement.GAP_DP * content.resources.displayMetrics.density)
        }

        /**
         * The stretch's rectangle on screen: its horizontal position on the line it starts on, that
         * line's top and bottom, and the anchor view's location - so a word in the middle of a line is
         * placed where it actually is rather than at the bubble's corner. A stretch that wraps uses the
         * first line's top and bottom and is clamped to at least one pixel of width, which is all the
         * placement needs - the card points at where the mark begins.
         *
         * <p>It needs a [Layout], so this half is not testable on the JVM; the arithmetic that consumes
         * what it returns is.
         *
         * <p>Public where the Java was package-private, for the same reason [update] is: the caller
         * in this package ([ReviewPopup]) names it as a method and the JVM name is not mangled.
         */
        @JvmStatic
        fun boundsOfRange(text: TextView, start: Int, end: Int): Rect? {
            val layout = text.layout
            val body: CharSequence? = text.text
            if (layout == null ||
                    body == null ||
                    start < 0 ||
                    end > body.length ||
                    end <= start) {
                return null
            }
            val line = layout.getLineForOffset(start)
            val location = IntArray(2)
            text.getLocationOnScreen(location)
            val left =
                    location[0] +
                            text.totalPaddingLeft +
                            layout.getPrimaryHorizontal(start)
            val right =
                    location[0] +
                            text.totalPaddingLeft +
                            layout.getPrimaryHorizontal(end)
            val top = location[1] + text.totalPaddingTop + layout.getLineTop(line)
            val bottom = location[1] + text.totalPaddingTop + layout.getLineBottom(line)
            return Rect(
                    left.toInt(),
                    top,
                    Math.max(Math.round(right), Math.round(left) + 1),
                    bottom)
        }
    }
}
