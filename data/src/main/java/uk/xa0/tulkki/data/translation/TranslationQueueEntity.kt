package uk.xa0.tulkki.data.translation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.messages.MessageEntity

/**
 * `translation_queue` as an entity (schema 78, S5-6).
 *
 * <p>**Why it could not be declared before 78.** Room validates a declared entity by comparing each
 * column's normalised affinity, not-null flag, default and primary key against the file, and it
 * validates **before** it runs any `RoomDatabase.Callback`. The two builds that carried the S5-12
 * rekey left files whose queue table had no foreign key - and, for one of them, no primary key at all
 * (`Schema77.QUEUE_COLUMNS` records how the missing key was found: declaring this entity is what threw
 * it). Declaring it at 77 would have refused those files at launch with no migration to repair them;
 * declaring it here is safe because `MIGRATION_77_78` runs the guarded repair *inside a migration*,
 * which is a step Room takes before validation.
 *
 * <p>**The declaration is the file's own shape, column for column and in the file's order**, because
 * `FreshInstallSchemaTest` and `RoomValidationTest` compare this entity against the migrated file's
 * `PRAGMA table_info` through Room's validator rather than trusting that a Kotlin type and a DDL
 * string agree. The primary key is `message_uuid`; the foreign key is the `ON DELETE CASCADE` S5-12
 * added, which is what stops a deleted message's queue row - and with it its raw `body`, a phantom
 * failures row and a paid retry on a dead uuid - outliving its message.
 *
 * <p>The index is declared rather than left to `TranslationTables.CREATE_QUEUE_INDEX` alone so that a
 * fresh install (Room's generated `createAllTables`) and an owner's migrated file carry the same
 * index; both statements are `IF NOT EXISTS`, so whichever runs second is a no-op.
 *
 * <p>`failed_at` is schema 73's column and is nullable on purpose: a row written before that
 * migration has no failure time, which the failures screen says rather than passing the arrival off
 * as the failure.
 */
@Entity(
    tableName = TranslationTables.QUEUE_TABLE,
    primaryKeys = [TranslationTables.QUEUE_MESSAGE_UUID],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["uuid"],
            childColumns = [TranslationTables.QUEUE_MESSAGE_UUID],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "translation_queue_due",
            value = [TranslationTables.QUEUE_STATE, TranslationTables.QUEUE_NEXT_ATTEMPT_AT],
        ),
    ],
)
internal data class TranslationQueueEntity(
    @ColumnInfo(name = TranslationTables.QUEUE_MESSAGE_UUID) val messageUuid: String,
    @ColumnInfo(name = TranslationTables.QUEUE_CONVERSATION_UUID)
    val conversationUuid: String?,
    @ColumnInfo(name = TranslationTables.QUEUE_BODY) val body: String,
    @ColumnInfo(name = TranslationTables.QUEUE_TARGET_LANGUAGE) val targetLanguage: String?,
    @ColumnInfo(name = TranslationTables.QUEUE_CACHE_KEY) val cacheKey: String,
    @ColumnInfo(name = TranslationTables.QUEUE_STATE, defaultValue = "0") val state: Int,
    @ColumnInfo(name = TranslationTables.QUEUE_ATTEMPTS, defaultValue = "0") val attempts: Int,
    @ColumnInfo(name = TranslationTables.QUEUE_NEXT_ATTEMPT_AT, defaultValue = "0")
    val nextAttemptAt: Long,
    @ColumnInfo(name = TranslationTables.QUEUE_LAST_ERROR) val lastError: String?,
    @ColumnInfo(name = TranslationTables.QUEUE_CREATED_AT) val createdAt: Long,
    @ColumnInfo(name = TranslationTables.QUEUE_FAILED_AT) val failedAt: Long?,
    @ColumnInfo(name = TranslationTables.QUEUE_FAILURE_CAUSE) val failureCause: String?,
)
