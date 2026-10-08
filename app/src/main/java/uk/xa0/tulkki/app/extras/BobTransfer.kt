package uk.xa0.tulkki.app.extras

import android.net.Uri
import android.util.Base64
import android.util.Log
import io.ipfs.cid.Cid
import io.ipfs.multihash.Multihash
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.security.NoSuchAlgorithmException
import java.util.HashMap
import uk.xa0.tulkki.app.http.AesGcmURL
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.data.utils.BobCid
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xml.Element

/**
 * Downloads a XEP-0231 bits-of-binary payload into the file backends.
 *
 * <p><strong>`attempts` loses its `protected`.</strong> Java's `protected static Map` has no Kotlin
 * spelling: a companion member cannot be `protected`, so it is a public `@JvmField`, which is a
 * widening nothing in the tree can observe - only this file names it - and it keeps the field
 * *static*, as `BobTransfer.attempts` was. Its mutable-static-state nature is unchanged.
 *
 * <p>**`@Throws` on the two entries that cross to Java.** `uri(Cid)` and the `ForMessage`
 * constructor both declared `URISyntaxException` (and `NoSuchAlgorithmException`) in the Java, and
 * Java callers catch them; Kotlin declares no checked exceptions by default, so the attributes are
 * written out. `multihashAlgo` stays a private companion member, its only caller being `uri`.
 *
 * <p>**A fourth multi-catch split**: `catch (IOException | uk.xa0.tulkki.xmpp.services.BlockedMediaException)`
 * becomes two catch blocks with the same body.
 *
 * <p>Everything else is the Java's shape: the protected instance fields stay protected properties
 * (nothing outside this file reads them as fields), the `Iq`/`Element` calls keep their exact
 * setters, and `start()`'s early returns, ten-second attempt window and null-`outputStream` branch
 * are unchanged.
 */
open class BobTransfer(
        protected var uri: URI,
        // Tulkki: nullable because `Message.getConversation()`/`getCounterpart()` are (`Message.kt`
        // keeps the null `IndividualMessage.fromSnapshot(…, null)` stores), so the Java's bare
        // dereference would have thrown. A transfer with no account or no counterpart is skipped in
        // `start()` rather than given an invented one.
        protected var account: Account?,
        protected var to: Jid?,
        protected var xmppConnectionService: XmppConnectionService
) : Transferable {

    private var statusValue: Int = Transferable.STATUS_OFFER

    override fun start(): Boolean {
        if (!xmppConnectionService.isDataSaverDisabled()) return false

        if (statusValue == Transferable.STATUS_DOWNLOADING) return true
        // Tulkki: 3.7 C5-E3 - `getFileForCid` answers the ref; `canRead()` is on it, and
        // `finish(File)` still wants the JDK `File`, so the bridge is `asFile()` and the null check
        // stays in front of it.
        val f: DownloadableFileRef? = xmppConnectionService.getFileForCid(cid(uri))

        if (f != null && f.canRead()) {
            finish(f.asFile())
            return true
        }

        if (xmppConnectionService.hasInternetConnection() &&
                attempts.getOrDefault(uri, 0L) + 10000L < System.currentTimeMillis()) {
            attempts.put(uri, System.currentTimeMillis())
            changeStatus(Transferable.STATUS_DOWNLOADING)

            // Tulkki: the two nullable ends of the transfer. With either absent there is no request
            // to send, so the unit of work is skipped rather than given a stand-in.
            val requestAccount = account ?: return false
            val requestTo = to ?: return false
            val request = Iq(Iq.Type.GET)
            request.setTo(requestTo)
            val dataq = request.addChild("data", "urn:xmpp:bob")
            dataq.setAttribute("cid", uri.schemeSpecificPart)
            xmppConnectionService.sendIqPacket(requestAccount, request) { packet ->
                val data = packet.findChild("data", "urn:xmpp:bob")
                if (packet.getType() == Iq.Type.ERROR || data == null) {
                    Log.d(Config.LOGTAG, "BobTransfer failed: " + packet)
                    finish(null)
                } else {
                    val contentType = data.getAttribute("type")
                    // Nullable: guessExtensionFromMimeType answers null for an unknown type, the
                    // value Java let this assignment carry.
                    var fileExtension: String? = "dat"
                    if (contentType != null) {
                        fileExtension = MimeUtils.guessExtensionFromMimeType(contentType)
                    }

                    try {
                        val bytes = Base64.decode(data.getContent(), Base64.DEFAULT)

                        val file =
                                FileBackends.get()
                                        .getStorageLocation(
                                                null,
                                                ByteArrayInputStream(bytes),
                                                fileExtension)
                        (file.getParentFile() ?: throw NullPointerException()).mkdirs()
                        if (!file.exists() && !file.createNewFile()) {
                            throw IOException(file.getAbsolutePath())
                        }

                        val outputStream: OutputStream? =
                                AbstractConnectionManager.createOutputStream(
                                        DownloadableFile(file.getAbsolutePath()), false, false)

                        if (outputStream != null && bytes != null) {
                            outputStream.write(bytes)
                            outputStream.flush()
                            outputStream.close()
                            finish(file)
                        } else {
                            Log.w(
                                    Config.LOGTAG,
                                    "Could not write BobTransfer, null outputStream")
                            finish(null)
                        }
                    } catch (e: IOException) {
                        Log.w(Config.LOGTAG, "Could not write BobTransfer: " + e)
                        finish(null)
                    } catch (e: uk.xa0.tulkki.xmpp.services.BlockedMediaException) {
                        Log.w(Config.LOGTAG, "Could not write BobTransfer: " + e)
                        finish(null)
                    }
                }
            }
            return true
        } else {
            return false
        }
    }

    override fun getStatus(): Int = statusValue

    override fun getProgress(): Int = 0

    override fun getFileSize(): Long? = null

    override fun cancel() {
        // No real way to cancel an iq in process...
        changeStatus(Transferable.STATUS_CANCELLED)
    }

    protected open fun changeStatus(newStatus: Int) {
        statusValue = newStatus
        xmppConnectionService.updateConversationUi()
    }

    protected open fun finish(f: File?) {
        if (f != null) xmppConnectionService.updateConversationUi()
    }

    class ForMessage
    @Throws(URISyntaxException::class)
    constructor(message: Message, xmppConnectionService: XmppConnectionService) :
            BobTransfer(
                    URI(message.getFileParams().url),
                    message.getConversation()?.getAccount(),
                    message.getCounterpart(),
                    xmppConnectionService) {

        protected var message: Message = message

        override fun cancel() {
            super.cancel()
            this.message.setTransferable(null)
        }

        override fun finish(f: File?) {
            if (f != null) {
                message.setRelativeFilePath(f.absolutePath)
                val privateMessage = message.isPrivateMessage()
                message.setType(
                        if (privateMessage) Message.TYPE_PRIVATE_FILE else Message.TYPE_FILE)
                FileBackends.get().updateFileParams(message, uri.toString(), false)
                xmppConnectionService.updateMessage(message)
            }
            message.setTransferable(null)
            super.finish(f)
        }
    }

    companion object {

        @JvmField val attempts: MutableMap<URI, Long> = HashMap()

        // Nullable: BobCid answers null for a reference that is not a cid, which is what Java let
        // these forwards return.
        @JvmStatic fun cid(uri: Uri): Cid? = BobCid.cid(uri)

        @JvmStatic fun cid(uri: URI): Cid? = BobCid.cid(uri)

        @JvmStatic fun cid(bobCid: String): Cid? = BobCid.cid(bobCid)

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class, URISyntaxException::class)
        fun uri(cid: Cid): URI =
                URI(
                        "cid",
                        multihashAlgo(cid.type) + "+" + CryptoHelper.bytesToHex(cid.hash) +
                                "@bob.xmpp.org",
                        null)

        @Throws(NoSuchAlgorithmException::class)
        private fun multihashAlgo(type: Multihash.Type): String {
            val algo = CryptoHelper.multihashAlgo(type)
            if (algo == "sha-1") return "sha1"
            return algo
        }
    }
}
