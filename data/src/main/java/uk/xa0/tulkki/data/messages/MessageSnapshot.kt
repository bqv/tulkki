package uk.xa0.tulkki.data.messages

/**
 * One `messages` row as `:ui` may see it (S5-6): immutable, public, and the entity's field set with
 * the two stored strings moved into [bodies].
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §2.7 names this type - "Immutable, `public`
 * read-model types - `ConversationSnapshot`, `MessageSnapshot`, `ConversationBodies`" - and makes
 * the entity itself `internal`, so this is the only spelling of the row a screen or a projector can
 * hold. Its fields are the row's ids and scalars, carried at the width and the nullability the file
 * has: nothing here reinterprets a stored `NUMBER` into a `Boolean` or an enum, because the meaning
 * of those columns is `Message`'s and `MessageProjection`'s, and a snapshot that decided one would
 * be a second place for the decision to drift.
 *
 * <p>**Nothing here decides concealment.** [bodies] carries the original because the projector in
 * `:ui` needs it to build the smear; whether it is ever drawn is `SecondHalf`'s and
 * `DisplayedBody`'s decision, made over that module's settings, and no field of this type records
 * it.
 *
 * <p>**`toString()` prints ids and shapes only.** A generated `data class` `toString()` would put
 * both bodies into every log line, crash report and saved-state bundle - the leak `Compose UI` §2.3
 * invariant 1 is written against - so it is overridden here and pinned by a test.
 */
data class MessageSnapshot(
    /** The local uuid: authoritative for row identity, and the LazyColumn key (`Compose UI` §2.3.6). */
    val id: String,
    /** The conversation this row belongs to, as the file holds it. */
    val conversationId: String?,
    /** The stanza's own instant, milliseconds since the epoch. */
    val timeSent: Long?,
    /** Who sent it, as the row records it. */
    val counterpart: String?,
    /** The real sender behind a carbon, when there is one. */
    val trueCounterpart: String?,
    /** `Message.TYPE_*`. */
    val type: Long?,
    /** `Message.STATUS_*`: the send/receive lifecycle the bubble draws. */
    val status: Long?,
    /** `Message.ENCRYPTION_*`. */
    val encryption: Long?,
    /** The schema-76 delivery marker: live, archive or unknown. */
    val delivery: Long,
    /** The read flag, `messages.read`. */
    val read: Long?,
    /** The soft-delete flag, `messages.deleted`: the row is a tombstone. */
    val deleted: Long?,
    /** `messages.file_deleted`: the attachment is gone, the row is not. */
    val fileDeleted: Long?,
    /** The markable flag, `messages.markable`. */
    val markable: Long?,
    /** The out-of-band flag, `messages.oob`. */
    val oob: Long?,
    /** A carbon copy: the row is somebody else's message the account also receives. */
    val carbon: Long?,
    /** The retraction target, when this row retracts another. */
    val retractId: String?,
    /** The edited stamp, when this row replaces an earlier one. */
    val edited: String?,
    /** The wire ids, secondary to [id] and never the row's identity. */
    val serverMsgId: String?,
    val remoteMsgId: String?,
    /** The OMEMO fingerprint the row was written with; the model's cursor reader takes it. */
    val axolotlFingerprint: String?,
    /** The MUC occupant, on a group row. */
    val occupantId: String?,
    /** The attachment's path inside the app's files directory, or null. */
    val relativeFilePath: String?,
    /** The transfer's own parameters, as the store serialises them. */
    val fileParams: String?,
    /** The OOB URI a preview is built from. */
    val oobUri: String?,
    /** A failure reason already worded for the row, or null. */
    val errorMsg: String?,
    /** The language the original body was detected in, when it was recorded. */
    val bodyLanguage: String?,
    /** The row's reactions, as the store serialises them. */
    val reactions: String?,
    /** The receipt markers, as the store serialises them. */
    val readByMarkers: String?,
    /** `Message.TRANSLATION_*`: none, done, same language, failed. */
    val translationState: Long,
    /** The language the stored translation was written into, or null. */
    val translationLang: String?,
    /** When the row was received, as distinct from when it was sent. */
    val timeReceived: Long?,
    /** The subject line, on a row that carries one. */
    val subject: String?,
    /** The ephemeral expiry instant, or 0 when the row does not expire. */
    val expireAt: Long?,
    /** The ephemeral timer, in seconds, or 0. */
    val ephemeralTimer: Long?,
    /** Whether the notification for this row was dismissed. */
    val notificationDismissed: Long?,
    /** The two stored strings, in the one type that holds them. */
    val bodies: ConversationBodies,
) {

    /**
     * Ids and shapes only. `docs/MIGRATION.md`, "Design: the data layer" §2.7 pins this: a snapshot's
     * `toString()` prints no body, "exactly as `UiMessage` does"; `Compose UI` §2.3 invariant 1(b) is
     * the matching cell on the `Ui*` side.
     */
    override fun toString(): String =
        "MessageSnapshot(id=$id, conversationId=$conversationId, type=$type, bodies=(${bodies}))"
}
