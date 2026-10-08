package uk.xa0.tulkki.data.schema

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.Argon2KeyDerivation
import uk.xa0.tulkki.data.SecurePasswordStorage
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.EncryptionException

/**
 * The history file's re-encryption, crash-safe: a full SQLCipher export into a temporary file, then
 * two renames and the key state write, in that order.
 *
 * <p>**The flag that says a swap is in flight, and the object that writes it.** The two other
 * readers of [REKEY_MIGRATION_IN_PROGRESS] live on `DatabaseBackend` still
 * (`recoverFromInterruptedMigration` and `encryptLegacyPlaintextDatabase`), because the connection
 * group moves last; `DatabaseBackend`'s own constant is now an alias of this one, so the string
 * still has one owner and no second spelling.
 *
 * <p>**The home is `schema/` and the seam is three values the caller hands in.** The migration needs
 * the database name, the SQLCipher hook the old connection is opened with, and a way to drop the
 * singleton once the swap has happened - none of which it may take from the class it is leaving
 * without creating the very dependency the move removes. So `DatabaseBackend` passes
 * `DATABASE_NAME`, `ARGON2_DATABASE_HOOK` and `DatabaseBackend::closeInstance`; [InstanceCloser] is
 * the named one-method interface, the same device `MessageExpiryStore.ColumnLookup` and
 * `AccountStore.PushRegistrationCleanup` use. `docs/MIGRATION.md`'s `port-45` row is the inventory
 * this comes out of.
 *
 * <p>**The Java-visible surface is `DatabaseBackend.migrate`'s, unchanged.** That method is now a
 * one-line `synchronized` delegation (keeping the lock the Java held), so `WelcomeActivity`'s and
 * `SecuritySettingsFragment`'s calls compile against byte for byte what they did before.
 *
 * <p>**The null contracts are read off the Java's own bodies.** `oldPassword`/`newPassword` are both
 * nullable and carry the three states the javadoc above `DatabaseBackend.migrate` names: both null
 * is auto-to-auto, `newPassword` alone is a fresh auto key, both non-null is the password change.
 * The new salt and the new auto key are each present on exactly one side of that branch, and the
 * state write proves it with `checkNotNull` where the Java would have thrown an NPE at the same
 * call. `@Throws(Exception::class)` is the Java's own `throws Exception`, not a widening.
 */
fun interface InstanceCloser {
    fun closeInstance()
}

object RekeyMigration {

    /**
     * Whether a file swap has started and not finished. Set before the first rename, cleared after
     * the last, and read on the next launch by `recoverFromInterruptedMigration`.
     */
    const val REKEY_MIGRATION_IN_PROGRESS = "rekey_migration_in_progress"

    /**
     * Re-encrypts the history database with a new password (Argon2id) or reverts it to
     * auto-encryption.
     *
     * @param oldPassword `null` when the old file is auto-encrypted; required when it is Argon2id
     * @param newPassword `null` when the new file should auto-encrypt; a fresh random key is made
     * @param dbName the history database's name, `DatabaseBackend.DATABASE_NAME`
     * @param hook the SQLCipher hook the old connection is opened with
     * @param closer drops the singleton once the swap is done, `DatabaseBackend.closeInstance`
     */
    @JvmStatic
    @Throws(Exception::class)
    fun migrate(
        context: Context,
        oldPassword: CharArray?,
        newPassword: CharArray?,
        dbName: String,
        hook: SQLiteDatabaseHook,
        closer: InstanceCloser,
    ) {
        System.loadLibrary("sqlcipher")
        val settings = AppSettings(context)
        val dbFile = context.getDatabasePath(dbName)

        // Generate new key material entirely in memory. It is persisted only AFTER the DB file
        // rename succeeds (crash-safety: if we crash before persisting, recovery opens the
        // backup with the OLD key and restores a consistent state).
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
                closer.closeInstance()
                return
            }

            val tempFile = context.getDatabasePath(dbName + ".tmp")
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

            // Derive the OLD key. We cannot call getKeyBytes() here because it reads the current
            // KDF state from prefs, which still reflects the OLD state.
            val oldRawKey: ByteArray
            if (settings.isArgon2idKdf()) {
                if (oldPassword == null) {
                    throw EncryptionException(
                        "Old password required to open Argon2id-encrypted database",
                        null,
                        EncryptionException.Reason.NEEDS_SESSION_PASSWORD,
                    )
                }
                val oldSalt = settings.getArgon2Salt()
                if (oldSalt == null) {
                    throw EncryptionException(
                        "Cannot open old Argon2id DB: salt missing",
                        null,
                        EncryptionException.Reason.KEYSTORE_ERROR,
                    )
                }
                oldRawKey = Argon2KeyDerivation.deriveRawKeyBytes(oldPassword, oldSalt)
            } else {
                // Auto mode: read the stored auto key - never generate here to avoid overwriting.
                val storedAutoKey = SecurePasswordStorage(context).readAutoKey()
                if (storedAutoKey == null) {
                    throw EncryptionException(
                        "Cannot open auto-encrypted DB: auto key missing from storage",
                        null,
                        EncryptionException.Reason.KEYSTORE_ERROR,
                    )
                }
                try {
                    oldRawKey = Argon2KeyDerivation.deriveAutoRawKeyBytes(storedAutoKey)
                } finally {
                    java.util.Arrays.fill(storedAutoKey, 0.toByte())
                }
            }

            // ENABLE_WRITE_AHEAD_LOGGING: the singleton already holds the DB in WAL mode; opening a
            // second connection without this flag causes SQLCipher to attempt
            // PRAGMA journal_mode=delete, which fails with SQLITE_BUSY on Android 8.1 and older.
            val db: SQLiteDatabase
            try {
                db =
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        oldRawKey,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or
                            SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                        hook,
                    )
            } finally {
                java.util.Arrays.fill(oldRawKey, 0.toByte())
            }

            val version = db.version

            // If the DB is brand-new and empty, skip the export and just configure prefs.
            val isEmpty: Boolean =
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table'", null).use {
                    cursor -> version == 0 && cursor.moveToFirst() && cursor.getInt(0) == 0
                }
            if (isEmpty) {
                db.close()
                tempFile.delete()
                FileHelper.secureDelete(dbFile)
                FileHelper.secureDelete(java.io.File(dbFile.absolutePath + "-wal"))
                FileHelper.secureDelete(java.io.File(dbFile.absolutePath + "-shm"))
                persistNewKeyState(settings, newPassword, newSalt, newAutoKey)
                closer.closeInstance()
                return
            }

            try {
                db.rawExecSQL("PRAGMA cipher_default_use_hmac = ON;")
                db.rawExecSQL("PRAGMA cipher_default_memory_security = ON;")
                // CRITICAL: wrap in SQL string literal, not blob literal, so SQLCipher detects the
                // x'...' prefix and uses raw-key mode (see SQLCipher API docs for KEY).
                val keyStr = String(newRawKey, java.nio.charset.StandardCharsets.UTF_8)
                val attachKeySql = "'" + keyStr.replace("'", "''") + "'"
                db.rawExecSQL(
                    "ATTACH DATABASE " +
                        android.database.DatabaseUtils.sqlEscapeString(tempFile.absolutePath) +
                        " AS encrypted KEY " +
                        attachKeySql,
                )
                db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                db.rawExecSQL("PRAGMA encrypted.user_version = " + version)
                db.rawExecSQL("DETACH DATABASE encrypted;")
            } finally {
                db.close()
            }

            val backupFile = context.getDatabasePath(dbName + ".bak")
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
                            "rekey: CRITICAL - failed to rollback after temp rename failure",
                        )
                    }
                    throw java.io.IOException("Failed to rename temporary database file")
                }
                // Persist new key state AFTER the file rename so the stored key always matches the
                // DB file on disk (crash-safety invariant for recoverFromInterruptedMigration).
                persistNewKeyState(settings, newPassword, newSalt, newAutoKey)
                prefsUpdated = true
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .remove(REKEY_MIGRATION_IN_PROGRESS)
                    .commit()
                FileHelper.secureDelete(backupFile)
                FileHelper.secureDelete(java.io.File(backupFile.absolutePath + "-wal"))
                FileHelper.secureDelete(java.io.File(backupFile.absolutePath + "-shm"))
                FileHelper.secureDelete(java.io.File(dbFile.absolutePath + "-wal"))
                FileHelper.secureDelete(java.io.File(dbFile.absolutePath + "-shm"))
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
        closer.closeInstance()
    }

    /**
     * Persists the new KDF state after a successful DB file rename. For Argon2id mode: writes the
     * password and salt, sets the KDF flag, clears any auto key. For auto mode: writes the new auto
     * key, sets auto KDF, clears the old password and salt.
     *
     * <p>The `checkNotNull`s are the branch's own invariant, not `!!`: the salt exists exactly when a
     * password was set and the auto key exactly when one was not, and the Java would have thrown an
     * NPE at the same call had either been absent.
     */
    private fun persistNewKeyState(
        settings: AppSettings,
        newPassword: CharArray?,
        newSalt: ByteArray?,
        newAutoKey: ByteArray?,
    ) {
        if (newPassword != null) {
            settings.setDatabasePasswordAndSalt(
                newPassword,
                checkNotNull(newSalt) { "password mode without a salt" },
            )
            settings.setArgon2idKdf()
            settings.clearAutoKey()
        } else {
            settings.writeAutoKey(checkNotNull(newAutoKey) { "auto mode without an auto key" })
            settings.setAutoKeyMode()
            settings.clearPersistedDatabasePassword()
            settings.clearMainDbArgon2Salt()
        }
    }
}
