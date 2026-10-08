package uk.xa0.tulkki.data.utils

import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.common.collect.ComparisonChain
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Ordering
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import uk.xa0.tulkki.data.BuildConfig
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.BackupFileHeader
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BackupFile private constructor(
    val uri: Uri,
    val header: BackupFileHeader,
) : Comparable<BackupFile> {

    override fun compareTo(other: BackupFile): Int =
        ComparisonChain.start()
            .compare(header.getJid(), other.header.getJid())
            .compare(other.header.getTimestamp(), header.getTimestamp())
            .result()

    companion object {

        private val BACKUP_FILE_READER_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()

        @JvmStatic
        fun readAsync(context: Context, uri: Uri): ListenableFuture<BackupFile> =
            Futures.submit(Callable { read(context, uri) }, BACKUP_FILE_READER_EXECUTOR)

        @Throws(IOException::class)
        private fun read(file: File): BackupFile {
            val fileInputStream = FileInputStream(file)
            val dataInputStream = DataInputStream(fileInputStream)
            val backupFileHeader = BackupFileHeader.read(dataInputStream)
            fileInputStream.close()
            return BackupFile(Uri.fromFile(file), backupFileHeader)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(context: Context, uri: Uri): BackupFile {
            val inputStream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            val dataInputStream = DataInputStream(inputStream)
            val backupFileHeader = BackupFileHeader.read(dataInputStream)
            inputStream.close()
            return BackupFile(uri, backupFileHeader)
        }

        @JvmStatic
        fun listAsync(context: Context): ListenableFuture<List<BackupFile>> =
            Futures.submit(Callable { list(context) }, BACKUP_FILE_READER_EXECUTOR)

        private fun list(context: Context): List<BackupFile> {
            val database = DatabaseBackend.getInstance(context)
            val accounts = database.getAccountJids(false)
            val backupFiles = ImmutableList.builder<BackupFile>()
            // Tulkki: "monocles chat" is here because that is the app the owner is migrating from. Its
            // backups live in a directory named after it, so without this the restore never finds them -
            // the rebrand would quietly cost the old history.
            val apps =
                ImmutableSet.of(
                    "Conversations",
                    "Quicksy",
                    "monocles chat",
                    BuildConfig.APP_NAME,
                )

            val uriPermissions = context.contentResolver.persistedUriPermissions

            for (uriPermission in uriPermissions) {
                val uri = uriPermission.uri
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && DocumentsContract.isTreeUri(uri)) {
                    Log.d(Config.LOGTAG, "looking for backups in " + uri)
                    val tree = DocumentFile.fromTreeUri(context, uriPermission.uri)
                    val files: Array<DocumentFile> = tree?.listFiles() ?: emptyArray()
                    for (documentFile in files) {
                        val name = documentFile.name
                        if (documentFile.isFile &&
                            (BackupMimeType.MIME_TYPE == documentFile.type ||
                                (name != null && name.endsWith(".ceb")))
                        ) {
                            try {
                                val backupFile = read(context, documentFile.uri)
                                if (accounts.contains(backupFile.header.getJid())) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "skipping backup for " + backupFile.header.getJid(),
                                    )
                                } else {
                                    backupFiles.add(backupFile)
                                }
                            } catch (e: IOException) {
                                Log.d(Config.LOGTAG, "unable to read backup file ", e)
                            } catch (e: IllegalArgumentException) {
                                Log.d(Config.LOGTAG, "unable to read backup file ", e)
                            } catch (e: BackupFileHeader.OutdatedBackupFileVersion) {
                                Log.d(Config.LOGTAG, "unable to read backup file ", e)
                            }
                        }
                    }
                }
            }

            val directories = ArrayList<File>()
            for (app in apps) {
                directories.add(FileBackend.getLegacyBackupDirectory(app))
            }
            if (uriPermissions.isEmpty()) {
                Log.d(
                    Config.LOGTAG,
                    "including default directory since no uri permissions have been granted",
                )
                directories.add(FileBackend.getBackupDirectory(context))
            }
            for (directory in directories) {
                if (!directory.exists() || !directory.isDirectory) {
                    Log.d(Config.LOGTAG, "directory not found: " + directory.absolutePath)
                    continue
                }
                val files = directory.listFiles()
                if (files == null) {
                    continue
                }
                Log.d(Config.LOGTAG, "looking for backups in " + directory)
                for (file in files) {
                    if (file.isFile && file.name.endsWith(".ceb")) {
                        try {
                            val backupFile = read(file)
                            if (accounts.contains(backupFile.header.getJid())) {
                                Log.d(
                                    Config.LOGTAG,
                                    "skipping backup for " + backupFile.header.getJid(),
                                )
                            } else {
                                backupFiles.add(backupFile)
                            }
                        } catch (e: IOException) {
                            Log.d(Config.LOGTAG, "unable to read backup file ", e)
                        } catch (e: IllegalArgumentException) {
                            Log.d(Config.LOGTAG, "unable to read backup file ", e)
                        } catch (e: BackupFileHeader.OutdatedBackupFileVersion) {
                            Log.d(Config.LOGTAG, "unable to read backup file ", e)
                        }
                    }
                }
            }
            // 3.7 pair 2: the `isQuicksy()` filter that used to wrap this return was dead -
            // `AbstractContactListSyncService.isQuicksy()` is a hardcoded `false` since the flavour
            // collapse, so the branch could never be taken and the import was the last `:data` -> `:app`
            // reason this file had. The plain sort is what the live path always did.
            val list = backupFiles.build()
            return Ordering.natural<BackupFile>().immutableSortedCopy(list)
        }
    }
}
