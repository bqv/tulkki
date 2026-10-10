package uk.xa0.tulkki.data.text

import android.text.Spanned
import uk.xa0.tulkki.xml.Element

/**
 * The XHTML writer `:data`'s model needs, declared here and implemented in `:ui`.
 *
 * 3.7 pair 2. `SpannedToXHTML` - the writer that turns a styled body into
 * `http://jabber.org/protocol/xhtml-im` markup - names `uk.xa0.tulkki.ui.text.QuoteSpan` and
 * `uk.xa0.tulkki.ui.utils.StylingHelper`, so it belongs in `:ui` and cannot live in `:data`. The four
 * call sites it had were all inside `Message`: `getOrMakeHtml`, the `Spanned` overloads of `setBody` and
 * `appendBody`, and - the one the pair's brief missed - `Message.updateReplyTo(Message, Spanned)`, which
 * appends through `appendBody(Spanned)` from inside the model. So the methods stay on `Message` and ask
 * through this port instead; moving them up to `:ui` would have broken a caller in `:data` that has no
 * business leaving the model.
 *
 * **The holder fails loudly.** [append] and [isPlainText] throw an [IllegalStateException] naming the port
 * and the install point rather than returning plain text: a silently unstyled XHTML body is a message
 * whose formatting is lost on the wire, which is exactly the kind of invisible wrong answer round 155
 * ruled out. The implementation is `uk.xa0.tulkki.ui.text.SpannedToXHTML`, installed in one line from
 * `uk.xa0.tulkki.app.TulkkiApplication.onCreate` - the same composition point as 8b's `ViewPorts`, pair
 * 10's `EngineHost` and this pair's `PhoneNumberNormalizer`.
 *
 * It is expressible without respelling an edge (round 151's rule for ports, applied to a non-island
 * module): every type in these signatures - `Spanned`, `Element` - is the wire layer's or the JDK's,
 * never `:app`'s or `:ui`'s.
 *
 * **It reads as well as writes.** [Writer.markup] answers the model's plain body with the spans the
 * syntax carries, because the parser is `:ui`'s and the body that reaches `Message` is text with
 * markers in it (`getOrMakeHtml`'s own call site). The writer answers from the one parser it already
 * shares with `StylingHelper.createSpanForStyle`, so the drawn body and the sent body cannot read
 * the syntax differently.
 */
object XhtmlBody {

    /** What `:ui` can do for the model: write markup, say whether it is needed, and read it. */
    interface Writer {

        /** Append `text`'s styling to `out`, and return it. */
        fun append(out: Element, text: Spanned): Element

        /** Whether `text` carries no styling at all, so no markup has to be written. */
        fun isPlainText(text: Spanned): Boolean

        /**
         * The markup a plain body carries, as spans `append` can write: `*bold*`, `_italic_`,
         * `~strike~` and the two code forms.
         *
         * `:data` holds the body as text and cannot read the syntax itself - `ImStyleParser` is
         * `:ui`'s - so a body that arrived as plain text (the Compose composer's draft is a `String`
         * with markers in it) asks here, and `SpannedToXHTML` answers with the one parse it shares
         * with `StylingHelper.createSpanForStyle`. A body that already carries styling is returned
         * as it is, so a caller that decorated it first is not styled twice.
         */
        fun markup(body: CharSequence): Spanned
    }

    @Volatile
    private var installed: Writer? = null

    /** The composition root's one line, from `TulkkiApplication.onCreate`. */
    @JvmStatic
    fun install(writer: Writer) {
        installed = writer
    }

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun append(out: Element, text: Spanned): Element = require().append(out, text)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a runtime state */
    @JvmStatic
    fun isPlainText(text: Spanned): Boolean = require().isPlainText(text)

    /** The plain body's markup, read by the writer that shares the parser with the row. See [Writer.markup]. */
    @JvmStatic
    fun markup(body: CharSequence): Spanned = require().markup(body)

    private fun require(): Writer {
        val writer = installed
        if (writer == null) {
            throw IllegalStateException(
                "uk.xa0.tulkki.data.text.XhtmlBody has no implementation: uk.xa0.tulkki.app.TulkkiApplication" +
                    ".onCreate must call XhtmlBody.install at process start, and it has" +
                    " not",
            )
        }
        return writer
    }
}
