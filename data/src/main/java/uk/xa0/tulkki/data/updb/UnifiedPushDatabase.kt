package uk.xa0.tulkki.data.updb

import android.content.Context
import android.database.DatabaseUtils
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import java.io.File
import java.util.concurrent.Callable
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.Argon2KeyDerivation
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.SecurePasswordStorage
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.EncryptionException

/** The distributor's file. One name, one place: `getDatabasePath` resolves it from here. */
private const val DATABASE_NAME = "unified-push-distributor"

/** The file's version, and the `@Database`'s: 1, because no device has ever taken a step. */
private const val DATABASE_VERSION = 1

private const val REKEY_MIGRATION_IN_PROGRESS = "rekey_migration_updb_in_progress"

/**
 * The UnifiedPush distributor's own Room database, in its own file `unified-push-distributor`.
 *
 * <p>It is a second database, not a second table: a different file, different key material
 * ([getKeyBytesForUpdb]), and its own lifecycle (`closeInstance`) - the reasons `docs/MIGRATION.md`,
 * "Design: the data layer" §2.6 gives for keeping it out of `HistoryDatabase`. Merging them would
 * put a one-version table behind a 77-version migration chain and would let a failure in the push
 * path corrupt the chat database, whose recovery is deliberately the opposite of this file's
 * (`resetOnInterruptedMigration`).
 *
 * <p><strong>The file is adopted, not recreated, and `version` stays 1.</strong> The legacy file is
 * already at `user_version = 1` with a `push` table whose `expiration` is spelled `NUMBER`, which
 * Room cannot validate - *"Pre-packaged database has an invalid schema"*, measured rather than
 * guessed - so before Room opens it, [LegacyPreflight] rebuilds that one table with its true
 * affinities and a surrogate key, preserving every row and the `UNIQUE(instance)` guarantee. No
 * `Migration(1, 2)` is registered and the version is not bumped: no device has taken a step, and a
 * bump would put a migration in front of a file whose only change is a rebuild the preflight
 * already performs. There is deliberately no destructive fallback, for the same reason the history
 * database has none (`DestructiveMigrationBanTest` pins the spelling).
 *
 * <p><strong>Main-thread queries are allowed here, on purpose.</strong> The registration path is
 * `UnifiedPushDistributor`'s `BroadcastReceiver`, which calls
 * `UnifiedPushDatabase.getInstance(context).register(...)` on the main thread; refusing
 * main-thread queries would be a behaviour change smuggled in under a rename. Moving that call off
 * the main thread is **S5-6's work, not this change's**.
 *
 * <p>`getInstance` warms the file on `XmppConnectionService`'s background thread and is also
 * reachable from the main thread; the open is eager so that a refused open leaves no cached
 * instance behind, and the key is derived once and shared with [LegacyPreflight] so the expensive
 * Argon2id derivation does not run twice.
 */
@Database(entities = [PushEntity::class], version = DATABASE_VERSION, exportSchema = false)
abstract class UnifiedPushDatabase : RoomDatabase() {

    /** The registrations' DAO, registered by this accessor and by nothing else. */
    internal abstract fun pushDao(): PushDao

    /**
     * Records an application/instance registration, or answers whether the instance is already
     * registered to this application. The instance is unique, so the check is at most one row.
     */
    fun register(application: String, instance: String): Boolean =
        runInTransaction(
            Callable {
                val existing = pushDao().applicationByInstance(instance)
                if (existing != null) {
                    application == existing
                } else {
                    // The legacy path was `SQLiteDatabase.insert`, which swallowed a constraint
                    // violation and answered -1 rather than throwing out of a BroadcastReceiver.
                    val inserted =
                        try {
                            pushDao().insert(application, instance)
                        } catch (e: android.database.SQLException) {
                            -1L
                        }
                    if (inserted > 0L) {
                        Log.d(
                            Config.LOGTAG,
                            "inserted new application/instance tuple into unified push db",
                        )
                    }
                    true
                }
            },
        )

    /**
     * Every registration that is not this account's and transport's current one, or whose endpoint
     * has fallen outside the renewal window - the broker's renewal list.
     */
    fun getRenewals(account: String, transport: String): List<PushTarget> {
        val expiration = System.currentTimeMillis() + TIME_TO_RENEW
        return pushDao()
            .renewals(account, transport, expiration)
            .map { PushTarget(it.application, it.instance) }
    }

    /** This registration's endpoint and application, or `null` when there is nothing to send. */
    fun getEndpoint(account: String, transport: String, instance: String): ApplicationEndpoint? {
        val expiration = System.currentTimeMillis() + TIME_TO_RENEW
        val row = pushDao().endpoint(account, transport, instance, expiration) ?: return null
        return ApplicationEndpoint(row.application, row.endpoint)
    }

    /**
     * The first registration the file hands back, having removed every registration. The legacy
     * method read exactly one row before deleting, and that is what a broadcast-unregister needs.
     */
    fun deletePushTargets(): List<PushTarget> {
        val builder = ArrayList<PushTarget>()
        val first =
            try {
                pushDao().allTargets().firstOrNull()
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "unable to retrieve push targets", e)
                return builder
            }
        if (first != null) {
            builder.add(PushTarget(first.application, first.instance))
        }
        pushDao().deleteAll()
        return builder
    }

    /** Whether this account and transport have any endpoint at all. */
    fun hasEndpoints(transport: UnifiedPushTransport): Boolean {
        // port-5: the account uuid is the endpoint row's key, and an account always has one - its
        // constructor takes a non-null uuid and nothing ever clears it.
        val uuid = transport.account.getUuid() ?: return false
        return pushDao().hasEndpoints(uuid, transport.transport.toString()) > 0
    }

    /**
     * Writes the broker's endpoint onto the registration's own row and answers whether the endpoint
     * moved - the read and the write happen in one transaction, as they always have.
     */
    fun updateEndpoint(
        instance: String,
        account: String,
        transport: String,
        endpoint: String,
        expiration: Long,
    ): Boolean =
        runInTransaction(
            Callable {
                val existing = pushDao().endpointByInstance(instance)
                pushDao().updateEndpoint(instance, account, transport, endpoint, expiration)
                endpoint != existing
            },
        )

    /**
     * This account's registrations. It takes the transport the caller passes and ignores it, which
     * is what the legacy statement did; narrowing it would change what the distributor reads.
     */
    fun getPushTargets(account: String, transport: String): List<PushTarget> =
        pushDao().targetsByAccount(account).map { PushTarget(it.application, it.instance) }

    /** Removes one instance's registration. True when a row went. */
    fun deleteInstance(instance: String): Boolean = pushDao().deleteInstance(instance) >= 1

    /**
     * S5-12: every registration this account owns. Called from `DatabaseBackend.deleteAccount`, the
     * one place an account is deleted - the `history` file cannot cascade into this one, so the
     * cross-file half of the account's deletion is explicit and lives here.
     */
    fun deleteByAccount(account: String): Boolean = pushDao().deleteByAccount(account) >= 1

    /** Removes every registration an application owns. True when at least one row went. */
    fun deleteApplication(application: String): Boolean =
        pushDao().deleteApplication(application) >= 1

    /** One application/instance registration, as the distributor's broadcasts carry it. */
    class PushTarget(
        @JvmField val application: String,
        @JvmField val instance: String,
    ) {

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("application", application)
                .add("instance", instance)
                .toString()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PushTarget) return false
            return application == other.application && instance == other.instance
        }

        override fun hashCode(): Int = Objects.hashCode(application, instance)
    }

    /** One registration's application and endpoint, as the broker hands it to the application. */
    class ApplicationEndpoint(
        @JvmField val application: String,
        @JvmField val endpoint: String,
    )

    companion object {

        /**
         * How long a registered UnifiedPush endpoint stays valid, moved down out of
         * `uk.xa0.tulkki.app.services.UnifiedPushBroker` by 3.7 pair 2. The row's own lifetime is
         * the database's business, and the broker keeps no second copy.
         */
        const val TIME_TO_RENEW = 86_400_000L

        @Volatile private var instance: UnifiedPushDatabase? = null

        /** Drops the singleton, and with it the file handle. The only closer. */
        @JvmStatic
        fun closeInstance() {
            synchronized(UnifiedPushDatabase::class.java) {
                instance?.close()
                instance = null
            }
        }

        /**
         * Re-encrypts the UPDB with a new password (Argon2id) or reverts to auto-encryption.
         * Mirrors DatabaseBackend.migrate() semantics: {@code oldPassword null} = old UPDB is
         * auto-encrypted; {@code newPassword null} = new UPDB uses auto-encryption.
         */
        @JvmStatic
        @Throws(Exception::class)
        fun migrate(context: Context, oldPassword: CharArray?, newPassword: CharArray?) {
            closeInstance()
            System.loadLibrary("sqlcipher")
            val settings = AppSettings(context)
            val dbFile = context.getDatabasePath(DATABASE_NAME)

            // Generate new key material in memory; persisted only AFTER the file rename.
            val newSalt: ByteArray?
            val newAutoKey: ByteArray?
            val newRawKey: ByteArray
            if (newPassword != null) {
                newSalt = Argon2KeyDerivation.generateSalt()
                newAutoKey = null
                newRawKey = Argon2KeyDerivation.deriveRawKeyBytes(newPassword, newSalt)
            } else {
                newSalt = null
                newAutoKey = Argon2KeyDerivation.generateRandomKey()
                newRawKey = Argon2KeyDerivation.deriveAutoRawKeyBytes(newAutoKey)
            }

            try {
                if (!dbFile.exists()) {
                    persistNewKeyState(settings, newPassword, newSalt, newAutoKey)
                    return
                }

                val tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp")
                if (tempFile.exists() && !tempFile.delete()) {
                    throw java.io.IOException("Failed to delete existing temporary database file")
                }
                if (tempFile.parentFile != null &&
                    !tempFile.parentFile.exists() &&
                    !tempFile.parentFile.mkdirs()
                ) {
                    throw java.io.IOException("Failed to create database directory")
                }
                if (!tempFile.createNewFile()) {
                    throw java.io.IOException("Failed to create temporary database file")
                }

                // Derive the OLD key using the UPDB-specific salt (not the main DB's).
                val oldRawKey: ByteArray
                val oldUpdbSalt = settings.getArgon2SaltForUpdb()
                if (oldUpdbSalt != null) {
                    // UPDB was Argon2id-encrypted with user password.
                    if (oldPassword == null) {
                        throw EncryptionException(
                            "Old password required to open Argon2id-encrypted UPDB",
                            null,
                            EncryptionException.Reason.NEEDS_SESSION_PASSWORD,
                        )
                    }
                    oldRawKey =
                        Argon2KeyDerivation.deriveRawKeyBytes(oldPassword, oldUpdbSalt)
                } else {
                    // UPDB was auto-encrypted - read its auto key (never generate here).
                    val storedUpdbAutoKey = SecurePasswordStorage(context).readAutoKeyForUpdb()
                    if (storedUpdbAutoKey == null) {
                        throw EncryptionException(
                            "Cannot open auto-encrypted UPDB: auto key missing",
                            null,
                            EncryptionException.Reason.KEYSTORE_ERROR,
                        )
                    }
                    try {
                        oldRawKey = Argon2KeyDerivation.deriveAutoRawKeyBytes(storedUpdbAutoKey)
                    } finally {
                        java.util.Arrays.fill(storedUpdbAutoKey, 0.toByte())
                    }
                }

                val db: SQLiteDatabase
                try {
                    db =
                        SQLiteDatabase.openDatabase(
                            dbFile.absolutePath,
                            oldRawKey,
                            null,
                            SQLiteDatabase.OPEN_READWRITE or
                                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                            DatabaseBackend.ARGON2_DATABASE_HOOK,
                        )
                } finally {
                    java.util.Arrays.fill(oldRawKey, 0.toByte())
                }

                val version = db.version

                val isEmpty: Boolean =
                    db.rawQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table'",
                        null,
                    ).use { cursor -> version == 0 && cursor.moveToFirst() && cursor.getInt(0) == 0 }
                if (isEmpty) {
                    db.close()
                    tempFile.delete()
                    FileHelper.secureDelete(dbFile)
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-shm"))
                    persistNewKeyState(settings, newPassword, newSalt, newAutoKey)
                    return
                }

                try {
                    db.rawExecSQL("PRAGMA cipher_default_use_hmac = ON;")
                    db.rawExecSQL("PRAGMA cipher_default_memory_security = ON;")
                    // Wrap in SQL string literal (not blob literal) for SQLCipher raw-key detection.
                    val keyStr =
                        String(newRawKey, java.nio.charset.StandardCharsets.UTF_8)
                    val attachKeySql = "'" + keyStr.replace("'", "''") + "'"
                    db.rawExecSQL(
                        "ATTACH DATABASE " +
                            DatabaseUtils.sqlEscapeString(tempFile.absolutePath) +
                            " AS encrypted KEY " +
                            attachKeySql,
                    )
                    db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                    db.rawExecSQL("PRAGMA encrypted.user_version = " + version)
                    db.rawExecSQL("DETACH DATABASE encrypted;")
                } finally {
                    db.close()
                }

                val backupFile = context.getDatabasePath(DATABASE_NAME + ".bak")
                if (backupFile.exists() && !backupFile.delete()) {
                    throw java.io.IOException("Failed to delete existing backup file")
                }

                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(REKEY_MIGRATION_IN_PROGRESS, true)
                    .commit()

                var prefsUpdated = false
                try {
                    if (!dbFile.renameTo(backupFile)) {
                        throw java.io.IOException("Failed to backup old database file")
                    }
                    if (!tempFile.renameTo(dbFile)) {
                        if (!backupFile.renameTo(dbFile)) {
                            Log.e(
                                Config.LOGTAG,
                                "updb rekey: CRITICAL - failed to rollback after temp rename failure",
                            )
                        }
                        throw java.io.IOException("Failed to rename temporary database file")
                    }
                    persistNewKeyState(settings, newPassword, newSalt, newAutoKey)
                    prefsUpdated = true
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .remove(REKEY_MIGRATION_IN_PROGRESS)
                        .commit()
                    FileHelper.secureDelete(backupFile)
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-shm"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-shm"))
                } catch (e: Exception) {
                    if (!prefsUpdated) {
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .remove(REKEY_MIGRATION_IN_PROGRESS)
                            .apply()
                    }
                    throw e
                }
            } finally {
                java.util.Arrays.fill(newRawKey, 0.toByte())
                if (newAutoKey != null) java.util.Arrays.fill(newAutoKey, 0.toByte())
            }
        }

        /**
         * Persists UPDB key state after a successful file rename. For Argon2id mode: writes
         * UPDB-specific salt (password managed by DatabaseBackend). For auto mode: writes new UPDB
         * auto key, clears old UPDB salt.
         */
        private fun persistNewKeyState(
            settings: AppSettings,
            newPassword: CharArray?,
            newSalt: ByteArray?,
            newAutoKey: ByteArray?,
        ) {
            if (newPassword != null) {
                settings.setUpdbPasswordAndSalt(newPassword, newSalt)
                settings.setArgon2idKdf() // idempotent if already set by DatabaseBackend
                settings.clearAutoKeyForUpdb() // clean up any pre-existing UPDB auto key
            } else {
                // Auto mode: write new UPDB auto key first, then update state and clear old salt.
                settings.writeAutoKeyForUpdb(newAutoKey)
                settings.setAutoKeyMode() // idempotent
                settings.clearUpdbArgon2Salt() // clear old Argon2id UPDB salt if any
            }
        }

        /**
         * Derives the key bytes for the UnifiedPush distributor database. The UPDB is always
         * encrypted - either with a user password (Argon2id, UPDB-specific salt) or with a
         * hardware-bound random auto key.
         */
        private fun getKeyBytesForUpdb(context: Context): ByteArray {
            val appSettings = AppSettings(context)
            val updbSalt = appSettings.getArgon2SaltForUpdb()
            if (updbSalt != null) {
                // UPDB is Argon2id-encrypted with a user password.
                val password = appSettings.getDatabasePasswordChars() ?: throw NullPointerException()
                try {
                    return Argon2KeyDerivation.deriveRawKeyBytes(password, updbSalt)
                } finally {
                    java.util.Arrays.fill(password, '\u0000')
                }
            }
            // Auto mode: use or generate a hardware-bound random UPDB key.
            val rawAutoKey = appSettings.getOrCreateAutoKeyForUpdb()
            try {
                return Argon2KeyDerivation.deriveAutoRawKeyBytes(rawAutoKey)
            } finally {
                java.util.Arrays.fill(rawAutoKey, 0.toByte())
            }
        }

        /**
         * The distributor's database, opened. The UPDB key is derived once and handed to both the
         * preflight and Room, and the file is opened here rather than at first use so that a refused
         * open (a database key that is not available yet, which is a legitimate state in Argon2id
         * mode) leaves no cached instance behind and the next call retries.
         */
        @JvmStatic
        fun getInstance(context: Context): UnifiedPushDatabase {
            instance?.let { return it }
            synchronized(UnifiedPushDatabase::class.java) {
                instance?.let { return it }
                System.loadLibrary("sqlcipher")
                resetOnInterruptedMigration(context)
                encryptLegacyPlaintextDatabase(context)
                val appContext = context.applicationContext
                val keyBytes = getKeyBytesForUpdb(appContext)
                // Before Room opens the file: the legacy `push` table is rebuilt in place, inside
                // one transaction on one connection, so the file is never left half-adopted.
                LegacyPreflight.adopt(appContext.getDatabasePath(DATABASE_NAME), keyBytes)
                val opened =
                    Room.databaseBuilder(
                            appContext,
                            UnifiedPushDatabase::class.java,
                            DATABASE_NAME,
                        )
                        .openHelperFactory(
                            SupportOpenHelperFactory(
                                keyBytes,
                                DatabaseBackend.ARGON2_DATABASE_HOOK,
                                true,
                            ),
                        )
                        .allowMainThreadQueries()
                        .build()
                opened.openHelper.writableDatabase
                instance = opened
                return opened
            }
        }

        /**
         * If the process was killed during a UPDB rekey migration, delete all UPDB files and reset
         * the UPDB key state to auto mode. UPDB data (push endpoint registrations) is non-critical
         * and repopulated automatically, so a full crash recovery is unnecessary.
         *
         * Clearing the UPDB Argon2id salt ensures getKeyBytesForUpdb() always falls back to auto
         * mode on the next open, regardless of whether the crash happened before or after
         * persistNewKeyState() updated the prefs.
         */
        private fun resetOnInterruptedMigration(context: Context) {
            if (!PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(REKEY_MIGRATION_IN_PROGRESS, false)
            ) {
                return
            }

            Log.w(
                Config.LOGTAG,
                "updb rekey: sentinel set - resetting UPDB after interrupted migration",
            )

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp")
            val backupFile = context.getDatabasePath(DATABASE_NAME + ".bak")

            for (f in
                arrayOf(
                    dbFile,
                    File(dbFile.absolutePath + "-wal"),
                    File(dbFile.absolutePath + "-shm"),
                    tempFile,
                    File(tempFile.absolutePath + "-wal"),
                    File(tempFile.absolutePath + "-shm"),
                    backupFile,
                    File(backupFile.absolutePath + "-wal"),
                    File(backupFile.absolutePath + "-shm"),
                )) {
                if (f.exists()) FileHelper.secureDelete(f)
            }

            // Reset UPDB to auto mode so a fresh DB is created with a consistent key state.
            AppSettings(context).clearUpdbArgon2Salt()

            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(REKEY_MIGRATION_IN_PROGRESS)
                .apply()
        }

        /** Encrypts a plaintext UPDB left over from a pre-encryption release. Mirrors DatabaseBackend. */
        private fun encryptLegacyPlaintextDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists() || !DatabaseBackend.looksLikePlainSqlite(dbFile)) return

            Log.i(
                Config.LOGTAG,
                "updb rekey: plaintext database from pre-encryption release - encrypting",
            )

            val settings = AppSettings(context)
            val newAutoKey = Argon2KeyDerivation.generateRandomKey()
            val newRawKey = Argon2KeyDerivation.deriveAutoRawKeyBytes(newAutoKey)
            try {
                val tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp")
                if (tempFile.exists() && !tempFile.delete()) {
                    throw java.io.IOException("Failed to delete existing temp file")
                }
                if (!tempFile.createNewFile()) {
                    throw java.io.IOException("Failed to create temp file")
                }

                val db =
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null as ByteArray?,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                        null,
                    )
                try {
                    val version = db.version
                    val keyStr = String(newRawKey, java.nio.charset.StandardCharsets.UTF_8)
                    val attachKeySql = "'" + keyStr.replace("'", "''") + "'"
                    db.rawExecSQL(
                        "ATTACH DATABASE " +
                            DatabaseUtils.sqlEscapeString(tempFile.absolutePath) +
                            " AS encrypted KEY " +
                            attachKeySql,
                    )
                    db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                    db.rawExecSQL("PRAGMA encrypted.user_version = " + version)
                    db.rawExecSQL("DETACH DATABASE encrypted;")
                } finally {
                    db.close()
                }

                val backupFile = context.getDatabasePath(DATABASE_NAME + ".bak")
                if (backupFile.exists()) backupFile.delete()

                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(REKEY_MIGRATION_IN_PROGRESS, true)
                    .commit()

                var prefsUpdated = false
                try {
                    if (!dbFile.renameTo(backupFile)) {
                        throw java.io.IOException("Failed to rename DB to backup")
                    }
                    if (!tempFile.renameTo(dbFile)) {
                        if (!backupFile.renameTo(dbFile)) {
                            Log.e(
                                Config.LOGTAG,
                                "updb rekey: CRITICAL - could not roll back legacy encryption",
                            )
                        }
                        throw java.io.IOException("Failed to rename temp to DB")
                    }
                    settings.writeAutoKeyForUpdb(newAutoKey)
                    prefsUpdated = true
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .remove(REKEY_MIGRATION_IN_PROGRESS)
                        .commit()
                    FileHelper.secureDelete(backupFile)
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-shm"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-shm"))
                    Log.i(Config.LOGTAG, "updb rekey: legacy database successfully encrypted")
                } catch (e: Exception) {
                    if (!prefsUpdated) {
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .remove(REKEY_MIGRATION_IN_PROGRESS)
                            .apply()
                    }
                    throw e
                }
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "updb rekey: failed to encrypt legacy plaintext database", e)
            } finally {
                java.util.Arrays.fill(newRawKey, 0.toByte())
                java.util.Arrays.fill(newAutoKey, 0.toByte())
            }
        }
    }
}
