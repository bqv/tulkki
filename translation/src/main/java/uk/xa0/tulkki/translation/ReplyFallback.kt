package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

/**
 * Where a message's quoted fallback is, read from the span the sender declared.
 *
 * <p>XEP-0461 records the quote as a `<body start="0" end="n"/>` span inside the
 * `urn:xmpp:reply:0` fallback, and upstream reads that span the same way when it takes the
 * quote back out of a body (`Message.bodyMinusFallbacks`). This is that read, in one place:
 * the send path has to know which part of a reply's body is the quote it carries and which part is
 * the owner's own text, and a second copy of the arithmetic is a second place for it to be wrong.
 *
 * <p><strong>The read is split across the boundary on purpose.</strong> Walking the payload tree -
 * `Message.getFallbacks("urn:xmpp:reply:0")`, the child named `body`, its two attributes
 * - is `uk.xa0.tulkki.xml.Element` work, and this module may not name an island type,
 * so the host does it ([EngineHost.replySpan]) and hands the answer back as a
 * [ReplySpan]: two strings and a flag. Nothing below that line knows what an element is.
 *
 * <p>Nothing here looks at the text either. A span that is missing, malformed, reversed or out of
 * range leaves the whole body as the message's own text through [ComposedBody] - the direction
 * that costs a wasted translation rather than sending half a message untranslated - and
 * [declared] is what tells a caller the difference between "there is no quote here" and
 * "there is one and it cannot be placed", which is the case that must be covered rather than shown.
 *
 * <p>Pure Kotlin over the body and the two attributes - no Android, no database, no island - so the
 * JVM tests put a span through the same read the send path uses. `getBody()` is a Kotlin-declared
 * getter, so it stays a call.
 */
object ReplyFallback {

    /**
     * The composed body of a message: the quote it carries, verbatim, and the text that is its own.
     *
     * @param message the message; may be `null`, which is all text
     * @param host where the message's declared span is read from; may be `null`
     */
    @JvmStatic
    fun composedBody(message: Message?, host: EngineHost?): ComposedBody =
            of(
                    message?.getBody(),
                    if (message == null || host == null) ReplySpan.NONE
                    else host.replySpan(message))

    /**
     * The same split, for a caller that already holds the body text and the declared span - which is
     * also the form the JVM tests use, because `Message.getBody` cannot run without a device.
     *
     * @param body the composed body; may be `null`
     * @param span the declared reply fallback; `null` is treated as [ReplySpan.NONE]
     */
    @JvmStatic
    fun of(body: String?, span: ReplySpan?): ComposedBody =
            ComposedBody.of(body, span?.start(), span?.end())

    /**
     * True when the message declares a reply fallback at all. A fallback whose span cannot be read is
     * still declared: [of] then treats the whole body as the message's own text, and a caller
     * that must not show a fallback it cannot place asks this to tell the two cases apart.
     */
    @JvmStatic fun declared(span: ReplySpan?): Boolean = span != null && span.declared()
}
