package uk.xa0.tulkki.data.omemo

import androidx.room.Dao
import androidx.room.Query

/**
 * `omemo/`'s own DAO (S5-3): the OMEMO session store's four tables - the sessions, the one-time
 * and signed pre-keys, and the identities with their trust state.
 *
 * <p>**The SQL is spelled here and in [OmemoQueries], and the test keeps the two equal.**
 * `OmemoStoreDaoTest` reads these annotations back and asserts they are exactly that object's
 * constants, so a statement that drifts on either side is a red test rather than a query Room runs
 * and no one read (`BlockingDao`'s comment carries the rule in full). Naming the constant from the
 * annotation would compile to the same SQL and hide the statement from a later reader.
 *
 * <p>**Every write is `@Query`, and that is the tables' own decision.** The three keyed tables
 * deduplicate on an inline `UNIQUE ... ON CONFLICT REPLACE` and the fourth on
 * `UNIQUE(account, name, fingerprint)`, so an upsert names only the payload columns and lets
 * SQLite's constraint do the conflict. A whole-row `@Insert` would have to carry the surrogate
 * `_id` as well, which is not the writer's to invent, and Room's index read skips SQLite's
 * `sqlite_autoindex_%`, so the constraint cannot be declared on the entity either.
 *
 * <p>`internal`, like every DAO: the module's public surface is the read models
 * (`docs/MIGRATION.md`, "Design: the data layer" §2.5).
 */
@Dao
internal interface OmemoStoreDao {

    // -- sessions ---------------------------------------------------------------------------------

    /** `loadSession`'s row: the one address names, or no row. */
    @Query(
        "SELECT * FROM sessions WHERE account = :account AND name = :name AND device_id = :deviceId"
    )
    fun session(account: String?, name: String?, deviceId: Long?): SessionEntity?

    /** `containsSession`: the same row, asked for its count. */
    @Query(
        "SELECT count(*) FROM sessions " +
            "WHERE account = :account AND name = :name AND device_id = :deviceId"
    )
    fun sessionCount(account: String?, name: String?, deviceId: Long?): Int

    /** `getSubDeviceSessions`: every device this name has a session for. */
    @Query("SELECT device_id FROM sessions WHERE account = :account AND name = :name")
    fun deviceIds(account: String?, name: String?): List<Long>

    /** `getKnownSignalAddresses`: the distinct names the account holds a session for. */
    @Query("SELECT DISTINCT name FROM sessions WHERE account = :account")
    fun names(account: String?): List<String>

    /** `storeSession`. */
    @Query(
        "INSERT OR REPLACE INTO sessions (account, name, device_id, key) " +
            "VALUES (:account, :name, :deviceId, :key)"
    )
    fun upsertSession(account: String?, name: String?, deviceId: Long?, key: String?): Long

    /** `deleteSession`: one address's row. */
    @Query("DELETE FROM sessions WHERE account = :account AND name = :name AND device_id = :deviceId")
    fun deleteSession(account: String?, name: String?, deviceId: Long?): Int

    /** `deleteAllSessions`: every device of one name. */
    @Query("DELETE FROM sessions WHERE account = :account AND name = :name")
    fun deleteSessionsByName(account: String?, name: String?): Int

    /** `wipeAxolotlDb`: the account's own rows, the other half of the cascade's promise. */
    @Query("DELETE FROM sessions WHERE account = :account")
    fun deleteSessionsForAccount(account: String?): Int

    // -- one-time pre-keys ------------------------------------------------------------------------

    /** `loadPreKey`: the key itself, or `null` when no row matches. */
    @Query("SELECT key FROM prekeys WHERE account = :account AND id = :id")
    fun preKey(account: String?, id: Long?): String?

    /** `storePreKey`. */
    @Query("INSERT OR REPLACE INTO prekeys (account, id, key) VALUES (:account, :id, :key)")
    fun upsertPreKey(account: String?, id: Long?, key: String?): Long

    /** `deletePreKey`: the one-time key is consumed, so it goes. */
    @Query("DELETE FROM prekeys WHERE account = :account AND id = :id")
    fun deletePreKey(account: String?, id: Long?): Int

    /** `wipeAxolotlDb`. */
    @Query("DELETE FROM prekeys WHERE account = :account")
    fun deletePreKeysForAccount(account: String?): Int

    // -- signed pre-keys --------------------------------------------------------------------------

    /** `loadSignedPreKey`: the key itself, or `null` when no row matches. */
    @Query("SELECT key FROM signed_prekeys WHERE account = :account AND id = :id")
    fun signedPreKey(account: String?, id: Long?): String?

    /** `loadSignedPreKeys`: every key the account holds. */
    @Query("SELECT key FROM signed_prekeys WHERE account = :account")
    fun signedPreKeys(account: String?): List<String>

    /** `getSignedPreKeysCount`: `count(key)`, so a row with a `NULL` key is not one of them. */
    @Query("SELECT count(key) FROM signed_prekeys WHERE account = :account")
    fun signedPreKeyCount(account: String?): Int

    /** `storeSignedPreKey`. */
    @Query(
        "INSERT OR REPLACE INTO signed_prekeys (account, id, key) " +
            "VALUES (:account, :id, :key)"
    )
    fun upsertSignedPreKey(account: String?, id: Long?, key: String?): Long

    /** `deleteSignedPreKey`: the rotated-out key goes. */
    @Query("DELETE FROM signed_prekeys WHERE account = :account AND id = :id")
    fun deleteSignedPreKey(account: String?, id: Long?): Int

    /** `wipeAxolotlDb`. */
    @Query("DELETE FROM signed_prekeys WHERE account = :account")
    fun deleteSignedPreKeysForAccount(account: String?): Int

    // -- identities -------------------------------------------------------------------------------

    /**
     * `getIdentityKeyCursor(account, name, own)`: `loadOwnIdentityKeyPair` passes `ownKey` 1,
     * `loadIdentityKeys` passes 0. The projection is `FingerprintStatus.fromCursor`'s four columns.
     */
    @Query(
        "SELECT trust, active, last_activation, key FROM identities " +
            "WHERE account = :account AND name = :name AND ownkey = :ownKey"
    )
    fun identityStatus(account: String?, name: String?, ownKey: Long?): IdentityStatusRow?

    /** `getFingerprintStatus`: one fingerprint's status, by account and fingerprint. */
    @Query(
        "SELECT trust, active, last_activation, key FROM identities " +
            "WHERE account = :account AND fingerprint = :fingerprint"
    )
    fun identityStatusForFingerprint(
        account: String?,
        fingerprint: String?,
    ): IdentityStatusRow?

    /** `numTrustedKeys`: trusted or verified, and active. */
    @Query(
        "SELECT count(*) FROM identities WHERE account = :account AND name = :name AND " +
            "(trust = :trusted OR trust = :verified OR trust = :verifiedX509) AND active > 0"
    )
    fun trustedCount(
        account: String?,
        name: String?,
        trusted: String?,
        verified: String?,
        verifiedX509: String?,
    ): Int

    /**
     * `storeIdentityKey`'s and `storePreVerification`'s insert branch: the seven columns their
     * `ContentValues` carried. `key` is `NULL` for a pre-verification row, exactly as it was.
     */
    @Query(
        "INSERT INTO identities (account, name, ownkey, fingerprint, key, trust, active) " +
            "VALUES (:account, :name, :ownKey, :fingerprint, :key, :trust, :active)"
    )
    fun insertIdentity(
        account: String?,
        name: String?,
        ownKey: Long?,
        fingerprint: String?,
        key: String?,
        trust: String?,
        active: Long?,
    ): Long

    /**
     * `storeIdentityKey`'s update branch, on the triple its own `UNIQUE` names. It leaves
     * `last_activation` and `certificate` alone, which is what the legacy `ContentValues` did:
     * `FingerprintStatus.toContentValues()` only carries `last_activation` when it has one.
     */
    @Query(
        "UPDATE identities SET account = :account, name = :name, ownkey = :ownKey, " +
            "fingerprint = :fingerprint, key = :key, trust = :trust, active = :active " +
            "WHERE account = :account AND name = :name AND fingerprint = :fingerprint"
    )
    fun updateIdentity(
        account: String?,
        name: String?,
        ownKey: Long?,
        fingerprint: String?,
        key: String?,
        trust: String?,
        active: Long?,
    ): Int

    /** `setIdentityKeyTrust`: the two columns a trust change moves. */
    @Query(
        "UPDATE identities SET trust = :trust, active = :active " +
            "WHERE account = :account AND fingerprint = :fingerprint"
    )
    fun updateIdentityStatus(
        account: String?,
        fingerprint: String?,
        trust: String?,
        active: Long?,
    ): Int

    /** `getIdentityKeyCertifcate`: the DER bytes, or `null` when the row or the column is empty. */
    @Query("SELECT certificate FROM identities WHERE account = :account AND fingerprint = :fingerprint")
    fun certificate(account: String?, fingerprint: String?): ByteArray?

    /** `setIdentityKeyCertificate`: the DER bytes onto the row that fingerprint names. */
    @Query(
        "UPDATE identities SET certificate = :certificate " +
            "WHERE account = :account AND fingerprint = :fingerprint"
    )
    fun updateCertificate(account: String?, fingerprint: String?, certificate: ByteArray?): Int

    /** `wipeAxolotlDb`. */
    @Query("DELETE FROM identities WHERE account = :account")
    fun deleteIdentitiesForAccount(account: String?): Int
}
