package uk.xa0.tulkki.data.model

import android.view.View

/**
 * One ad-hoc command page (XEP-0050) as plain data: the rows of the XEP-0004 form it
 * carries and the action buttons beneath them.
 *
 * It is the model `ui-9`'s command-form slice needs and the one the [CommandFormRenderer]
 * in `:ui` draws. A command page used to be a `RecyclerView.Adapter` of Android views,
 * built inside `Conversation.kt`; the field-type dispatch and every value/label decision
 * stay here, in `:data`, and only the drawing moves to Compose. Nothing here holds a
 * binding, an `R.layout` or a widget, so the page is a JVM value a cell can build.
 *
 * The island `Option`'s label is non-null (`docs/MIGRATION.md`, "Kotlin-negative (3)"), so the
 * option lists below carry the label resolved at snapshot time rather than the object.
 *
 * @property items the rows, in the order the form reports them, already filtered of the
 *     `hidden` fields and the actions field the page draws itself
 * @property actions the buttons, `cancel`/`prev` first and the rest in the server's order
 */
class CommandForm(
    val items: List<CommandFormItem>,
    val actions: List<CommandFormAction>,
)

/** One row of a command page. */
sealed interface CommandFormItem

/** A note, an instruction or an error line. */
class NoteItem(val message: String, val error: Boolean) : CommandFormItem

/** The waiting row drawn while a command is in flight. */
class ProgressItem(val showText: Boolean) : CommandFormItem

/** An out-of-band (`jabber:x:oob`) panel, whose page posts actions back as `xmpp_xep0050`. */
class WebItem(val desc: String?, val url: String) : CommandFormItem

/** A read-only `result`/`fixed` field. */
class ResultFieldItem(
    val label: String?,
    val desc: String?,
    val mediaUrl: String?,
    val values: List<ResultValue>,
) : CommandFormItem

/** One value line of a [ResultFieldItem]; [linkUrl] is present when the value is a link. */
class ResultValue(
    val text: String,
    val linkUrl: String?,
    val onOpenLink: (String) -> Unit,
    val onCopy: (String) -> Unit,
)

/** A `result`/`item` table cell. */
class ResultCellItem(
    val text: String,
    val header: Boolean,
    val linkUrl: String?,
    val onOpenLink: (String) -> Unit,
    val onCopy: (String) -> Unit,
) : CommandFormItem

/** A card wrapping several read-only fields (one `item` of a multi-column result). */
class ItemCardItem(val fields: List<ResultFieldItem>) : CommandFormItem

/** A labelled checkbox. */
class CheckboxItem(
    val label: String?,
    val desc: String?,
    val checked: Boolean,
    val onChecked: (Boolean) -> Unit,
) : CommandFormItem

/** A filter box over a list of options (`list-single` with more than nine, or `list-multi`). */
class SearchListItem(
    val label: String?,
    val desc: String?,
    val error: String?,
    val query: String,
    val options: List<CommandFormOption>,
    val selected: Set<String>,
    val multi: Boolean,
    val canType: Boolean,
    val onQuery: (String) -> Unit,
    val onSelect: (CommandFormOption, Boolean) -> Unit,
) : CommandFormItem

/** A radio grid plus an optional free-text field (`open`). */
class RadioEditItem(
    val label: String?,
    val desc: String?,
    val error: String?,
    val options: List<CommandFormOption>,
    val selected: String?,
    val open: Boolean,
    val onSelect: (CommandFormOption) -> Unit,
    val onOpen: (String) -> Unit,
) : CommandFormItem

/** A single-choice dropdown (`list-single` with a value and no `open`). */
class SpinnerItem(
    val label: String?,
    val desc: String?,
    val options: List<CommandFormOption>,
    val selected: String?,
    val onSelect: (CommandFormOption) -> Unit,
) : CommandFormItem

/** A grid of buttons: the one-fillable-field shortcut. */
class ButtonGridItem(
    val label: String?,
    val desc: String?,
    val error: String?,
    val options: List<CommandFormOption>,
    val defaultOption: CommandFormOption?,
    val open: Boolean,
    val customText: String,
    val onChoose: (CommandFormOption) -> Unit,
    val onDefault: () -> Unit,
    val onCustom: (String) -> Unit,
) : CommandFormItem

/** One text input. */
class TextFieldItem(
    val label: String?,
    val desc: String?,
    val error: String?,
    val prefix: String?,
    val suffix: String?,
    val value: String,
    val input: CommandFormInput,
    val onChange: (String) -> Unit,
) : CommandFormItem

/** A numeric slider (`validate/range` with both bounds and a numeric datatype). */
class SliderItem(
    val label: String?,
    val desc: String?,
    val value: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val onChange: (Float) -> Unit,
) : CommandFormItem

/** One option of a XEP-0004 field. [icon] is an SVG `<image href>`, when the option carries one. */
class CommandFormOption(val value: String, val label: String, val icon: String?)

/** One action button. The colours are resolved from the action's name at snapshot time. */
class CommandFormAction(
    val name: String,
    val label: String,
    val onAccent: Int,
    val background: Int,
)

/** What soft keyboard a [TextFieldItem] asks for, from the field's `type` and `validate`. */
enum class CommandFormInput {
    TEXT,
    MULTILINE,
    EMAIL,
    PASSWORD,
    NUMBER,
    DECIMAL,
    DATE,
    TIME,
    DATETIME,
    URI,
    PHONE,
}

/**
 * The state a renderer draws and drives: the open command page, its form and its actions.
 *
 * It is the narrow face of `ConversationPagerAdapter.CommandSession` that `:ui` is allowed
 * to name, and it is deliberately free of `RecyclerView`, bindings and widgets. Every
 * mutation - a field's value, an action - goes back through its methods, so the page's
 * decisions stay in `:data` and the renderer only draws and reports.
 */
interface CommandFormSession {

    /** The page's current rows and actions, already assembled. */
    fun form(): CommandForm

    /** The page's title, which a server may replace when a form arrives. */
    fun title(): String?

    /** Subscribe to state changes; the listener is called on the main thread. */
    fun addFormListener(listener: CommandFormListener)

    /** Unsubscribe a listener added by [addFormListener]. */
    fun removeFormListener(listener: CommandFormListener)

    /**
     * Run one action by name, the same path the action buttons take.
     *
     * @return `true` when the session is finished and its page should be removed
     */
    fun executeAction(name: String): Boolean

    /**
     * Run one action posted by an out-of-band page's JavaScript (`xmpp_xep0050/<action>`),
     * clearing the web anchor the way the old bridge did before it runs.
     *
     * @return `true` when the session is finished and its page should be removed
     */
    fun executeWebAction(action: String): Boolean

    /**
     * Remember [view] as the page's live `WebView`, so a form submit can be posted to it as
     * `xmpp_xep0050/<action>` instead of going to the wire.
     */
    fun preventDefault(view: View)
}

/** One state change of a [CommandFormSession]. */
fun interface CommandFormListener {
    fun onFormChanged()
}

/**
 * The `:ui` door to the command page: a renderer builds the one `View` a pager page is.
 *
 * `:data` owns the page's state and this interface; `:ui` owns the Compose drawing and
 * installs itself through [CommandFormRenderers]. The seam exists because `:data` cannot
 * name Compose (the module dependency runs the other way) and a session cannot build a
 * `ComposeView` for itself.
 */
fun interface CommandFormRenderer {

    /**
     * Build the page's view, already bound to [session]'s state and observing it.
     *
     * @param anchor a view of the host's, for the session's link and clipboard ports; it may
     *     be `null` while the page has no window yet, so a port that needs one must cope
     */
    fun createView(context: android.content.Context, session: CommandFormSession, anchor: View?): View
}

/** The installed renderer, set once by `:ui` at startup; `null` is a plain client's no-op. */
object CommandFormRenderers {

    /**
     * The renderer in force, or `null` before `:ui` installs one. A `null` renderer means no
     * command page is drawn at all, which is the state of a JVM cell or a headless run.
     */
    @JvmStatic
    var renderer: CommandFormRenderer? = null
}
