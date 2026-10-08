package uk.xa0.tulkki.data.roster

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * `roster/`'s own DAO (S5-3): the account's contacts, the discovery cache, and the roster version
 * string on `accounts`.
 *
 * <p>**The roster version is here because the roster is what moves it.** `DatabaseBackend` reads
 * `accounts.rosterversion` to decide whether to fetch the whole roster or ask for a delta, and
 * writes it back after one; the design puts that pair with `roster/` rather than `accounts/`
 * (`docs/MIGRATION.md`, "Design: the data layer" §2.5) because the decision is the roster's, not the
 * account row's.
 *
 * <p>The SQL is spelled here and again in [RosterQueries]; `RosterDaoTest` keeps the two equal.
 * The whole-row contact write is the generator's, from [ContactEntity] - with `REPLACE`, because
 * that is what the table's own `UNIQUE(accountUuid, jid) ON CONFLICT REPLACE` does for the legacy
 * writer.
 */
@Dao
internal interface RosterDao {

    /** Every contact of one account, in a stable order. */
    @Query("SELECT * FROM contacts WHERE accountUuid = :account ORDER BY jid ASC")
    fun contactsForAccount(account: String): List<ContactEntity>

    /** One contact, matched on the pair the table's `UNIQUE` names. */
    @Query("SELECT * FROM contacts WHERE accountUuid = :account AND jid = :jid")
    fun contactByJid(account: String, jid: String): ContactEntity?

    /** A whole contact row: `REPLACE` so the unique pair replaces rather than duplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveContact(contact: ContactEntity): Long

    /** The whole row again. */
    @Update fun updateContact(contact: ContactEntity)

    /** One contact gone. */
    @Query("DELETE FROM contacts WHERE accountUuid = :account AND jid = :jid")
    fun removeContact(account: String, jid: String): Int

    /** The whole roster of one account gone - what a roster version reset means. */
    @Query("DELETE FROM contacts WHERE accountUuid = :account")
    fun removeContactsForAccount(account: String): Int

    /** One cached discovery answer, by the pair the cache is keyed on. */
    @Query("SELECT * FROM discovery_results WHERE hash = :hash AND ver = :ver")
    fun discoveryByKey(hash: String, ver: String): DiscoveryResultEntity?

    /** The cache write: one row per pair, replaced rather than duplicated. */
    @Query(
        "INSERT OR REPLACE INTO discovery_results (hash, ver, result) " +
            "VALUES (:hash, :ver, :result)"
    )
    fun saveDiscoveryResult(hash: String, ver: String, result: String?): Long

    /** The account's roster version, or `null` when it has never synced one. */
    @Query("SELECT rosterversion FROM accounts WHERE uuid = :account")
    fun rosterVersionOf(account: String): String?

    /** The version the account's roster was last written at. */
    @Query("UPDATE accounts SET rosterversion = :version WHERE uuid = :account")
    fun setRosterVersion(account: String, version: String?): Int
}
