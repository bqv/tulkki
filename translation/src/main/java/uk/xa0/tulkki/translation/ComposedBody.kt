package uk.xa0.tulkki.translation

/**
 * A composed body split into the quote it carries and the text that is the owner's own.
 *
 * <p>A reply's `body` is two different things glued together: a verbatim copy of the message it
 * answers - which is already in the conversation's language and must go on the wire exactly as the
 * other person wrote it - and the reply's own text, which is what Tulkki translates and what
 * `translated_body` is the rendering of. The boundary between them is declared by the message
 * itself: XEP-0461 records the fallback as a `<body start="0" end="n"/>` span inside the
 * `urn:xmpp:reply:0` fallback payload, and upstream already reads that span the same way when
 * it strips the quote back out (`Message.bodyMinusFallbacks`).
 *
 * <p>Both the send path (translate only the owner's own text, keep the quote verbatim, store only
 * the owner's text as the app-language half) and the display path (the bubble shows the owner's own
 * text; the quote is rendered from the referenced row, not from this body) need that one boundary.
 * Putting it in one place is the point: a second copy would drift, and the two halves of a
 * half-translated message are exactly what that drift looks like.
 *
 * <p><strong>The boundary is only ever the declared span.</strong> Nothing here scans for `>`
 * markers, trailing blank lines or any other shape of the text: those are guesses, and a guess puts
 * half a message on each side of a translation. When the span cannot be read the whole body is
 * treated as the owner's own text, which is the safe direction - a reply that is translated together
 * with its quote costs a wasted request, while a reply whose own text was mistaken for a quote would
 * be sent untranslated, and nothing is sent untranslated.
 *
 * <p>Offsets are <strong>code points</strong>, the same unit `Message.bodyMinusFallbacks` uses,
 * so the one declared span means the same range in both places. A span that is missing, empty,
 * unparseable, negative, reversed, out of range or does not start at zero is treated as no span at
 * all. A span covering the whole body is honoured: it declares that there is no reply text, not that
 * something is wrong.
 *
 * <p>Pure Kotlin, no Android and no message entity - the caller passes the body and the two
 * attributes it read from the reply fallback - so it is exercised by JVM unit tests without a
 * device.
 *
 * <p>How the span is read (one recipe, owned by the host rather than reinvented here): the public
 * `Message.getFallbacks("urn:xmpp:reply:0")` returns the fallbacks declared for the reply; the
 * child named `body` of that element carries `start` and `end`; those two strings, or a missing
 * fallback, are what reaches this class as a [ReplySpan]. A fallback declared for something else (a
 * reaction, for instance) must not be read: the namespace it was asked for is what makes it the
 * reply's.
 */
class ComposedBody private constructor(
        private val carriedText: String,
        private val translatableText: String
) {

    companion object {

        /**
         * The whole body is the message's own text: there is no quote to carry. This is also what
         * every unreadable span becomes, so it is the answer that fails toward translating too much
         * rather than too little.
         */
        @JvmStatic
        fun whole(body: String?): ComposedBody = ComposedBody("", body ?: "")

        /**
         * The split, from the reply's own declared fallback span.
         *
         * @param body the composed body as stored or as it is about to be sent; may be `null`
         * @param spanStart the reply fallback's `<body start>` attribute, verbatim, or `null`
         *     when the message declares no reply span
         * @param spanEnd the reply fallback's `<body end>` attribute, verbatim, or `null`
         * @return the carried quote prefix and the translatable remainder, or the whole body as the
         *     translatable part when the span cannot be trusted.
         */
        @JvmStatic
        fun of(body: String?, spanStart: String?, spanEnd: String?): ComposedBody {
            val text = body ?: ""
            val start = codePointOffset(spanStart)
            val end = codePointOffset(spanEnd)
            if (start != 0 || end < 0) {
                // No span, a span that is not a prefix, or one that could not be read: carry nothing.
                return whole(text)
            }
            val codePoints = text.codePointCount(0, text.length)
            if (end <= start || end > codePoints) {
                // Empty, reversed or past the end: the same answer, and the same one upstream
                // reaches when its own span arithmetic overruns.
                return whole(text)
            }
            val charEnd = text.offsetByCodePoints(0, end)
            return ComposedBody(text.substring(0, charEnd), text.substring(charEnd))
        }

        /**
         * The declared offset as a code-point count, or `-1` when it is not a non-negative whole
         * number. Leading and trailing whitespace is accepted because the attribute is XML text;
         * `trim()` here is Java's own (`<= ' '`), as in the Java this replaces.
         */
        private fun codePointOffset(value: String?): Int {
            if (value == null) {
                return -1
            }
            return try {
                val parsed = value.javaTrim().toInt()
                if (parsed < 0) -1 else parsed
            } catch (e: NumberFormatException) {
                -1
            }
        }
    }

    /** The quote this body carries, verbatim. Empty when there is none. Never `null`. */
    fun carried(): String = carriedText

    /** The message's own text: what Tulkki translates, and what a translation is the rendering of. */
    fun translatable(): String = translatableText

    /** True when there is a quote carried in front of the translatable text. */
    fun hasCarried(): Boolean = carriedText.isNotEmpty()

    /**
     * The body as it goes on the wire, given the translation of [translatable]: the quote
     * back in front of it, verbatim and untranslated. A `null` argument counts as empty text.
     */
    fun recompose(translatedRemainder: String?): String =
            carriedText + (translatedRemainder ?: "")

    override fun toString(): String =
            if (carriedText.isEmpty()) {
                "WHOLE(" + translatableText + ")"
            } else {
                "CARRIED(" + carriedText + ") + (" + translatableText + ")"
            }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and this class's Java read its span
 * attributes with Java's `trim()`; the port keeps that, exactly as `ScriptReading` does.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
