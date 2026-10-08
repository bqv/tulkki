package uk.xa0.tulkki.data.messages

/**
 * The two stored strings of one `messages` row, held in a type of their own (S5-6).
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §2.7 is the rule this type exists for: the read
 * models "carry ids, scalars and the two body Strings, because the concealment decision is
 * `MessageProjection.of(snapshot, context, settings, perProcess)`'s and is made in `:ui` - the
 * projector needs the original to be able to smear it. What must not happen is a body appearing in
 * a `Ui*` type ... or in a `snapshot.toString()`".
 *
 * <p>So the two strings travel together and nowhere else: [translated] is the app-language side a
 * translated row was written with, [original] is the text as it arrived or was typed, and every
 * other field of [MessageSnapshot] is an id or a scalar. Neither is a `String` property of a `Ui*`
 * type, and neither is printed by either `toString()` - see [MessageSnapshot.toString].
 *
 * <p>Either may be null and both are nullable for the same reason: a language-less body (a link, a
 * ping, a number) needed no translation, so `translated_body` is null on a row that is shown as it
 * arrived, and a row Room has never had translated has a null translation too. The state field, not
 * the null, is what says whether a translation exists (`MessagesQueries.TRANSLATION_STATE`).
 */
data class ConversationBodies(
    val translated: String?,
    val original: String?,
) {

    /** True when the row holds text at all; the shape only, never the text. */
    val present: Boolean
        get() = translated != null || original != null

    /**
     * Presence, and never a body. `docs/MIGRATION.md`, "Design: the data layer" §2.7: the snapshot's
     * `toString()` "prints ids and shapes only"; a `data class` would otherwise print both strings
     * into every log line, saved-state bundle and crash report.
     */
    override fun toString(): String =
        "ConversationBodies(translated=${translated != null}, original=${original != null})"
}
