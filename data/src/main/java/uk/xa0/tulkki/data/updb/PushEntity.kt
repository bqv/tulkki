package uk.xa0.tulkki.data.updb

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `push` - the UnifiedPush distributor's registrations, in their own Room database (S5-3).
 *
 * <p>Storage shape, not model: the table is the app's oldest surviving one, it lives in a *second*
 * file with its own key material (`docs/MIGRATION.md`, "Design: the data layer" §2.6), and the
 * island never sees it - `UnifiedPushDatabase.PushTarget` is the public face.
 *
 * <p><strong>Why the entity declares the index the table already carries.</strong> The file's own
 * `UNIQUE(instance)` is a table constraint, and an entity cannot emit one - Room spells a unique
 * constraint as `@Index(unique = true)`, which becomes a separate `CREATE UNIQUE INDEX`. Declaring
 * it here is what gives a *fresh install* the guarantee at all; keeping the constraint in the
 * rebuild of an existing file is [UpdbQueries.TAIL]'s job. Both are needed and neither is
 * redundant: Room compares only indices whose `PRAGMA index_list` origin is `c`, so the legacy
 * autoindex (`origin = u`) is invisible to validation and would not satisfy this declaration on an
 * adopted file.
 *
 * <p><strong>Every nullable column is nullable here.</strong> The legacy table allowed null
 * `account`, `transport`, `endpoint` and `expiration`, and [LegacyPreflight] may not make a row the
 * file already holds illegal; `application` and `instance` keep the `NOT NULL` they always had.
 * `_id` is the surrogate key the rebuild adds, carried from the legacy row's `rowid`, because Room
 * needs a key it can name and `INSTANCE` is `UNIQUE` rather than a primary key.
 *
 * <p>`expiration` keeps its `DEFAULT 0` and its `INTEGER` affinity: the file spelled it `NUMBER`,
 * which is the whole reason this table is rebuilt rather than adopted where it stands.
 */
@Entity(
    tableName = UpdbQueries.TABLE,
    indices = [Index(value = [UpdbQueries.INSTANCE], unique = true)],
)
internal data class PushEntity(
    @PrimaryKey @ColumnInfo(name = UpdbQueries.ID) val id: Long,
    @ColumnInfo(name = UpdbQueries.ACCOUNT) val account: String?,
    @ColumnInfo(name = UpdbQueries.TRANSPORT) val transport: String?,
    @ColumnInfo(name = UpdbQueries.APPLICATION) val application: String,
    @ColumnInfo(name = UpdbQueries.INSTANCE) val instance: String,
    @ColumnInfo(name = UpdbQueries.ENDPOINT) val endpoint: String?,
    @ColumnInfo(name = UpdbQueries.EXPIRATION, defaultValue = "0") val expiration: Long?,
)
