package uk.xa0.tulkki.ui.utils

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.fragment.settings.InterfaceSettingsFragment

object ChatBackgroundHelper {
    const val REQUEST_IMPORT_BACKGROUND = 0xbf8704

    @JvmStatic
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        conversationUUID: String?
    ) {
        if (requestCode == REQUEST_IMPORT_BACKGROUND) {
            if (resultCode == Activity.RESULT_OK) {
                val bguri = (data ?: throw NullPointerException("data")).data
                onPickFile(activity, bguri, conversationUUID)
            }
        }
    }

    @JvmStatic
    fun onRequestPermissionsResult(
        settingsFragment: InterfaceSettingsFragment,
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            requestCode == REQUEST_IMPORT_BACKGROUND
        ) {
            openBGPicker(settingsFragment)
        }
    }

    @JvmStatic
    fun onRequestPermissionsResult(
        fragment: Fragment,
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            requestCode == REQUEST_IMPORT_BACKGROUND
        ) {
            openBGPicker(fragment)
        }
    }

    @JvmStatic
    fun getBgFile(activity: Activity, conversationUUID: String?): File {
        return if (conversationUUID == null) {
            File(
                activity.filesDir.toString() + File.separator + "backgrounds" +
                    File.separator + "bg.jpg"
            )
        } else {
            File(
                activity.filesDir.toString() + File.separator + "backgrounds" +
                    File.separator + "bg_" + conversationUUID + ".jpg"
            )
        }
    }

    @JvmStatic
    fun getBgUri(activity: Activity, conversationUUID: String?): Uri? {
        val chatBgfileUri = File(
            activity.filesDir.toString() + File.separator + "backgrounds" +
                File.separator + "bg_" + conversationUUID + ".jpg"
        )
        val bgfileUri = File(
            activity.filesDir.toString() + File.separator + "backgrounds" +
                File.separator + "bg.jpg"
        )
        return if (chatBgfileUri.exists()) {
            Uri.fromFile(chatBgfileUri)
        } else if (bgfileUri.exists()) {
            Uri.fromFile(bgfileUri)
        } else {
            null
        }
    }

    @JvmStatic
    fun openBGPicker(settingsFragment: InterfaceSettingsFragment) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        settingsFragment.startActivityForResult(
            Intent.createChooser(intent, "Select image"), REQUEST_IMPORT_BACKGROUND
        )
    }

    @JvmStatic
    fun openBGPicker(fragment: Fragment) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        fragment.startActivityForResult(
            Intent.createChooser(intent, "Select image"), REQUEST_IMPORT_BACKGROUND
        )
    }

    private fun onPickFile(activity: Activity, uri: Uri?, conversationUUID: String?) {
        if (uri != null) {
            try {
                val bgfolder = File(
                    activity.filesDir.toString() + File.separator + "backgrounds"
                )
                val bgfile: File

                if (conversationUUID != null) {
                    bgfile = File(
                        activity.filesDir.toString() + File.separator + "backgrounds" +
                            File.separator + "bg_" + conversationUUID + ".jpg"
                    )
                } else {
                    bgfile = File(
                        activity.filesDir.toString() + File.separator + "backgrounds" +
                            File.separator + "bg.jpg"
                    )
                }
                //create output directory if it doesn't exist
                if (!bgfolder.exists()) {
                    bgfolder.mkdirs()
                }

                val inStream: InputStream =
                    activity.contentResolver.openInputStream(uri)
                        ?: throw NullPointerException("in")
                val outStream = FileOutputStream(bgfile)
                val buffer = ByteArray(4096)
                var read: Int
                while (true) {
                    read = inStream.read(buffer)
                    if (read == -1) break
                    outStream.write(buffer, 0, read)
                }
                inStream.close()
                // write the output file
                outStream.flush()
                outStream.close()
                compressImage(activity, bgfile, uri, 0)
                Toast.makeText(activity, R.string.custom_background_set, Toast.LENGTH_LONG).show()
            } catch (exception: IOException) {
                Toast.makeText(activity, R.string.create_background_failed, Toast.LENGTH_LONG)
                    .show()
                Log.d(Config.LOGTAG, "Could not create background" + exception)
            }
        }
    }

    private fun compressImage(activity: Activity, f: File, image: Uri, sampleSize: Int) {
        var input: InputStream? = null
        var output: OutputStream? = null
        val IMAGE_QUALITY = 65
        val ImageSize = (0.08 * 1024 * 1024).toInt()
        try {
            if (!f.exists() && !f.createNewFile()) {
                throw IOException(
                    uk.xa0.tulkki.data.R.string.error_unable_to_create_temporary_file.toString()
                )
            }
            input = activity.contentResolver.openInputStream(image)
            if (input == null) {
                throw IOException(
                    uk.xa0.tulkki.data.R.string.error_not_an_image_file.toString()
                )
            }
            val options = BitmapFactory.Options()
            val inSampleSize = Math.pow(2.0, sampleSize.toDouble()).toInt()
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize)
            options.inSampleSize = inSampleSize
            val originalBitmap = BitmapFactory.decodeStream(input, null, options)
            input.close()
            if (originalBitmap == null) {
                throw IOException("Source file was not an image")
            }
            if ("image/jpeg" != options.outMimeType && hasAlpha(originalBitmap)) {
                originalBitmap.recycle()
                throw IOException("Source file had alpha channel")
            }
            var size: Int
            val resolution = 1920
            if (resolution == 0) {
                val height = originalBitmap.height
                val width = originalBitmap.width
                size = if (height > width) height else width
            } else {
                size = resolution
            }
            var scaledBitmap = resize(originalBitmap, size)
            val rotation = getRotation(activity, image)
            scaledBitmap = rotate(scaledBitmap, rotation)
            var targetSizeReached = false
            var quality = IMAGE_QUALITY
            while (!targetSizeReached) {
                output = FileOutputStream(f)
                val success = scaledBitmap.compress(Config.IMAGE_FORMAT, quality, output)
                if (!success) {
                    throw IOException(
                        uk.xa0.tulkki.data.R.string.error_compressing_image.toString()
                    )
                }
                output.flush()
                targetSizeReached = (f.length() <= ImageSize && ImageSize != 0) || quality <= 50
                quality -= 5
            }
            scaledBitmap.recycle()
        } catch (e: FileNotFoundException) {
            cleanup(f)
            throw IOException(uk.xa0.tulkki.data.R.string.error_file_not_found.toString())
        } catch (e: IOException) {
            cleanup(f)
            throw IOException(uk.xa0.tulkki.data.R.string.error_io_exception.toString())
        } catch (e: SecurityException) {
            cleanup(f)
            throw IOException(
                uk.xa0.tulkki.data.R.string.error_security_exception_during_image_copy.toString()
            )
        } catch (e: OutOfMemoryError) {
            val next = sampleSize + 1
            if (next <= 3) {
                compressImage(activity, f, image, next)
            } else {
                throw IOException(uk.xa0.tulkki.data.R.string.error_out_of_memory.toString())
            }
        } finally {
            close(output)
            close(input)
        }
    }

    private fun getRotation(activity: Activity, image: Uri): Int {
        return try {
            activity.contentResolver.openInputStream(image).use { input ->
                if (input == null) 0 else getRotation(input)
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun getRotation(inputStream: InputStream): Int {
        val exif = ExifInterface(inputStream)
        val orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotate(bitmap: Bitmap, degree: Int): Bitmap {
        if (degree == 0) {
            return bitmap
        }
        val w = bitmap.width
        val h = bitmap.height
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        val result = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        return result
    }

    private fun close(stream: Closeable?) {
        if (stream != null) {
            try {
                stream.close()
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "unable to close stream", e)
            }
        }
    }

    private fun cleanup(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
        }
    }

    private fun resize(originalBitmap: Bitmap, size: Int): Bitmap {
        val w = originalBitmap.width
        val h = originalBitmap.height
        if (w <= 0 || h <= 0) {
            throw IOException("Decoded bitmap reported bounds smaller 0")
        } else if (maxOf(w, h) > size) {
            val scalledW: Int
            val scalledH: Int
            if (w <= h) {
                scalledW = maxOf((w / (h.toDouble() / size)).toInt(), 1)
                scalledH = size
            } else {
                scalledW = size
                scalledH = maxOf((h / (w.toDouble() / size)).toInt(), 1)
            }
            val result = Bitmap.createScaledBitmap(originalBitmap, scalledW, scalledH, true)
            if (!originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
            return result
        } else {
            return originalBitmap
        }
    }

    private fun hasAlpha(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val yStep = maxOf(1, w / 100)
        val xStep = maxOf(1, h / 100)
        for (x in 0 until w step xStep) {
            for (y in 0 until h step yStep) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) {
                    return true
                }
            }
        }
        return false
    }
}
