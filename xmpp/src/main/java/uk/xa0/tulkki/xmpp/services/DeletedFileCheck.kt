package uk.xa0.tulkki.xmpp.services

import android.os.SystemClock
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.FilePathInfoRef
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * Tulkki: the start-up sweep for files the database still lists and the disk no longer has, lifted
 * out of `XmppConnectionService`.
 *
 * The Java body read two members that belong to other chunks, and both travel in rather than having
 * a visibility widened: the non-volatile `destroyed` flag of chunk `C22` arrives as a
 * [BooleanSupplier], so the loop re-reads it exactly as the Java's two `if (destroyed)` tests did,
 * and the private `markChangedFiles` of chunk `C29` arrives as a [Consumer]. The chunk's other
 * member, `FILENAMES_TO_IGNORE_DELETION`, does not move here: `:data`'s `CryptoStore` names it as
 * `service.FILENAMES_TO_IGNORE_DELETION`, and this sweep never reads it.
 */
object DeletedFileCheck {

    @JvmStatic
    fun checkForDeletedFiles(
        service: XmppConnectionService,
        destroyed: BooleanSupplier,
        markChangedFiles: Consumer<List<FilePathInfoRef>>,
    ) {
        if (destroyed.asBoolean) {
            Log.d(Config.LOGTAG, "Do not check for deleted files because service has been destroyed")
            return
        }
        val start = SystemClock.elapsedRealtime()
        val relativeFilePaths = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getFilePathInfo()
        val changed = ArrayList<FilePathInfoRef>()
        for (filePath in relativeFilePaths) {
            if (destroyed.asBoolean) {
                Log.d(
                    Config.LOGTAG,
                    "Stop checking for deleted files because service has been destroyed",
                )
                return
            }
            val file = service.getFileBackend().getFileForPath(filePath.getPath()).asFile()
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath)
            }
        }
        val duration = SystemClock.elapsedRealtime() - start
        Log.d(
            Config.LOGTAG,
            ("found "
                + changed.size
                + " changed files on start up. total="
                + relativeFilePaths.size
                + ". ("
                + duration
                + "ms)"),
        )
        if (changed.size > 0) {
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).markFilesAsChanged(changed)
            markChangedFiles.accept(changed)
        }
    }
}
