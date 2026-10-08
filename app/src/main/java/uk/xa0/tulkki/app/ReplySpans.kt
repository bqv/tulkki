package uk.xa0.tulkki.app

import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.ReplySpan

/**
 * The reply fallback a message declares, read out of its payload tree as plain text.
 *
 * <p>XEP-0461 records a reply's quote as a {@code <body start="0" end="n"/>} span inside the
 * {@code urn:xmpp:reply:0} fallback payload. Walking that tree is {@code Element} work, and the
 * engine that needs the answer may not name an island type - so it happens here, on this side of the
 * boundary, and what crosses back is a [ReplySpan]: the two attributes and whether the fallback is
 * there at all.
 *
 * <p>The namespace is the whole of the filter. A {@code urn:xmpp:fallback:0} element declared for a
 * reaction lives in the same shape and is not a reply, so the reply's own namespace is what is asked
 * for ({@code Message.getFallbacks}); upstream writes and strips the reply's fallback by exactly that
 * name.
 *
 * <p>The three answers map to [ReplySpan]'s three states, and the middle one is why the read is not
 * a pair of strings: a reply fallback with no {@code body} child, or one whose attributes are
 * missing, is <em>declared</em> - the caller must be able to tell that apart from "no quote here",
 * because it must not show a quote it cannot place.
 */
class ReplySpans private constructor() {

    companion object {

        /** The namespace a reply's fallback is declared in. */
        private const val REPLY = "urn:xmpp:reply:0"

        /**
         * The declared reply span of [message], or [ReplySpan.NONE] when there is none.
         *
         * <p>`@JvmStatic` keeps the Java spelling: `XmppTulkkiHost`'s adapter and
         * `ReplySpanReadTest` both call `ReplySpans.of(...)`, and a companion member is not
         * reachable as a static without it.
         */
        @JvmStatic
        fun of(message: Message?): ReplySpan {
            if (message == null) {
                return ReplySpan.NONE
            }
            val fallbacks = message.getFallbacks(REPLY)
            for (fallback in fallbacks) {
                for (child in fallback.children) {
                    if ("body" == child.name) {
                        return ReplySpan.of(child.getAttribute("start"), child.getAttribute("end"))
                    }
                }
            }
            // Declared and unplaceable, or not declared at all: the two answers a caller must not
            // confuse.
            return if (fallbacks.isEmpty()) ReplySpan.NONE else ReplySpan.of(null, null)
        }
    }
}
