package uk.xa0.tulkki.data.model

import android.database.Cursor
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.libs.Jid

/**
 * The one `Message` subclass: a message that is not part of a linked list.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **Java's two private constructors stay two private secondary constructors.** The one-argument
 *    one initialises `Message`'s primary constructor - the defaults Java left - and the row reader
 *    initialises its big secondary constructor with the same 32 arguments, `timeSent` passed twice;
 *    routing the first through the big one would set fields Java left at their defaults.
 * 2. **`createDateSeparator` sets the body through a private instance helper.** `Message.body` is a
 *    `@JvmField protected` field and `Message`'s own KDoc records this subclass as the Java reason it
 *    is one; Kotlin cannot reach an inherited `protected` member from a companion object, so the
 *    three assignments sit in [markDateSeparator] on an `IndividualMessage` receiver - the same field
 *    write. `setBody` was **not** used: it also drops an HTML payload, which Java's field assignment
 *    did not.
 * 3. **`fromSnapshot` and `createDateSeparator` are `@JvmStatic`** (`MessageSearchTask.java:130`,
 *    `DateSeparator.java:44`); `fromCursor` has no caller left - its one consumer was ported to
 *    `fromSnapshot` - but keeps the Java surface. **Interop debt: three `@JvmStatic`s.**
 * 4. **The nullability is Java's.** `conversation` is `Conversational?` (`Message`'s own signature),
 *    `readByMarkers` a `Set<ReadByMarker>?` (`ReadByMarker.fromJsonString` answers a non-null `Set`
 *    into the nullable slot), `reactions` a `Collection<Reaction>` (`Reaction.fromString`'s answer),
 *    and [orZero] keeps `Cursor.getLong`/`getInt`'s `0` for a stored `null` number.
 * 5. **The two exception guards are Java's, branch for branch.** An unparsable counterpart is a null
 *    JID; `fromCursor`'s `IllegalStateException` (a too-long message) makes the whole row `null`,
 *    while `fromSnapshot` keeps that guard on the counterpart read and not on the true-counterpart
 *    read, exactly as Java wrote them.
 */
class IndividualMessage : Message {

    private constructor(conversation: Conversational?) : super(conversation)

    private constructor(
        conversation: Conversational?,
        uuid: String?,
        conversationUUid: String?,
        counterpart: Jid?,
        trueCounterpart: Jid?,
        body: String?,
        timeSent: Long,
        encryption: Int,
        status: Int,
        type: Int,
        carbon: Boolean,
        remoteMsgId: String?,
        relativeFilePath: String?,
        serverMsgId: String?,
        fingerprint: String?,
        read: Boolean,
        edited: String?,
        oob: Boolean,
        errorMessage: String?,
        readByMarkers: Set<ReadByMarker>?,
        markable: Boolean,
        deleted: Boolean,
        bodyLanguage: String?,
        occupantId: String?,
        reactions: Collection<Reaction>,
        retractId: String?,
        ephemeralTimer: Int,
        expireAt: Long,
    ) : super(
        conversation,
        uuid,
        conversationUUid,
        counterpart,
        trueCounterpart,
        body,
        timeSent,
        encryption,
        status,
        type,
        carbon,
        remoteMsgId,
        relativeFilePath,
        serverMsgId,
        fingerprint,
        read,
        edited,
        oob,
        errorMessage,
        readByMarkers,
        markable,
        deleted,
        bodyLanguage,
        occupantId,
        reactions,
        timeSent,
        null,
        null,
        null,
        retractId,
        ephemeralTimer,
        expireAt,
    )

    override fun next(): Message? = null

    override fun prev(): Message? = null

    override fun isValidInSession(): Boolean = true

    private fun markDateSeparator(time: Long) {
        setType(Message.TYPE_STATUS)
        body = Message.DATE_SEPARATOR_BODY
        setTime(time)
    }

    companion object {

        @JvmStatic
        fun createDateSeparator(message: Message): Message {
            val separator = IndividualMessage(message.getConversation())
            separator.markDateSeparator(message.getTimeSent())
            return separator
        }

        @JvmStatic
        fun fromCursor(cursor: Cursor, conversation: Conversational?): Message? {
            val jid: Jid? =
                try {
                    val value = cursor.getString(cursor.getColumnIndexOrThrow(Message.COUNTERPART))
                    if (value != null) Jid.of(value) else null
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: IllegalStateException) {
                    return null // message too long?
                }
            val trueCounterpart: Jid? =
                try {
                    val value = cursor.getString(cursor.getColumnIndexOrThrow(Message.TRUE_COUNTERPART))
                    if (value != null) Jid.of(value) else null
                } catch (e: IllegalArgumentException) {
                    null
                }
            return IndividualMessage(
                conversation,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.UUID)),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.CONVERSATION)),
                jid,
                trueCounterpart,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.BODY)),
                cursor.getLong(cursor.getColumnIndexOrThrow(Message.TIME_SENT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.ENCRYPTION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.TYPE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.CARBON)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.REMOTE_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.RELATIVE_FILE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.SERVER_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.FINGERPRINT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.READ)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.EDITED)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.OOB)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.ERROR_MESSAGE)),
                ReadByMarker.fromJsonString(cursor.getString(cursor.getColumnIndexOrThrow(Message.READ_BY_MARKERS))),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.MARKABLE)) > 0,
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.DELETED)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(Message.BODY_LANGUAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.OCCUPANT_ID)),
                Reaction.fromString(cursor.getString(cursor.getColumnIndexOrThrow(Message.REACTIONS))),
                cursor.getString(cursor.getColumnIndexOrThrow(Message.RETRACT_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Message.EPHEMERAL_TIMER)),
                cursor.getLong(cursor.getColumnIndexOrThrow(Message.EXPIRE_AT)),
            )
        }

        @JvmStatic
        fun fromSnapshot(snapshot: MessageSnapshot, conversation: Conversational?): Message? {
            val jid: Jid? =
                try {
                    val value = snapshot.counterpart
                    if (value == null) null else Jid.of(value)
                } catch (e: IllegalArgumentException) {
                    null
                } catch (e: IllegalStateException) {
                    return null // message too long, as fromCursor reads the same failure
                }
            val trueCounterpart: Jid? =
                try {
                    val value = snapshot.trueCounterpart
                    if (value == null) null else Jid.of(value)
                } catch (e: IllegalArgumentException) {
                    null
                }
            return IndividualMessage(
                conversation,
                snapshot.id,
                snapshot.conversationId,
                jid,
                trueCounterpart,
                snapshot.bodies.original,
                orZero(snapshot.timeSent),
                orZero(snapshot.encryption).toInt(),
                orZero(snapshot.status).toInt(),
                orZero(snapshot.type).toInt(),
                orZero(snapshot.carbon) > 0,
                snapshot.remoteMsgId,
                snapshot.relativeFilePath,
                snapshot.serverMsgId,
                snapshot.axolotlFingerprint,
                orZero(snapshot.read) > 0,
                snapshot.edited,
                orZero(snapshot.oob) > 0,
                snapshot.errorMsg,
                ReadByMarker.fromJsonString(snapshot.readByMarkers),
                orZero(snapshot.markable) > 0,
                orZero(snapshot.deleted) > 0,
                snapshot.bodyLanguage,
                snapshot.occupantId,
                Reaction.fromString(snapshot.reactions),
                snapshot.retractId,
                orZero(snapshot.ephemeralTimer).toInt(),
                orZero(snapshot.expireAt),
            )
        }

        /** A nullable stored number as the cursor reader's `getLong`/`getInt` answer it: `0` when null. */
        private fun orZero(value: Long?): Long = value ?: 0L
    }
}
