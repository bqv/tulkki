package uk.xa0.tulkki.data.utils

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

object FileHelper {

    private const val TAG = "FileHelper"

    /**
     * Attempts to securely delete a file by overwriting it with random data before unlinking.
     * This is a "best effort" approach as modern flash storage (SSD/EMMC) may wear-level
     * the writes to different physical blocks.
     *
     * @param file The file to securely delete.
     * @return true if the file was deleted successfully.
     */
    @JvmStatic
    fun secureDelete(file: File?): Boolean {
        if (file == null || !file.exists()) {
            return true
        }

        if (file.length() > 0) {
            try {
                FileOutputStream(file).use { fos ->
                    val length = file.length()
                    val buffer = ByteArray(4096)
                    val random = SecureRandom()
                    var written = 0L
                    while (written < length) {
                        random.nextBytes(buffer)
                        val toWrite = Math.min(buffer.size.toLong(), length - written).toInt()
                        fos.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    fos.flush()
                    fos.fd.sync()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to overwrite file before deletion: " + file.absolutePath, e)
            }
        }

        return file.delete()
    }

    /**
     * Zeros out a character array to clear sensitive data from memory.
     *
     * @param array The array to clear.
     */
    @JvmStatic
    fun zero(array: CharArray?) {
        if (array != null) {
            java.util.Arrays.fill(array, '\u0000')
        }
    }
}
