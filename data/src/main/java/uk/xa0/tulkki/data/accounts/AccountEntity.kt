package uk.xa0.tulkki.data.accounts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `accounts` as Room validates it (S5-2b).
 *
 * <p>It exists for the foreign keys, and that is not a workaround: Room's `@ForeignKey` names its
 * target by *entity class*, and `conversations` points at `accounts`, so without this entity
 * `ConversationEntity` could not declare the `ON DELETE CASCADE` that account deletion depends on -
 * and an undeclared foreign key is a mismatch against the file, not a pass.
 *
 * <p>This is the **storage** shape, not the model: `uk.xa0.tulkki.data.model.Account` stays the type
 * the islands speak, and the mapping between them belongs to the repository (`docs/MIGRATION.md`,
 * "Design: the data layer" §2.3). Every integer column is `Long` for that reason - the declared
 * affinity is what Room compares, and a narrower Kotlin type buys nothing here.
 *
 * <p>`options` and `port` were declared `NUMBER` on disk, which Room normalises to `UNDEFINED` and
 * no entity can emit; that is why schema 76 rebuilds this table rather than altering it. The columns,
 * their order, their defaults and their nullability are the rebuild's own list, and `Schema76`'s
 * comment carries the other half of that contract.
 */
@Entity(tableName = "accounts")
internal data class AccountEntity(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "username") val username: String?,
    @ColumnInfo(name = "server") val server: String?,
    @ColumnInfo(name = "password") val password: String?,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "status_message") val statusMessage: String?,
    @ColumnInfo(name = "rosterversion") val rosterVersion: String?,
    @ColumnInfo(name = "options") val options: Long?,
    @ColumnInfo(name = "avatar") val avatar: String?,
    @ColumnInfo(name = "keys") val keys: String?,
    @ColumnInfo(name = "hostname") val hostname: String?,
    @ColumnInfo(name = "resource") val resource: String?,
    @ColumnInfo(name = "pinned_mechanism") val pinnedMechanism: String?,
    @ColumnInfo(name = "pinned_channel_binding") val pinnedChannelBinding: String?,
    @ColumnInfo(name = "fast_mechanism") val fastMechanism: String?,
    @ColumnInfo(name = "fast_token") val fastToken: String?,
    @ColumnInfo(name = "ordering", defaultValue = "0") val ordering: Long?,
    @ColumnInfo(name = "port", defaultValue = "5222") val port: Long?,
)
