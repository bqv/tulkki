package uk.xa0.tulkki.app.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.common.collect.ImmutableList
import com.google.gson.stream.JsonWriter
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.AEADBlockCipher
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import uk.xa0.tulkki.app.BuildConfig
import uk.xa0.tulkki.app.TulkkiApplication
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.backup.BackupColumn
import uk.xa0.tulkki.data.backup.BackupExport
import uk.xa0.tulkki.data.backup.BackupRow
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.BackupMimeType
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.BackupFileHeader

/**
 * The backup exporter, ported line for line: the accounts, their messages and files, and - when the
 * owner asked for them - the unencrypted readable logs, written into the gzip-encrypted container
 * the importer reads back.
 *
 * <p>Kotlin notes from the port. `getKey` and `getLegacyKey` are companion `@JvmStatic`s because
 * [ImportBackupWorker] calls both as `ExportBackupWorker.getKey(...)`, and both take a nullable
 * `password` because that caller holds a `String?` and the Java parameter accepted it - the Java
 * dereferenced it too, so the `!!` is where its `NullPointerException` was. `MIME_TYPE` stays a
 * companion `const val`, so `:ui`'s readers still see the `public static final` field. The two
 * fields the Java assigned halfway through `export` - `mDatabaseBackend` and `mAccounts` - are
 * `lateinit`, which is the only Kotlin spelling for a field written after construction and read by
 * two later methods; the `m` names stay because the outer `export` has a local `accounts`.
 * `readableLogsEnabled` drops the Java's package-private visibility, which had no reader outside
 * this file. Each try-with-resources whose resource Java tolerated as null is `?.use`, and
 * [Progress.build] is public where Java's was private: a Kotlin nested class's private member is not
 * reachable from its enclosing class, and the outer's `progress.build(p)` is every progress tick.
 */
class ExportBackupWorker(context: Context, workerParams: WorkerParameters) :
        Worker(context, workerParams) {

    private val recurringBackup: Boolean

    private var lastNotificationUpdate = 0L
    private var readableLogsEnabled = false
    private lateinit var mDatabaseBackend: DatabaseBackend
    private lateinit var mAccounts: List<Account>

    init {
        val inputData = workerParams.getInputData()
        // The key is RecurringBackup.NAME, the same text as the preference the owner's interval is
        // stored under and the unique work name beside this row: one constant, no second spelling.
        recurringBackup = inputData.getBoolean(RecurringBackup.NAME, false)
    }

    override fun doWork(): Result {
        setForegroundAsync(getForegroundInfo())
        val files =
                try {
                    export()
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "could not create backup", e)
                    showToast(uk.xa0.tulkki.ui.R.string.could_not_create_backup)
                    return Result.failure()
                } finally {
                    applicationContext
                            .getSystemService(NotificationManager::class.java)
                            .cancel(NOTIFICATION_ID)
                }
        Log.d(Config.LOGTAG, "done creating " + files.size + " backup files")
        if (files.isEmpty() || recurringBackup) {
            return Result.success()
        }
        notifySuccess(files)
        return Result.success()
    }

    override fun getForegroundInfo(): ForegroundInfo {
        Log.d(Config.LOGTAG, "getForegroundInfo()")
        val notification = getNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification.build())
        }
    }

    private fun export(): List<Uri> {
        val context = applicationContext
        val appSettings = AppSettings(context)
        val backupLocation = appSettings.getBackupLocation()
        val database = DatabaseBackend.getInstance(context)
        val accounts = database.getAccounts()
        // S5-6: the backup reads the file through `:data`'s export read model, never through a
        // handle of its own. `BackupExport.get` is the same open the app has - Room's version check
        // and migrations run on it - so a worker cannot see a second, differently-shaped file.
        val backup = BackupExport.get(context)

        var currentCount = 0
        val max = accounts.size
        val locations = ImmutableList.Builder<Uri>()
        Log.d(Config.LOGTAG, "starting backup for $max accounts")
        for (account in accounts) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have")
                return locations.build()
            }
            val password = account.getPassword()
            if (password == null || password.javaTrim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        "skipping backup for %s because password is empty. unable to encrypt"
                                .format(account.getJid().asBareJid()))
                currentCount++
                continue
            }
            val uri =
                    try {
                        export(backup, account, password, backupLocation, max, currentCount)
                    } catch (e: WorkStoppedException) {
                        Log.d(
                                Config.LOGTAG,
                                "ExportBackupWorker has stopped. Returning what we have")
                        return locations.build()
                    }
            locations.add(uri)
            currentCount++
        }
        return locations.build()
    }

    private fun export(
            backup: BackupExport,
            account: Account,
            password: String,
            backupLocation: Uri,
            max: Int,
            count: Int): Uri {
        val context = applicationContext
        val secureRandom = SecureRandom()
        Log.d(
                Config.LOGTAG,
                "exporting data for account %s (%s)"
                        .format(account.getJid().asBareJid(), account.getUuid()))
        val IV = ByteArray(12)
        val salt = ByteArray(16)
        secureRandom.nextBytes(IV)
        secureRandom.nextBytes(salt)
        val backupFileHeader =
                BackupFileHeader(
                        BuildConfig.APP_NAME,
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt)
        val notification = getNotification()
        val cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId())
        notification.addAction(
                NotificationCompat.Action.Builder(
                                uk.xa0.tulkki.ui.R.drawable.ic_cancel_24dp,
                                context.getString(R.string.cancel),
                                cancelPendingIntent)
                        .build())
        val progress = Progress(notification, max, count)
        val filename =
                "%s.%s.ceb".format(
                        account.getJid().asBareJid().toString(), DATE_FORMAT.format(Date()))
        val location: Uri
        if ("file".equals(backupLocation.getScheme(), ignoreCase = true)) {
            val file = File(backupLocation.getPath(), filename)
            val directory = file.getParentFile()
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath())
            }
            location = Uri.fromFile(file)
        } else {
            val tree = DocumentFile.fromTreeUri(context, backupLocation)
            if (tree == null) {
                throw IOException(
                        "DocumentFile.fromTreeUri returned null for %s".format(backupLocation))
            }
            val file = tree.createFile(MIME_TYPE, filename)
            if (file == null) {
                throw IOException("Could not create %s in %s".format(filename, backupLocation))
            }
            location = file.getUri()
        }

        try {
            context.getContentResolver().openOutputStream(location).use { outputStream ->
                val dataOutputStream = DataOutputStream(outputStream)
                backupFileHeader.write(dataOutputStream)
                dataOutputStream.flush()

                val aeadBlockCipher: AEADBlockCipher =
                        GCMBlockCipher.newInstance(AESEngine.newInstance())
                val key = getKey(password, salt)
                aeadBlockCipher.init(true, AEADParameters(KeyParameter(key), 128, IV))
                val cipherOutputStream = CipherOutputStream(outputStream, aeadBlockCipher)
                val gzipOutputStream = GZIPOutputStream(cipherOutputStream)
                JsonWriter(OutputStreamWriter(gzipOutputStream, StandardCharsets.UTF_8)).use { jsonWriter ->
                    jsonWriter.beginArray()
                    // port-5: `AbstractEntity.getUuid()` is nullable; an account with no uuid has no
                    // rows to export under it, so its sections are left empty. `muteRows` names no
                    // uuid and is exported either way, in its original order.
                    val uuid = account.getUuid()
                    if (uuid != null) {
                        accountExport(backup, uuid, jsonWriter)
                        writeRows(jsonWriter, backup.conversationRows(uuid))
                        fileExport(backup, uuid, jsonWriter, progress)
                        messageExport(backup, uuid, jsonWriter, progress)
                        webxdcExport(backup, uuid, jsonWriter, progress)
                        writeRows(jsonWriter, backup.pinnedRows(uuid))
                    }
                    writeRows(jsonWriter, backup.muteRows())
                    if (uuid != null) {
                        writeRows(jsonWriter, backup.omemoRows(uuid))
                    }
                    jsonWriter.endArray()
                    jsonWriter.flush()
                }
            }
        } catch (e: Exception) {
            deleteFile(context, location)
            throw e
        }
        if ("file".equals(location.getScheme(), ignoreCase = true)) {
            mediaScannerScanFile(File(location.getPath()))
        }
        Log.d(Config.LOGTAG, "written backup to $location")

        if (getFileSize(context, location) > 80) {
            cleanup(backupLocation, account.getJid())
        } else {
            Log.w(Config.LOGTAG, "Backup file " + location + " is too small. Skipping cleanup.")
            showToast(uk.xa0.tulkki.ui.R.string.could_not_create_backup)
        }

        // Tulkki: `TulkkiApplication.getContext()` is nullable by construction - its backing
        // `CONTEXT` is null before `onCreate`, which is what the Java's own `Conversations.CONTEXT`
        // field was - and `DatabaseBackend.getInstance(Context)` dereferences it. The Java's own
        // `NullPointerException` is named here rather than narrowing the declaration past the Java's.
        mDatabaseBackend =
                DatabaseBackend.getInstance(
                        TulkkiApplication.getContext() ?: throw NullPointerException())
        mAccounts = mDatabaseBackend.getAccounts()
        val readableLogs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        readableLogsEnabled =
                readableLogs.getBoolean(
                        "export_plain_text_logs",
                        applicationContext
                                .getResources()
                                .getBoolean(uk.xa0.tulkki.ui.R.bool.plain_text_logs))

        try {
            if (readableLogsEnabled) { // todo
                val conversationList =
                        mDatabaseBackend.getConversationList(Conversation.STATUS_AVAILABLE)
                conversationList.addAll(
                        mDatabaseBackend.getConversationList(Conversation.STATUS_ARCHIVED))
                for (conversation in conversationList) {
                    writeToFile(conversation)
                    Log.d(Config.LOGTAG, "Exporting readable logs for " + conversation.getJid())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return location
    }

    private fun showToast(resId: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, resId, Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        val scheme = uri.getScheme()
        return if ("file".equals(scheme, ignoreCase = true)) {
            val path = uri.getPath()
            if (path == null) 0 else File(path).length()
        } else {
            val file = DocumentFile.fromSingleUri(context, uri)
            if (file == null) 0 else file.length()
        }
    }

    private fun cleanup(backupLocation: Uri, jid: Jid) {
        val context = applicationContext
        val prefix = jid.asBareJid().toString() + "."
        val scheme = backupLocation.getScheme()
        if ("file".equals(scheme, ignoreCase = true)) {
            val path = backupLocation.getPath() ?: return
            val directory = File(path)
            val files =
                    directory.listFiles { _, name ->
                        name.startsWith(prefix) && name.endsWith(".ceb")
                    }
            if (files != null && files.size > 3) {
                Arrays.sort(files, Comparator<File> { f1, f2 ->
                    f1.getName().compareTo(f2.getName())
                })
                for (i in 0 until files.size - 3) {
                    if (files[i].delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + files[i].getName())
                    }
                }
            }
        } else {
            val tree = DocumentFile.fromTreeUri(context, backupLocation) ?: return
            val files = tree.listFiles()
            val backups = ArrayList<DocumentFile>()
            for (file in files) {
                val name = file.getName()
                if (name != null && name.startsWith(prefix) && name.endsWith(".ceb")) {
                    backups.add(file)
                }
            }
            if (backups.size > 3) {
                Collections.sort(
                        backups,
                        Comparator<DocumentFile> { f1, f2 ->
                            val n1 = f1.getName()
                            val n2 = f2.getName()
                            if (n1 == null) -1 else if (n2 == null) 1 else n1.compareTo(n2)
                        })
                for (i in 0 until backups.size - 3) {
                    val fileToDelete = backups[i]
                    if (fileToDelete.delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + fileToDelete.getName())
                    }
                }
            }
        }
    }

    private fun getNotification(): NotificationCompat.Builder {
        val context = applicationContext
        val notification = NotificationCompat.Builder(context, "backup")
        notification
                .setContentTitle(
                        context.getString(
                                uk.xa0.tulkki.ui.R.string.notification_create_backup_title))
                .setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false)
        notification.setOngoing(true)
        notification.setLocalOnly(true)
        return notification
    }

    private fun throwIfWorkStopped() {
        if (isStopped()) {
            throw WorkStoppedException()
        }
    }

    private fun deleteFile(context: Context, uri: Uri) {
        if ("file".equals(uri.getScheme(), ignoreCase = true)) {
            val file = File(uri.getPath())
            if (file.delete()) {
                Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath())
            }
        } else {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            if (documentFile != null && documentFile.delete()) {
                Log.d(Config.LOGTAG, "deleted $uri")
            }
        }
    }

    private fun mediaScannerScanFile(file: File) {
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        intent.setData(Uri.fromFile(file))
        applicationContext.sendBroadcast(intent)
    }

    /**
     * Writes one of the export's named row sets (S5-6). The set is `:data`'s - the read model
     * [BackupExport] publishes - and all this does is render it into the file's own shape, which is
     * the one thing a backup file's format is allowed to be a worker's business.
     */
    private fun writeRows(writer: JsonWriter, rows: List<BackupRow>) {
        for (row in rows) {
            throwIfWorkStopped()
            writeRow(writer, row)
        }
    }

    /** One row as `{table, values:{column: value}}`, with the export's own per-column rules. */
    private fun writeRow(writer: JsonWriter, row: BackupRow) {
        writer.beginObject()
        writer.name("table")
        writer.value(row.table)
        writer.name("values")
        writer.beginObject()
        for (column in row.columns) {
            writer.name(column.name)
            writeValue(writer, row.table, column)
        }
        writer.endObject()
        writer.endObject()
    }

    /**
     * The two column rules the backup file has always carried, and they stay here rather than in
     * `:data` because both are the *format's*: a JSON number is a rendering, not a read.
     *
     * - An account row's `rosterversion` is written as `null`, and so is any null: a restored
     *   account must ask the server for the roster again rather than trust a version from the
     *   file's own device.
     * - An account row's `options` gains the disabled bit, so a restored account arrives
     *   disabled and the owner enables it deliberately.
     * - A message's relative file path becomes portable (`FILES` root), because the paths of one
     *   install are not the paths of the next; `ImportBackupWorker.fromPortablePath` is the
     *   other half, and the two spell the four roots together.
     */
    private fun writeValue(writer: JsonWriter, table: String, column: BackupColumn) {
        var value = column.value
        if (Account.TABLENAME == table) {
            if (value == null || Account.ROSTERVERSION == column.name) {
                writer.nullValue()
                return
            }
            if (Account.OPTIONS == column.name && value.matches("\\d+".toRegex())) {
                var intValue = value.toInt()
                intValue = intValue or (1 shl Account.OPTION_DISABLED)
                writer.value(intValue)
                return
            }
        }
        if (Message.TABLENAME == table && Message.RELATIVE_FILE_PATH == column.name) {
            value = toPortablePath(value)
        }
        writer.value(value)
    }

    private fun webxdcExport(
            backup: BackupExport,
            uuid: String,
            writer: JsonWriter,
            progress: Progress) {
        val rows = backup.webxdcRows(uuid)
        val notificationManager =
                applicationContext.getSystemService(NotificationManager::class.java)

        Log.d(Config.LOGTAG, "exporting " + rows.size + " WebXDC updates for account " + uuid)
        var i = 0
        var p = Int.MIN_VALUE
        for (row in rows) {
            throwIfWorkStopped()
            writeRow(writer, row)
            val percentage = i * 100 / (if (rows.isEmpty()) 1 else rows.size)
            if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage
                lastNotificationUpdate = SystemClock.elapsedRealtime()
                notificationManager.notify(NOTIFICATION_ID, progress.build(p))
            }
            i++
        }
    }

    /**
     * The account's own row. It is written without a stop check, exactly as the cursor it replaces
     * was: the loop is one row, and `accountExport` never consulted `isStopped()`.
     */
    private fun accountExport(backup: BackupExport, uuid: String, writer: JsonWriter) {
        for (row in backup.account(uuid)) {
            writeRow(writer, row)
        }
    }

    private fun messageExport(
            backup: BackupExport,
            uuid: String,
            writer: JsonWriter,
            progress: Progress) {
        val rows = backup.messageRows(uuid)
        val notificationManager =
                applicationContext.getSystemService(NotificationManager::class.java)
        Log.d(Config.LOGTAG, "exporting " + rows.size + " messages for account " + uuid)
        var i = 0
        var p = Int.MIN_VALUE
        for (row in rows) {
            throwIfWorkStopped()
            writeRow(writer, row)
            val percentage = i * 100 / (if (rows.isEmpty()) 1 else rows.size)
            if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage
                lastNotificationUpdate = SystemClock.elapsedRealtime()
                notificationManager.notify(NOTIFICATION_ID, progress.build(p))
            }
            i++
        }
    }

    private fun notifySuccess(locations: List<Uri>) {
        val context = applicationContext
        val appSettings = AppSettings(context)
        val path = appSettings.getBackupLocationAsPath()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        val uris = ArrayList<Uri>()
        for (uri in locations) {
            if ("file".equals(uri.getScheme(), ignoreCase = true)) {
                val file = File(uri.getPath())
                uris.add(FileBackend.getUriForFile(context, file, file.getName()))
            } else {
                uris.add(uri)
            }
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setType(MIME_TYPE)
        val chooser =
                Intent.createChooser(
                        intent, context.getString(uk.xa0.tulkki.ui.R.string.share_backup_files))
        val shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS)

        val builder = NotificationCompat.Builder(context, "backup")
        builder.setContentTitle(
                        context.getString(
                                uk.xa0.tulkki.ui.R.string.notification_backup_created_title))
                .setContentText(
                        context.getString(
                                uk.xa0.tulkki.ui.R.string.notification_backup_created_subtitle,
                                path))
                .setStyle(
                        NotificationCompat.BigTextStyle()
                                .bigText(
                                        context.getString(
                                                uk.xa0.tulkki.ui.R.string
                                                        .notification_backup_created_subtitle,
                                                path)))
                .setAutoCancel(true)
                .setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_archive_24dp)

        builder.addAction(
                uk.xa0.tulkki.ui.R.drawable.ic_share_24dp,
                context.getString(uk.xa0.tulkki.ui.R.string.share_backup_files),
                shareFilesIntent)
        builder.setLocalOnly(true)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, builder.build())
    }

    private fun writeToFile(conversation: Conversation) {
        val accountJid = resolveAccountUuid(conversation.getAccountUuid()!!) ?: return
        val contactJid = conversation.getJid()!!
        val context = applicationContext
        val appSettings = AppSettings(context)
        val path = appSettings.getBackupLocationAsPath()
        val dir = File(path, accountJid.asBareJid().toString())
        dir.mkdirs()

        var bw: BufferedWriter? = null
        try {
            for (message in mDatabaseBackend.getMessagesIterable(conversation)) {
                if (isStopped()) return
                if (message == null) continue
                if (message.getType() == Message.TYPE_TEXT || message.hasFileOnRemoteHost()) {
                    val date = DATE_FORMAT.format(Date(message.getTimeSent()))
                    if (bw == null) {
                        bw =
                                BufferedWriter(
                                        FileWriter(
                                                File(
                                                        dir,
                                                        contactJid.asBareJid().toString() +
                                                                ".txt")))
                    }
                    var jid: String? = null
                    when (message.getStatus()) {
                        Message.STATUS_RECEIVED -> jid = getMessageCounterpart(message)
                        Message.STATUS_SEND,
                        Message.STATUS_SEND_RECEIVED,
                        Message.STATUS_SEND_DISPLAYED,
                        Message.STATUS_SEND_FAILED -> jid = accountJid.asBareJid().toString()
                    }
                    if (jid != null) {
                        // Tulkki: the plain-text log is a second display of the conversation, so it
                        // writes what the interface would have written - the translation, the
                        // original when nothing needed translating, and the cover's own wording for
                        // a body that needed one and did not get it. It used to write
                        // {@code message.getBody()}, the raw original, into an unencrypted file in
                        // the backup directory: the setting promises "unencrypted", not "shows the
                        // text the app refuses to show". The URL of a file message is not prose and
                        // is what the line is for, so that branch is unchanged.
                        val body =
                                if (message.hasFileOnRemoteHost()) {
                                    message.getFileParams().url.toString()
                                } else {
                                    UIHelper.getDisplayedBody(context, message).toString()
                                }
                        bw.write(
                                MESSAGE_STRING_FORMAT.format(
                                        date,
                                        jid,
                                        body.replace("\\\n", "\\ \n")
                                                .replace("\n", "\\ \n")))
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                bw?.close()
            } catch (e1: IOException) {
                e1.printStackTrace()
            }
        }
    }

    private fun resolveAccountUuid(accountUuid: String): Jid? {
        for (account in mAccounts) {
            if (account.getUuid() == accountUuid) {
                return account.getJid()
            }
        }
        return null
    }

    private fun getMessageCounterpart(message: Message): String {
        val trueCounterpart = message.getContentValues().get(Message.TRUE_COUNTERPART) as String?
        return trueCounterpart ?: message.getCounterpart().toString()
    }

    private fun toPortablePath(path: String?): String? {
        if (path == null) return null
        val cacheDir = applicationContext.getCacheDir().getAbsolutePath()
        val filesDir = applicationContext.getFilesDir().getAbsolutePath()
        val externalDir = Environment.getExternalStorageDirectory().getAbsolutePath()
        val documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .getAbsolutePath()

        // The `$` is escaped: an unescaped `${CACHE}` here would be a Kotlin template, not the
        // literal prefix the backup file carries.
        if (path.startsWith(cacheDir)) {
            return "\${CACHE}" + path.substring(cacheDir.length)
        } else if (path.startsWith(filesDir)) {
            return "\${FILES}" + path.substring(filesDir.length)
        } else if (path.startsWith(externalDir)) {
            return "\${EXTERNAL}" + path.substring(externalDir.length)
        } else if (path.startsWith(documentsDir)) {
            return "\${DOCUMENTS}" + path.substring(documentsDir.length)
        }
        return path
    }

    /**
     * The files the account's backup carries: every attachment a message of it names, and the
     * account's and its contacts' avatars. The paths and the names come from `:data`
     * ([BackupExport.attachmentPaths], [BackupExport.avatarNames]); this method's own business is
     * only which of them live under the app's private files directory and where the avatars are
     * kept.
     */
    private fun getFilesForAccount(backup: BackupExport, accountUuid: String): Set<File> {
        val files = HashSet<File>()
        val filesDir = applicationContext.getFilesDir().getAbsolutePath()
        // Message attachments
        for (path in backup.attachmentPaths(accountUuid)) {
            if (path.startsWith(filesDir)) {
                files.add(File(path))
            }
        }

        val avatarDir = File(applicationContext.getFilesDir(), "avatars")
        // Avatars: the account's own, then its contacts'
        for (name in backup.avatarNames(accountUuid)) {
            files.add(File(avatarDir, name))
        }

        return files
    }

    private fun fileExport(
            backup: BackupExport,
            uuid: String,
            writer: JsonWriter,
            progress: Progress) {
        val notificationManager =
                applicationContext.getSystemService(NotificationManager::class.java)
        val files = getFilesForAccount(backup, uuid)
        Log.d(Config.LOGTAG, "exporting " + files.size + " files for account " + uuid)
        var i = 0
        var p = Int.MIN_VALUE
        for (file in files) {
            throwIfWorkStopped()
            if (file.exists() && file.canRead()) {
                try {
                    exportFile(file, writer)
                } catch (e: IOException) {
                    Log.w(Config.LOGTAG, "failed to export file " + file.getAbsolutePath(), e)
                }
            }
            i++
            val percentage = i * 100 / Math.max(1, files.size)
            if (p < percentage && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage
                lastNotificationUpdate = SystemClock.elapsedRealtime()
                notificationManager.notify(NOTIFICATION_ID, progress.build(p))
            }
        }
    }

    private fun exportFile(file: File, writer: JsonWriter) {
        val portablePath = toPortablePath(file.getAbsolutePath())
        val buffer = ByteArray(1024 * 1024) // 1MB chunks
        FileInputStream(file).use { inputStream ->
            var sequence = 0
            var length = inputStream.read(buffer)
            while (length > 0) {
                writer.beginObject()
                writer.name("table")
                writer.value("files")
                writer.name("values")
                writer.beginObject()
                writer.name("path")
                writer.value(portablePath)
                writer.name("sequence")
                writer.value(sequence)
                sequence++
                writer.name("content")
                if (length == buffer.size) {
                    writer.value(Base64.encodeToString(buffer, Base64.NO_WRAP))
                } else {
                    val smallBuffer = ByteArray(length)
                    System.arraycopy(buffer, 0, smallBuffer, 0, length)
                    writer.value(Base64.encodeToString(smallBuffer, Base64.NO_WRAP))
                }
                writer.endObject()
                writer.endObject()
                length = inputStream.read(buffer)
            }
        }
    }

    private class Progress(
            private val notification: NotificationCompat.Builder,
            private val max: Int,
            private val count: Int) {

        fun build(percentage: Int): Notification {
            notification.setProgress(max * 100, count * 100 + percentage, false)
            return notification.build()
        }
    }

    private class WorkStoppedException : Exception()

    companion object {

        private val DATE_FORMAT: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US)

        private const val KEY_TYPE = "AES"
        private const val CIPHER_MODE = "AES/GCM/NoPadding"
        private const val PROVIDER = "BC"

        /**
         * 3.7 pair 2 moved the string itself down to
         * {@code uk.xa0.tulkki.data.utils.BackupMimeType}, because
         * {@code BackupFile} and {@code MimeUtils} - both {@code :data} - name it and may not name
         * this module. The name stays here as a compile-time constant so `:ui`'s two readers do not
         * move.
         */
        const val MIME_TYPE = BackupMimeType.MIME_TYPE

        private const val MESSAGE_STRING_FORMAT = "(%s) %s: %s\n"

        private const val NOTIFICATION_ID = 19
        private const val BACKUP_CREATED_NOTIFICATION_ID = 23

        private val PENDING_INTENT_FLAGS: Int =
                if (Compatibility.s()) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

        /**
         * The `password` is nullable because [ImportBackupWorker] holds a `String?` from
         * `Data.getString`; the Java dereferenced it here, so the `!!` is its own
         * `NullPointerException`, not a new one.
         */
        @JvmStatic
        fun getKey(password: String?, salt: ByteArray): ByteArray {
            val params =
                    Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                            .withIterations(3)
                            .withMemoryAsKB(65536) // 64MB
                            .withParallelism(4)
                            .withSalt(salt)
                            .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val result = ByteArray(32) // 256-bit key for AES-256
            gen.generateBytes(password!!.toCharArray(), result)
            return result
        }

        /** As [getKey]: the Java declared this checked exception, so a Java caller may still catch it. */
        @JvmStatic
        @Throws(InvalidKeySpecException::class)
        fun getLegacyKey(password: String?, salt: ByteArray): ByteArray {
            val factory: SecretKeyFactory
            try {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            } catch (e: NoSuchAlgorithmException) {
                throw IllegalStateException(e)
            }
            return factory.generateSecret(PBEKeySpec(password!!.toCharArray(), salt, 1024, 128))
                    .getEncoded()
        }
    }
}

// Java's `String.trim()`: only the characters up to U+0020. Kotlin's `trim()` is the Unicode set and
// would also strip a non-breaking space (U+00A0), which the empty-password test above never did.
private fun String.javaTrim(): String = trim { it <= ' ' }
