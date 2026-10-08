package uk.xa0.tulkki.app.services

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import java.io.File
import java.io.FileNotFoundException
import java.util.Objects
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import uk.xa0.tulkki.app.utils.TranscoderStrategies
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Copies or transcodes one picked file into a conversation, then hands it to the service to send.
 *
 * <p>**`isVideoMessage` stays both a field and a public `isVideoMessage()`.** The Java had a private
 * `final boolean isVideoMessage` and an explicit public getter of the same name, and Java calls the
 * getter (`XmppTulkkiHost`'s port wrapper). A Kotlin `val isVideoMessage` is exactly that pair: the
 * field stays private and the generated getter keeps the Java's name, because Kotlin does not rename
 * an `is`-prefixed boolean.
 *
 * <p>**`getVideoCompression(Context)` is a `@JvmStatic` companion member**, its two callers being
 * `UiAppHost` and `XmppTulkkiHost`, both Java. The instance overload stays private and forwards, as
 * the Java's did.
 *
 * <p>`callback` is `UiCallbackPort<MessageRef>?`, because the Java both checks it (`else if (callback
 * != null)`) and dereferences it unchecked (`callback.success(...)` immediately after a send, and
 * `callback.error(...)` in the copy failure path); those unchecked reads keep `!!`, which is the NPE
 * the Java would have thrown.
 *
 * <p>`message` is the `(Message) messageRef` cast the Java made, and the commented-out
 * private-storage branch is carried over verbatim - it is the Java's own record of why `path` is read
 * and then unused.
 *
 * <p>Name-string audit: 0 hits for `AttachFileToConversationRunnable` in the manifest, `res/xml`,
 * `res/layout*`, `preferences_*.xml` and the ProGuard rules.
 */
class AttachFileToConversationRunnable(
        private val mXmppConnectionService: XmppConnectionService,
        private val uri: Uri,
        private val type: String,
        messageRef: MessageRef,
        private val callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<MessageRef>?
) : Runnable, TranscoderListener {

    // Tulkki: 3.7 pair 9, part 16 - the island hands its ref in and this class keeps working in
    // the model type it always did (`getFileParams().url` is a public *field* it assigns). The
    // object really is a `Message`: the island built it through `DataStatics.newMessage`.
    private val message: Message = messageRef as Message

    private val mimeType: String? =
            MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type)

    val isVideoMessage: Boolean

    private val originalFileSize: Long = FileBackend.getFileSize(mXmppConnectionService, uri)

    private var currentProgress = -1

    init {
        val autoAcceptFileSize =
                mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize)
        isVideoMessage =
                (mimeType != null && mimeType.startsWith("video/")) &&
                        originalFileSize > autoAcceptFileSize &&
                        "uncompressed" != getVideoCompression()
    }

    private fun processAsFile() {
        val path = FileBackends.get().getOriginalPath(uri)
        if ("https" == uri.getScheme()) {
            message.getFileParams().url = uri.toString()
            message.getFileParams().setMediaType(mimeType)
            val encryption = message.getEncryption()
            mXmppConnectionService.getHttpConnectionManager().createNewDownloadConnection(
                    message, false
            ) { _ ->
                message.setEncryption(encryption)
                mXmppConnectionService.sendMessage(message) { callback!!.success(message) }
            }
            /*      // TODO for now we copy all files to private storage
        } else if (path != null && !FileBackend.isPathBlacklisted(path)) {
            message.setRelativeFilePath(path);
            FileBackends.get().updateFileParams(message);
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                mXmppConnectionService.getPgpEngine().encrypt(message, callback);
            } else {
                mXmppConnectionService.sendMessage(message, () -> callback.success(message));
            }
             */
        } else {
            try {
                FileBackends.get().copyFileToPrivateStorage(message, uri, type)
                FileBackends.get().updateFileParams(message)
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    val pgpEngine = mXmppConnectionService.getPgpEngine()
                    if (pgpEngine != null) {
                        pgpEngine.encrypt(message, callback)
                    } else if (callback != null) {
                        callback.error(R.string.unable_to_connect_to_keychain, null)
                    }
                } else {
                    mXmppConnectionService.sendMessage(message) { callback!!.success(message) }
                }
            } catch (e: FileBackend.FileCopyException) {
                callback!!.error(e.resId, message)
            }
        }
    }

    private fun fallbackToProcessAsFile() {
        val file = FileBackends.get().getFile(message)
        if (file.exists() && file.delete()) {
            Log.d(Config.LOGTAG, "deleted preexisting file " + file.getAbsolutePath())
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(this::processAsFile)
    }

    @Throws(FileNotFoundException::class)
    private fun processAsVideo() {
        Log.d(Config.LOGTAG, "processing file as video")
        mXmppConnectionService.startOngoingVideoTranscodingForegroundNotification()
        FileBackends.get()
                .setupRelativeFilePath(message, String.format("%s.%s", message.getUuid(), "mp4"))
        val file: DownloadableFile = FileBackends.get().getFile(message)
        val parent = file.getParentFile()
                ?: throw NullPointerException("the video file has no parent directory")
        if (parent.mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory for video file")
        }

        val highQuality = "720" == getVideoCompression()

        val future: Future<Void>
        try {
            future =
                    Transcoder.into(file.getAbsolutePath())
                            .addDataSource(mXmppConnectionService, uri)
                            .setVideoTrackStrategy(
                                    if (highQuality) TranscoderStrategies.VIDEO_720P
                                    else TranscoderStrategies.VIDEO_360P)
                            .setAudioTrackStrategy(
                                    if (highQuality) TranscoderStrategies.AUDIO_HQ
                                    else TranscoderStrategies.AUDIO_MQ)
                            .setListener(this)
                            .transcode()
        } catch (e: RuntimeException) {
            // transcode can already throw if there is an invalid file format or a platform bug
            mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification()
            fallbackToProcessAsFile()
            return
        }
        try {
            future.get()
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        } catch (e: ExecutionException) {
            if (e.cause is Error) {
                mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification()
                fallbackToProcessAsFile()
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Handled by onTranscodeFiled()")
            }
        }
    }

    override fun onTranscodeProgress(progress: Double) {
        val p = Math.round(progress * 100).toInt()
        if (p > currentProgress) {
            currentProgress = p
            mXmppConnectionService
                    .getNotificationService()
                    .updateFileAddingNotification(p, message)
        }
    }

    override fun onTranscodeCompleted(successCode: Int) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification()
        val file = FileBackends.get().getFile(message)
        val convertedFileSize = FileBackends.get().getFile(message).getSize()
        Log.d(
                Config.LOGTAG,
                "originalFileSize=" + originalFileSize + " convertedFileSize=" + convertedFileSize)
        if (originalFileSize != 0L && convertedFileSize >= originalFileSize) {
            if (file.delete()) {
                Log.d(
                        Config.LOGTAG,
                        "original file size was smaller. deleting and processing as file")
                fallbackToProcessAsFile()
                return
            } else {
                Log.d(Config.LOGTAG, "unable to delete converted file")
            }
        }
        FileBackends.get().updateFileParams(message)
        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            val pgpEngine = mXmppConnectionService.getPgpEngine()
                ?: throw NullPointerException("the PGP engine is not installed")
            pgpEngine.encrypt(message, callback)
        } else {
            mXmppConnectionService.sendMessage(message) { callback!!.success(message) }
        }
    }

    override fun onTranscodeCanceled() {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification()
        fallbackToProcessAsFile()
    }

    override fun onTranscodeFailed(exception: Throwable) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification()
        Log.d(Config.LOGTAG, "video transcoding failed", exception)
        fallbackToProcessAsFile()
    }

    override fun run() {
        if (this.isVideoMessage) {
            try {
                processAsVideo()
            } catch (e: FileNotFoundException) {
                processAsFile()
            }
        } else {
            processAsFile()
        }
    }

    private fun getVideoCompression(): String? =
            getVideoCompression(mXmppConnectionService)

    companion object {

        @JvmStatic
        fun getVideoCompression(context: Context): String? {
            val preferences: SharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(context)
            return preferences.getString(
                    "video_compression",
                    context.getResources().getString(uk.xa0.tulkki.ui.R.string.video_compression))
        }
    }
}
