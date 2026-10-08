package uk.xa0.tulkki.data.omemo

import uk.xa0.tulkki.crypto.axolotl.SQLiteAxolotlStore
import uk.xa0.tulkki.data.model.Account

/**
 * `omemo/`'s schema and every statement the package publishes (S5-3, the `omemo/` capability).
 *
 * <p>Four tables - `identities`, `sessions`, `prekeys`, `signed_prekeys` - which are the OMEMO
 * session store's whole persistence. Their `CREATE`s were `DatabaseBackend`'s until this commit and
 * that class now executes [CREATE_STATEMENTS]; the shapes the entities declare - the surrogate key,
 * the `NUMBER` columns normalised to `INTEGER` - are `Schema77`'s, on the same terms as `roster/`'s
 * two tables and `presence/`'s one.
 *
 * <p>**The persisted names are the island's, and they are spelled once here.** Every table and every
 * column is named through `uk.xa0.tulkki.crypto.axolotl.SQLiteAxolotlStore`, the class the OMEMO
 * store in `:crypto` already exposes them from: `sessions`, `prekeys`, `signed_prekeys`,
 * `identities`, and `account`/`name`/`device_id`/`id`/`key`/`fingerprint`/`ownkey`/`trust`/`active`/
 * `last_activation`/`certificate`. They are not ours to rename (`docs/MIGRATION.md`, "Names"), and a
 * second literal spelling of one of them is the defect this object exists to prevent.
 *
 * <p>**Every statement below is one of the live axolotl accessors' own.** `DatabaseBackend`'s
 * `getCursorForSession`, `getSubDeviceSessions`, `getKnownSignalAddresses`, `containsSession`,
 * `storeSession`, `deleteSession`, `deleteAllSessions`, `getCursorForPreKey`, `storePreKey`,
 * `deletePreKey`, `getCursorForSignedPreKey`, `loadSignedPreKeys`, `getSignedPreKeysCount`,
 * `storeSignedPreKey`, `deleteSignedPreKey`, `getIdentityKeyCursor`, `numTrustedKeys`,
 * `storeIdentityKey`, `storePreVerification`, `setIdentityKeyTrust`,
 * `setIdentityKeyCertificate`, `getIdentityKeyCertifcate` and `wipeAxolotlDb` are the callers, and
 * the SQL is theirs, line for line. The one thing not carried over is `FingerprintStatus`'s
 * conditional `last_activation`: its `toContentValues()` omits the column when it has nothing to
 * say, which a statement cannot express, so [UPDATE_IDENTITY] names the seven columns
 * `storeIdentityKey` actually writes and leaves `last_activation` to the row it already has.
 *
 * <p>**The upserts are `INSERT OR REPLACE`, and that is the table's own `UNIQUE` doing the work.**
 * `prekeys` and `signed_prekeys` carry `UNIQUE(account, id) ON CONFLICT REPLACE`, `sessions`
 * `UNIQUE(account, name, device_id) ON CONFLICT REPLACE` and `identities`
 * `UNIQUE(account, name, fingerprint) ON CONFLICT IGNORE`; those constraints are **kept** by the
 * rebuild, so `REPLACE` still deduplicates on exactly the triple the legacy writer relied on and no
 * entity has to declare an index SQLite hides from Room.
 */
internal object OmemoQueries {

    // -- the persisted names, read from the island ------------------------------------------------

    const val IDENTITIES_TABLE = SQLiteAxolotlStore.IDENTITIES_TABLENAME

    const val SESSIONS_TABLE = SQLiteAxolotlStore.SESSION_TABLENAME

    const val PREKEYS_TABLE = SQLiteAxolotlStore.PREKEY_TABLENAME

    const val SIGNED_PREKEYS_TABLE = SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME

    const val ACCOUNT = SQLiteAxolotlStore.ACCOUNT

    const val NAME = SQLiteAxolotlStore.NAME

    const val DEVICE_ID = SQLiteAxolotlStore.DEVICE_ID

    const val ID = SQLiteAxolotlStore.ID

    const val KEY = SQLiteAxolotlStore.KEY

    const val OWN = SQLiteAxolotlStore.OWN

    const val FINGERPRINT = SQLiteAxolotlStore.FINGERPRINT

    const val CERTIFICATE = SQLiteAxolotlStore.CERTIFICATE

    const val TRUST = SQLiteAxolotlStore.TRUST

    const val ACTIVE = SQLiteAxolotlStore.ACTIVE

    const val LAST_ACTIVATION = SQLiteAxolotlStore.LAST_ACTIVATION

    /** The `accounts` side of every foreign key, from the model the island and this module share. */
    private const val ACCOUNTS_TABLE = Account.TABLENAME

    private const val ACCOUNTS_KEY = Account.UUID

    private val ACCOUNT_REFERENCE =
        " FOREIGN KEY(" +
            ACCOUNT +
            ") REFERENCES " +
            ACCOUNTS_TABLE +
            "(" +
            ACCOUNTS_KEY +
            ") ON DELETE CASCADE"

    // -- the DDL: the legacy text, verbatim, with the names read from the island ------------------

    /** `prekeys`: the one-time keys, keyed on the pair the inline `UNIQUE` names. */
    @JvmField
    val CREATE_PREKEYS =
        "CREATE TABLE if not exists " +
            PREKEYS_TABLE +
            "(" +
            ACCOUNT +
            " TEXT,  " +
            ID +
            " INTEGER, " +
            KEY +
            " TEXT," +
            ACCOUNT_REFERENCE +
            ", UNIQUE( " +
            ACCOUNT +
            ", " +
            ID +
            ") ON CONFLICT REPLACE);"

    /** `signed_prekeys`: the signed pre-keys, the same shape as the one-time ones. */
    @JvmField
    val CREATE_SIGNED_PREKEYS =
        "CREATE TABLE if not exists " +
            SIGNED_PREKEYS_TABLE +
            "(" +
            ACCOUNT +
            " TEXT,  " +
            ID +
            " INTEGER, " +
            KEY +
            " TEXT," +
            ACCOUNT_REFERENCE +
            ", UNIQUE( " +
            ACCOUNT +
            ", " +
            ID +
            ") ON CONFLICT REPLACE);"

    /** `sessions`: one Signal session per `(account, name, device_id)`. */
    @JvmField
    val CREATE_SESSIONS =
        "CREATE TABLE if not exists " +
            SESSIONS_TABLE +
            "(" +
            ACCOUNT +
            " TEXT,  " +
            NAME +
            " TEXT, " +
            DEVICE_ID +
            " INTEGER, " +
            KEY +
            " TEXT," +
            ACCOUNT_REFERENCE +
            ", UNIQUE( " +
            ACCOUNT +
            ", " +
            NAME +
            ", " +
            DEVICE_ID +
            ") ON CONFLICT REPLACE);"

    /**
     * `identities`: one row per known fingerprint, with its trust state and its certificate.
     * `active` and `last_activation` are the file's two `NUMBER` columns, which is why the entity
     * cannot declare this table's shape and the rebuild exists.
     */
    @JvmField
    val CREATE_IDENTITIES =
        "CREATE TABLE if not exists " +
            IDENTITIES_TABLE +
            "(" +
            ACCOUNT +
            " TEXT,  " +
            NAME +
            " TEXT, " +
            OWN +
            " INTEGER, " +
            FINGERPRINT +
            " TEXT, " +
            CERTIFICATE +
            " BLOB, " +
            TRUST +
            " TEXT, " +
            ACTIVE +
            " NUMBER, " +
            LAST_ACTIVATION +
            " NUMBER," +
            KEY +
            " TEXT," +
            ACCOUNT_REFERENCE +
            ", UNIQUE( " +
            ACCOUNT +
            ", " +
            NAME +
            ", " +
            FINGERPRINT +
            ") ON CONFLICT IGNORE);"

    /** The four, in the order the legacy `onCreate` ran them, for the fixtures. */
    @JvmField
    val CREATE_STATEMENTS: List<String> =
        listOf(CREATE_SESSIONS, CREATE_PREKEYS, CREATE_SIGNED_PREKEYS, CREATE_IDENTITIES)

    // -- sessions ---------------------------------------------------------------------------------

    /** `getCursorForSession`: the one row an address names, or no row. */
    const val SESSION_BY_ADDRESS =
        "SELECT * FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND " +
            DEVICE_ID +
            " = :deviceId"

    /** `containsSession`: the same row, asked whether it exists rather than what it holds. */
    const val SESSION_COUNT_BY_ADDRESS =
        "SELECT count(*) FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND " +
            DEVICE_ID +
            " = :deviceId"

    /** `getSubDeviceSessions`: every device this name has a session for. */
    const val SESSION_DEVICE_IDS =
        "SELECT " +
            DEVICE_ID +
            " FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name"

    /** `getKnownSignalAddresses`: the distinct names the account holds a session for. */
    const val SESSION_NAMES =
        "SELECT DISTINCT " +
            NAME +
            " FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account"

    /** `storeSession`: `REPLACE` on the table's own `UNIQUE(account, name, device_id)`. */
    const val UPSERT_SESSION =
        "INSERT OR REPLACE INTO " +
            SESSIONS_TABLE +
            " (" +
            ACCOUNT +
            ", " +
            NAME +
            ", " +
            DEVICE_ID +
            ", " +
            KEY +
            ") VALUES (:account, :name, :deviceId, :key)"

    /** `deleteSession`: one address's row. */
    const val DELETE_SESSION =
        "DELETE FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND " +
            DEVICE_ID +
            " = :deviceId"

    /** `deleteAllSessions`: every device of one name. */
    const val DELETE_SESSIONS_BY_NAME =
        "DELETE FROM " +
            SESSIONS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name"

    /** `wipeAxolotlDb`: the account's own rows, the second half of the cascade's promise. */
    const val DELETE_SESSIONS_BY_ACCOUNT =
        "DELETE FROM " + SESSIONS_TABLE + " WHERE " + ACCOUNT + " = :account"

    // -- one-time pre-keys ------------------------------------------------------------------------

    /** `getCursorForPreKey`: the key itself, or no row. */
    const val PREKEY_KEY =
        "SELECT " +
            KEY +
            " FROM " +
            PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            ID +
            " = :id"

    /** `storePreKey`: `REPLACE` on `UNIQUE(account, id)`. */
    const val UPSERT_PREKEY =
        "INSERT OR REPLACE INTO " +
            PREKEYS_TABLE +
            " (" +
            ACCOUNT +
            ", " +
            ID +
            ", " +
            KEY +
            ") VALUES (:account, :id, :key)"

    /** `deletePreKey`: the one-time key is consumed, so it goes. */
    const val DELETE_PREKEY =
        "DELETE FROM " +
            PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            ID +
            " = :id"

    /** `wipeAxolotlDb`. */
    const val DELETE_PREKEYS_BY_ACCOUNT =
        "DELETE FROM " + PREKEYS_TABLE + " WHERE " + ACCOUNT + " = :account"

    // -- signed pre-keys --------------------------------------------------------------------------

    /** `getCursorForSignedPreKey`: the key itself, or no row. */
    const val SIGNED_PREKEY_KEY =
        "SELECT " +
            KEY +
            " FROM " +
            SIGNED_PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            ID +
            " = :id"

    /** `loadSignedPreKeys`: every key the account holds, newest id last - the file's own order. */
    const val SIGNED_PREKEY_KEYS =
        "SELECT " +
            KEY +
            " FROM " +
            SIGNED_PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account"

    /** `getSignedPreKeysCount`: `count(key)`, so a row with a NULL key is not one of them. */
    const val SIGNED_PREKEY_COUNT =
        "SELECT count(" +
            KEY +
            ") FROM " +
            SIGNED_PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account"

    /** `storeSignedPreKey`: `REPLACE` on `UNIQUE(account, id)`. */
    const val UPSERT_SIGNED_PREKEY =
        "INSERT OR REPLACE INTO " +
            SIGNED_PREKEYS_TABLE +
            " (" +
            ACCOUNT +
            ", " +
            ID +
            ", " +
            KEY +
            ") VALUES (:account, :id, :key)"

    /** `deleteSignedPreKey`: the rotated-out key goes. */
    const val DELETE_SIGNED_PREKEY =
        "DELETE FROM " +
            SIGNED_PREKEYS_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            ID +
            " = :id"

    /** `wipeAxolotlDb`. */
    const val DELETE_SIGNED_PREKEYS_BY_ACCOUNT =
        "DELETE FROM " + SIGNED_PREKEYS_TABLE + " WHERE " + ACCOUNT + " = :account"

    // -- identities -------------------------------------------------------------------------------

    /**
     * `getIdentityKeyCursor`'s four columns: what `FingerprintStatus.fromCursor` reads, and the key
     * it hands back. One statement for the name-and-own and the fingerprint forms, because the
     * legacy helper is one method with optional clauses and the DAO keeps the shape.
     */
    const val IDENTITY_STATUS =
        "SELECT " +
            TRUST +
            ", " +
            ACTIVE +
            ", " +
            LAST_ACTIVATION +
            ", " +
            KEY +
            " FROM " +
            IDENTITIES_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND " +
            OWN +
            " = :ownKey"

    /** `getFingerprintStatus`: the status of one fingerprint of the account. */
    const val IDENTITY_STATUS_BY_FINGERPRINT =
        "SELECT " +
            TRUST +
            ", " +
            ACTIVE +
            ", " +
            LAST_ACTIVATION +
            ", " +
            KEY +
            " FROM " +
            IDENTITIES_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            FINGERPRINT +
            " = :fingerprint"

    /** `numTrustedKeys`: trusted or verified, and active. */
    const val IDENTITY_TRUSTED_COUNT =
        "SELECT count(*) FROM " +
            IDENTITIES_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND (" +
            TRUST +
            " = :trusted OR " +
            TRUST +
            " = :verified OR " +
            TRUST +
            " = :verifiedX509) AND " +
            ACTIVE +
            " > 0"

    /**
     * [IDENTITY_TRUSTED_COUNT] with positional binds, for the live `rawQuery` caller that has no
     * `SupportSQLiteDatabase` to resolve the named ones: same five arguments, in the same order.
     */
    const val IDENTITY_TRUSTED_COUNT_BOUND =
        "SELECT count(*) FROM " +
            IDENTITIES_TABLE +
            " WHERE " +
            ACCOUNT +
            " = ? AND " +
            NAME +
            " = ? AND (" +
            TRUST +
            " = ? OR " +
            TRUST +
            " = ? OR " +
            TRUST +
            " = ?) AND " +
            ACTIVE +
            " > 0"

    /**
     * `storeIdentityKey`'s and `storePreVerification`'s insert branch: the seven columns the legacy
     * `ContentValues` carried. `key`, `certificate` and `last_activation` are left to the file's
     * defaults, exactly as they were.
     */
    const val INSERT_IDENTITY =
        "INSERT INTO " +
            IDENTITIES_TABLE +
            " (" +
            ACCOUNT +
            ", " +
            NAME +
            ", " +
            OWN +
            ", " +
            FINGERPRINT +
            ", " +
            KEY +
            ", " +
            TRUST +
            ", " +
            ACTIVE +
            ") VALUES (:account, :name, :ownKey, :fingerprint, :key, :trust, :active)"

    /** `storeIdentityKey`'s update branch, on the triple its own `UNIQUE` names. */
    const val UPDATE_IDENTITY =
        "UPDATE " +
            IDENTITIES_TABLE +
            " SET " +
            ACCOUNT +
            " = :account, " +
            NAME +
            " = :name, " +
            OWN +
            " = :ownKey, " +
            FINGERPRINT +
            " = :fingerprint, " +
            KEY +
            " = :key, " +
            TRUST +
            " = :trust, " +
            ACTIVE +
            " = :active WHERE " +
            ACCOUNT +
            " = :account AND " +
            NAME +
            " = :name AND " +
            FINGERPRINT +
            " = :fingerprint"

    /** `setIdentityKeyTrust`: the two columns a trust change moves, by account and fingerprint. */
    const val UPDATE_IDENTITY_STATUS =
        "UPDATE " +
            IDENTITIES_TABLE +
            " SET " +
            TRUST +
            " = :trust, " +
            ACTIVE +
            " = :active WHERE " +
            ACCOUNT +
            " = :account AND " +
            FINGERPRINT +
            " = :fingerprint"

    /** `getIdentityKeyCertifcate`: the DER bytes, or no row. */
    const val IDENTITY_CERTIFICATE =
        "SELECT " +
            CERTIFICATE +
            " FROM " +
            IDENTITIES_TABLE +
            " WHERE " +
            ACCOUNT +
            " = :account AND " +
            FINGERPRINT +
            " = :fingerprint"

    /** `setIdentityKeyCertificate`: the DER bytes onto the row that fingerprint names. */
    const val UPDATE_IDENTITY_CERTIFICATE =
        "UPDATE " +
            IDENTITIES_TABLE +
            " SET " +
            CERTIFICATE +
            " = :certificate WHERE " +
            ACCOUNT +
            " = :account AND " +
            FINGERPRINT +
            " = :fingerprint"

    /** `wipeAxolotlDb`. */
    const val DELETE_IDENTITIES_BY_ACCOUNT =
        "DELETE FROM " + IDENTITIES_TABLE + " WHERE " + ACCOUNT + " = :account"
}
