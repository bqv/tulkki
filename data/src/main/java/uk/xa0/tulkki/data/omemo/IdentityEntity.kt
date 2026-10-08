package uk.xa0.tulkki.data.omemo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `identities` as Room validates it (S5-3, the `omemo/` capability): one known fingerprint, its
 * own/other bit, its trust state and the X.509 certificate when the peer has one.
 *
 * <p>**Two of its columns are why this table is rebuilt rather than altered.** The file spells
 * `active NUMBER` and `last_activation NUMBER`, and a `NUMBER` declaration normalises to `UNDEFINED`,
 * which no entity can emit - so `Schema77` creates the table new, copies every row, drops and
 * renames, on the terms `Schema76`'s three and the `roster/`/`presence/` four already use. `BLOB`
 * for the certificate is the file's own type and round-trips unchanged.
 *
 * <p>The surrogate `_id` and the kept `UNIQUE(account, name, fingerprint) ON CONFLICT IGNORE` are
 * [SessionEntity]'s arrangement: the file has no primary key and every column is nullable. Storage
 * shape, not model (`AccountEntity`'s comment): every integer column is `Long`.
 */
@Entity(
    tableName = "identities",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class IdentityEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "account") val account: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "ownkey") val ownKey: Long?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    @ColumnInfo(name = "certificate") val certificate: ByteArray?,
    @ColumnInfo(name = "trust") val trust: String?,
    @ColumnInfo(name = "active") val active: Long?,
    @ColumnInfo(name = "last_activation") val lastActivation: Long?,
    @ColumnInfo(name = "key") val key: String?,
)
