package uk.xa0.tulkki.data.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `blocked_media` — the content-addressed set of files the owner has blocked, one column, one key.
 *
 * <p>The second half of `upload/` (S5-3). `docs/MIGRATION.md`, "Design: the data layer" §2.3 gives
 * this package `UploadFileEntity` (`cids`) and this entity (`blocked_media`), and both are the
 * owner's data rather than a cache: `cids` maps a content hash to a local file, and this set says
 * which of those files must never be rendered.
 *
 * <p>It is one `TEXT NOT NULL PRIMARY KEY` column and nothing else - the table
 * `DatabaseBackend.ensureMessageFileDeletedColumn` has always created, now spelled once in
 * [UploadQueries]. There is no index and no foreign key: a blocked CID is blocked whether or not its
 * file is still in `cids`, and `clearBlockedMedia` empties the table wholesale rather than by key.
 *
 * <p>Storage shape, not model (`AccountEntity`'s comment carries the rule).
 */
@Entity(tableName = "blocked_media")
internal data class BlockedMediaEntity(
    @PrimaryKey @ColumnInfo(name = "cid") val cid: String,
)
