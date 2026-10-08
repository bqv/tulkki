package uk.xa0.tulkki.translation

/**
 * The span a reply's declared fallback carries, as plain text: the two attributes and whether the
 * fallback is there at all.
 *
 * <p>It exists because reading that span is an island's job and deciding what it means is this
 * module's. The fallback is a `urn:xmpp:reply:0` payload inside the message's own payload tree,
 * and its `body` child carries `start` and `end`; both are read out by the host
 * ([EngineHost.replySpan]) and handed here as strings, so nothing in this module names
 * `uk.xa0.tulkki.xml.Element` or `Message.getFallbacks`.
 *
 * <p>The three states are the whole of the protocol, and the difference between the last two is
 * load-bearing: a message with <em>no</em> reply fallback is all its own text, while a fallback whose
 * span could not be read is still <em>declared</em> - [declared] is true and both attributes
 * are `null` - which is the case a caller has to be able to tell apart, because it must not
 * show a quote it cannot place. [ComposedBody.of] treats both the same way (the whole body is
 * the owner's own text), failing toward translating too much rather than sending half a message
 * untranslated.
 *
 * <p>The three members stay methods, not properties: their Java names are the API the Java callers
 * (`EngineHost`, `ReplyFallback` and `:app`'s host) already call, and Kotlin's synthetic-property
 * rule only applies to Java-declared getters anyway.
 */
class ReplySpan private constructor(
        private val isDeclared: Boolean,
        private val startText: String?,
        private val endText: String?
) {

    companion object {
        /** No reply fallback at all: the message is all its own text. */
        @JvmField val NONE = ReplySpan(false, null, null)

        /**
         * A declared reply fallback, with the span exactly as it was read.
         *
         * <p>Either side may be `null` - a fallback with no `body` child, or one whose
         * attributes are missing - and that is still a declared fallback.
         */
        @JvmStatic
        fun of(start: String?, end: String?): ReplySpan = ReplySpan(true, start, end)
    }

    /** True when the message declares a `urn:xmpp:reply:0` fallback, readable or not. */
    fun declared(): Boolean = isDeclared

    /** The declared `start` attribute, or `null`. */
    fun start(): String? = startText

    /** The declared `end` attribute, or `null`. */
    fun end(): String? = endText

    override fun toString(): String =
            if (isDeclared) "declared[" + startText + "," + endText + ")" else "none"
}
