package uk.xa0.tulkki.data.omemo

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.HashSet
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import uk.xa0.tulkki.crypto.axolotl.AxolotlService
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * `omemo/`'s capability: the Signal session store's four tables, read and written.
 *
 * <p>**The `SQLiteAxolotlStore` accessors, the biggest of `port-45`'s remaining capability groups.**
 * Forty-three methods over `sessions`, `prekeys`, `signed_prekeys` and `identities`: the sessions
 * (a cursor, a load, the sub-device ids, the known names, contains/store/delete and the two deletes),
 * the one-time pre-keys, the signed pre-keys, and the identity store (the four cursor shapes, the own
 * pair, the contact keys, the trusted count, the trust and certificate writers, the pre-verification
 * and the own-pair writer), plus the two administrative bodies, `recreateAxolotlDb` and
 * `wipeAxolotlDb`. `docs/MIGRATION.md`'s `port-45` row is the inventory this comes out of; the group
 * re-measures at **43 methods / 601 body-lines**.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**Every table and column name is `OmemoQueries`'s, which is the island's own spelling.**
 * `OmemoQueries` reads them from `uk.xa0.tulkki.crypto.axolotl.SQLiteAxolotlStore`, so the persisted
 * names keep one owner. The live accessors asked for their rows with the `query` builder and a
 * selection built from those names, exactly as the Java did, so the store does too; the two places
 * that need a whole statement - `numTrustedKeys`' aggregate and `recreateAxolotlDb`'s four
 * drop-and-create pairs - take `OmemoQueries.IDENTITY_TRUSTED_COUNT_BOUND` and the four published
 * `CREATE_*` texts. The named-bind `SELECT`s beside them are the Room DAO's, not these methods'.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all twenty-nine public methods.** The
 * fourteen private helpers (`getCursorForSession`, the db-taking `getSubDeviceSessions`/
 * `deleteSession`/`loadOwnIdentityKeyPair`/`setIdentityKeyTrust`, `getCursorForPreKey`,
 * `getCursorForSignedPreKey`, the five `getIdentityKeyCursor` overloads, the seven-argument
 * `storeIdentityKey` and `recreateAxolotlDb`) move with the bodies they serve; `recreateAxolotlDb`
 * had no caller at all and lives here as public rather than as dead private code, and the four
 * forwards-only helpers collapse into the one form that takes the connection. `DatabaseBackend`'s
 * twenty-nine methods are the one delegating step, so `:data`'s `CryptoStore`, `:crypto`'s
 * `SQLiteAxolotlStore` and `PgpStore` and every other caller compile against byte for byte what they
 * did before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries the
 * un-mangled name - a Kotlin `internal` member would be emitted as `loadSession$data` and Java could
 * not see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the Java's own bodies.** The two `loadSession`/`loadPreKey`
 * "no row" answers stay nullable, as do `loadSignedPreKey`, `loadOwnIdentityKeyPair`,
 * `getFingerprintStatus` and `getIdentityKeyCertifcate`; `loadSignedPreKeys` and
 * `getKnownSignalAddresses` answer real lists, the latter's entries nullable because the Java put
 * `Cursor.getString` straight into a `List<String>`. Every `Account`, address, name, fingerprint and
 * record parameter is the caller's non-null value, as the Java dereferenced it.
 *
 * <p>**One Java quirk is carried, not repaired: `containsSignedPreKey` asks the *pre-key* cursor.**
 * The Java called `getCursorForPreKey(account, signedPreKeyId)` where every sibling uses
 * `getCursorForSignedPreKey`, so the method answers from `prekeys` rather than `signed_prekeys`. This
 * is a move, so the call is kept and named. The two `AssertionError` throws and the swallowed
 * `IOException` in `loadSignedPreKeys` are the Java's own.
 *
 * <p>**No `@Throws`: nothing here throws a checked exception the Java catches.** The `IOException`,
 * `InvalidKeyException`, `CertificateEncodingException` and `CertificateException` catches stay
 * exactly where the Java had them.
 */
object OmemoStore {

    // -- sessions ---------------------------------------------------------------------------------

    /** One session row's cursor, by the account/name/device triple. */
    private fun getCursorForSession(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ): Cursor {
        val selectionArgs = arrayOf(
            account.getUuid(), contact.getName(), contact.getDeviceId().toString(),
        )
        return db.query(
            OmemoQueries.SESSIONS_TABLE,
            null,
            OmemoQueries.ACCOUNT +
                " = ? AND " +
                OmemoQueries.NAME +
                " = ? AND " +
                OmemoQueries.DEVICE_ID +
                " = ? ",
            selectionArgs,
            null,
            null,
            null,
        )
    }

    /** The stored session for one address, or `null` when the table has no row for it. */
    @JvmStatic
    fun loadSession(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ): SessionRecord? {
        var session: SessionRecord? = null
        val cursor = getCursorForSession(db, account, contact)
        if (cursor.count != 0) {
            cursor.moveToFirst()
            try {
                session = SessionRecord(
                    Base64.decode(
                        cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY)),
                        Base64.DEFAULT,
                    ),
                )
            } catch (e: IOException) {
                cursor.close()
                throw AssertionError(e)
            }
        }
        cursor.close()
        return session
    }

    /** Every device id this name has a session for. */
    @JvmStatic
    fun getSubDeviceSessions(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ): List<Int> {
        val devices = ArrayList<Int>()
        val columns = arrayOf(OmemoQueries.DEVICE_ID)
        val selectionArgs = arrayOf(account.getUuid(), contact.getName())
        val cursor = db.query(
            OmemoQueries.SESSIONS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + " = ? AND " + OmemoQueries.NAME + " = ?",
            selectionArgs,
            null,
            null,
            null,
        )

        while (cursor.moveToNext()) {
            devices.add(cursor.getInt(cursor.getColumnIndex(OmemoQueries.DEVICE_ID)))
        }

        cursor.close()
        return devices
    }

    /** The distinct names the account holds a session for. An entry is nullable, as the column is. */
    @JvmStatic
    fun getKnownSignalAddresses(db: SQLiteDatabase, account: Account): MutableList<String?> {
        val addresses = ArrayList<String?>()
        val columns = arrayOf("DISTINCT " + OmemoQueries.NAME)
        val selectionArgs = arrayOf(account.getUuid())
        val cursor = db.query(
            OmemoQueries.SESSIONS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + " = ?",
            selectionArgs,
            null,
            null,
            null,
        )
        while (cursor.moveToNext()) {
            addresses.add(cursor.getString(0))
        }
        cursor.close()
        return addresses
    }

    /** Whether the account holds a session for that exact address. */
    @JvmStatic
    fun containsSession(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ): Boolean {
        val cursor = getCursorForSession(db, account, contact)
        val count = cursor.count
        cursor.close()
        return count != 0
    }

    /** The session row for that address, replaced if the address already has one. */
    @JvmStatic
    fun storeSession(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
        session: SessionRecord,
    ) {
        val values = ContentValues()
        values.put(OmemoQueries.NAME, contact.getName())
        values.put(OmemoQueries.DEVICE_ID, contact.getDeviceId())
        values.put(OmemoQueries.KEY, Base64.encodeToString(session.serialize(), Base64.DEFAULT))
        values.put(OmemoQueries.ACCOUNT, account.getUuid())
        db.insert(OmemoQueries.SESSIONS_TABLE, null, values)
    }

    /** That address's one session row gone. */
    @JvmStatic
    fun deleteSession(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ) {
        val args = arrayOf(
            account.getUuid(), contact.getName(), contact.getDeviceId().toString(),
        )
        db.delete(
            OmemoQueries.SESSIONS_TABLE,
            OmemoQueries.ACCOUNT +
                " = ? AND " +
                OmemoQueries.NAME +
                " = ? AND " +
                OmemoQueries.DEVICE_ID +
                " = ? ",
            args,
        )
    }

    /** Every device's session row for that name gone. */
    @JvmStatic
    fun deleteAllSessions(
        db: SQLiteDatabase,
        account: Account,
        contact: SignalProtocolAddress,
    ) {
        val args = arrayOf(account.getUuid(), contact.getName())
        db.delete(
            OmemoQueries.SESSIONS_TABLE,
            OmemoQueries.ACCOUNT + "=? AND " + OmemoQueries.NAME + " = ?",
            args,
        )
    }

    // -- one-time pre-keys ------------------------------------------------------------------------

    /** One pre-key row's cursor, by the account/id pair. */
    private fun getCursorForPreKey(db: SQLiteDatabase, account: Account, preKeyId: Int): Cursor {
        val columns = arrayOf(OmemoQueries.KEY)
        val selectionArgs = arrayOf(account.getUuid(), preKeyId.toString())
        return db.query(
            OmemoQueries.PREKEYS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + "=? AND " + OmemoQueries.ID + "=?",
            selectionArgs,
            null,
            null,
            null,
        )
    }

    /** The stored one-time pre-key, or `null` when the table has no row for it. */
    @JvmStatic
    fun loadPreKey(db: SQLiteDatabase, account: Account, preKeyId: Int): PreKeyRecord? {
        var record: PreKeyRecord? = null
        val cursor = getCursorForPreKey(db, account, preKeyId)
        if (cursor.count != 0) {
            cursor.moveToFirst()
            try {
                record = PreKeyRecord(
                    Base64.decode(
                        cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY)),
                        Base64.DEFAULT,
                    ),
                )
            } catch (e: IOException) {
                throw AssertionError(e)
            }
        }
        cursor.close()
        return record
    }

    /** Whether the account still holds that one-time pre-key. */
    @JvmStatic
    fun containsPreKey(db: SQLiteDatabase, account: Account, preKeyId: Int): Boolean {
        val cursor = getCursorForPreKey(db, account, preKeyId)
        val count = cursor.count
        cursor.close()
        return count != 0
    }

    /** The one-time pre-key written, replaced on the table's own `UNIQUE(account, id)`. */
    @JvmStatic
    fun storePreKey(db: SQLiteDatabase, account: Account, record: PreKeyRecord) {
        val values = ContentValues()
        values.put(OmemoQueries.ID, record.getId())
        values.put(OmemoQueries.KEY, Base64.encodeToString(record.serialize(), Base64.DEFAULT))
        values.put(OmemoQueries.ACCOUNT, account.getUuid())
        db.insert(OmemoQueries.PREKEYS_TABLE, null, values)
    }

    /** The one-time pre-key consumed; the answer is the row count the Java returned. */
    @JvmStatic
    fun deletePreKey(db: SQLiteDatabase, account: Account, preKeyId: Int): Int {
        val args = arrayOf(account.getUuid(), preKeyId.toString())
        return db.delete(
            OmemoQueries.PREKEYS_TABLE,
            OmemoQueries.ACCOUNT + "=? AND " + OmemoQueries.ID + "=?",
            args,
        )
    }

    // -- signed pre-keys --------------------------------------------------------------------------

    /** One signed pre-key row's cursor, by the account/id pair. */
    private fun getCursorForSignedPreKey(
        db: SQLiteDatabase,
        account: Account,
        signedPreKeyId: Int,
    ): Cursor {
        val columns = arrayOf(OmemoQueries.KEY)
        val selectionArgs = arrayOf(account.getUuid(), signedPreKeyId.toString())
        return db.query(
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + "=? AND " + OmemoQueries.ID + "=?",
            selectionArgs,
            null,
            null,
            null,
        )
    }

    /** The stored signed pre-key, or `null` when the table has no row for it. */
    @JvmStatic
    fun loadSignedPreKey(
        db: SQLiteDatabase,
        account: Account,
        signedPreKeyId: Int,
    ): SignedPreKeyRecord? {
        var record: SignedPreKeyRecord? = null
        val cursor = getCursorForSignedPreKey(db, account, signedPreKeyId)
        if (cursor.count != 0) {
            cursor.moveToFirst()
            try {
                record = SignedPreKeyRecord(
                    Base64.decode(
                        cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY)),
                        Base64.DEFAULT,
                    ),
                )
            } catch (e: IOException) {
                throw AssertionError(e)
            }
        }
        cursor.close()
        return record
    }

    /** Every signed pre-key the account holds; an unreadable row is skipped, as the Java did. */
    @JvmStatic
    fun loadSignedPreKeys(db: SQLiteDatabase, account: Account): List<SignedPreKeyRecord> {
        val prekeys = ArrayList<SignedPreKeyRecord>()
        val columns = arrayOf(OmemoQueries.KEY)
        val selectionArgs = arrayOf(account.getUuid())
        val cursor = db.query(
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + "=?",
            selectionArgs,
            null,
            null,
            null,
        )

        while (cursor.moveToNext()) {
            try {
                prekeys.add(
                    SignedPreKeyRecord(
                        Base64.decode(
                            cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY)),
                            Base64.DEFAULT,
                        ),
                    ),
                )
            } catch (e: IOException) {
                // as the Java: a row that will not parse is not a key the account holds
            }
        }
        cursor.close()
        return prekeys
    }

    /** How many signed pre-keys the account holds. */
    @JvmStatic
    fun getSignedPreKeysCount(db: SQLiteDatabase, account: Account): Int {
        val columns = arrayOf("count(" + OmemoQueries.KEY + ")")
        val selectionArgs = arrayOf(account.getUuid())
        val cursor = db.query(
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            columns,
            OmemoQueries.ACCOUNT + "=?",
            selectionArgs,
            null,
            null,
            null,
        )
        val count: Int
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        } else {
            count = 0
        }
        cursor.close()
        return count
    }

    /**
     * **The Java's own cursor, and it is the pre-key one.** Every sibling asks
     * `getCursorForSignedPreKey`; this method asks `getCursorForPreKey`, so it answers from
     * `prekeys` rather than `signed_prekeys`. Carried, not repaired: this is a move.
     */
    @JvmStatic
    fun containsSignedPreKey(db: SQLiteDatabase, account: Account, signedPreKeyId: Int): Boolean {
        val cursor = getCursorForPreKey(db, account, signedPreKeyId)
        val count = cursor.count
        cursor.close()
        return count != 0
    }

    /** The signed pre-key written, replaced on the table's own `UNIQUE(account, id)`. */
    @JvmStatic
    fun storeSignedPreKey(db: SQLiteDatabase, account: Account, record: SignedPreKeyRecord) {
        val values = ContentValues()
        values.put(OmemoQueries.ID, record.getId())
        values.put(OmemoQueries.KEY, Base64.encodeToString(record.serialize(), Base64.DEFAULT))
        values.put(OmemoQueries.ACCOUNT, account.getUuid())
        db.insert(OmemoQueries.SIGNED_PREKEYS_TABLE, null, values)
    }

    /** The signed pre-key gone. */
    @JvmStatic
    fun deleteSignedPreKey(db: SQLiteDatabase, account: Account, signedPreKeyId: Int) {
        val args = arrayOf(account.getUuid(), signedPreKeyId.toString())
        db.delete(
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            OmemoQueries.ACCOUNT + "=? AND " + OmemoQueries.ID + "=?",
            args,
        )
    }

    // -- identity keys ----------------------------------------------------------------------------

    /**
     * The identity rows' cursor: account always, then the optional name, fingerprint and own
     * clauses the Java's four forwarding overloads each supplied a subset of. The four columns are
     * the ones `FingerprintStatus.fromCursor` reads.
     */
    private fun getIdentityKeyCursor(
        db: SQLiteDatabase,
        account: Account,
        name: String?,
        own: Boolean?,
        fingerprint: String?,
    ): Cursor {
        val columns = arrayOf(
            OmemoQueries.TRUST,
            OmemoQueries.ACTIVE,
            OmemoQueries.LAST_ACTIVATION,
            OmemoQueries.KEY,
        )
        val selectionArgs = ArrayList<String?>(4)
        selectionArgs.add(account.getUuid())
        var selectionString = OmemoQueries.ACCOUNT + " = ?"
        if (name != null) {
            selectionArgs.add(name)
            selectionString += " AND " + OmemoQueries.NAME + " = ?"
        }
        if (fingerprint != null) {
            selectionArgs.add(fingerprint)
            selectionString += " AND " + OmemoQueries.FINGERPRINT + " = ?"
        }
        if (own != null) {
            selectionArgs.add(if (own) "1" else "0")
            selectionString += " AND " + OmemoQueries.OWN + " = ?"
        }
        return db.query(
            OmemoQueries.IDENTITIES_TABLE,
            columns,
            selectionString,
            selectionArgs.toTypedArray(),
            null,
            null,
            null,
        )
    }

    /** The account's own identity pair, or `null` when the table has none. */
    @JvmStatic
    fun loadOwnIdentityKeyPair(db: SQLiteDatabase, account: Account): IdentityKeyPair? {
        val name = account.getJid().asBareJid().toString()
        var identityKeyPair: IdentityKeyPair? = null
        val cursor = getIdentityKeyCursor(db, account, name, true, null)
        if (cursor.count != 0) {
            cursor.moveToFirst()
            try {
                identityKeyPair = IdentityKeyPair(
                    Base64.decode(
                        cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY)),
                        Base64.DEFAULT,
                    ),
                )
            } catch (e: InvalidKeyException) {
                Log.d(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account) +
                        "Encountered invalid IdentityKey in database for account" +
                        account.getJid().asBareJid() +
                        ", address: " +
                        name,
                )
            }
        }
        cursor.close()

        return identityKeyPair
    }

    /** That name's identity keys, with no status filter. */
    @JvmStatic
    fun loadIdentityKeys(db: SQLiteDatabase, account: Account, name: String): Set<IdentityKey> =
        loadIdentityKeys(db, account, name, null)

    /** That name's identity keys, optionally only those whose stored status equals `status`. */
    @JvmStatic
    fun loadIdentityKeys(
        db: SQLiteDatabase,
        account: Account,
        name: String,
        status: FingerprintStatus?,
    ): Set<IdentityKey> {
        val identityKeys = HashSet<IdentityKey>()
        val cursor = getIdentityKeyCursor(db, account, name, false, null)

        while (cursor.moveToNext()) {
            if (status != null && FingerprintStatus.fromCursor(cursor) != status) {
                continue
            }
            try {
                val key = cursor.getString(cursor.getColumnIndex(OmemoQueries.KEY))
                if (key != null) {
                    identityKeys.add(IdentityKey(Base64.decode(key, Base64.DEFAULT), 0))
                } else {
                    Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(account) +
                            "Missing key (possibly preverified) in database for account" +
                            account.getJid().asBareJid() +
                            ", address: " +
                            name,
                    )
                }
            } catch (e: InvalidKeyException) {
                Log.d(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account) +
                        "Encountered invalid IdentityKey in database for account" +
                        account.getJid().asBareJid() +
                        ", address: " +
                        name,
                )
            }
        }
        cursor.close()

        return identityKeys
    }

    /** How many of that name's keys are trusted or verified and still active. */
    @JvmStatic
    fun numTrustedKeys(db: SQLiteDatabase, account: Account, name: String): Long {
        val args = arrayOf(
            account.getUuid(),
            name,
            FingerprintStatus.Trust.TRUSTED.toString(),
            FingerprintStatus.Trust.VERIFIED.toString(),
            FingerprintStatus.Trust.VERIFIED_X509.toString(),
        )
        db.rawQuery(OmemoQueries.IDENTITY_TRUSTED_COUNT_BOUND, args).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 0
    }

    /**
     * The identity row's update, or its insert when no row named the triple; the seven columns are
     * the ones `storeIdentityKey` and `storePreVerification` write.
     */
    private fun storeIdentityKey(
        db: SQLiteDatabase,
        account: Account,
        name: String,
        own: Boolean,
        fingerprint: String,
        base64Serialized: String,
        status: FingerprintStatus,
    ) {
        val values = ContentValues()
        values.put(OmemoQueries.ACCOUNT, account.getUuid())
        values.put(OmemoQueries.NAME, name)
        values.put(OmemoQueries.OWN, if (own) 1 else 0)
        values.put(OmemoQueries.FINGERPRINT, fingerprint)
        values.put(OmemoQueries.KEY, base64Serialized)
        values.putAll(status.toContentValues())
        val where = OmemoQueries.ACCOUNT +
            "=? AND " +
            OmemoQueries.NAME +
            "=? AND " +
            OmemoQueries.FINGERPRINT +
            " =?"
        val whereArgs = arrayOf(account.getUuid(), name, fingerprint)
        val rows = db.update(OmemoQueries.IDENTITIES_TABLE, values, where, whereArgs)
        if (rows == 0) {
            db.insert(OmemoQueries.IDENTITIES_TABLE, null, values)
        }
    }

    /** A pre-verified contact key: no serialized key of its own, so `key` keeps the file's value. */
    @JvmStatic
    fun storePreVerification(
        db: SQLiteDatabase,
        account: Account,
        name: String,
        fingerprint: String,
        status: FingerprintStatus,
    ) {
        val values = ContentValues()
        values.put(OmemoQueries.ACCOUNT, account.getUuid())
        values.put(OmemoQueries.NAME, name)
        values.put(OmemoQueries.OWN, 0)
        values.put(OmemoQueries.FINGERPRINT, fingerprint)
        values.putAll(status.toContentValues())
        db.insert(OmemoQueries.IDENTITIES_TABLE, null, values)
    }

    /** One fingerprint's status, or `null` when the table has no row for it. */
    @JvmStatic
    fun getFingerprintStatus(
        db: SQLiteDatabase,
        account: Account,
        fingerprint: String,
    ): FingerprintStatus? {
        val cursor = getIdentityKeyCursor(db, account, null, null, fingerprint)
        val status: FingerprintStatus?
        if (cursor.count > 0) {
            cursor.moveToFirst()
            status = FingerprintStatus.fromCursor(cursor)
        } else {
            status = null
        }
        cursor.close()
        return status
    }

    /** The trust and activity of one fingerprint's row moved; one row updated, or `false`. */
    @JvmStatic
    fun setIdentityKeyTrust(
        db: SQLiteDatabase,
        account: Account,
        fingerprint: String,
        fingerprintStatus: FingerprintStatus,
    ): Boolean {
        val selectionArgs = arrayOf(account.getUuid(), fingerprint)
        val rows = db.update(
            OmemoQueries.IDENTITIES_TABLE,
            fingerprintStatus.toContentValues(),
            OmemoQueries.ACCOUNT + " = ? AND " + OmemoQueries.FINGERPRINT + " = ? ",
            selectionArgs,
        )
        return rows == 1
    }

    /** The certificate's DER bytes onto that fingerprint's row; `false` if it will not encode. */
    @JvmStatic
    fun setIdentityKeyCertificate(
        db: SQLiteDatabase,
        account: Account,
        fingerprint: String,
        x509Certificate: X509Certificate,
    ): Boolean {
        val selectionArgs = arrayOf(account.getUuid(), fingerprint)
        try {
            val values = ContentValues()
            values.put(OmemoQueries.CERTIFICATE, x509Certificate.getEncoded())
            return db.update(
                OmemoQueries.IDENTITIES_TABLE,
                values,
                OmemoQueries.ACCOUNT + " = ? AND " + OmemoQueries.FINGERPRINT + " = ? ",
                selectionArgs,
            ) == 1
        } catch (e: CertificateEncodingException) {
            Log.d(Config.LOGTAG, "could not encode certificate")
            return false
        }
    }

    /** The certificate on that fingerprint's row, or `null` when there is none usable. */
    @JvmStatic
    fun getIdentityKeyCertifcate(
        db: SQLiteDatabase,
        account: Account,
        fingerprint: String,
    ): X509Certificate? {
        val selectionArgs = arrayOf(account.getUuid(), fingerprint)
        val colums = arrayOf(OmemoQueries.CERTIFICATE)
        val selection = OmemoQueries.ACCOUNT + " = ? AND " + OmemoQueries.FINGERPRINT + " = ? "
        val cursor = db.query(
            OmemoQueries.IDENTITIES_TABLE,
            colums,
            selection,
            selectionArgs,
            null,
            null,
            null,
        )
        if (cursor.count < 1) {
            return null
        } else {
            cursor.moveToFirst()
            val certificate = cursor.getBlob(cursor.getColumnIndex(OmemoQueries.CERTIFICATE))
            cursor.close()
            if (certificate == null || certificate.size == 0) {
                return null
            }
            try {
                val certificateFactory = CertificateFactory.getInstance("X.509")
                return certificateFactory.generateCertificate(
                    ByteArrayInputStream(certificate),
                ) as X509Certificate
            } catch (e: CertificateException) {
                Log.d(Config.LOGTAG, "certificate exception " + e.message)
                return null
            }
        }
    }

    /** A contact's identity key, hex-fingerprinted and base64-serialized. */
    @JvmStatic
    fun storeIdentityKey(
        db: SQLiteDatabase,
        account: Account,
        name: String,
        identityKey: IdentityKey,
        status: FingerprintStatus,
    ) {
        storeIdentityKey(
            db,
            account,
            name,
            false,
            CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()),
            Base64.encodeToString(identityKey.serialize(), Base64.DEFAULT),
            status,
        )
    }

    /** The account's own pair, under its bare JID and marked verified and inactive. */
    @JvmStatic
    fun storeOwnIdentityKeyPair(
        db: SQLiteDatabase,
        account: Account,
        identityKeyPair: IdentityKeyPair,
    ) {
        storeIdentityKey(
            db,
            account,
            account.getJid().asBareJid().toString(),
            true,
            CryptoHelper.bytesToHex(identityKeyPair.getPublicKey().serialize()),
            Base64.encodeToString(identityKeyPair.serialize(), Base64.DEFAULT),
            FingerprintStatus.createActiveVerified(false),
        )
    }

    // -- administration ---------------------------------------------------------------------------

    /**
     * The four tables dropped and recreated from `OmemoQueries`' own `CREATE`s.
     *
     * <p>**The Java kept this private and nothing called it.** It moves as public rather than as a
     * dead private method the store cannot be asked for; the class that held it drops it.
     */
    @JvmStatic
    fun recreateAxolotlDb(db: SQLiteDatabase) {
        Log.d(
            Config.LOGTAG,
            OmemoSessionPort.LOGPREFIX + " : " + ">>> (RE)CREATING AXOLOTL DATABASE <<<",
        )
        db.execSQL("DROP TABLE IF EXISTS " + OmemoQueries.SESSIONS_TABLE)
        db.execSQL(OmemoQueries.CREATE_SESSIONS)
        db.execSQL("DROP TABLE IF EXISTS " + OmemoQueries.PREKEYS_TABLE)
        db.execSQL(OmemoQueries.CREATE_PREKEYS)
        db.execSQL("DROP TABLE IF EXISTS " + OmemoQueries.SIGNED_PREKEYS_TABLE)
        db.execSQL(OmemoQueries.CREATE_SIGNED_PREKEYS)
        db.execSQL("DROP TABLE IF EXISTS " + OmemoQueries.IDENTITIES_TABLE)
        db.execSQL(OmemoQueries.CREATE_IDENTITIES)
    }

    /** Every row of the account's four tables gone, in the Java's order. */
    @JvmStatic
    fun wipeAxolotlDb(db: SQLiteDatabase, account: Account) {
        val accountName = account.getUuid()
        Log.d(
            Config.LOGTAG,
            AxolotlService.getLogprefix(account) +
                ">>> WIPING AXOLOTL DATABASE FOR ACCOUNT " +
                accountName +
                " <<<",
        )
        val deleteArgs = arrayOf(accountName)
        db.delete(
            OmemoQueries.SESSIONS_TABLE,
            OmemoQueries.ACCOUNT + " = ?",
            deleteArgs,
        )
        db.delete(
            OmemoQueries.PREKEYS_TABLE,
            OmemoQueries.ACCOUNT + " = ?",
            deleteArgs,
        )
        db.delete(
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            OmemoQueries.ACCOUNT + " = ?",
            deleteArgs,
        )
        db.delete(
            OmemoQueries.IDENTITIES_TABLE,
            OmemoQueries.ACCOUNT + " = ?",
            deleteArgs,
        )
    }
}
