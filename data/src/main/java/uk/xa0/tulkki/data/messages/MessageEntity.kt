package uk.xa0.tulkki.data.messages

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `messages` as Room validates it (S5-2b), with the eight indexes the file already carries and the
 * `delivery` marker schema 76 adds.
 *
 * <p>Eleven columns here were declared `NUMBER` on disk - `timeSent`, `encryption`, `status`,
 * `type`, `read`, `oob`, `markable`, `file_deleted`, `deleted`, `expire_at`, `timeReceived`,
 * `notificationDismissed` - and `NUMBER` normalises to `UNDEFINED`, which no entity can emit. That
 * is why schema 76 **rebuilds** the table rather than altering it: an `ALTER` cannot change a
 * declared type.
 *
 * <p>Every index is named: `message_conversation_index`, `message_deleted_index`,
 * `message_expire_at_index`, `message_file_deleted_index`, `message_file_path_index`,
 * `message_time_index`, `message_time_received_index`, `message_type_index`. Room compares the
 * index *names* as well as their columns, and these are the names the file has had since schema 71
 * and earlier, so they are spelled, not generated.
 *
 * <p>`translated_body` is here because it is a column, not because the search index is declared:
 * `messages_index` stays hand-written FTS4 managed by three triggers (`docs/MIGRATION.md`, "Design:
 * the data layer" §2.4), and nothing may read this column to mean "a translation exists" - the
 * state field decides that.
 *
 * <p>Storage shape, not model - see `AccountEntity`'s comment.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["conversationUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "message_conversation_index", value = ["conversationUuid"]),
        Index(name = "message_deleted_index", value = ["deleted"]),
        Index(name = "message_expire_at_index", value = ["expire_at"]),
        Index(name = "message_file_deleted_index", value = ["file_deleted"]),
        Index(name = "message_file_path_index", value = ["relativeFilePath"]),
        Index(name = "message_time_index", value = ["timeSent"]),
        Index(name = "message_time_received_index", value = ["timeReceived"]),
        Index(name = "message_type_index", value = ["type"]),
    ],
)
internal data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "conversationUuid") val conversationUuid: String?,
    @ColumnInfo(name = "timeSent") val timeSent: Long?,
    @ColumnInfo(name = "counterpart") val counterpart: String?,
    @ColumnInfo(name = "trueCounterpart") val trueCounterpart: String?,
    @ColumnInfo(name = "body") val body: String?,
    @ColumnInfo(name = "encryption") val encryption: Long?,
    @ColumnInfo(name = "status") val status: Long?,
    @ColumnInfo(name = "type") val type: Long?,
    @ColumnInfo(name = "relativeFilePath") val relativeFilePath: String?,
    @ColumnInfo(name = "serverMsgId") val serverMsgId: String?,
    @ColumnInfo(name = "axolotl_fingerprint") val axolotlFingerprint: String?,
    @ColumnInfo(name = "carbon") val carbon: Long?,
    @ColumnInfo(name = "edited") val edited: String?,
    @ColumnInfo(name = "read", defaultValue = "1") val read: Long?,
    @ColumnInfo(name = "oob") val oob: Long?,
    @ColumnInfo(name = "errorMsg") val errorMsg: String?,
    @ColumnInfo(name = "readByMarkers") val readByMarkers: String?,
    @ColumnInfo(name = "markable", defaultValue = "0") val markable: Long?,
    @ColumnInfo(name = "file_deleted", defaultValue = "0") val fileDeleted: Long?,
    @ColumnInfo(name = "deleted", defaultValue = "0") val deleted: Long?,
    @ColumnInfo(name = "bodyLanguage") val bodyLanguage: String?,
    @ColumnInfo(name = "retractId") val retractId: String?,
    @ColumnInfo(name = "occupantId") val occupantId: String?,
    @ColumnInfo(name = "occupant_id") val occupantIdSnake: String?,
    @ColumnInfo(name = "reactions") val reactions: String?,
    @ColumnInfo(name = "remoteMsgId") val remoteMsgId: String?,
    @ColumnInfo(name = "ephemeral_timer", defaultValue = "0") val ephemeralTimer: Long?,
    @ColumnInfo(name = "expire_at", defaultValue = "0") val expireAt: Long?,
    @ColumnInfo(name = "translated_body") val translatedBody: String?,
    @ColumnInfo(name = "translation_lang") val translationLang: String?,
    @ColumnInfo(name = "translation_state", defaultValue = "0") val translationState: Long,
    @ColumnInfo(name = "subject") val subject: String?,
    @ColumnInfo(name = "oobUri") val oobUri: String?,
    @ColumnInfo(name = "fileParams") val fileParams: String?,
    @ColumnInfo(name = "payloads") val payloads: String?,
    @ColumnInfo(name = "timeReceived") val timeReceived: Long?,
    @ColumnInfo(name = "notificationDismissed", defaultValue = "0") val notificationDismissed: Long?,
    @ColumnInfo(name = "delivery", defaultValue = "2") val delivery: Long,
)
