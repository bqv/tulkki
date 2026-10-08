package uk.xa0.tulkki.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `sync_gap` — the ledger: one row per catch-up region the engine opened.
 *
 * <p>An episode table, not a column on the cursor: several regions can be open at once (the
 * account-wide query, one per single conversation, a MUC join's own catch-up, the REVERSE region),
 * and the whole point is that "is it complete" survives the reader thread, the socket and the
 * process ("Design: synchronisation" §1.1).
 *
 * <p>**`conversation_uuid` is `NOT NULL DEFAULT ''`, and the empty string is the account-wide
 * scope.** SQLite treats `NULL`s as distinct in a unique index, so an account-wide row with a null
 * conversation could be inserted twice and the composite primary key would not stop it. Kotlin
 * spells the default as `''` because Room writes the `@ColumnInfo` default into the `CREATE`
 * verbatim (`Property.kt`: `append(" DEFAULT $defaultValue")`) and `PRAGMA table_info` answers
 * `''` for it - `""` would emit `DEFAULT ` and break the statement.
 *
 * <p>`state`'s and `reason`'s vocabularies are the engine's; `SyncQueries` carries the state
 * constants. The `ON DELETE CASCADE` is the per-account rule again.
 *
 * <p>Storage shape, not model.
 */
@Entity(
    tableName = "sync_gap",
    primaryKeys = ["account_uuid", "conversation_uuid", "gap_start", "region"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SyncGapEntity(
    @ColumnInfo(name = "account_uuid") val accountUuid: String,
    @ColumnInfo(name = "conversation_uuid", defaultValue = "''") val conversationUuid: String,
    @ColumnInfo(name = "gap_start") val gapStart: Long,
    @ColumnInfo(name = "gap_end") val gapEnd: Long,
    @ColumnInfo(name = "region", defaultValue = "0") val region: Long,
    @ColumnInfo(name = "state", defaultValue = "0") val state: Long,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
)
