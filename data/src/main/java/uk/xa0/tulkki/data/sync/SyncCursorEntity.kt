package uk.xa0.tulkki.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `sync_cursor` — one row per account: the paging anchor the sync engine asks the server from.
 *
 * <p>It replaces the one global `long` watermark (`docs/MIGRATION.md`, "Design: synchronisation"
 * §0-§1), so `account_uuid` is the key and there is no second index: every query this package
 * publishes goes through it or through the gap ledger.
 *
 * <p>**The primary key is `TEXT NOT NULL PRIMARY KEY`, not the `TEXT PRIMARY KEY` the design
 * spells**, and the reason is Room rather than taste: `RoomConnectionManager.onMigrate` validates
 * every declared entity against the file right after the 75 -> 77 migrations, comparing each
 * column's `notNull` flag unconditionally, and SQLite reports `notnull = 0` for a bare
 * `TEXT PRIMARY KEY`. `SyncQueries` carries the measurement.
 *
 * <p>`anchor_time == 0` is the engine's `initial()` and means "no gap, and none will be invented";
 * `anchor_source`'s vocabulary lives in [SyncQueries]. `updated_at` is diagnostics only and is
 * never an input to a decision ("Design: synchronisation" §1.1).
 *
 * <p>The foreign key is the same `ON DELETE CASCADE` every per-account table carries, so a cursor
 * row can never outlive the account it describes.
 *
 * <p>Storage shape, not model: the entity is `internal` and the island keeps its own types
 * (`AccountEntity`'s comment).
 */
@Entity(
    tableName = "sync_cursor",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SyncCursorEntity(
    @PrimaryKey @ColumnInfo(name = "account_uuid") val accountUuid: String,
    @ColumnInfo(name = "anchor_stanza_id") val anchorStanzaId: String?,
    @ColumnInfo(name = "anchor_time", defaultValue = "0") val anchorTime: Long,
    @ColumnInfo(name = "anchor_source", defaultValue = "0") val anchorSource: Long,
    @ColumnInfo(name = "gap_end", defaultValue = "0") val gapEnd: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
