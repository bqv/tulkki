package uk.xa0.tulkki.data.roster

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.ServiceDiscoveryResult

/**
 * `roster/`'s schema and every statement the package publishes (S5-3, the `roster/` capability).
 *
 * <p>Two tables and one column of a third: `contacts`, `discovery_results`, and `accounts
 * .rosterversion` - the roster's own version string, which the design puts here rather than in
 * `accounts/` because the roster is what reads and moves it (`docs/MIGRATION.md`, "Design: the data
 * layer" §2.5). The `CREATE`s were `DatabaseBackend`'s (`onCreate`) until S5-5; this object is
 * where they live, and the host fixture models the owner's file with them.
 *
 * <p>**Both tables gain a shape, and that is what `Schema77` is doing in this commit.** Room
 * validates a declared entity against the file by comparing each column's normalised affinity and
 * not-null flag, and this table declares four columns the file spells `NUMBER` or `boolean` -
 * `options`, `systemaccount`, `last_time` and `callsDisabled` - which normalise to `UNDEFINED` and
 * no entity can emit; `discovery_results` has no primary key at all, and Room requires one. The
 * version number does not move: the file's version is 77, so the rebuild runs in the schema-77
 * definition every caller already executes, and a device that reaches 77 gets it exactly once.
 */
internal object RosterQueries {

    const val TABLE = Contact.TABLENAME
    const val ACCOUNT = Contact.ACCOUNT
    const val JID = Contact.JID
    const val GROUPS = Contact.GROUPS
    const val LAST_PRESENCE = Contact.LAST_PRESENCE
    const val RTP_CAPABILITY = Contact.RTP_CAPABILITY
    const val CALLS_DISABLED = Contact.CALLS_DISABLED

    const val DISCOVERY_TABLE = ServiceDiscoveryResult.TABLENAME
    const val DISCOVERY_HASH = ServiceDiscoveryResult.HASH
    const val DISCOVERY_VER = ServiceDiscoveryResult.VER

    /** The roster's version string on `accounts`, read and moved by this package's DAO. */
    const val ROSTER_VERSION = Account.ROSTERVERSION

    /** The legacy `CREATE`, verbatim from `DatabaseBackend`: the four normalised columns and all. */
    @JvmField
    val CREATE_CONTACTS =
        "create table if not exists " +
            TABLE +
            "(" +
            ACCOUNT +
            " TEXT, " +
            Contact.SERVERNAME +
            " TEXT, " +
            Contact.SYSTEMNAME +
            " TEXT," +
            Contact.PRESENCE_NAME +
            " TEXT," +
            JID +
            " TEXT," +
            Contact.KEYS +
            " TEXT," +
            Contact.PHOTOURI +
            " TEXT," +
            Contact.OPTIONS +
            " NUMBER," +
            Contact.SYSTEMACCOUNT +
            " NUMBER, " +
            Contact.AVATAR +
            " TEXT, " +
            LAST_PRESENCE +
            " TEXT, " +
            CALLS_DISABLED +
            " boolean DEFAULT 0," +
            Contact.LAST_TIME +
            " NUMBER, " +
            RTP_CAPABILITY +
            " TEXT," +
            GROUPS +
            " TEXT, FOREIGN KEY(" +
            ACCOUNT +
            ") REFERENCES " +
            Account.TABLENAME +
            "(" +
            Account.UUID +
            ") ON DELETE CASCADE, UNIQUE(" +
            ACCOUNT +
            ", " +
            JID +
            ") ON CONFLICT REPLACE);"

    /** The legacy `CREATE`: three nullable TEXT columns and an inline `UNIQUE`, no primary key. */
    @JvmField
    val CREATE_DISCOVERY_RESULTS =
        "create table if not exists " +
            DISCOVERY_TABLE +
            "(" +
            DISCOVERY_HASH +
            " TEXT, " +
            DISCOVERY_VER +
            " TEXT, " +
            ServiceDiscoveryResult.RESULT +
            " TEXT, " +
            "UNIQUE(" +
            DISCOVERY_HASH +
            ", " +
            DISCOVERY_VER +
            ") ON CONFLICT REPLACE);"

    // -- the statements the DAO publishes ----------------------------------------------------------

    /** Every contact of one account, in a stable order: the roster read. */
    const val CONTACTS_FOR_ACCOUNT =
        "SELECT * FROM " + TABLE + " WHERE " + ACCOUNT + " = :account ORDER BY " + JID + " ASC"

    /** One contact, matched on the pair the table's own `UNIQUE` names. */
    const val CONTACT_BY_JID =
        "SELECT * FROM " + TABLE + " WHERE " + ACCOUNT + " = :account AND " + JID + " = :jid"

    /** One contact gone: the roster's own answer to a removal stanza. */
    const val REMOVE_CONTACT =
        "DELETE FROM " + TABLE + " WHERE " + ACCOUNT + " = :account AND " + JID + " = :jid"

    /** The whole roster of one account gone, which is what a roster version reset means. */
    const val REMOVE_CONTACTS_FOR_ACCOUNT = "DELETE FROM " + TABLE + " WHERE " + ACCOUNT + " = :account"

    /** One cached discovery result, by the pair the cache is keyed on. */
    const val DISCOVERY_BY_KEY =
        "SELECT * FROM " +
            DISCOVERY_TABLE +
            " WHERE " +
            DISCOVERY_HASH +
            " = :hash AND " +
            DISCOVERY_VER +
            " = :ver"

    /** The cache write: one row per `(hash, ver)`, replaced rather than duplicated. */
    const val SAVE_DISCOVERY_RESULT =
        "INSERT OR REPLACE INTO " +
            DISCOVERY_TABLE +
            " (" +
            DISCOVERY_HASH +
            ", " +
            DISCOVERY_VER +
            ", " +
            ServiceDiscoveryResult.RESULT +
            ") VALUES (:hash, :ver, :result)"

    /** The account's roster version, or `null` when it has never synced one. */
    const val ROSTER_VERSION_OF =
        "SELECT " + ROSTER_VERSION + " FROM " + Account.TABLENAME + " WHERE " + Account.UUID + " = :account"

    /** The version the account's roster was last written at; `null` means "fetch the whole roster". */
    const val SET_ROSTER_VERSION =
        "UPDATE " + Account.TABLENAME + " SET " + ROSTER_VERSION + " = :version WHERE " + Account.UUID + " = :account"
}
