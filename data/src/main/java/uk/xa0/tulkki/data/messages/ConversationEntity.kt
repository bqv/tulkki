package uk.xa0.tulkki.data.messages

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `conversations` as Room validates it (S5-2b).
 *
 * <p>`uuid`, `contactUuid`, `accountUuid`, `contactJid`, `created`, `status`, `mode` and
 * `attributes` are the upstream table; `detected_language` and `language_override` are Tulkki's,
 * added by guarded `ALTER`s in schema 71 and part of 75, and `doubt_hold` is Tulkki's from schema
 * 79. It is a **tri-state**: `NULL` means this conversation never chose, `0` means the doubt-hold is
 * off here and `1` means it is explicitly on - the shape `language_override` already uses, so a
 * future change to the shipped default moves only the conversations that never decided. The default
 * itself is resolved by the rule that consults it, never stored here. `created`, `status` and `mode` were
 * declared `NUMBER` on disk, which Room normalises to `UNDEFINED` and no entity can emit - hence
 * the rebuild (`Schema76`).
 *
 * <p>The foreign key to `accounts` is the reason `AccountEntity` exists, and it is load-bearing in
 * the other direction too: `DatabaseBackend.deleteAccount` deletes one `accounts` row and relies on
 * this `ON DELETE CASCADE` to take the account's conversations, and their messages, with it.
 *
 * <p>Storage shape, not model - see `AccountEntity`'s comment.
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["accountUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationEntity(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "contactUuid") val contactUuid: String?,
    @ColumnInfo(name = "accountUuid") val accountUuid: String?,
    @ColumnInfo(name = "contactJid") val contactJid: String?,
    @ColumnInfo(name = "created") val created: Long?,
    @ColumnInfo(name = "status") val status: Long?,
    @ColumnInfo(name = "mode") val mode: Long?,
    @ColumnInfo(name = "attributes") val attributes: String?,
    @ColumnInfo(name = "detected_language") val detectedLanguage: String?,
    @ColumnInfo(name = "language_override") val languageOverride: String?,
    @ColumnInfo(name = "doubt_hold") val doubtHold: Int?,
)
