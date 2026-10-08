package uk.xa0.tulkki.data.omemo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `prekeys` as Room validates it (S5-3, the `omemo/` capability): the one-time pre-keys, keyed on
 * the `(account, id)` pair the file's inline `UNIQUE ... ON CONFLICT REPLACE` names.
 *
 * <p>The surrogate key and the kept `UNIQUE` are `SessionEntity`'s arrangement, for the same reason:
 * the file has no primary key and every column is nullable. The pre-key's own id is a *column* -
 * `id` - so the property is `preKeyId` and the table's `id` is named by `@ColumnInfo`.
 */
@Entity(
    tableName = "prekeys",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class PreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "account") val account: String?,
    @ColumnInfo(name = "id") val preKeyId: Long?,
    @ColumnInfo(name = "key") val key: String?,
)
