package uk.xa0.tulkki.data.omemo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `signed_prekeys` as Room validates it (S5-3, the `omemo/` capability): the signed pre-keys, the
 * same `(account, id)` pair and the same shape as [PreKeyEntity], and the same surrogate key with
 * the file's `UNIQUE ... ON CONFLICT REPLACE` kept.
 */
@Entity(
    tableName = "signed_prekeys",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SignedPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "account") val account: String?,
    @ColumnInfo(name = "id") val signedPreKeyId: Long?,
    @ColumnInfo(name = "key") val key: String?,
)
