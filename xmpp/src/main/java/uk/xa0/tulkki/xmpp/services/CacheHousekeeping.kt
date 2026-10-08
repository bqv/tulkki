@file:Suppress("DEPRECATION") // android.preference.PreferenceManager is the class the Java body used

package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import uk.xa0.tulkki.xmpp.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Tulkki: the cache and temporary-storage housekeeping, lifted out of `XmppConnectionService`
 *.
 *
 * Two one-shot jobs the service ran for itself. `migrateCacheToInternalStorage` moves the media
 * directories out of `getCacheDir()` into `getFilesDir()` once, behind a preference flag;
 * `cleanupTemporaryStorage` purges files under the cache and media directories whose last access is
 * older than the stored number of days. Both are reads and writes of the `Context` alone, so the
 * service passes itself and its executor in and keeps no state here.
 */
object CacheHousekeeping {

    /**
     * Moves `media`, `Camera`, `avatars` and `stories` from the cache directory into the files
     * directory, once, and records it in the `cache_migrated_to_internal_v2` preference. A file
     * that cannot be renamed is copied and then deleted; an `IOException` on one file is logged and
     * the rest are still attempted.
     */
    @JvmStatic
    fun migrateCacheToInternalStorage(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean("cache_migrated_to_internal_v2", false)) {
            return
        }
        val dirsToMigrate = listOf("media", "Camera", "avatars", "stories")
        for (dirName in dirsToMigrate) {
            val oldDir = File(context.cacheDir, dirName)
            val newDir = File(context.filesDir, dirName)
            if (oldDir.exists() && oldDir.isDirectory) {
                if (!newDir.exists()) {
                    newDir.mkdirs()
                }
                val files = oldDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        val newFile = File(newDir, file.name)
                        if (!file.renameTo(newFile)) {
                            try {
                                file.inputStream().use { input ->
                                    newFile.outputStream().use { output ->
                                        ByteStreams.copy(input, output)
                                        file.delete()
                                    }
                                }
                            } catch (e: IOException) {
                                Log.w(
                                    Config.LOGTAG,
                                    "Failed to migrate " + dirName + " file: " + file.absolutePath
                                )
                            }
                        }
                    }
                }
            }
        }
        // the member `SharedPreferences.Editor.apply()`, not the stdlib scope function
        preferences.edit().putBoolean("cache_migrated_to_internal_v2", true).apply()
    }

    /**
     * Purges files under the cache directory and the `media`/`Camera` files directories whose last
     * access is older than `cache_deletion_time` days. A malformed or `0` value returns before any
     * work, and so does an API level below 26 (the `File.toPath` guard); the purge itself is handed
     * to [executor] and sets its thread to the minimum priority, as the Java body did.
     */
    @JvmStatic
    fun cleanupTemporaryStorage(context: Context, executor: Executor) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        // getString is nullable in the SDK; parseInt would answer a NumberFormatException for null
        // and the Java body's catch turned that into a return, so a null returns here too
        val rawDeletionTimeDays = preferences.getString("cache_deletion_time", "30")
        if (rawDeletionTimeDays == null) {
            return
        }
        val deletionTimeDays: Int
        try {
            deletionTimeDays = Integer.parseInt(rawDeletionTimeDays)
        } catch (e: NumberFormatException) {
            return
        }
        if (deletionTimeDays == 0) {
            return
        }
        if (Build.VERSION.SDK_INT < 26) {
            return // Doesn't support file.toPath
        }
        executor.execute {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            val now = System.currentTimeMillis()
            val maxAge = 1000L * 60 * 60 * 24 * deletionTimeDays
            val directories = ArrayList<File>()
            directories.add(context.cacheDir)
            directories.add(File(context.filesDir, "media"))
            directories.add(File(context.filesDir, "Camera"))
            for (directory in directories) {
                if (!directory.exists()) {
                    continue
                }
                try {
                    for (file in Files.fileTraverser().breadthFirst(directory)) {
                        if (file.isFile && file.canRead() && file.canWrite()) {
                            val attrs = java.nio.file.Files.readAttributes(
                                file.toPath(),
                                java.nio.file.attribute.BasicFileAttributes::class.java
                            )
                            if ((now - attrs.lastAccessTime().toMillis()) > maxAge) {
                                Log.d(
                                    Config.LOGTAG,
                                    "cleanupTemporaryStorage removing file not used recently: " + file
                                )
                                file.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(Config.LOGTAG, "cleanupTemporaryStorage " + e)
                }
            }
        }
    }
}
