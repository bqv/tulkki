package uk.xa0.tulkki.ui.emoji

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import java.util.function.Consumer

/**
 * Tulkki: the conversation's emoji picker, in Compose.
 *
 * <p>It replaces the Java panel the shell drew - the `emojis_sticker_layout` `LinearLayout`, its
 * `EmojiPickerView`, its one-tab strip and the keyboard-height arithmetic that sized it - and the
 * Java that drove it: `memojiButtonListener`, `memojisButtonListener`, `mkeyboardButtonListener`,
 * `backPressedLeaveEmojiPicker`'s collapse, `onKeyboardStateChanged`, `getEmojiPickerHeight`,
 * `persistKeyboardHeight` and `updateEmojiPickerTabStyles`. The Java `emojiButton` and
 * `keyboardButton` and both their listeners are gone with the row they sat in: the one affordance is
 * `ConversationComposer`'s, it emits `ConversationEvents.onEmojiTap`, and the fragment's answer to it
 * is the only caller of this controller.
 *
 * <p>**It is a redesign, not a reproduction.** The old panel reserved a height derived from the
 * keyboard's - measured, persisted and replayed so the two never fought - and carried a tab strip
 * whose single tab selected the panel that was already open. Neither survives: the panel is open or
 * it is not, and when it is it takes a fixed share of the screen, which is what the keyboard-height
 * dance was approximating. The picker grid itself is still the platform's [EmojiPickerView], hosted
 * through [AndroidView]: reimplementing the emoji data and its categories is not this slice's job,
 * and the view is a platform component rather than the Java logic being dissolved.
 *
 * <p>**The insertion point is the Java composer's.** [install] takes the picked emoji and hands it
 * back; the fragment inserts it at the field's cursor, because the field is still the Java
 * `EditMessage`. When the draft field is Compose the consumer becomes the field's own state update,
 * and this file does not change at all.
 */
object EmojiPanelHost {

    /**
     * Sets the panel's content once and answers the controller the composer keeps.
     *
     * @param onPicked the emoji the owner chose, owned by the caller - the fragment, which owns the
     *     cursor; a picker that inserted it itself would need the field this file must not know
     * @param darkTheme the shell's theme, from `ConversationFragment.darkTheme()`
     */
    @JvmStatic
    fun install(
        view: ComposeView,
        darkTheme: Boolean,
        onPicked: Consumer<String>,
    ): EmojiPanelController {
        val controller = EmojiPanelController()
        view.setTulkkiContent(darkTheme) {
            EmojiPanelContent(controller, onPicked)
        }
        return controller
    }
}

/**
 * The panel's content, for a host that composes it directly rather than setting it on a `ComposeView`
 * of its own: [EmojiPanelHost.install]'s body without the view. See
 * [uk.xa0.tulkki.ui.pinnedmessage.PinnedBarContent] for why the two doors exist and why they must draw
 * the same panel.
 */
@Composable
fun EmojiPanelContent(controller: EmojiPanelController, onPicked: Consumer<String>) {
    EmojiPanel(controller, onPicked)
}

/**
 * Whether the panel is open, held as Compose state so the Java composer's two buttons can move it
 * without the panel being rebuilt.
 */
class EmojiPanelController internal constructor() {

    internal val open: MutableState<Boolean> = mutableStateOf(false)

    fun isOpen(): Boolean = open.value

    fun open() {
        open.value = true
    }

    fun close() {
        open.value = false
    }
}

@Composable
private fun EmojiPanel(controller: EmojiPanelController, onPicked: Consumer<String>) {
    if (!controller.open.value) {
        // Closed is no content at all, which is a zero-height `ComposeView` and no reflow: the old
        // panel kept a zero-height view in the tree for the same reason.
        return
    }
    val height = (LocalConfiguration.current.screenHeightDp * PANEL_OF_SCREEN).dp
    AndroidView(
        factory = { context ->
            EmojiPickerView(context).apply {
                setOnEmojiPickedListener { item -> onPicked.accept(item.emoji.toString()) }
            }
        },
        modifier = Modifier.fillMaxWidth().height(height),
    )
}

/**
 * The panel's share of the screen. The old code fell back to exactly this fraction when it had never
 * measured a keyboard, so this is the number that was always used on a fresh install.
 */
private const val PANEL_OF_SCREEN = 0.4f
