package uk.xa0.tulkki.translation

/**
 * The text a share or a copy may carry, for a surface that leaves the app.
 *
 * <p>The clipboard and the share sheet are the two places the interface stops being able to cover
 * anything: what lands there is readable by every other app, and on API 33 and later it is previewed
 * and kept in the clipboard history. So the same rule the bubble obeys has to be applied here, and
 * applied once - the two call sites (`ShareUtil.copyToClipboard` and `ShareUtil.share`)
 * drifted apart before, and the search result's own quote path was a third copy that leaked the raw
 * body outright.
 *
 * <p>The rule, as a value:
 *
 * <ul>
 *   <li>a covered body is <strong>refused</strong> ([REFUSED]); the caller tells the owner why
 *       instead of writing the original out of the app;
 *   <li>a translation is what is shared - the translation <em>is</em> the message as far as the
 *       interface is concerned;
 *   <li>a body that needed no translation is shared as itself, but <strong>without the reply
 *       fallback it carries</strong>: the quote inside a reply is somebody else's message, and the
 *       fallback is that message in their language. The caller passes the row's text with the
 *       fallback already removed (`Message#getBody(true)`), which is also the text the bubble
 *       would have shown for it (the quote is drawn from the referenced row, never from this body).
 * </ul>
 *
 * <p>Pure Kotlin, so both decisions are JVM-tested without a device. `REFUSED` is a `const val`;
 * the answer is still taken from [DisplayedBody] rather than a second opinion.
 */
object ShareText {

    /** What a share carries for a covered body: nothing, which the caller reports as a refusal. */
    const val REFUSED = ""

    /**
     * The text to hand to the clipboard or the share sheet.
     *
     * @param displayed the row's display decision; `null` is refused rather than guessed at
     * @param originalWithoutFallback the row's own text, reply fallback removed
     *     (`Message#getBody(true)`); used only when nothing was translated
     * @return the text, or [REFUSED] when nothing may leave the app
     */
    @JvmStatic
    fun of(displayed: DisplayedBody?, originalWithoutFallback: String?): String {
        if (displayed == null || displayed.isBlurred()) {
            return REFUSED
        }
        if (displayed.isTranslation()) {
            return displayed.text()
        }
        return originalWithoutFallback ?: REFUSED
    }

    /** Whether the answer means "refused": the caller shows why and writes nothing. */
    @JvmStatic
    fun refused(text: String?): Boolean = text == null || text.isEmpty()
}
