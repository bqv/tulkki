package uk.xa0.tulkki.app.http

import android.util.Log
import androidx.core.util.Consumer
import com.google.common.base.Strings
import com.google.common.io.ByteStreams
import com.google.common.primitives.Longs
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

// Tulkki: C5-C put this class on the island ref while `:data`'s model interface was the other name.
// 2026-10-08 retired both into one type, `uk.xa0.tulkki.libs.Transferable`, so this class implements
// the only name there is and the `STATUS_*` ints it compares against are declared on it.
/**
 * Tulkki: one HTTP download transfer - the HEAD that learns the size, the GET that fetches it and
 * the OMEMO decrypt that follows.
 *
 * Ported from `HttpDownloadConnection.java`. It is *ours*, so it is converted in place.
 *
 * The Java-visible surface, read off the one construction site and the interface:
 *
 *  * the constructor was package-private and `HttpConnectionManager.kt:171` is the only caller, so
 *    Kotlin's nearest shape, `internal`, is used - a widening no caller outside the module sees;
 *  * `getMessage` is read by `HttpConnectionManager.kt:167` for the identity comparison, and the
 *    interface's four `STATUS_*`/`getFileSize` members keep their exact shapes;
 *  * the status ints are `Transferable.STATUS_*`, **qualified**: a Kotlin implementor inherits
 *    none of a Java interface's static fields (`AbstractConnectionManager.kt` records the same fact
 *    for `VALID_CRYPTO_EXTENSIONS`);
 *  * the two private member classes stay `inner` classes (Java's `private class` is an inner class),
 *    because both read `mostRecentCall`, `file` and `message` off the connection;
 *  * `mUrl` is a `lateinit var`, so the field the Java assigned before `setupFile()` reads it is
 *    still non-null at the read; `file` stays nullable because `getFileSize` tests it and the
 *    constructor-then-`init` sequence can fail before `setupFile()` runs. Where the Java dereferenced
 *    it unguarded, [fileOrThrow] raises the same `NullPointerException`.
 *
 * Two Java-isms handled rather than inherited: `createOutputStream` answers `OutputStream?` in the
 * ported tree, and the Java would have NPE'd at `ByteStreams.copy` if it were null, so the explicit
 * throw sits at the same point; and `response.body` is nullable in okhttp 4's Kotlin API, so the
 * `byteStream()` read names the null that the Java call would have hit.
 */
class HttpDownloadConnection
internal constructor(
    private val message: MessageRef,
    private val mHttpConnectionManager: HttpConnectionManager,
    private val cb: Consumer<DownloadableFileRef?>?,
) : Transferable {

    private val mXmppConnectionService: XmppConnectionService =
        mHttpConnectionManager.getXmppConnectionService()

    private lateinit var mUrl: HttpUrl
    private var file: DownloadableFileRef? = null
    private var mStatus: Int = Transferable.STATUS_UNKNOWN
    private var acceptedAutomatically = false
    private var mProgress = 0
    private var mostRecentCall: Call? = null

    private fun fileOrThrow(): DownloadableFileRef =
        this.file ?: throw NullPointerException("file")

    override fun start(): Boolean {
        if (mXmppConnectionService.hasInternetConnection()) {
            if (this.mStatus == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                checkFileSize(true)
            } else {
                download(true)
            }
            return true
        } else {
            return false
        }
    }

    fun init(interactive: Boolean) {
        val fileParams = message.getFileParams()
        if (message.isDeleted()) {
            if (message.getType() == MessageRef.TYPE_PRIVATE_FILE) {
                message.setType(MessageRef.TYPE_PRIVATE)
            } else if (message.isFileOrImage()) {
                message.setType(MessageRef.TYPE_TEXT)
            }
            message.setDeleted(false)
            mXmppConnectionService.updateMessage(message)
        }
        this.message.setTransferable(this)
        try {
            if (message.hasFileOnRemoteHost()) {
                mUrl = AesGcmURL.of(fileParams.url() ?: throw NullPointerException("file has no url"))
            } else if (message.isOOb() && fileParams.url() != null) {
                mUrl = AesGcmURL.of(fileParams.url() ?: throw NullPointerException("file has no url"))
            } else {
                mUrl = AesGcmURL.of((message.getRawBody() ?: throw NullPointerException("message has no raw body")).split("\n")[0])
            }
            val extension =
                AbstractConnectionManager.Extension.of(mUrl.encodedPath)
            if (Transferable.VALID_CRYPTO_EXTENSIONS.contains(extension.main)) {
                this.message.setEncryption(MessageRef.ENCRYPTION_PGP)
            } else if (message.getEncryption() != MessageRef.ENCRYPTION_AXOLOTL &&
                message.getEncryption() != MessageRef.ENCRYPTION_PGP &&
                message.getEncryption() != MessageRef.ENCRYPTION_DECRYPTED
            ) {
                this.message.setEncryption(MessageRef.ENCRYPTION_NONE)
            }
            var ext = extension.getExtension()
            if (ext == null && fileParams.getMediaType() != null) {
                ext =
                    XmppConnectionService.dataStatics()
                        .guessExtensionFromMimeType(fileParams.getMediaType() ?: throw NullPointerException("file has no media type"))
            }
            val filename =
                if (Strings.isNullOrEmpty(ext)) {
                    message.getUuid()
                } else {
                    String.format("%s.%s", message.getUuid(), ext)
                }
            mXmppConnectionService.getFileBackend().setupRelativeFilePath(message, filename ?: throw NullPointerException("message has no uuid"))
            setupFile()
            if (this.message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL &&
                fileOrThrow().getKey() == null
            ) {
                this.message.setEncryption(MessageRef.ENCRYPTION_NONE)
            }
            val knownFileSize: Long?
            if (message.getEncryption() == MessageRef.ENCRYPTION_PGP ||
                message.getEncryption() == MessageRef.ENCRYPTION_DECRYPTED
            ) {
                knownFileSize = null
            } else {
                knownFileSize = message.getFileParams().getSize()
            }
            // Tulkki: the body's shape, never the body. This line used to print it whole, and the
            // text it printed is a message body - for a file offer it is the URL, but the rule is
            // about the column rather than about today's content, and the length is what this line
            // was ever used for.
            val rawBody = message.getRawBody()
            Log.d(
                Config.LOGTAG,
                "knownFileSize: " +
                    knownFileSize +
                    ", bodyChars: " +
                    (if (rawBody == null) 0 else rawBody.length),
            )
            if (knownFileSize != null && interactive) {
                if (message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL &&
                    fileOrThrow().getKey() != null
                ) {
                    fileOrThrow().setExpectedSize(knownFileSize + 16)
                } else {
                    fileOrThrow().setExpectedSize(knownFileSize)
                }
                download(true)
            } else {
                checkFileSize(interactive)
            }
        } catch (e: IllegalArgumentException) {
            this.cancel()
        }
    }

    private fun setupFile() {
        val reference = mUrl.fragment
        if (reference != null && AesGcmURL.IV_KEY.matcher(reference).matches()) {
            // Tulkki: 3.7 C5-E3 - an interface cannot be `new`ed and this file may not name `:data`'s
            // constructor, so the construction lands on the port beside `newMessage`/
            // `newTransferablePlaceholder`. The object it hands back is the model's own.
            val created =
                XmppConnectionService.dataStatics()
                    .newDownloadableFile(
                        mXmppConnectionService.getCacheDir(),
                        message.getUuid() ?: throw NullPointerException("message has no uuid"),
                    )
            this.file = created
            created.setKeyAndIv(CryptoHelper.hexToBytes(reference))
            Log.d(
                Config.LOGTAG,
                "create temporary OMEMO encrypted file: " +
                    created.getAbsolutePath() +
                    "(" +
                    message.getMimeType() +
                    ")",
            )
        } else {
            this.file = mXmppConnectionService.getFileBackend().getFile(message, false)
        }
    }

    private fun download(interactive: Boolean) {
        HttpConnectionManager.EXECUTOR.execute(FileDownloader(interactive))
    }

    private fun checkFileSize(interactive: Boolean) {
        HttpConnectionManager.EXECUTOR.execute(FileSizeChecker(interactive))
    }

    override fun cancel() {
        val call = this.mostRecentCall
        if (call != null && !call.isCanceled()) {
            call.cancel()
        }
        mHttpConnectionManager.finishConnection(this)
        message.setTransferable(null)
        if (message.isFileOrImage()) {
            message.setDeleted(true)
        }
        mHttpConnectionManager.updateConversationUi(true)
    }

    private fun decryptFile() {
        val outputFile = mXmppConnectionService.getFileBackend().getFile(message, true)

        if ((outputFile.getParentFile() ?: throw NullPointerException()).mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for " + outputFile.getAbsolutePath())
        }

        if (!outputFile.createNewFile()) {
            Log.w(Config.LOGTAG, "unable to create output file " + outputFile.getAbsolutePath())
        }

        val is0 = FileInputStream(fileOrThrow().asFile())

        // `getKey()`/`getIv()` are `ByteArray?` on the ref, and the setters are non-null:
        // the model's own Kotlin declarations already threw here, so this is the same NPE.
        outputFile.setKey(fileOrThrow().getKey() ?: throw NullPointerException())
        outputFile.setIv(fileOrThrow().getIv() ?: throw NullPointerException())
        val os =
            AbstractConnectionManager.createOutputStream(outputFile, false, true)
                ?: throw NullPointerException("outputStream")

        ByteStreams.copy(is0, os)

        XmppConnectionService.dataStatics().close(is0)
        XmppConnectionService.dataStatics().close(os)

        if (!fileOrThrow().delete()) {
            Log.w(
                Config.LOGTAG,
                "unable to delete temporary OMEMO encrypted file " + fileOrThrow().getAbsolutePath(),
            )
        }

        file = outputFile
    }

    private fun finish() {
        val notify = acceptedAutomatically && !message.isRead() && cb == null
        if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
            // Let PgpDecryptionService handle file placement: it reads from getFile(message,false)
            // (cacheDir/<uuid>.jpg.pgp) and writes decrypted output to getFile(message,true).
            // Renaming here would move the encrypted file to the wrong location and break that lookup.
            val pgpAccount =
                (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                    .getAccount()
                    ?: throw NullPointerException("conversation has no account")
            (pgpAccount.getPgpDecryptionService() ?: throw NullPointerException("no pgp decryption service"))
                .decryptMessage(message, notify)
            message.setTransferable(null)
            mXmppConnectionService.updateMessage(message)
            mHttpConnectionManager.finishConnection(this)
            return
        }
        // Tulkki: 3.7 C5-E3 - `file` and `tmp` are refs now. Three of the four sites below need a
        // real `File` (`FileInputStream`, `renameTo(File)`, `updateMediaScanner(File)`), so they
        // read `asFile()`; the rest (`getName`, `delete`, `getAbsolutePath`, `setKeyAndIv`,
        // `createNewFile`, `getKey`/`getIv`) are on the ref.
        val tmp = fileOrThrow()
        val extension =
            XmppConnectionService.dataStatics().extractRelevantExtension(tmp.getName())
        try {
            mXmppConnectionService
                .getFileBackend()
                .setupRelativeFilePath(message, FileInputStream(tmp.asFile()), extension)
            file = mXmppConnectionService.getFileBackend().getFile(message)
            val didRename = tmp.renameTo(fileOrThrow().asFile())
            if (!didRename) throw IOException("rename failed")
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "Failed to rename downloaded file: " + e)
            file = tmp
            message.setRelativeFilePath(fileOrThrow().getAbsolutePath())
        } catch (e: uk.xa0.tulkki.xmpp.services.BlockedMediaException) {
            file = tmp
            tmp.delete()
            message.setDeleted(true)
        }
        message.setTransferable(null)
        mXmppConnectionService.updateMessage(message)
        mHttpConnectionManager.finishConnection(this)
        val notifyAfterScan = notify
        mXmppConnectionService.getFileBackend().updateMediaScanner(fileOrThrow().asFile()) {
            if (notifyAfterScan) {
                mXmppConnectionService.getNotificationService().push(message)
            }
        }
    }

    private fun decryptIfNeeded() {
        if (fileOrThrow().getKey() != null && fileOrThrow().getIv() != null) {
            decryptFile()
        }
    }

    private fun changeStatus(status: Int) {
        this.mStatus = status
        mHttpConnectionManager.updateConversationUi(true)
    }

    private fun showToastForException(e: Exception?) {
        val call = mostRecentCall
        val cancelled = call != null && call.isCanceled()
        if (e == null || cancelled) {
            return
        }
        if (e is java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found)
        } else if (e is java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect)
        } else if (e is FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_write_file)
        } else if (e is InvalidFileException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_invalid_file)
        } else {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_file_not_found)
        }
    }

    private fun updateProgress(i: Long) {
        this.mProgress = i.toInt()
        mHttpConnectionManager.updateConversationUi(false)
    }

    override fun getStatus(): Int = this.mStatus

    override fun getFileSize(): Long? {
        val f = this.file
        return if (f != null) {
            f.getExpectedSize()
        } else {
            null
        }
    }

    override fun getProgress(): Int = this.mProgress

    fun getMessage(): MessageRef = message

    private inner class FileSizeChecker(private val interactive: Boolean) : Runnable {

        override fun run() {
            check()
        }

        private fun retrieveFailed(e: Exception?) {
            changeStatus(Transferable.STATUS_OFFER_CHECK_FILESIZE)
            if (interactive) {
                showToastForException(e)
            } else {
                this@HttpDownloadConnection.acceptedAutomatically = false
                this@HttpDownloadConnection.mXmppConnectionService
                    .getNotificationService()
                    .push(message)
            }
            cancel()
        }

        private fun check() {
            val size: Long
            try {
                size = retrieveFileSize()
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "io exception in http file size checker: " + e.message)
                retrieveFailed(e)
                return
            }
            val fileParams = message.getFileParams()
            // Tulkki: C5-R1 fixed the smell this comment recorded - `updateFileParams` is a real
            // instance member on `FileBackendRef` now, so this is an ordinary virtual call.
            mXmppConnectionService
                .getFileBackend()
                .updateFileParams(message, fileParams.url(), size)
            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, true)
            fileOrThrow().setExpectedSize(size)
            message.setFileParams(null)
            if (mHttpConnectionManager.hasStoragePermission() &&
                size <= mHttpConnectionManager.getAutoAcceptFileSize() &&
                mXmppConnectionService.isDataSaverDisabled()
            ) {
                this@HttpDownloadConnection.acceptedAutomatically = true
                download(interactive)
            } else {
                changeStatus(Transferable.STATUS_OFFER)
                this@HttpDownloadConnection.acceptedAutomatically = false
                if (cb == null) {
                    this@HttpDownloadConnection.mXmppConnectionService
                        .getNotificationService()
                        .push(message)
                } else {
                    cb.accept(null)
                }
            }
        }

        private fun retrieveFileSize(): Long {
            Log.d(Config.LOGTAG, "retrieve file size. interactive:" + interactive)
            changeStatus(Transferable.STATUS_CHECKING)
            val client =
                mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                        .getAccount()
                        ?: throw NullPointerException("conversation has no account"),
                    interactive,
                )
            val request =
                Request.Builder()
                    .url(URL.stripFragment(mUrl))
                    .addHeader("Accept-Encoding", "identity")
                    .head()
                    .build()
            val call = client.newCall(request)
            mostRecentCall = call
            try {
                val response = call.execute()
                throwOnInvalidCode(response)
                val contentLength = response.header("Content-Length")
                val contentType = response.header("Content-Type")
                val extension =
                    AbstractConnectionManager.Extension.of(mUrl.encodedPath)
                if (Strings.isNullOrEmpty(extension.getExtension()) && contentType != null) {
                    val fileExtension =
                        XmppConnectionService.dataStatics()
                            .guessExtensionFromMimeType(contentType)
                    if (fileExtension != null) {
                        mXmppConnectionService
                            .getFileBackend()
                            .setupRelativeFilePath(
                                message,
                                String.format("%s.%s", message.getUuid(), fileExtension),
                                contentType,
                            )
                        Log.d(
                            Config.LOGTAG,
                            "rewriting name after not finding extension in url but in content type",
                        )
                        setupFile()
                    }
                }
                if (contentLength == null || contentLength.isEmpty()) {
                    throw IOException("no content-length found in HEAD response")
                }
                val size = contentLength.toLong(10)
                if (size < 0) {
                    throw IOException("Server reported negative file size")
                }
                return size
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "io exception during HEAD " + e.message)
                throw e
            } catch (e: NumberFormatException) {
                throw IOException(e)
            }
        }
    }

    private inner class FileDownloader(private val interactive: Boolean) : Runnable {

        override fun run() {
            try {
                changeStatus(Transferable.STATUS_DOWNLOADING)
                download()
                decryptIfNeeded()
                finish()
                updateImageBounds()
            } catch (e: SSLHandshakeException) {
                changeStatus(Transferable.STATUS_OFFER)
            } catch (e: Exception) {
                Log.d(
                    Config.LOGTAG,
                    ((message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
                        ?: throw NullPointerException("conversation has no account")).getJid().asBareJid().toString() +
                        ": unable to download file",
                    e,
                )
                if (interactive) {
                    showToastForException(e)
                } else {
                    this@HttpDownloadConnection.acceptedAutomatically = false
                    this@HttpDownloadConnection.mXmppConnectionService
                        .getNotificationService()
                        .push(message)
                }
                cancel()
            } finally {
                if (cb != null) cb.accept(file)
            }
        }

        private fun download() {
            val client =
                mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                        .getAccount()
                        ?: throw NullPointerException("conversation has no account"),
                    interactive,
                )

            val requestBuilder = Request.Builder().url(URL.stripFragment(mUrl))

            val expected = fileOrThrow().getExpectedSize()
            val tryResume =
                fileOrThrow().exists() &&
                    fileOrThrow().getSize() > 0 &&
                    fileOrThrow().getSize() < expected
            val resumeSize: Long
            if (tryResume) {
                resumeSize = fileOrThrow().getSize()
                Log.d(
                    Config.LOGTAG,
                    "http download trying resume after " + resumeSize + " of " + expected,
                )
                requestBuilder.addHeader(
                    "Range",
                    String.format(Locale.ENGLISH, "bytes=%d-", resumeSize),
                )
            } else {
                resumeSize = 0
            }
            val request = requestBuilder.build()
            val call = client.newCall(request)
            mostRecentCall = call
            val response = call.execute()
            throwOnInvalidCode(response)
            val contentRange = response.header("Content-Range")
            val serverResumed =
                tryResume &&
                    contentRange != null &&
                    contentRange.startsWith("bytes " + resumeSize + "-")
            val body = response.body ?: throw NullPointerException("response body")
            val inputStream: InputStream = body.byteStream()
            val outputStream: OutputStream
            var transmitted = 0L
            if (tryResume && serverResumed) {
                Log.d(Config.LOGTAG, "server resumed")
                transmitted = fileOrThrow().getSize()
                updateProgress(Math.round((transmitted.toDouble() / expected) * 100))
                outputStream =
                    AbstractConnectionManager.createOutputStream(fileOrThrow(), true, false)
                        ?: throw NullPointerException("outputStream")
            } else {
                val contentLength = response.header("Content-Length")
                val size =
                    if (contentLength == null || contentLength.isEmpty()) {
                        0L
                    } else {
                        Longs.tryParse(contentLength)
                    }
                if (expected != size) {
                    Log.d(
                        Config.LOGTAG,
                        "content-length reported on GET (" +
                            size +
                            ") did not match Content-Length reported on HEAD (" +
                            expected +
                            ")",
                    )
                }
                (fileOrThrow().getParentFile() ?: throw NullPointerException()).mkdirs()
                Log.d(Config.LOGTAG, "creating file: " + fileOrThrow().getAbsolutePath())
                if (!fileOrThrow().exists() && !fileOrThrow().createNewFile()) {
                    throw FileWriterException(fileOrThrow().asFile())
                }
                outputStream =
                    AbstractConnectionManager.createOutputStream(fileOrThrow(), false, false)
                        ?: throw NullPointerException("outputStream")
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    break
                }
                transmitted += count
                try {
                    outputStream.write(buffer, 0, count)
                } catch (e: IOException) {
                    throw FileWriterException(fileOrThrow().asFile())
                }
                if (transmitted > expected) {
                    throw InvalidFileException(
                        String.format("File exceeds expected size of %d", expected)
                    )
                }
                updateProgress(Math.round((transmitted.toDouble() / expected) * 100))
            }
            outputStream.flush()
        }

        private fun updateImageBounds() {
            val privateMessage = message.isPrivateMessage()
            message.setType(if (privateMessage) MessageRef.TYPE_PRIVATE_FILE else MessageRef.TYPE_FILE)
            val url: String
            val ref = mUrl.fragment
            if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
                url = AesGcmURL.toAesGcmUrl(mUrl)
            } else {
                url = mUrl.toString()
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url)
            mXmppConnectionService.updateMessage(message)
        }
    }
}

private fun throwOnInvalidCode(response: Response) {
    val code = response.code
    if (code < 200 || code >= 300) {
        throw IOException(String.format(Locale.ENGLISH, "HTTP Status code was %d", code))
    }
}

private class InvalidFileException(message: String) : IOException(message)

/**
 * Tulkki: 3.7 C5-B - the island's own, in place of `:data`'s
 * `:data`'s `FileWriterException`, which this file imported.
 *
 * <p>**This class assumes the `:data` exception never reaches the `instanceof` above.** Measured
 * when the import was dropped: `FileWriterException` has four throw sites in `FileBackend`
 * (855/903/941/999) and every one of them sits inside a method that declares
 * `throws FileCopyException` and catches it, so it never crosses that boundary - there is no
 * `throws ... FileWriterException` anywhere in the repository. The only throwers that can reach
 * {@code showToastForException} are this file's own two, below. Nothing enforces that: if a future
 * path ever lets the `:data` type out, the `instanceof` stops matching and the toast silently
 * degrades to its generic branch. That is the whole reason this comment exists.
 */
private class FileWriterException(file: File) : Exception("Could not write to " + file.getAbsolutePath())
