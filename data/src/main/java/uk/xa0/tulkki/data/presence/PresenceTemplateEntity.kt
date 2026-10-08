package uk.xa0.tulkki.data.presence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `presence_templates` as Room validates it (S5-3, the `presence/` capability): one saved status
 * line, its message and its presence status.
 *
 * <p>**The key is a surrogate `_id`, for the reason `ContactEntity`'s comment gives at length.** The
 * file's table has no primary key - only `UNIQUE(message, status) ON CONFLICT REPLACE` - and all
 * four of its columns are nullable, so Room's alternatives were a nullable key (refused outright
 * with `You must annotate primary keys with @NonNull`) or a `NOT NULL` pair that would make the
 * rebuild's copy throw on a legacy row with a null message or status. So the rebuild adds
 * `_id INTEGER NOT NULL PRIMARY KEY`, copies each row's `rowid` into it, leaves the four columns
 * nullable and **keeps the `UNIQUE`**, which is what the live writer's `ContentValues` insert and
 * [PresenceQueries.UPSERT] conflict on. No row is added, removed or made `NOT NULL`.
 */
@Entity(tableName = "presence_templates")
internal data class PresenceTemplateEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "uuid") val uuid: String?,
    @ColumnInfo(name = "last_used") val lastUsed: Long?,
    @ColumnInfo(name = "message") val message: String?,
    @ColumnInfo(name = "status") val status: String?,
)
