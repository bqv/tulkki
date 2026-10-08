package uk.xa0.tulkki.data

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.StructStat
import android.util.Base64
import android.util.Base64OutputStream
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.io.ByteStreams
import com.wolt.blurhashkt.BlurHashDecoder
import io.ipfs.cid.Cid
import org.tomlj.Toml
import uk.xa0.tulkki.data.filepaths.FilePath
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.data.utils.BobCid
import uk.xa0.tulkki.data.utils.FileUtils
import uk.xa0.tulkki.data.utils.FileWriterException
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.data.utils.StorageDirectoryMigration
import uk.xa0.tulkki.data.utils.ThumbHash
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class FileBackend(
    private val mXmppConnectionService: XmppConnectionService,
    private val previewIcons: PreviewIcons,
) : FileBackendRef {

    companion object {

        private val THUMBNAIL_LOCK = Any()

        private val IMAGE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        private const val FILE_PROVIDER = ".files"
        private const val IGNORE_PADDING = 0.15f

        private val STORAGE_TYPES: List<String> =
            ImmutableList.Builder<String>()
                .add(
                    Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_PICTURES,
                    Environment.DIRECTORY_MOVIES,
                )
                .apply {
                    if (Build.VERSION.SDK_INT >= 19) {
                        add(Environment.DIRECTORY_DOCUMENTS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Environment.DIRECTORY_RECORDINGS)
                    }
                }
                .build()

        @JvmStatic
        fun getFileSize(context: Context, uri: Uri): Long {
            try {
                context.getContentResolver().query(uri, null, null, null, null).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index == -1) {
                            return -1
                        }
                        return cursor.getLong(index)
                    }
                    return -1
                }
            } catch (ignored: Exception) {
                return -1
            }
        }

        /**
         * 3.7 pair 2: `compressVideo` is a parameter now. The reading behind it is
         * `AttachFileToConversationRunnable.getVideoCompression`, which reads `R.string` and so
         * cannot move down, and whose only caller inside `:data` was this line - the one caller
         * (`:ui`'s `ConversationFragment`) computes it, where the preference screen that sets it
         * also lives.
         */
        @JvmStatic
        fun allFilesUnderSize(
            context: Context,
            attachments: List<Attachment>,
            max: Long,
            compressVideo: Boolean,
        ): Boolean {
            if (max <= 0) {
                Log.d(Config.LOGTAG, "server did not report max file size for http upload")
                return true // exception to be compatible with HTTP Upload < v0.2
            }
            for (attachment in attachments) {
                if (attachment.getType() != Attachment.Type.FILE) {
                    continue
                }
                val mime = attachment.getMime()
                if (mime != null && mime.startsWith("video/") && compressVideo) {
                    try {
                        val dimensions = getVideoDimensions(context, attachment.getUri())
                        if (dimensions.getMin() > 720) {
                            Log.d(
                                Config.LOGTAG,
                                "do not consider video file with min width larger than 720 for size" +
                                    " check",
                            )
                            continue
                        }
                    } catch (e: IOException) {
                        // ignore and fall through
                    } catch (e: NotAVideoFile) {
                        // ignore and fall through
                    }
                }
                if (getFileSize(context, attachment.getUri()) > max) {
                    Log.d(
                        Config.LOGTAG,
                        "not all files are under " + max + " bytes. suggesting falling back to jingle",
                    )
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun getBackupDirectory(context: Context): File {
            val downloadDirectory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    BuildConfig.APP_NAME,
                )
            return File(downloadDirectory, "Backup")
        }

        @JvmStatic
        fun getLegacyBackupDirectory(app: String): File {
            val appDirectory = File(Environment.getExternalStorageDirectory(), app)
            return File(appDirectory, "Backup")
        }

        private fun rotate(bitmap: Bitmap, degree: Int): Bitmap {
            if (degree == 0) {
                return bitmap
            }
            val w = bitmap.getWidth()
            val h = bitmap.getHeight()
            val matrix = Matrix()
            matrix.postRotate(degree.toFloat())
            val result = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
            if (!bitmap.isRecycled()) {
                bitmap.recycle()
            }
            return result
        }

        @JvmStatic
        fun isPathBlacklisted(path: String): Boolean {
            val androidDataPath =
                Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/"
            val f = File(path)
            return path.startsWith(androidDataPath) ||
                path.contains(".transforms/synthetic") ||
                !f.canRead()
        }

        private fun createAntiAliasingPaint(): Paint {
            val paint = Paint()
            paint.setAntiAlias(true)
            paint.setFilterBitmap(true)
            paint.setDither(true)
            return paint
        }

        @JvmStatic
        fun getUriForUri(context: Context, uri: Uri): Uri {
            return if ("file" == uri.getScheme()) {
                val file = File(uri.getPath())
                getUriForFile(context, file, file.getName())
            } else {
                uri
            }
        }

        @JvmStatic
        fun getUriForFile(context: Context, file: File, displayName: String): Uri {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ||
                Config.ONLY_INTERNAL_STORAGE ||
                file.toString().startsWith(context.getCacheDir().toString()) ||
                file.toString().startsWith(context.getFilesDir().toString())
            ) {
                try {
                    return FileProvider.getUriForFile(context, getAuthority(context), file, displayName)
                } catch (e: IllegalArgumentException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        throw SecurityException(e)
                    } else {
                        return Uri.fromFile(file)
                    }
                }
            } else {
                return Uri.fromFile(file)
            }
        }

        @JvmStatic
        fun getAuthority(context: Context): String {
            return context.getPackageName() + FILE_PROVIDER
        }

        private fun hasAlpha(bitmap: Bitmap): Boolean {
            val w = bitmap.getWidth()
            val h = bitmap.getHeight()
            val yStep = Math.max(1, w / 100)
            val xStep = Math.max(1, h / 100)
            var x = 0
            while (x < w) {
                var y = 0
                while (y < h) {
                    if (Color.alpha(bitmap.getPixel(x, y)) < 255) {
                        return true
                    }
                    y += yStep
                }
                x += xStep
            }
            return false
        }

        private fun calcSampleSize(image: File, size: Int): Int {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(image.getAbsolutePath(), options)
            return calcSampleSize(options, size)
        }

        private fun calcSampleSize(options: BitmapFactory.Options, size: Int): Int {
            var height = options.outHeight
            var width = options.outWidth
            var inSampleSize = 1

            if (height > size || width > size) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while ((halfHeight / inSampleSize) > size && (halfWidth / inSampleSize) > size) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private fun getVideoDimensions(context: Context, uri: Uri): Dimensions {
            val mediaMetadataRetriever = MediaMetadataRetriever()
            try {
                mediaMetadataRetriever.setDataSource(context, uri)
            } catch (e: RuntimeException) {
                throw NotAVideoFile(e)
            }
            return getVideoDimensions(mediaMetadataRetriever)
        }

        private fun getVideoDimensionsOfFrame(
            mediaMetadataRetriever: MediaMetadataRetriever,
        ): Dimensions? {
            var bitmap: Bitmap? = null
            try {
                bitmap = mediaMetadataRetriever.getFrameAtTime()
                val frame = bitmap ?: throw NullPointerException()
                return Dimensions(frame.getHeight(), frame.getWidth())
            } catch (e: Exception) {
                return null
            } finally {
                if (bitmap != null) {
                    bitmap.recycle()
                }
            }
        }

        private fun getVideoDimensions(metadataRetriever: MediaMetadataRetriever): Dimensions {
            val hasVideo =
                metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo == null) {
                throw NotAVideoFile()
            }
            val dimensions = getVideoDimensionsOfFrame(metadataRetriever)
            if (dimensions != null) {
                return dimensions
            }
            val rotation = extractRotationFromMediaRetriever(metadataRetriever)
            val rotated = rotation == 90 || rotation == 270
            var height: Int
            try {
                val h =
                    metadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    )
                height = Integer.parseInt(h ?: throw NumberFormatException())
            } catch (e: Exception) {
                height = -1
            }
            var width: Int
            try {
                val w =
                    metadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    )
                width = Integer.parseInt(w ?: throw NumberFormatException())
            } catch (e: Exception) {
                width = -1
            }
            try {
                metadataRetriever.release()
            } catch (e: IOException) {
                throw NotAVideoFile()
            }
            Log.d(Config.LOGTAG, "extracted video dims " + width + "x" + height)
            return if (rotated) Dimensions(width, height) else Dimensions(height, width)
        }

        private fun extractRotationFromMediaRetriever(
            metadataRetriever: MediaMetadataRetriever,
        ): Int {
            val r =
                metadataRetriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )
            try {
                return Integer.parseInt(r ?: throw NumberFormatException())
            } catch (e: Exception) {
                return 0
            }
        }

        @JvmStatic
        fun close(stream: Closeable?) {
            if (stream != null) {
                try {
                    stream.close()
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "unable to close stream", e)
                }
            }
        }

        @JvmStatic
        fun close(socket: Socket?) {
            if (socket != null) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    Log.d(Config.LOGTAG, "unable to close socket", e)
                }
            }
        }

        @JvmStatic
        fun close(socket: ServerSocket?) {
            if (socket != null) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    Log.d(Config.LOGTAG, "unable to close server socket", e)
                }
            }
        }

        @JvmStatic
        fun dangerousFile(uri: Uri?): Boolean {
            if (uri == null || Strings.isNullOrEmpty(uri.getScheme())) {
                return true
            }
            if (ContentResolver.SCHEME_FILE == uri.getScheme()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // On Android 7 (and apps that target 7) it is now longer possible to share files
                    // with a file scheme. By now you should probably not be running apps that target
                    // anything less than 7 any more
                    return true
                } else {
                    return isFileOwnedByProcess(uri)
                }
            }
            return false
        }

        private fun isFileOwnedByProcess(uri: Uri): Boolean {
            val path = uri.getPath()
            if (path == null) {
                return true
            }
            try {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { pfd ->
                        val fd: FileDescriptor = pfd.getFileDescriptor()
                        val st: StructStat = Os.fstat(fd)
                        return st.st_uid == android.os.Process.myUid()
                    }
            } catch (e: Exception) {
                // when in doubt. better safe than sorry
                return true
            }
        }

        @JvmStatic
        fun getMediaUri(context: Context, file: File): Uri? {
            val filePath = file.getAbsolutePath()
            try {
                context.getContentResolver()
                    .query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        MediaStore.Images.Media.DATA + "=? ",
                        arrayOf(filePath),
                        null,
                    )
                    .use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            val id =
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),
                                )
                            return Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id.toString(),
                            )
                        } else {
                            return null
                        }
                    }
            } catch (e: Exception) {
                return null
            }
        }

        @JvmStatic
        fun updateFileParams(message: Message, url: String?, size: Long) {
            val fileParams = Message.FileParams()
            fileParams.url = url
            fileParams.size = size
            message.setFileParams(fileParams)
        }

        private fun cleanup(file: File) {
            try {
                file.delete()
            } catch (e: Exception) {
                // ignore
            }
        }

        /** The public directory Tulkki keeps received files, recordings and backups in. */
        @JvmStatic
        fun getStorageDirectory(): File {
            return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS + "/" + StorageDirectoryMigration.DIRECTORY,
            )
        }

        /** The same directory under upstream's name, as it is on the owner's phone. */
        @JvmStatic
        fun getLegacyStorageDirectory(): File {
            return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS + "/" + StorageDirectoryMigration.LEGACY_DIRECTORY,
            )
        }

        /**
         * The one-time move of the collection directory to our name, called once at process start.
         *
         * It is deliberately here rather than in `:app`: the paths are this class's, and the
         * decision (and its test) is [StorageDirectoryMigration]'s. A missing legacy directory --
         * every fresh install -- makes this a `false` and touches nothing.
         */
        @JvmStatic
        fun migrateLegacyStorageDirectory(): Boolean {
            return StorageDirectoryMigration.migrate(
                getLegacyStorageDirectory(),
                getStorageDirectory(),
            )
        }

        @JvmStatic
        fun inAppStorageDirectory(context: Context, path: String): Boolean {
            val fileDirectory = File(path).getParentFile()
            for (type in STORAGE_TYPES) {
                val typeDirectory =
                    File(
                        Environment.getExternalStoragePublicDirectory(type),
                        BuildConfig.APP_NAME,
                    )
                if (typeDirectory == fileDirectory) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        @Throws(IOException::class)
        fun getRotation(inputStream: InputStream): Int {
            val exif = ExifInterface(inputStream)
            val orientation =
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_180 -> return 180
                ExifInterface.ORIENTATION_ROTATE_90 -> return 90
                ExifInterface.ORIENTATION_ROTATE_270 -> return 270
                else -> return 0
            }
        }

        @JvmStatic
        fun rectForSize(w: Int, h: Int, size: Int): Rect {
            var scalledW: Int
            var scalledH: Int
            if (w <= h) {
                scalledW = Math.max((w / (h.toDouble() / size)).toInt(), 1)
                scalledH = size
            } else {
                scalledW = size
                scalledH = Math.max((h / (w.toDouble() / size)).toInt(), 1)
            }

            if (scalledW > w || scalledH > h) return Rect(0, 0, w, h)

            return Rect(0, 0, scalledW, scalledH)
        }

        @JvmStatic
        fun drawDrawable(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null

            var bitmap: Bitmap? = null

            if (drawable is BitmapDrawable) {
                bitmap = drawable.getBitmap()
                if (bitmap != null) return bitmap
            }

            val bounds = drawable.getBounds()
            var width = drawable.getIntrinsicWidth()
            if (width < 1) width = if (bounds == null || bounds.right < 1) 256 else bounds.right
            var height = drawable.getIntrinsicHeight()
            if (height < 1) height = if (bounds == null || bounds.bottom < 1) 256 else bounds.bottom

            if (width < 1) {
                Log.w(Config.LOGTAG, "Drawable with no width: " + drawable)
                width = 48
            }
            if (height < 1) {
                Log.w(Config.LOGTAG, "Drawable with no height: " + drawable)
                height = 48
            }

            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight())
            drawable.draw(canvas)
            return bitmap
        }

        private fun scalePdfDimensions(`in`: Dimensions, target: Int, fit: Boolean): Dimensions {
            val w: Int
            val h: Int
            if (fit == (`in`.width <= `in`.height)) {
                w = Math.max((`in`.width / (`in`.height.toDouble() / target)).toInt(), 1)
                h = target
            } else {
                w = target
                h = Math.max((`in`.height / (`in`.width.toDouble() / target)).toInt(), 1)
            }
            return Dimensions(h, w)
        }
    }

    // Tulkki: C5-R1 - this member was `static` while the island reached it through the instance the
    // accessor handed back (`HttpDownloadConnection`'s size checker). The accessor now answers
    // `FileBackendRef`, and an interface carries no static implementation, so the `MessageRef` form
    // becomes a real instance method. The `(Message, String, long)` overload stays static and
    // stays `:data`'s: a `Message` argument selects it, being strictly more specific than `MessageRef`,
    // so the delegation here terminates and no call site is ambiguous.
    override fun updateFileParams(message: MessageRef, url: String?, size: Long) {
        Companion.updateFileParams(message as Message, url, size)
    }

    // Tulkki: C5-E3 - the parameter is the ref; the body reads `getUuid()`, `getMime()` and
    // `getUri()`, all of them on it, so nothing here casts and an `Attachment` argument still binds
    // (a model is a ref), which covers every existing caller (`MediaAdapter:337/422`,
    // `MediaPreviewAdapter:138/246`, the Kotlin calendar task). `allFilesUnderSize` stays
    // model-typed: it reads `getType()`.
    fun getPreviewForUri(attachment: AttachmentRef, size: Int, cacheOnly: Boolean): Bitmap? {
        val key = "attachment_" + attachment.getUuid().toString() + "_" + size
        val cache = mXmppConnectionService.getDrawableCache()
        val drawable = cache.get(key)
        if (drawable != null || cacheOnly) {
            return drawDrawable(drawable)
        }
        var bitmap: Bitmap? = null
        val mime = attachment.getMime()
        if ("application/pdf" == mime) {
            bitmap = cropCenterSquarePdf(attachment.getUri(), size)
            drawOverlay(
                bitmap,
                if (paintOverlayBlackPdf(bitmap)) previewIcons.openPdfBlack() else previewIcons.openPdfWhite(),
                0.75f,
            )
        } else if (mime != null && mime.startsWith("video/")) {
            bitmap = cropCenterSquareVideo(attachment.getUri(), size)
            drawOverlay(
                bitmap,
                if (paintOverlayBlack(bitmap)) previewIcons.playVideoBlack() else previewIcons.playVideoWhite(),
                0.75f,
            )
        } else {
            bitmap = cropCenterSquare(attachment.getUri(), size)
            if (bitmap != null && "image/gif" == mime) {
                val withGifOverlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                drawOverlay(
                    withGifOverlay,
                    if (paintOverlayBlack(withGifOverlay)) previewIcons.playGifBlack() else previewIcons.playGifWhite(),
                    1.0f,
                )
                bitmap.recycle()
                bitmap = withGifOverlay
            }
        }
        if (bitmap != null) {
            cache.put(key, BitmapDrawable(bitmap))
        }
        return bitmap
    }

    override fun updateMediaScanner(file: File) {
        updateMediaScanner(file, null)
    }

    override fun updateMediaScanner(file: File, callback: Runnable?) {
        MediaScannerConnection.scanFile(
            mXmppConnectionService,
            arrayOf(file.getAbsolutePath()),
            null,
            object : MediaScannerConnection.MediaScannerConnectionClient {
                override fun onMediaScannerConnected() {}

                override fun onScanCompleted(path: String?, uri: Uri?) {
                    if (callback != null && file.getAbsolutePath() == path) {
                        callback.run()
                    } else {
                        Log.d(Config.LOGTAG, "media scanner scanned wrong file")
                        if (callback != null) {
                            callback.run()
                        }
                    }
                }
            },
        )
    }

    // -------------------------------------------------------------------------------------------
    // Tulkki: 3.7 pair 9, part 12. `MessageParser` asks the file backend through the service
    // (`mXmppConnectionService.getFileBackend()`), and it holds a `MessageRef` now, so both methods it
    // calls answer for that static type as well. Each casts once and delegates; a `Message` argument
    // still selects the model method, because a class is strictly more specific than its interface.
    // `deleteFile(File)` is untouched and unshadowed: a `DownloadableFile` is a `File` but not a
    // `MessageRef`, so the two overloads can never compete.

    override fun deleteFile(message: MessageRef): Boolean {
        return deleteFile(message as Message)
    }

    override fun updateFileParams(message: MessageRef, url: String?, updateCids: Boolean) {
        updateFileParams(message as Message, url, updateCids)
    }

    // Tulkki: 3.7 pair 9, part 16 - the same two-line shape, forced by `XmppConnectionService`'s own
    // retype. `getFile` answers the model type: C5-E3 did not need it to move (every island caller
    // either stores the result in a ref-typed local or passes it on, and the JDK `File` half the old
    // comment said was missing has been on `DownloadableFileRef` since C5-B), so the return type
    // stays what it is and only the parameter is the ref.

    override fun getFile(message: MessageRef): DownloadableFile {
        return getFile(message as Message)
    }

    override fun getFile(message: MessageRef, decrypted: Boolean): DownloadableFile {
        return getFile(message as Message, decrypted)
    }

    override fun setupRelativeFilePath(message: MessageRef, filename: String) {
        setupRelativeFilePath(message as Message, filename)
    }

    override fun setupRelativeFilePath(message: MessageRef, filename: String, mime: String?) {
        setupRelativeFilePath(message as Message, filename, mime)
    }

    @Throws(IOException::class, uk.xa0.tulkki.xmpp.services.BlockedMediaException::class)
    override fun setupRelativeFilePath(
        message: MessageRef,
        inputStream: InputStream,
        extension: String?,
    ) {
        setupRelativeFilePath(message as Message, inputStream, extension)
    }

    override fun updateFileParams(message: MessageRef, url: String?) {
        updateFileParams(message as Message, url)
    }

    override fun updateFileParams(message: MessageRef) {
        updateFileParams(message as Message)
    }

    @Throws(ImageCompressionException::class, FileCopyException::class)
    override fun copyImageToPrivateStorage(message: MessageRef, image: Uri) {
        copyImageToPrivateStorage(message as Message, image)
    }

    @Throws(FileCopyException::class)
    override fun copyAttachmentToDownloadsFolder(message: MessageRef) {
        copyAttachmentToDownloadsFolder(message as Message)
    }

    fun deleteFile(message: Message): Boolean {
        val file = getFile(message)
        if (file.delete()) {
            message.setDeleted(true)
            updateMediaScanner(file)
            return true
        } else {
            return false
        }
    }

    fun getFile(message: Message): DownloadableFile {
        return getFile(message, true)
    }

    override fun getFileForPath(path: String): DownloadableFile {
        return getFileForPath(
            path,
            MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(path)),
        )
    }

    private fun getFileForPath(path: String, mime: String?): DownloadableFile {
        return if (path.startsWith("/")) {
            DownloadableFile(path)
        } else {
            getLegacyFileForFilename(path, mime)
        }
    }

    fun getLegacyFileForFilename(filename: String, mime: String?): DownloadableFile {
        return if (Strings.isNullOrEmpty(mime)) {
            DownloadableFile(getLegacyStorageLocation("Files"), filename)
        } else {
            val m = mime ?: throw NullPointerException()
            when {
                m.startsWith("image/") ->
                    DownloadableFile(getLegacyStorageLocation("Images"), filename)
                m.startsWith("video/") ->
                    DownloadableFile(getLegacyStorageLocation("Videos"), filename)
                m.startsWith("audio/") ->
                    DownloadableFile(getLegacyStorageLocation("Audios"), filename)
                else -> DownloadableFile(getLegacyStorageLocation("Files"), filename)
            }
        }
    }

    override fun isInternalFile(file: File): Boolean {
        val internalFile = getFileForPath(file.getName())
        return file.getAbsolutePath() == internalFile.getAbsolutePath()
    }

    fun getFile(message: Message, decrypted: Boolean): DownloadableFile {
        val encrypted =
            !decrypted &&
                (message.getEncryption() == Message.ENCRYPTION_PGP ||
                    message.getEncryption() == Message.ENCRYPTION_DECRYPTED)
        var path = message.getRelativeFilePath()
        if (path == null) {
            path = message.getUuid()
        }
        val msgFile = getFileForPath(path ?: throw NullPointerException(), message.getMimeType())

        val file: DownloadableFile
        if (encrypted) {
            file =
                DownloadableFile(
                    mXmppConnectionService.getCacheDir(),
                    String.format("%s.%s", msgFile.getName(), "pgp"),
                )
        } else {
            file = msgFile
        }

        try {
            if (file.exists() &&
                file.toString().startsWith(mXmppConnectionService.getCacheDir().toString()) &&
                Build.VERSION.SDK_INT >= 26
            ) {
                java.nio.file.Files.setAttribute(
                    file.toPath(),
                    "lastAccessTime",
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                )
            }
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "unable to set lastAccessTime for " + file)
        }

        return file
    }

    /**
     * Tulkki: port-13 - the parameter is the nested model again. It was the island's
     * `FilePathRef` while `DataStatics.loadAttachments` asked the island's `DatabaseBackendRef` for
     * `getRelativeFilePaths`; that member is gone and the composition root asks the model directly, so
     * the ref has no island consumer left and this signature returns to `:data`'s own type. The body
     * reads the same four fields - `getUuid()` is the stringified `UUID` the model builds, so
     * `UUID.fromString` on it is identity. The `Map` overload below was always model-typed.
     */
    fun convertToAttachments(
        relativeFilePaths: List<FilePath>,
    ): List<Attachment> {
        val attachments = ArrayList<Attachment>()
        for (relativeFilePath in relativeFilePaths) {
            val mime =
                MimeUtils.guessMimeTypeFromExtension(
                    MimeUtils.extractRelevantExtension(relativeFilePath.getPath()),
                )
            val file = getFileForPath(relativeFilePath.getPath(), mime)
            attachments.add(
                Attachment.of(
                    UUID.fromString(relativeFilePath.getUuid()),
                    file,
                    mime,
                    relativeFilePath.getTimestamp(),
                    relativeFilePath.getConversationUuid(),
                ),
            )
        }
        return attachments
    }

    fun convertToAttachments(
        relativeFilePaths: Map<Int, FilePath>,
    ): Map<Int, Attachment> {
        val attachments = HashMap<Int, Attachment>()
        relativeFilePaths.forEach { (key, value) ->
            val mime =
                MimeUtils.guessMimeTypeFromExtension(
                    MimeUtils.extractRelevantExtension(value.path),
                )
            val file = getFileForPath(value.path, mime)
            attachments[key] = Attachment.of(value.uuid, file, mime)
        }

        return attachments
    }

    private fun getLegacyStorageLocation(type: String): File {
        return if (Config.ONLY_INTERNAL_STORAGE) {
            File(mXmppConnectionService.getFilesDir(), type)
        } else {
            val appDirectory =
                File(Environment.getExternalStorageDirectory(), BuildConfig.APP_NAME)
            val appMediaDirectory = File(appDirectory, "Media")
            val locationName = String.format("%s %s", BuildConfig.APP_NAME, type)
            File(appMediaDirectory, locationName)
        }
    }

    @Throws(IOException::class)
    private fun resize(originalBitmap: Bitmap, size: Int): Bitmap {
        val w = originalBitmap.getWidth()
        val h = originalBitmap.getHeight()
        if (w <= 0 || h <= 0) {
            throw IOException("Decoded bitmap reported bounds smaller 0")
        } else if (Math.max(w, h) > size) {
            val scalledW: Int
            val scalledH: Int
            if (w <= h) {
                scalledW = Math.max((w / (h.toDouble() / size)).toInt(), 1)
                scalledH = size
            } else {
                scalledW = size
                scalledH = Math.max((h / (w.toDouble() / size)).toInt(), 1)
            }
            val result = Bitmap.createScaledBitmap(originalBitmap, scalledW, scalledH, true)
            if (!originalBitmap.isRecycled()) {
                originalBitmap.recycle()
            }
            return result
        } else {
            return originalBitmap
        }
    }

    fun getUriSize(uri: Uri): Long {
        var cursor: Cursor? = null
        try {
            cursor = mXmppConnectionService.getContentResolver().query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex < 0) return 0
                return cursor.getLong(sizeIndex)
            }
        } finally {
            cursor?.close()
        }
        return 0 // Return 0 if the size information is not available
    }

    override fun useImageAsIs(uri: Uri): Boolean {
        try {
            val fsize = getUriSize(uri)
            if (fsize == 0L ||
                fsize >=
                mXmppConnectionService
                    .getResources()
                    .getInteger(uk.xa0.tulkki.xmpp.R.integer.auto_accept_filesize)
                    .toLong()
            ) {
                return false
            }

            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source =
                    ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), uri)
                val size = intArrayOf(0, 0)
                val animated = booleanArrayOf(false)
                val mimeType = arrayOfNulls<String>(1)
                ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                    mimeType[0] = info.getMimeType()
                    animated[0] = info.isAnimated()
                    size[0] = info.getSize().getWidth()
                    size[1] = info.getSize().getHeight()
                }

                return animated[0] ||
                    (size[0] <= Config.IMAGE_SIZE && size[1] <= Config.IMAGE_SIZE)
            }

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            val inputStream =
                mXmppConnectionService.getContentResolver().openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            close(inputStream)
            if (options.outMimeType == null || options.outHeight <= 0 || options.outWidth <= 0) {
                return false
            }
            return options.outWidth <= Config.IMAGE_SIZE && options.outHeight <= Config.IMAGE_SIZE
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to get image dimensions", e)
            return false
        }
    }

    override fun getOriginalPath(uri: Uri): String? {
        return FileUtils.getPath(mXmppConnectionService, uri)
    }

    @Throws(FileCopyException::class)
    fun copyAttachmentToDownloadsFolder(m: Message) {
        val input = FileBackends.get().getFile(m)

        val parentDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var output = File(parentDirectory, input.getName())
        var counter = 1
        while (output.exists()) {
            output = File(parentDirectory, "(" + counter + ") " + input.getName())
            counter++
        }

        try {
            output.createNewFile()
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        }
        try {
            FileOutputStream(output).use { os ->
                val inputStream =
                    mXmppConnectionService
                        .getContentResolver()
                        .openInputStream(Uri.fromFile(input))
                if (inputStream == null) {
                    throw FileCopyException(R.string.error_file_not_found)
                }
                inputStream.use { stream ->
                    try {
                        ByteStreams.copy(stream, os)
                    } catch (e: IOException) {
                        throw FileWriterException(output)
                    }
                    try {
                        os.flush()
                    } catch (e: IOException) {
                        throw FileWriterException(output)
                    }

                    updateMediaScanner(output)
                }
            }
        } catch (e: FileNotFoundException) {
            cleanup(output)
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: FileWriterException) {
            cleanup(output)
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        } catch (e: SecurityException) {
            cleanup(output)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IllegalStateException) {
            cleanup(output)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IOException) {
            cleanup(output)
            throw FileCopyException(R.string.error_io_exception)
        }
    }

    @Throws(FileCopyException::class)
    override fun copyAttachmentToDownloadsFolder(input: File) {
        val parentDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var output = File(parentDirectory, input.getName())
        var counter = 1
        while (output.exists()) {
            output = File(parentDirectory, "(" + counter + ") " + input.getName())
            counter++
        }

        try {
            output.createNewFile()
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        }
        try {
            FileOutputStream(output).use { os ->
                val inputStream =
                    mXmppConnectionService
                        .getContentResolver()
                        .openInputStream(Uri.fromFile(input))
                if (inputStream == null) {
                    throw FileCopyException(R.string.error_file_not_found)
                }
                inputStream.use { stream ->
                    try {
                        ByteStreams.copy(stream, os)
                    } catch (e: IOException) {
                        throw FileWriterException(output)
                    }
                    try {
                        os.flush()
                    } catch (e: IOException) {
                        throw FileWriterException(output)
                    }

                    updateMediaScanner(output)
                }
            }
        } catch (e: FileNotFoundException) {
            cleanup(output)
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: FileWriterException) {
            cleanup(output)
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        } catch (e: SecurityException) {
            cleanup(output)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IllegalStateException) {
            cleanup(output)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IOException) {
            cleanup(output)
            throw FileCopyException(R.string.error_io_exception)
        }
    }

    @Throws(FileCopyException::class)
    fun copyFileToDocumentFile(ctx: Context, file: File, df: DocumentFile) {
        Log.d(Config.LOGTAG, "copy file (" + file + ") to " + df)
        try {
            FileInputStream(file).use { stream ->
                val os =
                    mXmppConnectionService.getContentResolver().openOutputStream(df.getUri())
                if (os == null) throw NullPointerException()
                os.use { out ->
                    try {
                        ByteStreams.copy(stream, out)
                        out.flush()
                    } catch (e: IOException) {
                        throw FileWriterException(file)
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: FileNotFoundException) {
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: FileWriterException) {
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        } catch (e: SecurityException) {
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IllegalStateException) {
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_io_exception)
        }
    }

    @Throws(IOException::class)
    fun openInputStream(uri: Uri?): InputStream {
        if (uri != null && "data" == uri.getScheme()) {
            val parts = uri.getSchemeSpecificPart().split(",", limit = 2).toMutableList()
            val data: ByteArray
            if (parts[0].split(";").contains("base64")) {
                val parts2 = parts[0].split(";", limit = 2)
                parts[0] = parts2[0]
                data = Base64.decode(parts[1], 0)
            } else {
                data = parts[1].toByteArray(Charsets.UTF_8)
            }
            return ByteArrayInputStream(data)
        }
        val inputStream =
            mXmppConnectionService.getContentResolver().openInputStream(uri ?: throw NullPointerException())
        if (inputStream == null) throw FileNotFoundException("File not found")
        return inputStream
    }

    @Throws(FileCopyException::class)
    override fun copyFileToPrivateStorage(file: File, uri: Uri) {
        val parentDirectory = file.getParentFile()
        if (parentDirectory != null && parentDirectory.mkdirs()) {
            Log.d(Config.LOGTAG, "created directory " + parentDirectory.getAbsolutePath())
        }
        try {
            if (!file.createNewFile() && file.length() > 0) {
                if (file.canRead() && file.getName().startsWith("zb2")) {
                    return // We have this content already
                }
                throw FileCopyException(R.string.error_unable_to_create_temporary_file)
            }
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        }
        try {
            FileOutputStream(file).use { os ->
                openInputStream(uri).use { stream ->
                    try {
                        ByteStreams.copy(stream, os)
                    } catch (e: IOException) {
                        throw FileWriterException(file)
                    }
                    try {
                        os.flush()
                    } catch (e: IOException) {
                        throw FileWriterException(file)
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            cleanup(file)
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: FileWriterException) {
            cleanup(file)
            throw FileCopyException(R.string.error_unable_to_create_temporary_file)
        } catch (e: SecurityException) {
            cleanup(file)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IllegalStateException) {
            cleanup(file)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IllegalArgumentException) {
            cleanup(file)
            throw FileCopyException(R.string.error_security_exception)
        } catch (e: IOException) {
            cleanup(file)
            throw FileCopyException(R.string.error_io_exception)
        }
    }

    @Throws(FileCopyException::class)
    fun copyFileToPrivateStorage(message: Message, uri: Uri, type: String?) {
        val mime = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type)
        Log.d(Config.LOGTAG, "copy " + uri.toString() + " to private storage (mime=" + mime + ")")
        var extension = MimeUtils.guessExtensionFromMimeType(mime)
        if (extension == null) {
            Log.d(Config.LOGTAG, "extension from mime type was null")
            extension = getExtensionFromUri(uri)
        }
        if ("ogg" == extension && type != null && type.startsWith("audio/")) {
            extension = "oga"
        }

        try {
            setupRelativeFilePath(message, uri, extension)
            copyFileToPrivateStorage(FileBackends.get().getFile(message), uri)
            /*  //TODO for now we don't use the original filename
            final String name = getDisplayNameFromUri(uri);
            if (name != null) {
                message.getFileParams().setName(name);
            }
            */
        } catch (e: uk.xa0.tulkki.xmpp.services.BlockedMediaException) {
            message.setRelativeFilePath(null)
            message.setDeleted(true)
        }
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        var filename: String? = null
        try {
            mXmppConnectionService
                .getContentResolver()
                .query(uri, projection, null, null, null)
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        filename = cursor.getString(0)
                    }
                }
        } catch (e: Exception) {
            filename = null
        }
        return filename
    }

    private fun getExtensionFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        var filename: String? = null
        try {
            mXmppConnectionService
                .getContentResolver()
                .query(uri, projection, null, null, null)
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        filename = cursor.getString(0)
                    }
                }
        } catch (e: Exception) {
            filename = null
        }
        if (filename == null) {
            val segments = uri.getPathSegments()
            if (segments.size > 0) {
                filename = segments[segments.size - 1]
            }
        }
        val name = filename
        val pos = name?.lastIndexOf('.') ?: -1
        return if (pos > 0) name?.substring(pos + 1) else null
    }

    @Throws(ImageCompressionException::class, FileCopyException::class)
    private fun copyImageToPrivateStorage(file: File, image: Uri, sampleSize: Int) {
        val parent = file.getParentFile()
        if (parent != null && parent.mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory")
        }
        var inputStream: InputStream? = null
        var os: OutputStream? = null
        try {
            if (!file.exists() && !file.createNewFile()) {
                throw FileCopyException(R.string.error_unable_to_create_temporary_file)
            }
            inputStream = mXmppConnectionService.getContentResolver().openInputStream(image)
            if (inputStream == null) {
                throw FileCopyException(R.string.error_not_an_image_file)
            }
            val options = BitmapFactory.Options()
            val inSampleSize = Math.pow(2.0, sampleSize.toDouble()).toInt()
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize)
            options.inSampleSize = inSampleSize
            val originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            if (originalBitmap == null) {
                throw ImageCompressionException("Source file was not an image")
            }
            var sourceBitmap = originalBitmap
            if ("image/jpeg" != options.outMimeType && hasAlpha(originalBitmap)) {
                val flattened =
                    Bitmap.createBitmap(
                        originalBitmap.getWidth(),
                        originalBitmap.getHeight(),
                        Bitmap.Config.ARGB_8888,
                    )
                val canvas = Canvas(flattened)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(originalBitmap, 0f, 0f, null)
                originalBitmap.recycle()
                sourceBitmap = flattened
            }
            var scaledBitmap = resize(sourceBitmap, Config.IMAGE_SIZE)
            val rotation = getRotation(image)
            scaledBitmap = rotate(scaledBitmap, rotation)
            var targetSizeReached = false
            var quality = Config.IMAGE_QUALITY
            val imageMaxSize =
                mXmppConnectionService
                    .getResources()
                    .getInteger(uk.xa0.tulkki.xmpp.R.integer.auto_accept_filesize)
            while (!targetSizeReached) {
                val out = FileOutputStream(file)
                os = out
                Log.d(Config.LOGTAG, "compressing image with quality " + quality)
                val success = scaledBitmap.compress(Config.IMAGE_FORMAT, quality, out)
                if (!success) {
                    throw FileCopyException(R.string.error_compressing_image)
                }
                out.flush()
                val fileSize = file.length()
                Log.d(Config.LOGTAG, "achieved file size of " + fileSize)
                targetSizeReached = fileSize <= imageMaxSize.toLong() || quality <= 50
                quality -= 5
            }
            scaledBitmap.recycle()
        } catch (e: FileNotFoundException) {
            cleanup(file)
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: IOException) {
            cleanup(file)
            throw FileCopyException(R.string.error_io_exception)
        } catch (e: SecurityException) {
            cleanup(file)
            throw FileCopyException(R.string.error_security_exception_during_image_copy)
        } catch (e: OutOfMemoryError) {
            val next = sampleSize + 1
            if (next <= 3) {
                copyImageToPrivateStorage(file, image, next)
            } else {
                throw FileCopyException(R.string.error_out_of_memory)
            }
        } finally {
            close(os)
            close(inputStream)
        }
    }

    @Throws(FileCopyException::class, ImageCompressionException::class)
    override fun copyImageToPrivateStorage(file: File, image: Uri) {
        Log.d(
            Config.LOGTAG,
            "copy image (" + image.toString() + ") to private storage " + file.getAbsolutePath(),
        )
        copyImageToPrivateStorage(file, image, 0)
    }

    @Throws(FileCopyException::class, ImageCompressionException::class)
    fun copyImageToPrivateStorage(message: Message, image: Uri) {
        val filename: String =
            when (Config.IMAGE_FORMAT) {
                Bitmap.CompressFormat.JPEG -> String.format("%s.%s", message.getUuid(), "jpg")
                Bitmap.CompressFormat.PNG -> String.format("%s.%s", message.getUuid(), "png")
                Bitmap.CompressFormat.WEBP -> String.format("%s.%s", message.getUuid(), "webp")
                else -> throw IllegalStateException("Unknown image format")
            }
        setupRelativeFilePath(message, filename)
        val tmp = getFile(message)
        copyImageToPrivateStorage(tmp, image)
        val extension = MimeUtils.extractRelevantExtension(filename)
        try {
            setupRelativeFilePath(message, FileInputStream(tmp), extension)
        } catch (e: FileNotFoundException) {
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_io_exception)
        } catch (e: uk.xa0.tulkki.xmpp.services.BlockedMediaException) {
            tmp.delete()
            message.setRelativeFilePath(null)
            message.setDeleted(true)
            return
        }
        tmp.renameTo(getFile(message))
        updateFileParams(message, null, false)
    }

    @Throws(FileCopyException::class, uk.xa0.tulkki.xmpp.services.BlockedMediaException::class)
    fun setupRelativeFilePath(message: Message, uri: Uri, extension: String?) {
        try {
            setupRelativeFilePath(message, openInputStream(uri), extension)
        } catch (e: FileNotFoundException) {
            throw FileCopyException(R.string.error_file_not_found)
        } catch (e: IOException) {
            throw FileCopyException(R.string.error_io_exception)
        }
    }

    @Throws(IOException::class)
    fun calculateCids(uri: Uri): Array<Cid> {
        return calculateCids(
            mXmppConnectionService.getContentResolver().openInputStream(uri)
                ?: throw NullPointerException(),
        )
    }

    @Throws(IOException::class)
    override fun calculateCids(inputStream: InputStream): Array<Cid> {
        try {
            return CryptoHelper.cid(inputStream, arrayOf("SHA-256", "SHA-1", "SHA-512"))
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @Throws(IOException::class, uk.xa0.tulkki.xmpp.services.BlockedMediaException::class)
    fun setupRelativeFilePath(message: Message, inputStream: InputStream, extension: String?) {
        message.setRelativeFilePath(
            getStorageLocation(message, inputStream, extension).getAbsolutePath(),
        )
    }

    fun setupRelativeFilePath(message: Message, filename: String) {
        val extension = MimeUtils.extractRelevantExtension(filename)
        val mime = MimeUtils.guessMimeTypeFromExtension(extension)
        setupRelativeFilePath(message, filename, mime)
    }

    @Throws(IOException::class, uk.xa0.tulkki.xmpp.services.BlockedMediaException::class)
    fun getStorageLocation(message: Message?, inputStream: InputStream, extension: String?): File {
        val mime = MimeUtils.guessMimeTypeFromExtension(extension)
        val cids = calculateCids(inputStream)
        var base = cids[0].toString()

        var file: File =
            getStorageLocation(message, String.format("%s.%s", base, extension), mime)
        base += "_"
        while (file.exists() && !file.canRead()) {
            file = getStorageLocation(message, String.format("%s.%s", base, extension), mime)
            base += "_"
        }
        for (cid in cids) {
            mXmppConnectionService.saveCid(cid, file)
        }
        return file
    }

    fun getStorageLocation(message: Message?, filename: String, mime: String?): File {
        val parentDirectory: File
        if (Strings.isNullOrEmpty(mime)) {
            parentDirectory = getStorageDirectory()
        } else {
            val m = mime ?: throw NullPointerException()
            parentDirectory =
                when {
                    m.startsWith("image/") -> File(getStorageDirectory(), "pictures")
                    m.startsWith("video/") -> File(getStorageDirectory(), "videos")
                    m.startsWith("audio/") -> File(getStorageDirectory(), "audios")
                    MimeUtils.DOCUMENT_MIMES.contains(m) -> File(getStorageDirectory(), "documents")
                    else -> getStorageDirectory()
                }
        }
        val appDirectory = File(parentDirectory.toString())
        val conversation = message?.getConversation()
        val storeSecurely =
            conversation is Conversation && conversation.storeSecurely(mXmppConnectionService)
        if (message == null || message.getStatus() == Message.STATUS_DUMMY || storeSecurely) {
            val mediaCache = File(mXmppConnectionService.getFilesDir(), "/media")
            return File(mediaCache, filename)
        } else {
            return File(appDirectory, filename)
        }
    }

    override fun setupNomedia(nomedia: Boolean) {
        val pictures = File(getStorageDirectory(), "pictures")
        val picturesNomedia = File(File(pictures.toString()), ".nomedia")
        val movies = File(getStorageDirectory(), "videos")
        val moviesNomedia = File(File(movies.toString()), ".nomedia")
        val audios = File(getStorageDirectory(), "audios")
        val audiosNomedia = File(File(audios.toString()), ".nomedia")

        var rescan = false
        if (nomedia) {
            rescan = rescan || picturesNomedia.mkdir()
            rescan = rescan || moviesNomedia.mkdir()
            rescan = rescan || audiosNomedia.mkdir()
        } else {
            rescan = rescan || picturesNomedia.delete()
            rescan = rescan || moviesNomedia.delete()
            rescan = rescan || audiosNomedia.delete()
        }
        if (rescan) {
            updateMediaScanner(File(pictures.toString()))
            updateMediaScanner(File(movies.toString()))
            updateMediaScanner(File(audios.toString()))
        }
    }

    fun setupRelativeFilePath(message: Message, filename: String, mime: String?) {
        val file = getStorageLocation(message, filename, mime)
        message.setRelativeFilePath(file.getAbsolutePath())
    }

    override fun unusualBounds(image: Uri): Boolean {
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            val inputStream =
                mXmppConnectionService.getContentResolver().openInputStream(image)
            BitmapFactory.decodeStream(inputStream, null, options)
            close(inputStream)
            val ratio = options.outHeight.toFloat() / options.outWidth
            return ratio > (21.0f / 9.0f) || ratio < (9.0f / 21.0f)
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "unable to detect image bounds", e)
            return false
        }
    }

    private fun getRotation(file: File): Int {
        try {
            FileInputStream(file).use { inputStream ->
                return getRotation(inputStream)
            }
        } catch (e: Exception) {
            return 0
        }
    }

    private fun getRotation(image: Uri): Int {
        try {
            val inputStream = mXmppConnectionService.getContentResolver().openInputStream(image)
            if (inputStream == null) return 0
            inputStream.use { stream ->
                return getRotation(stream)
            }
        } catch (e: Exception) {
            return 0
        }
    }

    fun getFallbackThumbnail(message: Message, size: Int, cacheOnly: Boolean): BitmapDrawable? {
        val thumbs = message.getFileParams().getThumbnails()
        if (thumbs.isNotEmpty()) {
            for (thumb in thumbs) {
                val uriS = thumb.getAttribute("uri") ?: continue
                val uri = Uri.parse(uriS)
                if ((uri.getScheme() ?: throw NullPointerException()) == "data") {
                    val parts = uri.getSchemeSpecificPart().split(",", limit = 2).toMutableList()

                    val cache = mXmppConnectionService.getDrawableCache()
                    val cached = cache.get(parts[1]) as BitmapDrawable?
                    if (cached != null || cacheOnly) return cached

                    val data: ByteArray
                    if (parts[0].split(";").contains("base64")) {
                        val parts2 = parts[0].split(";", limit = 2)
                        parts[0] = parts2[0]
                        data = Base64.decode(parts[1], 0)
                    } else {
                        data = parts[1].toByteArray(Charsets.UTF_8)
                    }

                    if (parts[0] == "image/blurhash") {
                        var width = message.getFileParams().width
                        val widthAttr = thumb.getAttribute("width")
                        if (width < 1 && widthAttr != null) width = Integer.parseInt(widthAttr)
                        if (width < 1) width = 1920

                        var height = message.getFileParams().height
                        val heightAttr = thumb.getAttribute("height")
                        if (height < 1 && heightAttr != null) height = Integer.parseInt(heightAttr)
                        if (height < 1) height = 1080
                        val r = rectForSize(width, height, size)

                        val blurhash =
                            BlurHashDecoder.decode(
                                parts[1],
                                r.width(),
                                r.height(),
                                1.0f,
                                false,
                            )
                        if (blurhash != null) {
                            val decoded = BitmapDrawable(blurhash)
                            cache.put(parts[1], decoded)
                            return decoded
                        }
                    } else if (parts[0] == "image/thumbhash") {
                        val image =
                            try {
                                ThumbHash.thumbHashToRGBA(data)
                            } catch (e: Exception) {
                                continue
                            }
                        val pixels = IntArray(image.width * image.height)
                        for (i in pixels.indices) {
                            pixels[i] =
                                Color.argb(
                                    image.rgba[(i * 4) + 3].toInt() and 0xff,
                                    image.rgba[i * 4].toInt() and 0xff,
                                    image.rgba[(i * 4) + 1].toInt() and 0xff,
                                    image.rgba[(i * 4) + 2].toInt() and 0xff,
                                )
                        }
                        val decoded =
                            BitmapDrawable(
                                Bitmap.createBitmap(
                                    pixels,
                                    image.width,
                                    image.height,
                                    Bitmap.Config.ARGB_8888,
                                ),
                            )
                        cache.put(parts[1], decoded)
                        return decoded
                    }
                }
            }
        }

        return null
    }

    @Throws(IOException::class)
    fun getThumbnail(message: Message, res: Resources, size: Int, cacheOnly: Boolean): Drawable? {
        val cache = mXmppConnectionService.getDrawableCache()
        val file = getFile(message)
        var thumbnail = cache.get(file.getAbsolutePath())
        if (thumbnail != null) return thumbnail

        if (thumbnail == null && !cacheOnly) {
            synchronized(THUMBNAIL_LOCK) {
                val thumbs = message.getFileParams().getThumbnails()
                if (thumbs.isNotEmpty()) {
                    for (thumb in thumbs) {
                        val uriS = thumb.getAttribute("uri") ?: continue
                        val uri = Uri.parse(uriS)
                        if ((uri.getScheme() ?: throw NullPointerException()) == "data") {
                            if (android.os.Build.VERSION.SDK_INT < 28) continue
                            val parts =
                                uri.getSchemeSpecificPart().split(",", limit = 2).toMutableList()

                            val data: ByteArray
                            if (parts[0].split(";").contains("base64")) {
                                val parts2 = parts[0].split(";", limit = 2)
                                parts[0] = parts2[0]
                                data = Base64.decode(parts[1], 0)
                            } else {
                                data = parts[1].toByteArray(Charsets.UTF_8)
                            }

                            if (parts[0] == "image/blurhash") continue // blurhash only for fallback
                            if (parts[0] == "image/thumbhash") continue // thumbhash only for fallback

                            if (android.os.Build.VERSION.SDK_INT >= 28) {
                                val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
                                thumbnail =
                                    ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                                        val w = info.getSize().getWidth()
                                        val h = info.getSize().getHeight()
                                        val r = rectForSize(w, h, size)
                                        decoder.setTargetSize(r.width(), r.height())
                                    }
                            }

                            if (thumbnail != null && file.getAbsolutePath() != null) {
                                cache.put(file.getAbsolutePath(), thumbnail)
                                return thumbnail
                            }
                        } else if ((uri.getScheme() ?: throw NullPointerException()) == "cid") {
                            val cid = BobCid.cid(uri)
                            if (cid == null) continue
                            val f = mXmppConnectionService.getFileForCid(cid)
                            if (f != null && f.canRead()) {
                                return getThumbnail(f, res, size, cacheOnly)
                            }
                        }
                    }
                }
            }
        }

        return getThumbnail(file, res, size, cacheOnly)
    }

    // Tulkki: C5-E3 - both forms take the ref. A `DownloadableFile` argument still binds (a model is
    // a ref), so every existing caller keeps compiling; what this buys is that `:data`'s
    // `Conversation`'s `Consumer` lambda, which now receives a ref, can pass it straight on. The
    // four helpers below take a `File`, which is what the `asFile()` calls are, and nothing here
    // casts.
    @Throws(IOException::class)
    fun getThumbnail(
        file: DownloadableFileRef,
        res: Resources,
        size: Int,
        cacheOnly: Boolean,
    ): Drawable? {
        return getThumbnail(file, res, size, cacheOnly, file.getAbsolutePath())
    }

    @Throws(IOException::class)
    fun getThumbnail(
        file: DownloadableFileRef,
        res: Resources,
        size: Int,
        cacheOnly: Boolean,
        cacheKey: String?,
    ): Drawable? {
        val cache = mXmppConnectionService.getDrawableCache()
        var thumbnail = cache.get(cacheKey)
        if (thumbnail == null && !cacheOnly && file.exists()) {
            synchronized(THUMBNAIL_LOCK) {
                thumbnail = cache.get(cacheKey)
                val locked = thumbnail
                if (locked != null) {
                    return locked
                }
                val mime = file.getMimeType()
                if ("image/svg+xml" == mime) {
                    thumbnail = getSVG(file.asFile(), size)
                } else if ("application/pdf" == mime) {
                    thumbnail = BitmapDrawable(res, getPdfDocumentPreview(file.asFile(), size))
                } else if (mime.startsWith("video/")) {
                    thumbnail = BitmapDrawable(res, getVideoPreview(file.asFile(), size))
                } else if (mime.startsWith("audio/")) {
                    thumbnail = res.getDrawable(previewIcons.audioFile())
                } else {
                    thumbnail = getImagePreview(file.asFile(), res, size, mime)
                    if (thumbnail == null) {
                        throw FileNotFoundException()
                    }
                }
                val drawn = thumbnail
                if (cacheKey != null && drawn != null) cache.put(cacheKey, drawn)
            }
        }
        return thumbnail
    }

    @Throws(IOException::class)
    fun getThumbnailBitmap(message: Message, res: Resources, size: Int): Bitmap? {
        val drawable = getThumbnail(message, res, size, false)
        if (drawable == null) return null
        return drawDrawable(drawable)
    }

    @Throws(IOException::class)
    fun getThumbnailBitmap(
        file: DownloadableFile,
        res: Resources,
        size: Int,
        cacheKey: String?,
    ): Bitmap? {
        val drawable = getThumbnail(file, res, size, false, cacheKey)
        if (drawable == null) return null
        return drawDrawable(drawable)
    }

    @Throws(IOException::class)
    private fun getImagePreview(
        file: File,
        res: Resources,
        size: Int,
        mime: String,
    ): Drawable? {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                val w = info.getSize().getWidth()
                val h = info.getSize().getHeight()
                val r = rectForSize(w, h, size)
                decoder.setTargetSize(r.width(), r.height())
            }
        } else {
            val options = BitmapFactory.Options()
            options.inSampleSize = calcSampleSize(file, size)
            var bitmap: Bitmap?
            try {
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options)
            } catch (e: OutOfMemoryError) {
                options.inSampleSize *= 2
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options)
            }
            if (bitmap == null) return null

            bitmap = resize(bitmap, size)
            bitmap = rotate(bitmap, getRotation(file))
            if (mime == "image/gif") {
                val withGifOverlay = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                drawOverlay(
                    withGifOverlay,
                    if (paintOverlayBlack(withGifOverlay)) previewIcons.playGifBlack() else previewIcons.playGifWhite(),
                    1.0f,
                )
                bitmap.recycle()
                bitmap = withGifOverlay
            }
            return BitmapDrawable(res, bitmap)
        }
    }

    private fun drawOverlay(bitmap: Bitmap, resource: Int, factor: Float) {
        val overlay =
            BitmapFactory.decodeResource(mXmppConnectionService.getResources(), resource)
                ?: throw NullPointerException()
        val canvas = Canvas(bitmap)
        val targetSize = Math.min(canvas.getWidth(), canvas.getHeight()) * factor
        Log.d(
            Config.LOGTAG,
            "target size overlay: " + targetSize + " overlay bitmap size was " + overlay.getHeight(),
        )
        val left = (canvas.getWidth() - targetSize) / 2.0f
        val top = (canvas.getHeight() - targetSize) / 2.0f
        val dst = RectF(left, top, left + targetSize - 1, top + targetSize - 1)
        canvas.drawBitmap(overlay, null, dst, createAntiAliasingPaint())
    }

    /** https://stackoverflow.com/a/3943023/210897 */
    private fun paintOverlayBlack(bitmap: Bitmap): Boolean {
        val h = bitmap.getHeight()
        val w = bitmap.getWidth()
        var record = 0
        for (y in Math.round(h * IGNORE_PADDING) until h - Math.round(h * IGNORE_PADDING)) {
            for (x in Math.round(w * IGNORE_PADDING) until w - Math.round(w * IGNORE_PADDING)) {
                val pixel = bitmap.getPixel(x, y)
                if ((Color.red(pixel) * 0.299 +
                        Color.green(pixel) * 0.587 +
                        Color.blue(pixel) * 0.114) > 186
                ) {
                    --record
                } else {
                    ++record
                }
            }
        }
        return record < 0
    }

    private fun paintOverlayBlackPdf(bitmap: Bitmap): Boolean {
        val h = bitmap.getHeight()
        val w = bitmap.getWidth()
        var white = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = bitmap.getPixel(x, y)
                if ((Color.red(pixel) * 0.299 +
                        Color.green(pixel) * 0.587 +
                        Color.blue(pixel) * 0.114) > 186
                ) {
                    white++
                }
            }
        }
        return white > (h * w * 0.4f)
    }

    private fun cropCenterSquareVideo(uri: Uri, size: Int): Bitmap {
        val metadataRetriever = MediaMetadataRetriever()
        try {
            metadataRetriever.setDataSource(mXmppConnectionService, uri)
            val frame = metadataRetriever.getFrameAtTime(0) ?: throw NullPointerException()
            metadataRetriever.release()
            return cropCenterSquare(frame, size)
        } catch (e: Exception) {
            val frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            frame.eraseColor(0xff000000.toInt())
            return frame
        }
    }

    private fun getVideoPreview(file: File, size: Int): Bitmap {
        val metadataRetriever = MediaMetadataRetriever()
        var frame: Bitmap
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath())
            frame = metadataRetriever.getFrameAtTime(0) ?: throw NullPointerException()
            metadataRetriever.release()
            frame = resize(frame, size)
        } catch (e: Exception) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            frame.eraseColor(0xff000000.toInt())
        }
        drawOverlay(
            frame,
            if (paintOverlayBlack(frame)) previewIcons.playVideoBlack() else previewIcons.playVideoWhite(),
            0.75f,
        )
        return frame
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun getPdfDocumentPreview(file: File, size: Int): Bitmap {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rendered = renderPdfDocument(fileDescriptor, size, true)
            drawOverlay(
                rendered,
                if (paintOverlayBlackPdf(rendered)) previewIcons.openPdfBlack() else previewIcons.openPdfWhite(),
                0.75f,
            )
            return rendered
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to render PDF document preview", e)
            val placeholder = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(0xff000000.toInt())
            return placeholder
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "unable to render PDF document preview", e)
            val placeholder = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(0xff000000.toInt())
            return placeholder
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun cropCenterSquarePdf(uri: Uri, size: Int): Bitmap {
        try {
            val fileDescriptor =
                mXmppConnectionService.getContentResolver().openFileDescriptor(uri, "r")
            val bitmap =
                renderPdfDocument(fileDescriptor ?: throw NullPointerException(), size, false)
            return cropCenterSquare(bitmap, size)
        } catch (e: Exception) {
            val placeholder = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(0xff000000.toInt())
            return placeholder
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Throws(IOException::class)
    private fun renderPdfDocument(
        fileDescriptor: ParcelFileDescriptor,
        targetSize: Int,
        fit: Boolean,
    ): Bitmap {
        val pdfRenderer = PdfRenderer(fileDescriptor)
        val page = pdfRenderer.openPage(0)
        val dimensions =
            scalePdfDimensions(
                Dimensions(page.getHeight(), page.getWidth()),
                targetSize,
                fit,
            )
        val rendered =
            Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888)
        rendered.eraseColor(0xffffffff.toInt())
        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        pdfRenderer.close()
        fileDescriptor.close()
        return rendered
    }

    fun getTakePhotoUri(): Uri {
        val filename = String.format("IMG_%s.%s", IMAGE_DATE_FORMAT.format(Date()), "jpg")
        val directory: File
        if (Config.ONLY_INTERNAL_STORAGE) {
            directory = File(mXmppConnectionService.getFilesDir(), "Camera")
        } else {
            directory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera",
                )
        }
        val file = File(directory, filename)
        (file.getParentFile() ?: throw NullPointerException()).mkdirs()
        return getUriForFile(mXmppConnectionService, file, filename)
    }

    override fun getPepAvatar(image: Uri, size: Int, format: Bitmap.CompressFormat): Avatar? {
        val uncompressAvatar = getUncompressedAvatar(image)
        if (uncompressAvatar != null) {
            val first = uncompressAvatar.first
            if (first != null) {
                val image0 = first.image ?: throw NullPointerException()
                if (image0.length <= Config.AVATAR_CHAR_LIMIT || uncompressAvatar.second) {
                    return first
                }
                Log.d(
                    Config.LOGTAG,
                    "uncompressed avatar exceeded char limit by " +
                        (image0.length - Config.AVATAR_CHAR_LIMIT),
                )
            }
        }

        var bm = cropCenterSquare(image, size) ?: return null
        if (hasAlpha(bm)) {
            Log.d(Config.LOGTAG, "alpha in avatar detected; uploading as PNG")
            bm.recycle()
            bm = cropCenterSquare(image, 96) ?: return null
            return getPepAvatar(bm, Bitmap.CompressFormat.PNG, 100)
        }
        return getPepAvatar(bm, format, 100)
    }

    private fun getUncompressedAvatar(uri: Uri): Pair<Avatar?, Boolean>? {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source =
                    ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), uri)
                val size = intArrayOf(0, 0)
                val animated = booleanArrayOf(false)
                val mimeType = arrayOfNulls<String>(1)
                val drawable =
                    ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                        mimeType[0] = info.getMimeType()
                        animated[0] = info.isAnimated()
                        size[0] = info.getSize().getWidth()
                        size[1] = info.getSize().getHeight()
                    }

                if (animated[0]) {
                    val avatar = getPepAvatar(uri, size[0], size[1], mimeType[0])
                    if (avatar != null) return Pair(avatar, true)
                }

                return Pair(
                    getPepAvatar(drawDrawable(drawable), Bitmap.CompressFormat.PNG, 100),
                    false,
                )
            } else {
                val bitmap =
                    BitmapFactory.decodeStream(
                        mXmppConnectionService.getContentResolver().openInputStream(uri),
                    )
                return Pair(getPepAvatar(bitmap, Bitmap.CompressFormat.PNG, 100), false)
            }
        } catch (e: Exception) {
            try {
                val svg =
                    SVG.getFromInputStream(
                        mXmppConnectionService.getContentResolver().openInputStream(uri),
                    )
                return Pair(
                    getPepAvatar(
                        uri,
                        svg.getDocumentWidth().toInt(),
                        svg.getDocumentHeight().toInt(),
                        "image/svg+xml",
                    ),
                    true,
                )
            } catch (e2: Exception) {
                return null
            }
        }
    }

    @Throws(IOException::class, NoSuchAlgorithmException::class)
    private fun getPepAvatar(uri: Uri, width: Int, height: Int, mimeType: String?): Avatar? {
        val fd =
            mXmppConnectionService.getContentResolver().openAssetFileDescriptor(uri, "r")
                ?: throw NullPointerException()
        if (fd.getLength() > 100000) return null // Too big to use raw file

        val mByteArrayOutputStream = ByteArrayOutputStream()
        val mBase64OutputStream = Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-1")
        val mDigestOutputStream = DigestOutputStream(mBase64OutputStream, digest)

        ByteStreams.copy(fd.createInputStream(), mDigestOutputStream)
        mDigestOutputStream.flush()
        mDigestOutputStream.close()

        val avatar = Avatar()
        avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest())
        avatar.image = String(mByteArrayOutputStream.toByteArray())
        avatar.type = mimeType
        avatar.width = width
        avatar.height = height
        return avatar
    }

    private fun getPepAvatar(bitmap: Bitmap?, format: Bitmap.CompressFormat, quality: Int): Avatar? {
        try {
            val bmp = bitmap ?: throw NullPointerException()
            val mByteArrayOutputStream = ByteArrayOutputStream()
            val mBase64OutputStream = Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("SHA-1")
            val mDigestOutputStream = DigestOutputStream(mBase64OutputStream, digest)
            if (!bmp.compress(format, quality, mDigestOutputStream)) {
                return null
            }
            mDigestOutputStream.flush()
            mDigestOutputStream.close()
            val chars = mByteArrayOutputStream.size().toLong()
            if (format != Bitmap.CompressFormat.PNG &&
                quality >= 50 &&
                chars >= Config.AVATAR_CHAR_LIMIT.toLong()
            ) {
                val q = quality - 2
                Log.d(
                    Config.LOGTAG,
                    "avatar char length was " + chars + " reducing quality to " + q,
                )
                return getPepAvatar(bitmap, format, q)
            }
            Log.d(Config.LOGTAG, "settled on char length " + chars + " with quality=" + quality)
            val avatar = Avatar()
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest())
            avatar.image = String(mByteArrayOutputStream.toByteArray())
            if (format == Bitmap.CompressFormat.WEBP) {
                avatar.type = "image/webp"
            } else if (format == Bitmap.CompressFormat.JPEG) {
                avatar.type = "image/jpeg"
            } else if (format == Bitmap.CompressFormat.PNG) {
                avatar.type = "image/png"
            }
            avatar.width = bmp.getWidth()
            avatar.height = bmp.getHeight()
            return avatar
        } catch (e: OutOfMemoryError) {
            Log.d(Config.LOGTAG, "unable to convert avatar to base64 due to low memory")
            return null
        } catch (e: Exception) {
            return null
        }
    }

    override fun getStoredPepAvatar(hash: String?): Avatar? {
        if (hash == null) {
            return null
        }
        val avatar = Avatar()
        val file = getAvatarFile(hash)
        var inputStream: FileInputStream? = null
        try {
            avatar.size = file.length()
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.getAbsolutePath(), options)
            val stream = FileInputStream(file)
            inputStream = stream
            val mByteArrayOutputStream = ByteArrayOutputStream()
            val mBase64OutputStream = Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("SHA-1")
            val os = DigestOutputStream(mBase64OutputStream, digest)
            val buffer = ByteArray(4096)
            while (true) {
                val length = stream.read(buffer)
                if (length <= 0) break
                os.write(buffer, 0, length)
            }
            os.flush()
            os.close()
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest())
            avatar.image = String(mByteArrayOutputStream.toByteArray())
            avatar.height = options.outHeight
            avatar.width = options.outWidth
            avatar.type = options.outMimeType
            return avatar
        } catch (e: NoSuchAlgorithmException) {
            return null
        } catch (e: IOException) {
            return null
        } finally {
            close(inputStream)
        }
    }

    override fun isAvatarCached(avatar: Avatar): Boolean {
        val file = getAvatarFile(avatar.getFilename())
        return file.exists()
    }

    override fun save(avatar: Avatar): Boolean {
        val file: File
        if (isAvatarCached(avatar)) {
            file = getAvatarFile(avatar.getFilename())
            avatar.size = file.length()
        } else {
            file =
                File(
                    mXmppConnectionService.getCacheDir().getAbsolutePath() +
                        "/" +
                        UUID.randomUUID().toString(),
                )
            if ((file.getParentFile() ?: throw NullPointerException()).mkdirs()) {
                Log.d(Config.LOGTAG, "created cache directory")
            }
            var os: OutputStream? = null
            try {
                if (!file.createNewFile()) {
                    Log.d(
                        Config.LOGTAG,
                        "unable to create temporary file " + file.getAbsolutePath(),
                    )
                }
                os = FileOutputStream(file)
                val digest = MessageDigest.getInstance("SHA-1")
                digest.reset()
                val mDigestOutputStream = DigestOutputStream(os, digest)
                val bytes = avatar.getImageAsBytes()
                mDigestOutputStream.write(bytes)
                mDigestOutputStream.flush()
                mDigestOutputStream.close()
                val sha1sum = CryptoHelper.bytesToHex(digest.digest())
                if (sha1sum == avatar.sha1sum) {
                    val outputFile = getAvatarFile(avatar.getFilename())
                    if ((outputFile.getParentFile() ?: throw NullPointerException()).mkdirs()) {
                        Log.d(Config.LOGTAG, "created avatar directory")
                    }
                    val avatarFile = getAvatarFile(avatar.getFilename())
                    if (!file.renameTo(avatarFile)) {
                        Log.d(
                            Config.LOGTAG,
                            "unable to rename " + file.getAbsolutePath() + " to " + outputFile,
                        )
                        return false
                    }
                } else {
                    Log.d(Config.LOGTAG, "sha1sum mismatch for " + avatar.owner)
                    if (!file.delete()) {
                        Log.d(Config.LOGTAG, "unable to delete temporary file")
                    }
                    return false
                }
                avatar.size = bytes.size.toLong()
            } catch (e: IllegalArgumentException) {
                return false
            } catch (e: IOException) {
                return false
            } catch (e: NoSuchAlgorithmException) {
                return false
            } finally {
                close(os)
            }
        }
        return true
    }

    fun getAvatarFile(avatar: String?): File {
        val f = File(mXmppConnectionService.getFilesDir(), "/avatars/" + avatar)
        if (Build.VERSION.SDK_INT < 26) return f // Doesn't support file.toPath
        try {
            if (f.exists()) {
                java.nio.file.Files.setAttribute(
                    f.toPath(),
                    "lastAccessTime",
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()),
                )
            }
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "unable to set lastAccessTime for " + f)
        }
        return f
    }

    fun getAvatarUri(avatar: String?): Uri {
        return Uri.fromFile(getAvatarFile(avatar))
    }

    fun cropCenterSquareDrawable(image: Uri, size: Int): Drawable? {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source =
                    ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), image)
                return ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                    val w = info.getSize().getWidth()
                    val h = info.getSize().getHeight()
                    val r = rectForSize(w, h, size)
                    decoder.setTargetSize(r.width(), r.height())

                    val newSize = Math.min(r.width(), r.height())
                    val left = (r.width() - newSize) / 2
                    val top = (r.height() - newSize) / 2
                    decoder.setCrop(Rect(left, top, left + newSize, top + newSize))
                }
            } catch (e: IOException) {
                return getSVGSquare(image, size)
            }
        } else {
            val bitmap = cropCenterSquare(image, size) ?: return null
            return BitmapDrawable(bitmap)
        }
    }

    fun cropCenterSquare(image: Uri?, size: Int): Bitmap? {
        if (image == null) {
            return null
        }
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options()
            options.inSampleSize = calcSampleSize(image, size)
            inputStream = openInputStream(image)
            val input = BitmapFactory.decodeStream(inputStream, null, options) ?: return null
            val rotated = rotate(input, getRotation(image))
            return cropCenterSquare(rotated, size)
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "unable to open file " + image.toString(), e)
            return null
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to open file " + image.toString(), e)
            return null
        } finally {
            close(inputStream)
        }
    }

    fun cropCenter(image: Uri?, newHeight: Int, newWidth: Int): Bitmap? {
        if (image == null) {
            return null
        }
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options()
            options.inSampleSize = calcSampleSize(image, Math.max(newHeight, newWidth))
            inputStream = mXmppConnectionService.getContentResolver().openInputStream(image)
            val source =
                if (inputStream == null) null
                else BitmapFactory.decodeStream(inputStream, null, options)
            if (source == null) {
                return null
            }
            val sourceWidth = source.getWidth()
            val sourceHeight = source.getHeight()
            val xScale = newWidth.toFloat() / sourceWidth
            val yScale = newHeight.toFloat() / sourceHeight
            val scale = Math.max(xScale, yScale)
            val scaledWidth = scale * sourceWidth
            val scaledHeight = scale * sourceHeight
            val left = (newWidth - scaledWidth) / 2
            val top = (newHeight - scaledHeight) / 2

            val targetRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
            val dest = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dest)
            canvas.drawBitmap(source, null, targetRect, createAntiAliasingPaint())
            if (source.isRecycled()) {
                source.recycle()
            }
            return dest
        } catch (e: SecurityException) {
            return null // android 6.0 with revoked permissions for example
        } catch (e: IOException) {
            return null
        } finally {
            close(inputStream)
        }
    }

    fun cropCenterSquare(input: Bitmap, size: Int): Bitmap {
        val w = input.getWidth()
        val h = input.getHeight()

        val scale = Math.max(size.toFloat() / h, size.toFloat() / w)

        val outWidth = scale * w
        val outHeight = scale * h
        val left = (size - outWidth) / 2
        val top = (size - outHeight) / 2
        val target = RectF(left, top, left + outWidth, top + outHeight)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(input, null, target, createAntiAliasingPaint())
        if (!input.isRecycled()) {
            input.recycle()
        }
        return output
    }

    @Throws(IOException::class, SecurityException::class)
    private fun calcSampleSize(image: Uri, size: Int): Int {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        val inputStream = openInputStream(image)
        BitmapFactory.decodeStream(inputStream, null, options)
        close(inputStream)
        return calcSampleSize(options, size)
    }

    fun updateFileParams(message: Message) {
        updateFileParams(message, null)
    }

    fun updateFileParams(message: Message, url: String?) {
        updateFileParams(message, url, true)
    }

    fun updateFileParams(message: Message, url: String?, updateCids: Boolean) {
        val encrypted =
            message.getEncryption() == Message.ENCRYPTION_PGP ||
                message.getEncryption() == Message.ENCRYPTION_DECRYPTED
        val file = getFile(message)
        val mime = file.getMimeType()
        val privateMessage = message.isPrivateMessage()
        val image = message.getType() == Message.TYPE_IMAGE || mime.startsWith("image/")
        val fileParams = message.getFileParams()
        var cids: Array<Cid> = emptyArray()
        try {
            cids = calculateCids(FileInputStream(file))
            fileParams.setCids(cids.toList())
        } catch (e: IOException) {
        } catch (e: NoSuchAlgorithmException) {
        }
        if (url != null) {
            fileParams.url = url
        }
        if (fileParams.getName() == null) fileParams.setName(file.getName())
        fileParams.setMediaType(mime)
        if (encrypted && !file.exists()) {
            Log.d(Config.LOGTAG, "skipping updateFileParams because file is encrypted")
            val encryptedFile = getFile(message, false)
            if (encryptedFile.canRead()) fileParams.size = encryptedFile.getSize()
        } else {
            Log.d(Config.LOGTAG, "running updateFileParams")
            val ambiguous = MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime)
            val video = mime.startsWith("video/")
            val audio = mime.startsWith("audio/")
            val pdf = "application/pdf" == mime
            if (file.canRead()) fileParams.size = file.getSize()
            if (ambiguous) {
                try {
                    val dimensions = getVideoDimensions(file)
                    if (dimensions.valid()) {
                        Log.d(Config.LOGTAG, "ambiguous file " + mime + " is video")
                        fileParams.width = dimensions.width
                        fileParams.height = dimensions.height
                    } else {
                        Log.d(Config.LOGTAG, "ambiguous file " + mime + " is audio")
                        fileParams.runtime = getMediaRuntime(file)
                    }
                } catch (e: IOException) {
                    Log.d(Config.LOGTAG, "ambiguous file " + mime + " is audio")
                    fileParams.runtime = getMediaRuntime(file)
                } catch (e: NotAVideoFile) {
                    Log.d(Config.LOGTAG, "ambiguous file " + mime + " is audio")
                    fileParams.runtime = getMediaRuntime(file)
                }
            } else if (image || video || pdf) {
                try {
                    val dimensions: Dimensions
                    if (video) {
                        dimensions = getVideoDimensions(file)
                    } else if (pdf) {
                        dimensions = getPdfDocumentDimensions(file)
                    } else if ("image/svg+xml" == mime) {
                        val svg = SVG.getFromInputStream(FileInputStream(file))
                        dimensions =
                            Dimensions(
                                svg.getDocumentHeight().toInt(),
                                svg.getDocumentWidth().toInt(),
                            )
                    } else {
                        dimensions = getImageDimensions(file)
                    }
                    if (dimensions.valid()) {
                        fileParams.width = dimensions.width
                        fileParams.height = dimensions.height
                    }
                } catch (e: IOException) {
                    Log.d(
                        Config.LOGTAG,
                        "file with mime type " + file.getMimeType() + " was not a video file",
                    )
                } catch (e: SVGParseException) {
                    Log.d(
                        Config.LOGTAG,
                        "file with mime type " + file.getMimeType() + " was not a video file",
                    )
                } catch (e: NotAVideoFile) {
                    Log.d(
                        Config.LOGTAG,
                        "file with mime type " + file.getMimeType() + " was not a video file",
                    )
                }
            } else if (audio) {
                fileParams.runtime = getMediaRuntime(file)
            }
            if ("application/webxdc+zip" == mime) {
                try {
                    val zip = ZipFile(file)
                    val manifestEntry = zip.getEntry("manifest.toml")
                    if (manifestEntry != null) {
                        val manifest = Toml.parse(zip.getInputStream(manifestEntry))
                        if (manifest != null) {
                            val name = manifest.getString("name")
                            if (name != null) fileParams.setName(name)
                        }
                    }
                } catch (e2: IOException) {
                }
            }
            try {
                val thumb =
                    getThumbnailBitmap(
                        file,
                        mXmppConnectionService.getResources(),
                        100,
                        file.getAbsolutePath() + " x 100",
                    )
                if (thumb != null) {
                    val pixels = IntArray(thumb.getWidth() * thumb.getHeight())
                    val rgba = ByteArray(pixels.size * 4)
                    try {
                        thumb.getPixels(
                            pixels,
                            0,
                            thumb.getWidth(),
                            0,
                            0,
                            thumb.getWidth(),
                            thumb.getHeight(),
                        )
                    } catch (e: IllegalStateException) {
                        val softThumb = thumb.copy(Bitmap.Config.ARGB_8888, false)
                        softThumb.getPixels(
                            pixels,
                            0,
                            thumb.getWidth(),
                            0,
                            0,
                            thumb.getWidth(),
                            thumb.getHeight(),
                        )
                        softThumb.recycle()
                    }
                    for (i in pixels.indices) {
                        rgba[i * 4] = ((pixels[i] shr 16) and 0xff).toByte()
                        rgba[(i * 4) + 1] = ((pixels[i] shr 8) and 0xff).toByte()
                        rgba[(i * 4) + 2] = (pixels[i] and 0xff).toByte()
                        rgba[(i * 4) + 3] = ((pixels[i] shr 24) and 0xff).toByte()
                    }
                    fileParams.addThumbnail(
                        thumb.getWidth(),
                        thumb.getHeight(),
                        "image/thumbhash",
                        "data:image/thumbhash;base64," +
                            Base64.encodeToString(
                                ThumbHash.rgbaToThumbHash(
                                    thumb.getWidth(),
                                    thumb.getHeight(),
                                    rgba,
                                ),
                                Base64.NO_WRAP,
                            ),
                    )
                }
            } catch (e: IOException) {
            }
        }
        message.setFileParams(fileParams)
        message.setDeleted(false)
        message.setType(
            if (privateMessage) {
                Message.TYPE_PRIVATE_FILE
            } else if (image) {
                Message.TYPE_IMAGE
            } else {
                Message.TYPE_FILE
            },
        )

        if (updateCids) {
            try {
                for (cid in cids) {
                    mXmppConnectionService.saveCid(cid, file)
                }
            } catch (e: uk.xa0.tulkki.xmpp.services.BlockedMediaException) {
            }
        }
    }

    override fun getTemporaryFile(mimeType: String?): DownloadableFile {
        val extension = MimeUtils.guessExtensionFromMimeType(mimeType)
        val filename =
            UUID.randomUUID().toString() + (if (extension == null) "" else "." + extension)
        val file = File(mXmppConnectionService.getCacheDir(), filename)
        return DownloadableFile(file.getAbsolutePath())
    }

    private fun getMediaRuntime(file: File): Int {
        try {
            val mediaMetadataRetriever = MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(file.toString())
            val value =
                mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (Strings.isNullOrEmpty(value)) {
                return 0
            }
            return Integer.parseInt(value ?: throw NumberFormatException())
        } catch (e: Exception) {
            return 0
        }
    }

    private fun getImageDimensions(file: File): Dimensions {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.getAbsolutePath(), options)
        val rotation = getRotation(file)
        val rotated = rotation == 90 || rotation == 270
        val imageHeight = if (rotated) options.outWidth else options.outHeight
        val imageWidth = if (rotated) options.outHeight else options.outWidth
        return Dimensions(imageHeight, imageWidth)
    }

    @Throws(NotAVideoFile::class, IOException::class)
    private fun getVideoDimensions(file: File): Dimensions {
        val metadataRetriever = MediaMetadataRetriever()
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath())
        } catch (e: RuntimeException) {
            throw NotAVideoFile(e)
        }
        return getVideoDimensions(metadataRetriever)
    }

    private fun getPdfDocumentDimensions(file: File): Dimensions {
        val fileDescriptor: ParcelFileDescriptor
        try {
            fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                ?: return Dimensions(0, 0)
        } catch (e: FileNotFoundException) {
            return Dimensions(0, 0)
        }
        try {
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val page = pdfRenderer.openPage(0)
            val height = page.getHeight()
            val width = page.getWidth()
            page.close()
            pdfRenderer.close()
            return scalePdfDimensions(Dimensions(height, width))
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to get dimensions for pdf document", e)
            return Dimensions(0, 0)
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "unable to get dimensions for pdf document", e)
            return Dimensions(0, 0)
        }
    }

    private fun scalePdfDimensions(`in`: Dimensions): Dimensions {
        val displayMetrics = mXmppConnectionService.getResources().getDisplayMetrics()
        val target = (displayMetrics.density * 288).toInt()
        return scalePdfDimensions(`in`, target, true)
    }

    fun getAvatar(avatar: String?, size: Int): Drawable? {
        if (avatar == null) {
            return null
        }

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(getAvatarFile(avatar))
                return ImageDecoder.decodeDrawable(source) { decoder, info, src ->
                    val w = info.getSize().getWidth()
                    val h = info.getSize().getHeight()
                    val r = rectForSize(w, h, size)
                    decoder.setTargetSize(r.width(), r.height())

                    val newSize = Math.min(r.width(), r.height())
                    val left = (r.width() - newSize) / 2
                    val top = (r.height() - newSize) / 2
                    decoder.setCrop(Rect(left, top, left + newSize, top + newSize))
                }
            } catch (e: IOException) {
                return getSVGSquare(getAvatarUri(avatar), size)
            }
        } else {
            val bm = cropCenter(getAvatarUri(avatar), size, size) ?: return null
            return BitmapDrawable(bm)
        }
    }

    fun getSVGSquare(uri: Uri, size: Int): Drawable? {
        try {
            val svg =
                SVG.getFromInputStream(mXmppConnectionService.getContentResolver().openInputStream(uri))
            svg.setDocumentPreserveAspectRatio(com.caverock.androidsvg.PreserveAspectRatio.FULLSCREEN)

            val w = svg.getDocumentWidth()
            val h = svg.getDocumentHeight()
            val scale = Math.max(size.toFloat() / h, size.toFloat() / w)
            val outWidth = scale * w
            val outHeight = scale * h
            val left = (size - outWidth) / 2
            val top = (size - outHeight) / 2
            val target = RectF(left, top, left + outWidth, top + outHeight)
            if (svg.getDocumentViewBox() == null) svg.setDocumentViewBox(0f, 0f, w, h)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")

            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            svg.renderToCanvas(canvas, target)

            return SVGDrawable(output)
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        } catch (e: SVGParseException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        }
    }

    fun getSVG(file: File, size: Int): Drawable? {
        try {
            val svg = SVG.getFromInputStream(FileInputStream(file))
            return drawSVG(svg, size)
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        } catch (e: SVGParseException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        }
    }

    fun drawSVG(svg: SVG, size: Int): Drawable? {
        try {
            svg.setDocumentPreserveAspectRatio(com.caverock.androidsvg.PreserveAspectRatio.LETTERBOX)

            val w = svg.getDocumentWidth()
            val h = svg.getDocumentHeight()
            val r = rectForSize(if (w < 1) size else w.toInt(), if (h < 1) size else h.toInt(), size)
            if (svg.getDocumentViewBox() == null) svg.setDocumentViewBox(0f, 0f, w, h)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")

            val output = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            svg.renderToCanvas(canvas)

            return SVGDrawable(output)
        } catch (e: SVGParseException) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e)
            return null
        }
    }

    override fun deleteFile(file: File): Boolean {
        return file.delete()
    }

    override fun getStoryCacheDirectory(): File {
        val filesDir = mXmppConnectionService.getFilesDir()
        val storyCache = File(filesDir, "stories")
        if (!storyCache.exists()) {
            storyCache.mkdirs()
        }
        return storyCache
    }

    fun getStoryCacheFile(url: String): File {
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(url.toByteArray())
            val messageDigest = digest.digest()
            val sha1 = CryptoHelper.bytesToHex(messageDigest)
            return File(getStoryCacheDirectory(), sha1)
        } catch (e: NoSuchAlgorithmException) {
            // This should not happen, but as a fallback, use a random name
            return File(getStoryCacheDirectory(), UUID.randomUUID().toString())
        }
    }

    fun getTakeVideoUri(): Uri {
        val filename = String.format("IMG_%s.%s", IMAGE_DATE_FORMAT.format(Date()), "mp4")
        val directory: File
        if (Config.ONLY_INTERNAL_STORAGE) {
            directory = File(mXmppConnectionService.getFilesDir(), "Camera")
        } else {
            directory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera",
                )
        }
        val file = File(directory, filename)
        (file.getParentFile() ?: throw NullPointerException()).mkdirs()
        return getUriForFile(mXmppConnectionService, file, filename)
    }

    private class Dimensions(val height: Int, val width: Int) {
        fun getMin(): Int = Math.min(width, height)

        fun valid(): Boolean = width > 0 && height > 0
    }

    private class NotAVideoFile : Exception {
        constructor(t: Throwable) : super(t)

        constructor() : super()
    }

    // Tulkki: C5-E3 - the declarations of both exceptions moved up to `FileBackendRef`, where the
    // island can catch them without naming this class. These stay as the model's own nested types,
    // under the same names and the same `public static` nesting, because `:app` catches
    // `FileBackend.FileCopyException` by name and the methods below keep naming the subclass in
    // their `throws` clauses - which is narrower than the interface's, legal, and keeps every
    // `:data`-side caller unchanged. The values do not move down: every `R.string.*` a
    // `FileCopyException` carries is still thrown from this file.

    class ImageCompressionException internal constructor(message: String) :
        FileBackendRef.ImageCompressionException(message)

    class FileCopyException internal constructor(@StringRes resId: Int) :
        FileBackendRef.FileCopyException(resId)

    class SVGDrawable(bm: Bitmap) : BitmapDrawable(bm)
}
