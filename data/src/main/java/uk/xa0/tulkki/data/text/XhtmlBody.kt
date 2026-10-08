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
 */
object XhtmlBody {

    /** What `:ui` can do for the model: write markup, and say whether it is needed. */
    interface Writer {

        /** Append `text`'s styling to `out`, and return it. */
        fun append(out: Element, text: Spanned): Element

        /** Whether `text` carries no styling at all, so no markup has to be written. */
        fun isPlainText(text: Spanned): Boolean
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
