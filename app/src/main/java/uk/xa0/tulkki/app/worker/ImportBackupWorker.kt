package uk.xa0.tulkki.app.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.common.base.Stopwatch
import com.google.common.base.Strings
import com.google.common.io.CountingInputStream
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import javax.crypto.BadPaddingException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.app.services.AbstractContactListSyncService
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.crypto.axolotl.SQLiteAxolotlStore
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.backup.BackupImport
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.BackupFileHeader

/**
 * The backup importer, ported line for line.
 *
 * <p>The Java's three final fields were assigned from the worker's input data in the constructor;
 * Kotlin has no constructor body before `super` returns, so they are `val`s assigned in an `init`
 * block, which runs after `Worker`'s own construction and before `doWork`. `password` is nullable
 * because `Data.getString` is: the Java stored a possibly-null `String` there too.
 *
 * <p>`data` and `Reason.valueOfOrGeneric` are `@JvmStatic` because `UiAppHost` still calls both as
 * Java statics, and `TAG_IMPORT_BACKUP` is a companion `const val` so the same caller can read it as
 * a `public static final` field. `SQLiteNotADatabaseException` stays fully qualified in the catch, as
 * the Java had it, because it is the one type this file uses from that package.
 */
class ImportBackupWorker(context: Context, workerParams: WorkerParameters) :
        Worker(context, workerParams) {

    private val password: String?
    private val uri: Uri
    private val includeOmemo: Boolean

    private var lastNotificationUpdate = 0L

    init {
        val inputData = workerParams.inputData
        password = inputData.getString(DATA_KEY_PASSWORD)
        uri = Uri.parse(inputData.getString(DATA_KEY_URI))
        includeOmemo = inputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true)
    }

    override fun doWork(): Result {
        setForegroundAsync(getForegroundInfo())
        return try {
            importBackup(uri, password)
        } catch (e: FileNotFoundException) {
            failure(Reason.FILE_NOT_FOUND)
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "error restoring backup $uri", e)
            val throwable = e.cause
            if (throwable is BadPaddingException || e is ZipException) {
                failure(Reason.DECRYPTION_FAILED)
            } else {
                failure(Reason.GENERIC)
            }
        } finally {
            applicationContext
                    .getSystemService(NotificationManager::class.java)
                    .cancel(NOTIFICATION_ID)
        }
    }

    override fun getForegroundInfo(): ForegroundInfo {
        val notification = createImportBackupNotification(1, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun importBackup(uri: Uri, password: String?): Result {
        val context = applicationContext
        val database = DatabaseBackend.getInstance(context)
        Log.d(Config.LOGTAG, "importing backup from $uri")
        val stopwatch = Stopwatch.createStarted()
        // S5-6: the restore writes through `:data`'s import surface, never through a handle of its
        // own. `BackupImport.get` is the same open the app has, and the only thing this worker still
        // catches here is the one state that is the file's rather than the row's: a database whose
        // key does not match, which the import deletes and re-creates.
        var importer: BackupImport
        try {
            importer = BackupImport.get(context)
        } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
            Log.d(Config.LOGTAG, "database is encrypted but we don't have the password. deleting it to proceed with import")
            DatabaseBackend.closeInstance()
            val dbFile = context.getDatabasePath("history")
            FileHelper.secureDelete(dbFile)
            FileHelper.secureDelete(File(dbFile.getAbsolutePath() + "-wal"))
            FileHelper.secureDelete(File(dbFile.getAbsolutePath() + "-shm"))
            if (dbFile.exists()) {
                throw e
            }
            // Re-create via the same surface so the new DB uses the current AppSettings password,
            // not the stale constructor-time password from the closed helper.
            importer = BackupImport.get(context)
        }
        val path = uri.getPath()
        val fileSize: Long
        val inputStream: InputStream?
        if ("file" == uri.getScheme() && path != null) {
            val file = File(path)
            inputStream = FileInputStream(file)
            fileSize = file.length()
        } else {
            val returnCursor = context.contentResolver.query(uri, null, null, null, null)
            if (returnCursor == null) {
                fileSize = 0
            } else {
                returnCursor.moveToFirst()
                fileSize =
                        returnCursor.getLong(
                                returnCursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                returnCursor.close()
            }
            inputStream = context.contentResolver.openInputStream(uri)
        }
        if (inputStream == null) {
            return failure(Reason.FILE_NOT_FOUND)
        }
        val countingInputStream = CountingInputStream(inputStream)
        val dataInputStream = DataInputStream(countingInputStream)
        val backupFileHeader = BackupFileHeader.read(dataInputStream)
        Log.d(Config.LOGTAG, backupFileHeader.toString())

        val accounts = database.getAccountJids(false)

        if (AbstractContactListSyncService.isQuicksy() && accounts.isNotEmpty()) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS)
        }

        if (accounts.contains(backupFileHeader.getJid())) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS)
        }

        val key: ByteArray
        if (backupFileHeader.getVersion() >= 4) {
            key = ExportBackupWorker.getKey(password, backupFileHeader.getSalt())
        } else {
            key = ExportBackupWorker.getLegacyKey(password, backupFileHeader.getSalt())
        }

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(false, AEADParameters(KeyParameter(key), 128, backupFileHeader.getIv()))
        val cipherInputStream = CipherInputStream(countingInputStream, cipher)

        val gzipInputStream = GZIPInputStream(cipherInputStream)
        val reader = BufferedReader(InputStreamReader(gzipInputStream, StandardCharsets.UTF_8))
        val jsonReader = JsonReader(reader)
        if (jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
            jsonReader.beginArray()
        } else {
            throw IllegalStateException("Backup file did not begin with array")
        }
        importer.begin()
        while (jsonReader.hasNext()) {
            if (isStopped()) {
                importer.end()
                return failure(Reason.GENERIC)
            }
            if (jsonReader.peek() == JsonToken.BEGIN_OBJECT) {
                importRow(importer, jsonReader, backupFileHeader.getJid(), password)
            } else if (jsonReader.peek() == JsonToken.END_ARRAY) {
                jsonReader.endArray()
                continue
            }
            if (SystemClock.elapsedRealtime() - lastNotificationUpdate > 2_000) {
                lastNotificationUpdate = SystemClock.elapsedRealtime()
                updateImportBackupNotification(fileSize, countingInputStream.getCount())
            }
        }
        updateImportBackupNotification(fileSize, countingInputStream.getCount())
        importer.setSuccessful()
        importer.end()
        // The FTS triggers fire on every individual INSERT during the big transaction, creating
        // thousands of tiny segments. Rebuild the index now to collapse them into one compact
        // segment so message search is fast on the first query.
        database.rebuildMessagesIndex()
        val jid = backupFileHeader.getJid()
        val count = importer.restoredMessageCount(jid.getLocal() ?: throw NullPointerException(), jid.getDomain().toString())
        Log.d(Config.LOGTAG, "restored %d messages in %s".format(count, stopwatch.stop()))
        stopBackgroundService()
        notifySuccess()
        return Result.success()
    }

    private fun importRow(
            importer: BackupImport,
            jsonReader: JsonReader,
            account: Jid,
            passphrase: String?) {
        jsonReader.beginObject()
        val firstParameter = jsonReader.nextName()
        if (firstParameter != "table") {
            throw IllegalStateException("Expected key 'table'")
        }
        val table = jsonReader.nextString()
        if (FILE_CARRIER == table) {
            importFile(jsonReader)
            jsonReader.endObject()
            return
        }
        if (!importer.accepts(table)) {
            throw IOException("%s is not recognized for import".format(table))
        }

        val contentValues = ContentValues()
        val secondParameter = jsonReader.nextName()
        if (secondParameter != "values") {
            throw IllegalStateException("Expected key 'values'")
        }
        jsonReader.beginObject()
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            val name = jsonReader.nextName()
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull()
                    contentValues.putNull(name)
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong())
                } else {
                    var value: String? = jsonReader.nextString()
                    if (Message.TABLENAME == table && Message.RELATIVE_FILE_PATH == name) {
                        value = fromPortablePath(value)
                    }
                    contentValues.put(name, value)
                }
            } else {
                throw IOException("Unexpected column name %s".format(name))
            }
        }
        jsonReader.endObject()
        jsonReader.endObject()
        if (Account.TABLENAME == table) {
            val jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null)
            val rowPassword = contentValues.getAsString(Account.PASSWORD)
            if (AbstractContactListSyncService.isQuicksy()) {
                if (!jid.getDomain().equals(Config.QUICKSY_DOMAIN)) {
                    throw IOException("Trying to restore non Quicksy account on Quicksy")
                }
            }
            // `passphrase` may be null (Data.getString), where the Java would have thrown; the
            // comparison is Kotlin's `==`, which answers false instead of dereferencing.
            if (jid.equals(account) && passphrase == rowPassword) {
                Log.d(Config.LOGTAG, "jid and password from backup header had matching row")
            } else {
                throw IOException("jid or password in table did not match backup")
            }
            val keys = Account.parseKeys(contentValues.getAsString(Account.KEYS))
            val deviceId = keys.optString(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID)
            val importReadyKeys = JSONObject()
            if (!Strings.isNullOrEmpty(deviceId) && includeOmemo) {
                try {
                    importReadyKeys.put(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID, deviceId)
                } catch (e: JSONException) {
                    Log.e(Config.LOGTAG, "error writing omemo registration id", e)
                }
            }
            contentValues.put(Account.KEYS, importReadyKeys.toString())
        }
        // S5-6: the table's acceptance, the conflict rule and the `omemo` rule are `:data`'s
        // (`BackupImport`), so the file's own table name cannot reach a table this app does not
        // restore into. A skipped key-material row answers 0, a duplicate -1.
        val rowId: Long = importer.insert(table, contentValues, includeOmemo)
        if (rowId == -1L) {
            Log.w(Config.LOGTAG, "skipped duplicate row in $table")
        } else if (rowId == 0L) {
            Log.d(Config.LOGTAG, "skipping over omemo key material in table $table")
        }
    }

    private fun importFile(jsonReader: JsonReader) {
        val valuesKey = jsonReader.nextName()
        if ("values" != valuesKey) {
            throw IllegalStateException("Expected key 'values'")
        }
        jsonReader.beginObject()
        var portablePath: String? = null
        var sequence = -1
        var content: ByteArray? = null
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            when (jsonReader.nextName()) {
                "path" -> portablePath = jsonReader.nextString()
                "sequence" -> sequence = jsonReader.nextInt()
                "content" -> content = Base64.decode(jsonReader.nextString(), Base64.NO_WRAP)
                else -> jsonReader.skipValue()
            }
        }
        jsonReader.endObject()

        // The two reads are copied out of their `var`s first: a mutable local does not smart-cast
        // across the null test, and the Java's `!= null` pair is the whole of the guard.
        val path = portablePath
        val bytes = content
        if (path != null && bytes != null) {
            val file = File(fromPortablePath(path)!!)
            val parent = file.getParentFile()
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            FileOutputStream(file, sequence > 0).use { os -> os.write(bytes) }
        }
    }

    private fun stopBackgroundService() {
        val intent = Intent(applicationContext, XmppConnectionService::class.java)
        applicationContext.stopService(intent)
    }

    private fun updateImportBackupNotification(total: Long, current: Long) {
        val max: Int
        val progress: Int
        if (total == 0L) {
            max = 1
            progress = 0
        } else {
            max = 100
            progress = (current * 100 / total).toInt()
        }
        applicationContext
                .getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createImportBackupNotification(max, progress))
    }

    private fun createImportBackupNotification(max: Int, progress: Int): Notification {
        val context = applicationContext
        val builder = NotificationCompat.Builder(applicationContext, "backup")
        builder.setContentTitle(context.getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0)
        return builder.build()
    }

    private fun notifySuccess() {
        val context = applicationContext
        val builder = NotificationCompat.Builder(context, "backup")
        builder.setContentTitle(context.getString(R.string.notification_restored_backup_title))
                .setContentText(context.getString(R.string.notification_restored_backup_subtitle))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
        val manageAccountActivity = AccountUtils.MANAGE_ACCOUNT_ACTIVITY
        if (manageAccountActivity != null) {
            builder.setContentText(
                    context.getString(R.string.notification_restored_backup_subtitle))
            builder.setContentIntent(
                    PendingIntent.getActivity(
                            context,
                            145,
                            Intent(context, manageAccountActivity),
                            if (Compatibility.s()) {
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }))
        }
        applicationContext
                .getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID + 2, builder.build())
    }

    private fun fromPortablePath(path: String?): String? {
        if (path == null) return null
        val cacheDir = applicationContext.cacheDir.absolutePath
        val filesDir = applicationContext.filesDir.absolutePath
        val externalDir = Environment.getExternalStorageDirectory().absolutePath
        val documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .absolutePath

        // The `$` is escaped: an unescaped `${CACHE}` here would be a Kotlin template, not the
        // literal prefix the backup file carries.
        if (path.startsWith("\${CACHE}")) {
            return cacheDir + path.substring("\${CACHE}".length)
        } else if (path.startsWith("\${FILES}")) {
            return filesDir + path.substring("\${FILES}".length)
        } else if (path.startsWith("\${EXTERNAL}")) {
            return externalDir + path.substring("\${EXTERNAL}".length)
        } else if (path.startsWith("\${DOCUMENTS}")) {
            return documentsDir + path.substring("\${DOCUMENTS}".length)
        }
        return path
    }

    enum class Reason {
        ACCOUNT_ALREADY_EXISTS,
        DECRYPTION_FAILED,
        FILE_NOT_FOUND,
        GENERIC;

        companion object {
            @JvmStatic
            fun valueOfOrGeneric(value: String?): Reason {
                if (value == null || value.isEmpty()) {
                    return GENERIC
                }
                return try {
                    Reason.valueOf(value)
                } catch (e: IllegalArgumentException) {
                    GENERIC
                }
            }
        }
    }

    companion object {

        const val TAG_IMPORT_BACKUP = "tag-import-backup"

        private const val DATA_KEY_PASSWORD = "password"
        private const val DATA_KEY_URI = "uri"
        private const val DATA_KEY_INCLUDE_OMEMO = "omemo"

        /**
         * The file's own pseudo-table: the carrier for a copied file, not a table the restore inserts
         * into. It is this worker's because it is the *file format*'s - `:data`'s `BackupImport` accepts
         * the ten real tables and nothing else, and `BackupQueries.TABLES` is that list's one spelling.
         */
        private const val FILE_CARRIER = "files"

        private val COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$")

        private const val NOTIFICATION_ID = 21

        @JvmStatic
        fun data(password: String?, uri: Uri, includeOmemo: Boolean): Data =
                Data.Builder()
                        .putString(DATA_KEY_PASSWORD, password)
                        .putString(DATA_KEY_URI, uri.toString())
                        .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                        .build()

        private fun failure(reason: Reason): Result =
                Result.failure(Data.Builder().putString("reason", reason.toString()).build())
    }
}
