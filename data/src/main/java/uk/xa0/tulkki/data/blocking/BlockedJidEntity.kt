package uk.xa0.tulkki.data.blocking

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import uk.xa0.tulkki.data.accounts.AccountEntity

/**
 * `blocked_jids` — the blocklist, as a table (S5-3, the `blocking/` capability).
 *
 * <p>It is **new in schema 77**, and it is the one table this step adds. Today's blocklist is
 * in-memory per account (`docs/MIGRATION.md`, "The module map" §1's `blocking/` row: "in-memory
 * blocklist today → new `blocked_jids`"), so creating it loses nothing.
 *
 * <p>**A new table is created by the shared definition, never by the fresh-install path alone:**
 * `Schema77.STATEMENTS` is executed by `MIGRATION_76_77` and by the fresh-install callback, so a
 * fresh install and an owner's upgrade cannot diverge. That is the rule §6 step 3 states for this
 * table in as many words; the third caller, the legacy hook, went with the rest of the legacy chain
 * in S5-5.
 *
 * <p>The primary key is the pair: one account cannot block the same JID twice, and a JID blocked by
 * one account is not blocked for another. There is no second index on purpose - every query this
 * package publishes goes through the key - and the foreign key is the same `ON DELETE CASCADE`
 * every per-account table carries, so removing an account cannot leave a blocklist behind.
 */
@Entity(
    tableName = "blocked_jids",
    primaryKeys = ["account_uuid", "jid"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["account_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class BlockedJidEntity(
    @ColumnInfo(name = "account_uuid") val accountUuid: String,
    @ColumnInfo(name = "jid") val jid: String,
)
