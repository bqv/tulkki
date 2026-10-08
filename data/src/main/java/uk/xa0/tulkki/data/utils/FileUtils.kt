package uk.xa0.tulkki.data.utils

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import uk.xa0.tulkki.data.FileBackend
import java.io.File

object FileUtils {

    private val PUBLIC_DOWNLOADS: Uri = Uri.parse("content://downloads/public_downloads")

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access Framework Documents, as well as
     * the _data field for the MediaStore and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     * @author paulburke
     */
    @SuppressLint("NewApi")
    @JvmStatic
    fun getPath(context: Context, uri: Uri?): String? {
        if (uri == null) {
            return null
        }

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                if ("primary".equals(type, ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                return try {
                    val contentUri = ContentUris.withAppendedId(PUBLIC_DOWNLOADS, id.toLong())
                    getDataColumn(context, contentUri, null, null)
                } catch (e: NumberFormatException) {
                    null
                }
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])

                return getDataColumn(context, contentUri ?: return null, selection, selectionArgs)
            }
        }
        // MediaStore (and general)
        else if ("content".equals(uri.scheme, ignoreCase = true)) {
            val segments = uri.pathSegments
            val path: String?
            if (FileBackend.getAuthority(context) == uri.authority &&
                segments.size > 1 &&
                segments[0] == "external"
            ) {
                path =
                    Environment.getExternalStorageDirectory().absolutePath +
                        (uri.path ?: throw NullPointerException()).substring(segments[0].length + 1)
            } else {
                path = getDataColumn(context, uri, null, null)
            }
            if (path != null) {
                val file = File(path)
                if (!file.canRead()) {
                    return null
                }
            }
            return path
        }
        // File
        else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    /**
     * Get the value of the data column for this Uri. This is useful for MediaStore Uris, and other file-based
     * ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    @JvmStatic
    fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (e: Exception) {
            return null
        } finally {
            cursor?.close()
        }
        return null
    }

    /** @param uri The Uri to check. @return Whether the Uri authority is ExternalStorageProvider. */
    @JvmStatic
    fun isExternalStorageDocument(uri: Uri): Boolean = "com.android.externalstorage.documents" == uri.authority

    /** @param uri The Uri to check. @return Whether the Uri authority is DownloadsProvider. */
    @JvmStatic
    fun isDownloadsDocument(uri: Uri): Boolean = "com.android.providers.downloads.documents" == uri.authority

    /** @param uri The Uri to check. @return Whether the Uri authority is MediaProvider. */
    @JvmStatic
    fun isMediaDocument(uri: Uri): Boolean = "com.android.providers.media.documents" == uri.authority
}
