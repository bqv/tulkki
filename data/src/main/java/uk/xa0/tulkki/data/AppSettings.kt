package uk.xa0.tulkki.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.annotation.BoolRes
import androidx.preference.PreferenceManager
import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.crypto.R as CryptoR
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R as XmppR
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import java.security.SecureRandom

class AppSettings(private val context: Context) : AppSettingsRef {

    override fun isDeleteUnusedFiles(): Boolean =
        getBooleanPreference(DELETE_UNUSED_FILES, R.bool.delete_unused_files)

    override fun isTrustSystemCAStore(): Boolean =
        getBooleanPreference(TRUST_SYSTEM_CA_STORE, R.bool.trust_system_ca_store)

    override fun isUseTor(): Boolean = getBooleanPreference(USE_TOR, XmppR.bool.use_tor)

    override fun isExtendedConnectionOptions(): Boolean =
        getBooleanPreference(SHOW_CONNECTION_OPTIONS, R.bool.show_connection_options)

    @Synchronized
    override fun getInstallationId(): Long {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val existing = sharedPreferences.getLong(INSTALLATION_ID, 0)
        if (existing != 0L) {
            return existing
        }
        val secureRandom = SecureRandom()
        val installationId = secureRandom.nextLong()
        sharedPreferences.edit().putLong(INSTALLATION_ID, installationId).apply()
        return installationId
    }

    @Synchronized
    override fun resetInstallationId() {
        val secureRandom = SecureRandom()
        val installationId = secureRandom.nextLong()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(INSTALLATION_ID, installationId)
            .apply()
    }

    override fun isRequireChannelBinding(): Boolean =
        getBooleanPreference(REQUIRE_CHANNEL_BINDING, R.bool.require_channel_binding)

    override fun isRequireTlsV13(): Boolean =
        getBooleanPreference(REQUIRE_TLS_V1_3, R.bool.require_tls_v1_3)

    override fun isDANEnforced(): Boolean = getBooleanPreference(DANE_ENFORCED, R.bool.enforce_dane)

    override fun isUseRelays(): Boolean = getBooleanPreference(USE_RELAYS, R.bool.use_relays)

    override fun preferIPv6(): Boolean = getBooleanPreference(PREFER_IPV6, R.bool.prefer_ipv6)

    override fun getCustomResourceName(): String {
        val value =
            Strings.nullToEmpty(
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(CUSTOM_RESOURCE_NAME, ""),
                )
                .trim { it <= ' ' }
        return if (value.length > CUSTOM_RESOURCE_NAME_MAX_LENGTH) {
            value.substring(0, CUSTOM_RESOURCE_NAME_MAX_LENGTH)
        } else {
            value
        }
    }

    private fun getBooleanPreference(name: String, @BoolRes res: Int): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getBoolean(name, context.resources.getBoolean(res))
    }

    fun isPasswordOnStartupRequired(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(REQUIRE_PASSWORD_ON_STARTUP, false)

    fun getRingtone(): Uri? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val incomingCallRingtone =
            sharedPreferences.getString(RINGTONE, context.getString(R.string.incoming_call_ringtone))
        return if (Strings.isNullOrEmpty(incomingCallRingtone)) null else Uri.parse(incomingCallRingtone)
    }

    fun setRingtone(uri: Uri?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().putString(RINGTONE, if (uri == null) null else uri.toString()).apply()
    }

    fun getNotificationTone(): Uri? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val incomingCallRingtone =
            sharedPreferences.getString(
                NOTIFICATION_RINGTONE,
                context.getString(R.string.notification_ringtone),
            )
        return if (Strings.isNullOrEmpty(incomingCallRingtone)) null else Uri.parse(incomingCallRingtone)
    }

    fun setNotificationTone(uri: Uri?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences
            .edit()
            .putString(NOTIFICATION_RINGTONE, if (uri == null) null else uri.toString())
            .apply()
    }

    fun isBTBVEnabled(): Boolean = getBooleanPreference(BTBV, XmppR.bool.btbv)

    fun isAllowScreenshots(): Boolean =
        getBooleanPreference(ALLOW_SCREENSHOTS, R.bool.allow_screenshots)

    fun isColorfulChatBubbles(): Boolean =
        getBooleanPreference(COLORFUL_CHAT_BUBBLES, R.bool.use_green_background)

    fun isLargeFont(): Boolean = getBooleanPreference(LARGE_FONT, R.bool.large_font)

    fun showLinkPreviews(): Boolean =
        getBooleanPreference(SHOW_LINK_PREVIEWS, R.bool.show_link_previews)

    fun isShowAvatars(): Boolean = getBooleanPreference(SHOW_AVATARS, R.bool.show_avatars)

    fun isCallIntegration(): Boolean = getBooleanPreference(CALL_INTEGRATION, R.bool.call_integration)

    fun isAlignStart(): Boolean = getBooleanPreference(ALIGN_START, R.bool.align_start)

    fun isSecureTLS(): Boolean = getBooleanPreference(SECURE_TLS, R.bool.secure_tls)

    fun isUseI2P(): Boolean = getBooleanPreference(USE_I2P, XmppR.bool.use_i2p)

    fun getOmemo(): String? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getString(
            OMEMO,
            context.getString(CryptoR.string.omemo_setting_default),
        )
    }

    fun getBackupLocation(): Uri {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val location = sharedPreferences.getString(BACKUP_LOCATION, null)
        if (location == null || location.isEmpty()) {
            val directory = FileBackend.getBackupDirectory(context)
            return Uri.fromFile(directory)
        }
        return Uri.parse(location)
    }

    fun getBackupLocationAsPath(): String = asPath(getBackupLocation())

    fun setBackupLocation(uri: Uri?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences
            .edit()
            .putString(BACKUP_LOCATION, if (uri == null) "" else uri.toString())
            .apply()
    }

    fun isSendCrashReports(): Boolean =
        getBooleanPreference(SEND_CRASH_REPORTS, R.bool.send_crash_reports)

    fun setSendCrashReports(value: Boolean) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().putBoolean(SEND_CRASH_REPORTS, value).apply()
    }

    fun getDatabasePasswordChars(): CharArray? {
        if (isPasswordOnStartupRequired()) {
            val session = sSessionPassword
            if (session == null) {
                throw EncryptionException(
                    "Database requires startup password",
                    null,
                    EncryptionException.Reason.NEEDS_SESSION_PASSWORD,
                )
            }
            return session.clone() // no String ever created in this path
        }
        try {
            return SecurePasswordStorage(context).readPassword()
        } catch (e: EncryptionException) {
            throw e
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not read secure password storage", e)
            throw EncryptionException("Could not read secure password storage", e)
        }
    }

    @Throws(EncryptionException::class)
    fun setDatabasePassword(password: CharArray?) {
        if (password != null && password.isEmpty()) {
            throw IllegalArgumentException("Password cannot be empty")
        }
        if (isPasswordOnStartupRequired()) {
            // Never write to disk in startup-required mode; update the in-memory session copy.
            if (password == null) {
                // Encryption disabled - clear session and remove the startup-required flag.
                clearSessionPassword()
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(REQUIRE_PASSWORD_ON_STARTUP, false)
                    .commit()
            } else {
                setSessionPassword(password) // no String created
            }
            // Purge any stale entry that might exist in persistent storage.
            clearPersistedDatabasePassword()
            return
        }
        try {
            SecurePasswordStorage(context).writePassword(password)
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not write to secure password storage", e)
            throw EncryptionException("Could not write to secure password storage", e)
        }
    }

    /** Erase the database password from persistent storage. */
    fun clearPersistedDatabasePassword() {
        try {
            SecurePasswordStorage(context).writePassword(null)
        } catch (e: Exception) {
        }
    }

    /** Returns true if the database is encrypted using Argon2id + KeyStore HMAC raw key. */
    fun isArgon2idKdf(): Boolean =
        DB_KDF_ARGON2ID ==
            PreferenceManager.getDefaultSharedPreferences(context).getString(DB_KDF_VERSION, null)

    /** Marks the database as using Argon2id KDF. Called after a successful migration. */
    fun setArgon2idKdf() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(DB_KDF_VERSION, DB_KDF_ARGON2ID)
            .commit()
    }

    /** Marks the databases as using auto-encryption mode. */
    fun setAutoKeyMode() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(DB_KDF_VERSION, DB_KDF_AUTO)
            .commit()
    }

    /**
     * Returns the stored 32-byte auto-key for the main DB, generating and persisting a new one if absent.
     * Sets `DB_KDF_VERSION = "auto"` on first generation. Caller must zero the returned array after use.
     */
    fun getOrCreateAutoKey(): ByteArray {
        val storage = SecurePasswordStorage(context)
        val existing = storage.readAutoKey()
        if (existing != null) return existing
        val newKey = Argon2KeyDerivation.generateRandomKey()
        storage.writeAutoKey(newKey)
        setAutoKeyMode()
        return newKey
    }

    /**
     * Returns the stored 32-byte auto-key for the UnifiedPush DB, generating and persisting a new one if
     * absent. Caller must zero the returned array after use.
     */
    fun getOrCreateAutoKeyForUpdb(): ByteArray {
        val storage = SecurePasswordStorage(context)
        val existing = storage.readAutoKeyForUpdb()
        if (existing != null) return existing
        val newKey = Argon2KeyDerivation.generateRandomKey()
        storage.writeAutoKeyForUpdb(newKey)
        return newKey
    }

    /**
     * Stores the given auto-key for the main DB. Called after a successful migration file-rename so the new
     * key is persisted only once the DB file is in place.
     */
    @Throws(EncryptionException::class)
    fun writeAutoKey(key: ByteArray) {
        try {
            SecurePasswordStorage(context).writeAutoKey(key)
        } catch (e: Exception) {
            throw EncryptionException("Failed to write auto key", e)
        }
    }

    /** Erases the main DB auto-key (called when switching to Argon2id mode). */
    fun clearAutoKey() {
        try {
            SecurePasswordStorage(context).writeAutoKey(null)
        } catch (e: Exception) {
        }
    }

    /**
     * Stores the given auto-key for the UnifiedPush DB. Called after a successful UPDB migration
     * file-rename.
     */
    @Throws(EncryptionException::class)
    fun writeAutoKeyForUpdb(key: ByteArray?) {
        try {
            SecurePasswordStorage(context).writeAutoKeyForUpdb(key)
        } catch (e: Exception) {
            throw EncryptionException("Failed to write UPDB auto key", e)
        }
    }

    /** Erases the UPDB auto-key (called when switching UPDB to Argon2id mode). */
    fun clearAutoKeyForUpdb() {
        try {
            SecurePasswordStorage(context).writeAutoKeyForUpdb(null)
        } catch (e: Exception) {
        }
    }

    /** Clears only the Argon2id salt for the main DB (does not touch the KDF version flag). */
    fun clearMainDbArgon2Salt() {
        try {
            SecurePasswordStorage(context).writeSalt(null)
        } catch (e: Exception) {
        }
    }

    /** Clears only the Argon2id salt for the UnifiedPush DB. */
    fun clearUpdbArgon2Salt() {
        try {
            SecurePasswordStorage(context).writeUpdbSalt(null)
        } catch (e: Exception) {
        }
    }

    /**
     * Returns the stored Argon2id salt for the main database, or null if not yet generated. The salt is
     * public (not secret) but must be persisted across restarts.
     */
    fun getArgon2Salt(): ByteArray? =
        try {
            SecurePasswordStorage(context).readSalt()
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not read Argon2id salt", e)
            null
        }

    /**
     * Returns the stored Argon2id salt for the UnifiedPush distributor database. Returns null when the UPDB
     * has not yet been migrated to Argon2id.
     */
    fun getArgon2SaltForUpdb(): ByteArray? =
        try {
            SecurePasswordStorage(context).readUpdbSalt()
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not read Argon2id UPDB salt", e)
            null
        }

    /** Atomically persists both the database password and the UnifiedPush DB Argon2id salt. */
    @Throws(EncryptionException::class)
    fun setUpdbPasswordAndSalt(password: CharArray?, salt: ByteArray?) {
        try {
            SecurePasswordStorage(context).writeUpdbSalt(salt)
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not persist Argon2id UPDB salt", e)
            throw EncryptionException("Could not persist Argon2id UPDB salt", e)
        }
        // The shared password is already set by setDatabasePasswordAndSalt(); don't overwrite.
    }

    /**
     * Atomically persists both the database password and the Argon2id salt so that neither can be written
     * without the other.
     */
    @Throws(EncryptionException::class)
    fun setDatabasePasswordAndSalt(password: CharArray?, salt: ByteArray) {
        if (isPasswordOnStartupRequired()) {
            // Startup-required mode: keep password off-disk, but persist the salt.
            if (password != null) {
                setSessionPassword(password)
            } else {
                clearSessionPassword()
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(REQUIRE_PASSWORD_ON_STARTUP, false)
                    .commit()
            }
            clearPersistedDatabasePassword()
            try {
                SecurePasswordStorage(context).writeSalt(salt)
            } catch (e: Exception) {
                Log.e("AppSettings", "Could not persist Argon2id salt", e)
                throw EncryptionException("Could not persist Argon2id salt", e)
            }
            return
        }
        try {
            SecurePasswordStorage(context).writePasswordAndSalt(password, salt)
        } catch (e: Exception) {
            Log.e("AppSettings", "Could not write password and salt", e)
            throw EncryptionException("Could not write password and salt", e)
        }
    }

    companion object {

        const val KEEP_FOREGROUND_SERVICE = "enable_foreground_service"
        const val AWAY_WHEN_SCREEN_IS_OFF = "away_when_screen_off"
        const val TREAT_VIBRATE_AS_SILENT = "treat_vibrate_as_silent"
        const val DND_ON_SILENT_MODE = "dnd_on_silent_mode"
        const val MANUALLY_CHANGE_PRESENCE = "manually_change_presence"
        const val BLIND_TRUST_BEFORE_VERIFICATION = "btbv"
        const val AUTOMATIC_MESSAGE_DELETION = "automatic_message_deletion"
        const val BROADCAST_LAST_ACTIVITY = "last_activity"
        const val THEME = "theme"
        const val DYNAMIC_COLORS = "dynamic_colors"
        const val SHOW_DYNAMIC_TAGS = "show_dynamic_tags"

        // The crypto island owns the key, and it is a Kotlin `const val` there, so this stays one:
        // a `@JvmField val` is a static field but not a Java compile-time constant, and
        // SecuritySettingsFragment's `switch` needs a constant case label.
        const val OMEMO = OmemoSetting.OMEMO

        const val OMEMO_AUTO_EXPIRY = "omemo_auto_expiry"
        const val ALLOW_SCREENSHOTS = "allow_screenshots"
        const val LOAD_PROVIDERS_EXTERNAL = "load_providers_list_external"
        const val RINGTONE = "call_ringtone"
        const val BTBV = "btbv"
        const val APP_LOCK_PIN = "app_lock_pin"

        const val CONFIRM_MESSAGES = "confirm_messages"
        const val ALLOW_MESSAGE_CORRECTION = "allow_message_correction"

        const val TRUST_SYSTEM_CA_STORE = "trust_system_ca_store"
        const val DANE_ENFORCED = "enforce_dane"
        const val REQUIRE_CHANNEL_BINDING = "channel_binding_required"
        const val REQUIRE_TLS_V1_3 = "require_tls_v1_3"
        const val NOTIFICATION_RINGTONE = "notification_ringtone"
        const val NOTIFICATION_HEADS_UP = "notification_headsup"
        const val NOTIFICATION_VIBRATE = "vibrate_on_notification"
        const val NOTIFICATION_LED = "led"
        const val SHOW_CONNECTION_OPTIONS = "show_connection_options"
        const val USE_TOR = "use_tor"
        const val USE_I2P = "use_i2p"
        const val USE_RELAYS = "use_relays"
        const val CHANNEL_DISCOVERY_METHOD = "channel_discovery_method"
        const val SEND_CRASH_REPORTS = "send_crash_reports"
        const val COLORFUL_CHAT_BUBBLES = "use_green_background"
        const val LARGE_FONT = "large_font"
        const val SHOW_LINK_PREVIEWS = "show_link_previews"
        const val SHOW_AVATARS = "show_avatars"
        const val CALL_INTEGRATION = "call_integration"
        const val ALIGN_START = "align_start"
        const val BACKUP_LOCATION = "backup_location"
        const val HIDE_EPHEMERAL_WARNING = "hide_ephemeral_warning"

        private const val ACCEPT_INVITES_FROM_STRANGERS = "accept_invites_from_strangers"
        private const val INSTALLATION_ID = "im.conversations.android.install_id"
        const val SECURE_TLS = "secure_tls"
        const val PREFER_IPV6 = "prefer_ipv6"
        const val UNENCRYPTED_REACTIONS = "allow_unencrypted_reactions"
        const val DELETE_UNUSED_FILES = "delete_unused_files"
        const val USE_INTERNAL_SECURE_STORAGE = "default_store_media_securely"
        const val REQUIRE_PASSWORD_ON_STARTUP = "require_password_on_startup"
        const val CUSTOM_RESOURCE_NAME = "custom_resource_name"
        const val CUSTOM_RESOURCE_NAME_MAX_LENGTH = 64

        // KDF version preference stored in SharedPreferences.
        //   absent or "auto"   -> auto-encryption with hardware-bound random key (default)
        //   "argon2id"         -> user-set password via Argon2id + KeyStore HMAC
        private const val DB_KDF_VERSION = "db_kdf_version"
        private const val DB_KDF_ARGON2ID = "argon2id"
        private const val DB_KDF_AUTO = "auto"

        // In-memory session password: the char[] the user typed at startup. Never written to disk. Zeroed
        // when no longer needed. Null when locked.
        @Volatile
        private var sSessionPassword: CharArray? = null

        @JvmStatic
        fun isSessionUnlocked(): Boolean = sSessionPassword != null

        /** Store the entered password for this process lifetime. Zeros any previously held value. */
        @JvmStatic
        fun setSessionPassword(password: CharArray?) {
            val old = sSessionPassword
            sSessionPassword = password?.clone()
            if (old != null) {
                java.util.Arrays.fill(old, '\u0000')
            }
        }

        /** Zero and discard the session password (e.g. on wrong-key error so the user retries). */
        @JvmStatic
        fun clearSessionPassword() {
            val pw = sSessionPassword
            sSessionPassword = null
            if (pw != null) {
                java.util.Arrays.fill(pw, '\u0000')
            }
        }

        /** Returns the live session-password array. Callers must NOT modify or zero it. */
        @JvmStatic
        fun getSessionPassword(): CharArray? = sSessionPassword

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * Tulkki: the deleted `Config.MAGIC_CREATE_DOMAIN` used to be added here too, and it is gone with
         * that constant. An account on that domain therefore no longer gets the channel binding and TLS 1.3
         * that this set forces - `isRequireChannelBinding()`/`isRequireTlsV13()`, which the owner can still
         * turn on per install.
         */
        @JvmField
        val SECURE_DOMAINS: Set<Jid> =
            ImmutableSet.Builder<Jid>()
                .apply {
                    if (Config.QUICKSY_DOMAIN != null) {
                        add(Config.QUICKSY_DOMAIN)
                    }
                }
                .build()

        @JvmStatic
        fun asPath(uri: Uri): String {
            val scheme = uri.scheme
            val path = uri.path
            if (path == null) {
                return uri.toString()
            }
            if ("file".equals(scheme, ignoreCase = true)) {
                return path
            } else if ("content".equals(scheme, ignoreCase = true)) {
                if (EXTERNAL_STORAGE_AUTHORITY.equals(uri.authority, ignoreCase = true)) {
                    val parts = Splitter.on(':').limit(2).splitToList(path)
                    if (parts.size == 2 && "/tree/primary" == parts[0]) {
                        return Joiner.on('/')
                            .join(Environment.getExternalStorageDirectory(), parts[1])
                    }
                }
            }
            return uri.toString()
        }
    }
}
