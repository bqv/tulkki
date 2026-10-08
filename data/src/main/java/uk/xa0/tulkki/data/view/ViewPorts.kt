package uk.xa0.tulkki.data.view

import android.content.Context
import android.text.Editable
import android.text.style.ClickableSpan
import android.view.View
import uk.xa0.tulkki.data.model.Account

/**
 * The last four things `:data` needs from `:ui`, declared here and implemented there.
 *
 * Pair 8b's residue. Everything else D9 carried moved down by package (the quote grammar, the
 * attachment-independent mime families, the pinned-message model, the URL/presence helpers); what is left
 * is genuinely view work that `:data`'s model code performs in place, and `:data` may not name it:
 *
 * - [LinkedText] - text becomes clickable spans. `Message.getSpannableBody` wraps its `URLSpan`s and
 *   `Message.getLinks` linkifies a copy; `Conversation`'s command form builds the same spans by hand.
 * - [Clipboard] - the command form's long-press "copy".
 * - [SoftKeyboard] - the command form's search/text fields ask for and give up the keyboard.
 * - [ScreenLaunch] - the command form hands an `xmpp:` result to the URI handler screen.
 * - [UiSettings] - the one preference (`load_image_from_any_link`) that `MessageUtils.treatAsDownloadable`
 *   reads, which used to be a static reach into `uk.xa0.tulkki.ui.XmppActivity`.
 *
 * **The holder fails loudly.** [linkedText] and its siblings throw an [IllegalStateException] naming the
 * port and the install point rather than returning a no-op: a silently unstyled body, or a preference read
 * as `false`, would make a missing install look like ordinary behaviour. The composition root installs
 * every port in one call from `uk.xa0.tulkki.app.TulkkiApplication.onCreate` - the same place and the same
 * shape as pair 10's `uk.xa0.tulkki.translation.EngineHost`. A JVM test that reaches one of these paths
 * must install a double explicitly; none does today. The ports are deleted when the command form and the
 * model's rendering helpers move up to `:ui`.
 */
object ViewPorts {

    /** Text that has to become clickable spans. */
    interface LinkedText {

        /** Wrap every `URLSpan` in `body` with the app's own clickable span. */
        fun fixLinks(body: Editable)

        /** The URLs `body` links to, in the order they appear. */
        fun extractLinks(body: Editable): List<String>

        /** A clickable span for one URL, for a caller that sets it on its own text. */
        fun linkSpan(url: String, account: Account?): ClickableSpan

        /** Open `url` as a tap on `widget` would. */
        fun openLink(url: String, account: Account?, widget: View)
    }

    /** The clipboard, with the app's own confirmation strings. */
    interface Clipboard {

        /** Copy `text`, toast `labelResource` on success; `false` when it failed. */
        fun copyText(context: Context, text: CharSequence, labelResource: Int): Boolean

        /** Copy a link, reading `text/plain` ourselves so no peer text is attached. */
        fun copyLink(context: Context, url: String)
    }

    /** The soft keyboard of one field. */
    interface SoftKeyboard {

        /** Focus `field` and ask for the keyboard. */
        fun show(field: View)

        /** Give the keyboard back. */
        fun hide(field: View)
    }

    /** A screen `:data` has to open but does not own. */
    interface ScreenLaunch {

        /** Hand an `xmpp:` URI to the URI handler screen. */
        fun launchUriHandler(context: Context, url: String)
    }

    /** The app preferences `:data` has to read but does not own. */
    interface UiSettings {

        /** `load_image_from_any_link`: may a bare image URL be treated as a downloadable file? */
        fun loadImageFromAnyLink(): Boolean
    }

    @Volatile
    private var linkedTextPort: LinkedText? = null

    @Volatile
    private var clipboardPort: Clipboard? = null

    @Volatile
    private var softKeyboardPort: SoftKeyboard? = null

    @Volatile
    private var screenLaunchPort: ScreenLaunch? = null

    @Volatile
    private var uiSettingsPort: UiSettings? = null

    /**
     * The composition root's one line, from `TulkkiApplication.onCreate`, before any `Service` or activity
     * exists.
     */
    @JvmStatic
    fun install(
        linkedText: LinkedText,
        clipboard: Clipboard,
        softKeyboard: SoftKeyboard,
        screenLaunch: ScreenLaunch,
        uiSettings: UiSettings,
    ) {
        linkedTextPort = linkedText
        clipboardPort = clipboard
        softKeyboardPort = softKeyboard
        screenLaunchPort = screenLaunch
        uiSettingsPort = uiSettings
    }

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun linkedText(): LinkedText = linkedTextPort ?: throw missing("linkedText", "ViewPorts.install")

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun clipboard(): Clipboard = clipboardPort ?: throw missing("clipboard", "ViewPorts.install")

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun softKeyboard(): SoftKeyboard =
        softKeyboardPort ?: throw missing("softKeyboard", "ViewPorts.install")

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun screenLaunch(): ScreenLaunch =
        screenLaunchPort ?: throw missing("screenLaunch", "ViewPorts.install")

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun uiSettings(): UiSettings = uiSettingsPort ?: throw missing("uiSettings", "ViewPorts.install")

    private fun missing(port: String, install: String): IllegalStateException =
        IllegalStateException(
            "uk.xa0.tulkki.data.view.ViewPorts." +
                port +
                " was never installed: uk.xa0.tulkki.app.TulkkiApplication.onCreate must call" +
                " " +
                install +
                " at process start, and it has not",
        )
}
