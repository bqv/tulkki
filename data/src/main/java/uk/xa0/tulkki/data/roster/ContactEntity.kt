package uk.xa0.tulkki.data.roster

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `contacts` as Room validates it (S5-3, the `roster/` capability).
 *
 * <p>The storage shape, not the model: `uk.xa0.tulkki.data.model.Contact` stays the type the islands
 * speak and the mapping belongs to the repository (`docs/MIGRATION.md`, "Design: the data layer"
 * §2.3). Four columns are `Long` because the file spelled them `NUMBER` or `boolean`, which Room
 * normalises to `UNDEFINED` - so `Schema77` **rebuilds** this table rather than altering it, an
 * `ALTER` being unable to change a declared type.
 *
 * <p>**The key is a surrogate `_id`, and that is a data decision rather than a convenience.** The
 * file's table has no primary key at all - only `UNIQUE(accountUuid, jid) ON CONFLICT REPLACE` - and
 * Room demands one. The two natural candidates were both refused or unsafe:
 *
 * <ul>
 *   <li>`primaryKeys = ["accountUuid", "jid"]` with the two properties nullable is what the file's
 *       columns are (SQLite calls a composite key's columns nullable unless they say `NOT NULL`),
 *       and Room refuses it outright, measured: *"You must annotate primary keys with @NonNull.
 *       'accountUuid' is nullable. SQLite considers this a bug and Room does not allow it."*
 *   <li>the same pair declared `NOT NULL` would make the rebuild's copy **throw** on a single legacy
 *       row with a null `accountUuid` or `jid`, and a throwing migration is a refused open on the
 *       owner's phone. The alternatives were to invent an empty jid or to delete the row; neither is
 *       this package's to do to the owner's data.
 * </ul>
 *
 * So the rebuild adds `_id INTEGER NOT NULL PRIMARY KEY`, copying each row's `rowid` into it - so
 * every existing row keeps its identity - and **keeps the `UNIQUE(accountUuid, jid) ON CONFLICT
 * REPLACE`**, which is what the legacy writer's `CONFLICT_REPLACE` and this DAO's `REPLACE` insert
 * actually deduplicate on. No row is added, removed or rewritten, and no column becomes `NOT NULL`.
 *
 * <p>**No index is declared.** SQLite creates `sqlite_autoindex_contacts_1` behind the `UNIQUE`
 * clause and Room's index read skips every `sqlite_autoindex_%`, so the entity side is an empty index
 * set; declaring a named index would be a mismatch, not a match.
 *
 * <p>Storage shape, not model - see `AccountEntity`'s comment.
 */
@Entity(
    tableName = "contacts",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["accountUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "accountUuid") val accountUuid: String?,
    @ColumnInfo(name = "servername") val serverName: String?,
    @ColumnInfo(name = "systemname") val systemName: String?,
    @ColumnInfo(name = "presence_name") val presenceName: String?,
    @ColumnInfo(name = "jid") val jid: String?,
    @ColumnInfo(name = "pgpkey") val pgpKey: String?,
    @ColumnInfo(name = "photouri") val photoUri: String?,
    @ColumnInfo(name = "options") val options: Long?,
    @ColumnInfo(name = "systemaccount") val systemAccount: Long?,
    @ColumnInfo(name = "avatar") val avatar: String?,
    @ColumnInfo(name = "last_presence") val lastPresence: String?,
    @ColumnInfo(name = "callsDisabled", defaultValue = "0") val callsDisabled: Long?,
    @ColumnInfo(name = "last_time") val lastTime: Long?,
    @ColumnInfo(name = "rtpCapability") val rtpCapability: String?,
    @ColumnInfo(name = "groups") val groups: String?,
)
