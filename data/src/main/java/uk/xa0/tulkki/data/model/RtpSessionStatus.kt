package uk.xa0.tulkki.data.model

/**
 * The outcome of a Jingle RTP session, as the message body carries it: `successful:duration`.
 *
 * <p>The island never reads the two fields, and that is measured: the parser asks
 * `DataStatics.newRtpSessionStatus` for the wire string a message body carries and nothing else of
 * this type crosses the boundary - while
 * `:ui` reads [successful] and [duration] directly to draw the call row. Ported from Java by the `port`
 * stage (port-2), with these decisions:
 *
 * 1. **A plain `class`, not a `data class`.** Java had identity equality, and a `data class` would
 *    synthesise `equals`/`hashCode` this type never had. Nothing in the tree compares two of these
 *    today, but the translation's job is to preserve the semantics rather than improve them, and the
 *    improvement has no test behind it.
 * 2. **The two fields stay fields: `@JvmField val`.** `:ui` reads them as fields
 *    (`CallsActivity:293,310`, `MessagePreview:308`, `UIHelper:308`; the deleted `CallsAdapter:98,107`,
 *    `MessageAdapter:3323,3332,3367` and `UIHelper:275` read them the same way). **Interop debt: two
 *    annotations.**
 * 3. **`of` is a companion `@JvmStatic`.** Its callers are Java (`:ui`'s
 *    `RtpSessionStatus.of(call.getBody())` and `.of(message.getBody())`); without `@JvmStatic` the
 *    method would only exist as `Companion.of`. **Debt: one annotation.**
 * 4. **`of(String?)` keeps Java's parse exactly, and drops one dead `try`.** Java's body is
 *    `Strings.nullToEmpty(body).split(":", 2)`, so a null body becomes `""` and yields
 *    `[""]` - a one-element array, no duration, `false`. Kotlin's `(body ?: "")` and
 *    `split(":", limit = 2)` are the same call with the same result, including the trailing-empty
 *    case (`"true:"` → `["true", ""]` → duration 0, because `""` does not parse). `Long.parseLong`
 *    was caught and turned into `0`; `toLongOrNull() ?: 0L` answers `0` for the same inputs *and* for
 *    an overflow that Java's catch also folded to `0`, so the two agree on every string. The `try`
 *    around `Boolean.parseBoolean` is **deleted because it is dead** - that method never throws, so
 *    Java's catch could not be reached - and `String.toBoolean()` is the same call. The parameter is
 *    `String?` rather than a Kotlin `String` because Java callers pass `Call.getBody()`/`Message
 *    .getBody()` straight through and those can be null: narrowing it would be a new NPE, not a
 *    translation.
 * 5. It stays final (nothing extends it) and its constructor stays public, so `:app`'s
 *    `DataStaticsHost.newRtpSessionStatus` still builds one - it answers the wire string, which is
 *    all the island ever read.
 */
class RtpSessionStatus(
    @JvmField val successful: Boolean,
    @JvmField val duration: Long,
) {

    /** The wire form the parser writes and `of` parses back. */
    override fun toString(): String = "$successful:$duration"

    companion object {

        /** The status a body spells, with Java's answers for a malformed one. */
        @JvmStatic
        fun of(body: String?): RtpSessionStatus {
            val parts = (body ?: "").split(":", limit = 2)
            val duration = if (parts.size == 2) parts[1].toLongOrNull() ?: 0L else 0L
            // `toBoolean()` is `java.lang.Boolean.parseBoolean`: true only for "true", ignoring case.
            // The Java version wrapped this in a try/catch that could never fire; there is no catch to
            // translate, and inventing one would suggest a failure mode this call does not have.
            val successful = parts[0].toBoolean()
            return RtpSessionStatus(successful, duration)
        }
    }
}
