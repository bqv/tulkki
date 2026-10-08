package uk.xa0.tulkki.data.omemo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `sessions` as Room validates it (S5-3, the `omemo/` capability): one Signal session per
 * `(account, name, device_id)`, the key being the store's own base64 `SessionRecord`.
 *
 * <p>**The key is a surrogate `_id`, for the reason `ContactEntity`'s comment gives at length.** The
 * file's table has no primary key at all - only `UNIQUE(account, name, device_id) ON CONFLICT
 * REPLACE` - and all four columns are nullable, so Room's alternatives were a nullable key (refused
 * outright) or a `NOT NULL` triple that would make the rebuild's copy throw on a legacy row with a
 * null `name` or `device_id`. So the rebuild adds `_id INTEGER NOT NULL PRIMARY KEY`, copies each
 * row's `rowid` into it, leaves the four columns nullable and **keeps the `UNIQUE`**, which is what
 * `storeSession`'s `REPLACE` insert and [OmemoQueries.UPSERT_SESSION] conflict on. No row is added,
 * removed or made `NOT NULL`.
 *
 * <p>Storage shape, not model (`AccountEntity`'s comment): every integer column is `Long`.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "account") val account: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "device_id") val deviceId: Long?,
    @ColumnInfo(name = "key") val key: String?,
)
