package uk.xa0.tulkki.data.roster

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `discovery_results` as Room validates it (S5-3, the `roster/` capability): the cached answer to a
 * service-discovery query, keyed by the `(hash, ver)` pair the file's own `UNIQUE` names.
 *
 * <p>**The key is a surrogate `_id`, for the reason `ContactEntity`'s own comment gives at length.**
 * Room demands a primary key and refuses nullable properties in one; the file's three columns are
 * all nullable and the pair is only an inline `UNIQUE(hash, ver) ON CONFLICT REPLACE`. Declaring the
 * pair as the key with `NOT NULL` columns would make the rebuild's copy **throw** on a single legacy
 * row whose `hash` or `ver` is null - and SQLite's `UNIQUE` treats nulls as distinct, so such rows
 * are exactly the ones the old table could hold. So the rebuild adds `_id INTEGER NOT NULL PRIMARY
 * KEY`, copies each row's `rowid` into it and **keeps the `UNIQUE`**, which is what
 * `RosterDao.saveDiscoveryResult`'s `INSERT OR REPLACE` conflicts on. Nothing becomes `NOT NULL`,
 * no row is added or removed, and duplicate pairs remain as impossible as the `UNIQUE` already made
 * them.
 */
@Entity(tableName = "discovery_results")
internal data class DiscoveryResultEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "hash") val hash: String?,
    @ColumnInfo(name = "ver") val ver: String?,
    @ColumnInfo(name = "result") val result: String?,
)
