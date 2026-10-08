package uk.xa0.tulkki.ui.imageeditor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.exifinterface.media.ExifInterface
import uk.xa0.tulkki.ui.R
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * The platform helpers the ported editor still needs, gathered from the vendored
 * `medialib.extensions` package.
 *
 * <p>Only what is called survives: the vendored package also carried `EditText`, `ImageView`,
 * `View` and `AlertDialog` extensions for the XML dialogs the Compose rewrite replaced, four unused
 * SDK predicates, a filenames/content-URI pair and a media rescan nothing called, and one cursor
 * accessor family of which only [Cursor.getStringValue] is used. Merging them into one file is the
 * migration's licence to delete pointless files and merge duplication, not a translation.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
fun isNougatPlus() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

/** The vendor's `isOnMainThread`, kept private: the two callers below are all that use it. */
private fun isOnMainThread() = Looper.myLooper() == Looper.getMainLooper()

/** The vendor's `ensureBackgroundThread`: a plain thread when called from the main thread. */
fun ensureBackgroundThread(callback: () -> Unit) {
    if (isOnMainThread()) {
        Thread(callback).start()
    } else {
        callback()
    }
}

fun Context.toast(id: Int, length: Int = Toast.LENGTH_SHORT) = toast(getString(id), length)

fun Context.toast(msg: String, length: Int = Toast.LENGTH_SHORT) {
    try {
        if (isOnMainThread()) {
            doToast(this, msg, length)
        } else {
            Handler(Looper.getMainLooper()).post { doToast(this, msg, length) }
        }
    } catch (e: Exception) {
    }
}

fun Context.showErrorToast(msg: String, length: Int = Toast.LENGTH_LONG) = toast(String.format(getString(R.string.error), msg), length)

fun Context.showErrorToast(exception: Exception, length: Int = Toast.LENGTH_LONG) = showErrorToast(exception.toString(), length)

private fun doToast(context: Context, message: String, length: Int) {
    if (context is Activity && (context.isFinishing || context.isDestroyed)) {
        return
    }
    Toast.makeText(context, message, length).show()
}

/**
 * The vendored `getRealPathFromURI`: a `file` URI answers itself, a downloads/external-storage/media
 * document URI is resolved through the matching content provider, and anything else falls back to
 * the `_data` column.
 */
fun Context.getRealPathFromURI(uri: Uri): String? {
    if (uri.scheme == "file") {
        return uri.path
    }

    if (isDownloadsDocument(uri)) {
        val id = DocumentsContract.getDocumentId(uri)
        if (id.areDigitsOnly()) {
            val newUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id.toLong())
            getDataColumn(newUri)?.let { return it }
        }
    } else if (isExternalStorageDocument(uri)) {
        val documentId = DocumentsContract.getDocumentId(uri)
        val parts = documentId.split(":")
        if (parts[0].equals("primary", true)) {
            return "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
        }
    } else if (isMediaDocument(uri)) {
        val documentId = DocumentsContract.getDocumentId(uri)
        val split = documentId.split(":").dropLastWhile { it.isEmpty() }.toTypedArray()
        val type = split[0]

        val contentUri =
            when (type) {
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        getDataColumn(contentUri, "_id=?", arrayOf(split[1]))?.let { return it }
    }

    return getDataColumn(uri)
}

private fun Context.getDataColumn(uri: Uri, selection: String? = null, selectionArgs: Array<String>? = null): String? {
    try {
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val data = cursor.getStringValue(MediaStore.Files.FileColumns.DATA)
                if (data != "null") {
                    return data
                }
            }
        }
    } catch (e: Exception) {
    }
    return null
}

private fun isDownloadsDocument(uri: Uri) = uri.authority == "com.android.providers.downloads.documents"

private fun isExternalStorageDocument(uri: Uri) = uri.authority == "com.android.externalstorage.documents"

private fun isMediaDocument(uri: Uri) = uri.authority == "com.android.providers.media.documents"

@SuppressLint("Range")
private fun Cursor.getStringValue(key: String) = getString(getColumnIndex(key))

/** The vendor's `String.getCompressionFormat`, the source of the saved file's `Bitmap.CompressFormat`. */
fun String.getCompressionFormat() =
    when (getFilenameExtension().lowercase()) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

private fun String.getFilenameExtension() = substring(lastIndexOf(".") + 1)

private fun String.areDigitsOnly() = matches(Regex("[0-9]+"))

/** The vendor's Exif copy: every public `TAG_` attribute except the seven dimension/orientation ones. */
fun ExifInterface.copyNonDimensionAttributesTo(destination: ExifInterface) {
    ExifInterfaceAttributes.AllNonDimensionAttributes.forEach {
        val value = getAttribute(it)
        if (value != null) {
            destination.setAttribute(it, value)
        }
    }

    try {
        destination.saveAttributes()
    } catch (ignored: Exception) {
    }
}

private class ExifInterfaceAttributes {
    companion object {
        val AllNonDimensionAttributes = getAllNonDimensionExifAttributes()

        private fun getAllNonDimensionExifAttributes(): List<String> {
            val tagFields = ExifInterface::class.java.fields.filter { field -> isExif(field) }

            val excludeAttributes =
                arrayListOf(
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_PIXEL_X_DIMENSION,
                    ExifInterface.TAG_PIXEL_Y_DIMENSION,
                    ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
                    ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
                    ExifInterface.TAG_ORIENTATION,
                )

            return tagFields
                .map { tagField -> tagField.get(null) as String }
                .filter { x -> !excludeAttributes.contains(x) }
                .distinct()
        }

        private fun isExif(field: Field): Boolean =
            field.type == String::class.java &&
                isPublicStaticFinal(field.modifiers) &&
                field.name.startsWith("TAG_")

        private const val PUBLIC_STATIC_FINAL = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL

        private fun isPublicStaticFinal(modifiers: Int) = modifiers and PUBLIC_STATIC_FINAL > 0
    }
}
