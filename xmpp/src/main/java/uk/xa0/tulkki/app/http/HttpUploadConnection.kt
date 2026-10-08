package uk.xa0.tulkki.app.http

import android.util.Log
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Future
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.Random

// Tulkki: C5-C put this class on the island ref while `:data`'s model interface was the other name.
// 2026-10-08 retired both into `uk.xa0.tulkki.libs.Transferable`, which is also what
// `Message.transferable` is declared as, so `message.setTransferable(this)` at :180 stores this
// object as it is and no cast stands between.
/**
 * Tulkki: one HTTP upload transfer - the slot, the PUT and the progress it reports.
 *
 * Ported from `HttpUploadConnection.java`. It is *ours*, so it is converted in place.
 *
 * The Java-visible surface, read off the two callers and the interfaces it implements:
 *
 *  * `WHITE_LISTED_HEADERS` is read by `SlotRequester.kt` as a **field**, so it stays a `@JvmField`
 *    in the companion (`static final List<String>` exactly as Java declared it);
 *  * both constructors stay public: `HttpConnectionManager.java:136,147` calls the
 *    `MessageRef`/`Method` one and the `AccountRef`/`DownloadableFileRef` one. The shared half is
 *    the private primary constructor, which is invisible - `private` members are outside the
 *    checked surface;
 *  * `message`, `cb` and `callback` are the three nullable members the Java annotated `@Nullable`,
 *    and `getMessage()` answers `MessageRef?` because the file-based construction stores none;
 *  * the status int is `Transferable.STATUS_UPLOADING`, **qualified**: a Kotlin implementor
 *    inherits none of a Java interface's static fields (`AbstractConnectionManager.kt` records the
 *    same fact for `VALID_CRYPTO_EXTENSIONS`);
 *  * `FutureCallback.onSuccess` takes a **non-null** result under the build's JSpecify reading of
 *    Guava, so the Java's `@Nullable` parameter is not reproduced; the checker's leg 1 needs
 *    `-Xjspecify-annotations=strict` to see that, and it did not at first - the Gradle build found it
 *    and the flag is now in the checker;
 *  * the Java's bare dereferences of the `file` and `slot` fields become explicit locals - a Kotlin
 *    mutable property does not smart-cast, and `!!` is not written here - so the NPE they would
 *    raise is the one the Java raised.
 */
class HttpUploadConnection private constructor(
    private val message: MessageRef?,
    private val account: AccountRef,
    private var file: DownloadableFileRef?,
    private val method: Method,
    private val mHttpConnectionManager: HttpConnectionManager,
    private val cb: Runnable?,
    private val callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<String>?,
) : Transferable, AbstractConnectionManager.ProgressListener {

    constructor(
        message: MessageRef,
        method: Method,
        httpConnectionManager: HttpConnectionManager,
        cb: Runnable?,
    ) : this(
        message,
        (message.getConversation() ?: throw NullPointerException("message has no conversation")).getAccount()
            ?: throw NullPointerException("conversation has no account"),
        null,
        method,
        httpConnectionManager,
        cb,
        null,
    )

    constructor(
        account: AccountRef,
        file: DownloadableFileRef,
        method: Method,
        httpConnectionManager: HttpConnectionManager,
        callback: uk.xa0.tulkki.xmpp.services.UiCallbackPort<String>,
    ) : this(
        null,
        account,
        file,
        method,
        httpConnectionManager,
        null,
        callback,
    )

    private val mXmppConnectionService: XmppConnectionService =
        mHttpConnectionManager.getXmppConnectionService()

    private var delayed = false
    private var slot: SlotRequester.Slot? = null
    private var key: ByteArray? = null

    private var transmitted = 0L
    private var mostRecentCall: Call? = null
    private var slotFuture: ListenableFuture<SlotRequester.Slot>? = null

    override fun start(): Boolean = false

    override fun getStatus(): Int = Transferable.STATUS_UPLOADING

    override fun getFileSize(): Long? = file?.getExpectedSize()

    override fun getProgress(): Int {
        val uploading = file ?: return 0
        return ((transmitted.toDouble() / uploading.getExpectedSize()) * 100).toInt()
    }

    override fun cancel() {
        val pendingSlot = this.slotFuture
        if (pendingSlot != null && !pendingSlot.isDone()) {
            if (pendingSlot.cancel(true)) {
                Log.d(Config.LOGTAG, "cancelled slot requester")
            }
        }
        val call = this.mostRecentCall
        if (call != null && !call.isCanceled()) {
            call.cancel()
        }
    }

    private fun fail(errorMessage: String?) {
        finish()
        val call = this.mostRecentCall
        val pendingSlot: Future<SlotRequester.Slot>? = this.slotFuture
        val cancelled =
            (call != null && call.isCanceled()) || (pendingSlot != null && pendingSlot.isCancelled())
        if (this.message != null) {
            mXmppConnectionService.markMessage(
                message,
                MessageRef.STATUS_SEND_FAILED,
                if (cancelled) MessageRef.ERROR_MESSAGE_CANCELLED else errorMessage,
            )
            if (cb != null) cb.run()
        } else if (this.callback != null) {
            callback.error(R.string.upload_failed_server_not_found, errorMessage)
        }
    }

    private fun finish() {
        mHttpConnectionManager.finishUploadConnection(this)
        if (this.message != null) {
            message.setTransferable(null)
        }
    }

    fun initForMessage(delay: Boolean) {
        val message = this.message ?: throw NullPointerException("message")
        val uploading = mXmppConnectionService.getFileBackend().getFile(message, false)
        this.file = uploading
        val mime: String
        if (message.getEncryption() == MessageRef.ENCRYPTION_PGP || message.getEncryption() == MessageRef.ENCRYPTION_DECRYPTED) {
            mime = "application/pgp-encrypted"
        } else {
            mime = uploading.getMimeType()
        }
        val originalFileSize = uploading.getSize()
        this.delayed = delay
        if (Config.ENCRYPT_ON_HTTP_UPLOADED
            || message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL
        ) {
            val key = ByteArray(44)
            Random.SECURE_RANDOM.nextBytes(key)
            this.key = key
            uploading.setKeyAndIv(key)
        }
        uploading.setExpectedSize(
            originalFileSize + (if (uploading.getKey() != null) 16 else 0),
        )
        val slotFuture = SlotRequester(mXmppConnectionService).request(
            method,
            account,
            uploading,
            message.getFileParams().getName(),
            mime,
        )
        this.slotFuture = slotFuture
        Futures.addCallback(slotFuture, object : FutureCallback<SlotRequester.Slot> {
            override fun onSuccess(result: SlotRequester.Slot) {
                this@HttpUploadConnection.slot = result
                try {
                    this@HttpUploadConnection.upload()
                } catch (e: Exception) {
                    fail(e.message)
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": unable to request slot",
                    throwable,
                )
                fail(throwable.message)
            }
        }, MoreExecutors.directExecutor())
        message.setTransferable(this)
        mXmppConnectionService.markMessage(message, MessageRef.STATUS_UNSEND)
    }

    fun initForFile() {
        val uploading = this.file ?: throw NullPointerException("file")
        val mime = uploading.getMimeType()
        val originalFileSize = uploading.getSize()
        this.delayed = false
        if (Config.ENCRYPT_ON_HTTP_UPLOADED) {
            val key = ByteArray(44)
            Random.SECURE_RANDOM.nextBytes(key)
            this.key = key
            uploading.setKeyAndIv(key)
        }
        uploading.setExpectedSize(
            originalFileSize + (if (uploading.getKey() != null) 16 else 0),
        )
        val slotFuture = SlotRequester(mXmppConnectionService).request(
            method,
            account,
            uploading,
            uploading.getName(),
            mime,
        )
        this.slotFuture = slotFuture
        Futures.addCallback(slotFuture, object : FutureCallback<SlotRequester.Slot> {
            override fun onSuccess(result: SlotRequester.Slot) {
                this@HttpUploadConnection.slot = result
                try {
                    this@HttpUploadConnection.upload()
                } catch (e: Exception) {
                    fail(e.message)
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": unable to request slot",
                    throwable,
                )
                fail(throwable.message)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun upload() {
        val slot = this.slot ?: throw NullPointerException("slot")
        val uploading = this.file ?: throw NullPointerException("file")
        val message = this.message
        val cb = this.cb
        val callback = this.callback
        val client: OkHttpClient = mHttpConnectionManager.buildHttpClient(
            slot.put,
            account,
            0,
            true,
        )
        val requestBody: RequestBody = AbstractConnectionManager.requestBody(uploading, this)
        val request = Request.Builder()
            .url(slot.put)
            .put(requestBody)
            .headers(slot.headers)
            .build()
        Log.d(Config.LOGTAG, "uploading file to " + slot.put)
        val call = client.newCall(request)
        this.mostRecentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(Config.LOGTAG, "http upload failed", e)
                fail(e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                if (code == 200 || code == 201) {
                    Log.d(Config.LOGTAG, "finished uploading file")
                    val get: String
                    val key = this@HttpUploadConnection.key
                    if (key != null) {
                        get = AesGcmURL.toAesGcmUrl(
                            slot.get.newBuilder().fragment(CryptoHelper.bytesToHex(key)).build(),
                        )
                    } else {
                        get = slot.get.toString()
                    }
                    if (message != null) {
                        mXmppConnectionService.getFileBackend().updateFileParams(message, get)
                        mXmppConnectionService.getFileBackend().updateMediaScanner(uploading.asFile())
                        finish()
                        if (!message.isPrivateMessage()) {
                            message.setCounterpart(
                                (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                                    .getJid()
                                    ?: throw NullPointerException("conversation has no jid"),
                            )
                        }
                        mXmppConnectionService.resendMessage(message, delayed, cb)
                    } else if (callback != null) {
                        mXmppConnectionService.getFileBackend().updateMediaScanner(uploading.asFile())
                        finish()
                        callback.success(get)
                    }
                } else {
                    Log.d(Config.LOGTAG, "http upload failed because response code was " + code)
                    fail("http upload failed because response code was " + code)
                }
            }
        })
    }

    fun getMessage(): MessageRef? = message

    override fun onProgress(progress: Long) {
        this.transmitted = progress
        mHttpConnectionManager.updateConversationUi(false)
    }

    companion object {

        @JvmField
        // `Arrays.asList` is fixed-size and `listOf` is immutable; only `contains` reads it, so the
        // stricter shape is unobservable - the `URL.kt` precedent.
        val WHITE_LISTED_HEADERS: List<String> = listOf(
            "Authorization",
            "Cookie",
            "Expires",
        )
    }
}
