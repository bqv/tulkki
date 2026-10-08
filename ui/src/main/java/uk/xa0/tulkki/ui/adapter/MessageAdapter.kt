package uk.xa0.tulkki.ui.adapter

import android.app.Activity
import android.text.Editable
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.customview.widget.ViewDragHelper

import com.google.android.material.color.MaterialColors

import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.ui.DraggableListView
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.text.DividerSpan
import uk.xa0.tulkki.ui.text.QuoteSpan
import uk.xa0.tulkki.ui.utils.StylingHelper

/**
 * The conversation's bubble adapter, reduced to the API its host still calls.
 *
 * <p>The visible message list is Compose's (`ConversationHost.showMessages` in a `ComposeView`, hosted
 * by the live fragment); the Java `ListView` it used to feed stays in
 * the tree as `GONE` because the fragment's scroll arithmetic still names it, and this adapter is
 * what that list is given. Nothing here draws a row any more: the eight `item_message_*` /
 * `link_description` layouts are gone (deleted at `158f5079dc`), so there is no inflation, no view
 * holder and no per-message state to memois. The members below are kept because the tree still calls
 * them - the fragment's lifecycle calls and the composer's
 * [StylingHelper.MessageEditorStyler] quote pass. The row content itself, including every concealment
 * decision, is the projector's.
 *
 * <p>**The three jobs that were never about a row are gone from here** (moved to
 * [uk.xa0.tulkki.ui.conversation.ConversationMedia]): the audio player's stop/unregister/play-pause
 * surface, opening a downloadable message, and the pending request the storage permission holds.
 * They were the adapter's only statement about a conversation, and with them went the last typed
 * reference to `ConversationFragment` - the field the never-called `setConversationFragment` set,
 * `quoteText`'s delegate to it (and with it `MessageTextActionModeCallback`, which had no caller),
 * and the static `registerPendingMessage` call in the download branch. `AudioPlayer` now takes a
 * `Context` instead of this adapter.
 *
 * <p>[getView] answers an empty view rather than `ArrayAdapter`'s resource-0 inflation, so nothing
 * can draw a message layout even if the hidden list is ever laid out.
 *
 * <p>This was the last Java file under `adapter/`; the port is `port-44`, and it is faithful because
 * the file is still live as an API stub rather than dead. The one island type it reads,
 * `uk.xa0.tulkki.xmpp.Config`, is written fully qualified and imported nowhere, as the Java did.
 */
class MessageAdapter(
    private val activity: XmppActivity,
    messages: List<Message>,
) : ArrayAdapter<Message>(activity, 0, messages), DraggableListView.DraggableAdapter {

    private val appSettings: AppSettings = AppSettings(activity)
    private var bubbleDesign = BubbleDesign(false, false, false, true)
    private var dragHelper: ViewDragHelper? = null

    /**
     * Tulkki: the swipe-to-reply gesture is gone with the rows. The list keeps its adapter so the
     * fragment's own setters still have a target, and no child can be captured for a drag.
     */
    private val dragCallback: ViewDragHelper.Callback = object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int): Boolean {
            return false
        }
    }

    init {
        updatePreferences()
    }

    override fun getDragCallback(): ViewDragHelper.Callback? {
        return dragCallback
    }

    override fun setViewDragHelper(helper: ViewDragHelper?) {
        this.dragHelper = helper
    }

    fun getActivity(): Activity {
        return activity
    }

    /**
     * The hidden list's `ArrayAdapter` leg. It answers an empty view, never a message layout, so the
     * adapter cannot bring a bubble back if the list is ever drawn again.
     */
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return convertView ?: View(context)
    }

    /**
     * The composer's quote pass ([StylingHelper.MessageEditorStyler]) still goes through this
     * adapter, so it keeps its own span work: it is the edit text's, not a bubble's.
     */
    fun handleTextQuotes(textView: TextView, body: Editable, deleteMarkers: Boolean) {
        val colorfulBackground = this.bubbleDesign.colorfulChatBubbles
        val bubbleColor =
            if (colorfulBackground) {
                if (deleteMarkers) BubbleColor.SECONDARY else BubbleColor.TERTIARY
            } else {
                BubbleColor.SURFACE
            }
        handleTextQuotes(textView, body, bubbleColor, deleteMarkers)
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters. Appends likebreaks
     * and applies DividerSpan to them to show a padding between quote and text.
     */
    fun handleTextQuotes(
        textView: TextView,
        body: Editable,
        bubbleColor: BubbleColor,
        deleteMarkers: Boolean,
    ): Boolean {
        var startsWithQuote = false
        var quoteDepth = 1
        while (QuoteHelper.bodyContainsQuoteStart(body) &&
            quoteDepth <= uk.xa0.tulkki.xmpp.Config.QUOTE_MAX_DEPTH) {
            var previous = '\n'
            var lineStart = -1
            var lineTextStart = -1
            var quoteStart = -1
            var skipped = 0
            var i = 0
            while (i <= body.length) {
                if (!deleteMarkers && QuoteHelper.isRelativeSizeSpanned(body, i)) {
                    skipped++
                    i++
                    continue
                }
                val current = if (body.length > i) body[i] else '\n'
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length && QuoteHelper.isPositionQuoteStart(body, i)) {
                            // Line start with quote
                            lineStart = i
                            if (quoteStart == -1) quoteStart = i - skipped
                            if (i == 0) startsWithQuote = true
                        } else if (quoteStart >= 0) {
                            // Line start without quote, apply spans there
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor, deleteMarkers)
                            quoteStart = -1
                        }
                    }
                } else {
                    // Remove extra spaces between > and first character in the line
                    // > character will be removed too
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i
                    }
                    if (current == '\n') {
                        if (deleteMarkers) {
                            i -= lineTextStart - lineStart
                            body.delete(lineStart, lineTextStart)
                            if (i == lineStart) {
                                // Avoid empty lines because span over empty line can be hidden
                                body.insert(i, " ")
                                i++
                            }
                        } else {
                            body.setSpan(
                                RelativeSizeSpan(
                                    if (i - (lineTextStart - lineStart) == lineStart) 1f else 0f),
                                lineStart,
                                lineTextStart,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or
                                    (StylingHelper.XHTML_REMOVE shl Spanned.SPAN_USER_SHIFT)
                            )
                        }
                        lineStart = -1
                        lineTextStart = -1
                    }
                }
                previous = current
                skipped = 0
                i++
            }
            if (quoteStart >= 0) {
                // Apply spans to finishing open quote
                applyQuoteSpan(textView, body, quoteStart, body.length, bubbleColor, deleteMarkers)
            }
            quoteDepth++
        }
        return startsWithQuote
    }

    private fun applyQuoteSpan(
        textView: TextView,
        body: Editable,
        start: Int,
        end: Int,
        bubbleColor: BubbleColor,
        makeEdits: Boolean,
    ) {
        var quoteStart = start
        var quoteEnd = end
        if (makeEdits && quoteStart > 1 &&
            "\n\n" != body.subSequence(quoteStart - 2, quoteStart).toString()) {
            body.insert(quoteStart, "\n")
            quoteStart++
            body.setSpan(
                DividerSpan(false), quoteStart - 2, quoteStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            quoteEnd++
        }
        if (makeEdits && quoteEnd < body.length - 1 &&
            "\n\n" != body.subSequence(quoteEnd, quoteEnd + 2).toString()) {
            body.insert(quoteEnd, "\n")
            body.setSpan(
                DividerSpan(false),
                quoteEnd,
                quoteEnd +
                    (if ("\n" == body.subSequence(quoteEnd + 1, quoteEnd + 2).toString()) 2 else 1),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val metrics = context.resources.displayMetrics
        body.setSpan(
            QuoteSpan(bubbleToOnSurfaceVariant(textView, bubbleColor), metrics),
            quoteStart,
            quoteEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun getFileBackend(): FileBackend {
        return FileBackends.get()
    }

    fun updatePreferences() {
        this.bubbleDesign =
            BubbleDesign(
                appSettings.isColorfulChatBubbles(),
                appSettings.isAlignStart(),
                appSettings.isLargeFont(),
                appSettings.isShowAvatars())
    }

    private class BubbleDesign(
        val colorfulChatBubbles: Boolean,
        val alignStart: Boolean,
        val largeFont: Boolean,
        val showAvatars: Boolean,
    )

    enum class BubbleColor {
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        WARNING;

        val isSurface: Boolean
            get() = this == SURFACE || this == SURFACE_HIGH
    }

    companion object {

        fun setTextColor(textView: TextView, bubbleColor: BubbleColor) {
            val color = bubbleToOnSurfaceColor(textView, bubbleColor)
            textView.setTextColor(color)
            if (bubbleColor.isSurface) {
                textView.setLinkTextColor(
                    MaterialColors.getColor(textView, androidx.appcompat.R.attr.colorPrimary))
            } else {
                textView.setLinkTextColor(color)
            }
        }

        @ColorInt
        private fun bubbleToOnSurfaceVariant(view: View, bubbleColor: BubbleColor): Int {
            val colorAttributeResId: Int
            if (bubbleColor.isSurface) {
                colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant
            } else {
                colorAttributeResId = bubbleToOnSurface(bubbleColor)
            }
            return MaterialColors.getColor(view, colorAttributeResId)
        }

        @ColorInt
        private fun bubbleToOnSurfaceColor(view: View, bubbleColor: BubbleColor): Int =
            MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor))

        @AttrRes
        private fun bubbleToOnSurface(bubbleColor: BubbleColor): Int = when (bubbleColor) {
            BubbleColor.SURFACE, BubbleColor.SURFACE_HIGH ->
                com.google.android.material.R.attr.colorOnSurface
            BubbleColor.PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer
            BubbleColor.SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer
            BubbleColor.TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer
            BubbleColor.WARNING -> com.google.android.material.R.attr.colorOnErrorContainer
        }
    }
}
