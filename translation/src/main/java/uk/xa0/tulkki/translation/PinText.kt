package uk.xa0.tulkki.translation

/**
 * What a pin stores, and whether there is anything to pin at all.
 *
 * <p>A pin is a message surfaced a second time: it is drawn at the head of the conversation and it is
 * kept in its own table (`pinned_messages`), which makes it a display surface like any other -
 * and one that outlives the bubble, so it must obey the same rule the bubble obeys. Upstream's pin
 * path reads the row's own text and stores it, which puts a contact's original on screen and on disk
 * with no cover and no tap; the audit left it open and this is the decision that closes it.
 *
 * <p>The answer is taken from [DisplayedBody], not from a second opinion, so a pin can only
 * ever store the translation, the original when nothing needed translating (a link, a code, a ping,
 * text already in the app language), or [NOTHING] when the body is covered - "needed a
 * translation and did not get one". A covered body has nothing to pin, and the caller says so rather
 * than storing the raw text: a pin the owner cannot read is worse than a refusal.
 *
 * <p>Pure Kotlin - the decision arrives as a value, so this is exercised by JVM tests without a
 * device, exactly like [DisplayedBody] and [ReplyQuote]. `NOTHING` is a `const val`, and
 * `nothingToPin`'s blank test uses Java's own `trim()` (`<= ' '`).
 */
object PinText {

    /** What a pin stores for a covered body: nothing at all. */
    const val NOTHING = ""

    /**
     * The text a pin may store.
     *
     * @param displayed the row's display decision; `null` stores nothing rather than guessing
     * @return the text to store, or [NOTHING] when the row is covered
     */
    @JvmStatic
    fun of(displayed: DisplayedBody?): String =
            if (displayed == null || displayed.isBlurred()) NOTHING else displayed.text()

    /**
     * Whether there is anything behind a pin at all: text that may be stored, or media to show. A
     * covered message has neither, and the caller refuses the pin instead of storing a cover's
     * caption as if it were the message.
     */
    @JvmStatic
    fun nothingToPin(text: String?, hasMedia: Boolean): Boolean =
            !hasMedia && (text == null || text.javaTrim().isEmpty())
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
