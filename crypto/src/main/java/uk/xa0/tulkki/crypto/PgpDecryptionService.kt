package uk.xa0.tulkki.crypto

import android.app.PendingIntent
import android.content.Intent
import android.util.Log

import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.util.OpenPgpApi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.ArrayDeque

import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

class PgpDecryptionService(store: PgpStore?, service: XmppConnectionService) :
    uk.xa0.tulkki.xmpp.services.PgpDecryptionPort {

    protected val messages: ArrayDeque<OmemoMessage> = ArrayDeque()
    protected val pendingNotifications: HashSet<OmemoMessage> = HashSet()
    private val store: PgpStore = store ?: throw NullPointerException("store")
    private val service: XmppConnectionService = service
    private var openPgpApi: OpenPgpApi? = null
    private var currentMessage: OmemoMessage? = null
    private var pendingIntent: PendingIntent? = null
    private var userInteractionResult: Intent? = null

    @Synchronized
    fun decrypt(message: OmemoMessage, notify: Boolean): Boolean {
        messages.add(message)
        return if (notify && pendingIntent == null) {
            pendingNotifications.add(message)
            continueDecryption()
            false
        } else {
            continueDecryption()
            notify
        }
    }

    @Synchronized
    fun decrypt(list: List<out OmemoMessage>) {
        for (message in list) {
            if (message.getEncryption() == OmemoMessage.ENCRYPTION_PGP) {
                messages.add(message)
            }
        }
        continueDecryption()
    }

    @Synchronized
    fun discard(discards: List<out OmemoMessage>) {
        val toRemove: List<OmemoMessage> = discards.toList()
        this.messages.removeAll(toRemove)
        this.pendingNotifications.removeAll(toRemove)
    }

    // Tulkki: 3.7 pair 9, part 12 - `uk.xa0.tulkki.xmpp.services.PgpDecryptionPort`'s two new members,
    // which `MessageParser`'s edit path calls now that it holds a `MessageRef`. This module is an
    // island that `.allow`s `:xmpp`, so the ref is nameable here; the object is a `Message`, which
    // is an `OmemoMessage`, so each cast is identity-safe and the two methods below are untouched.

    @Synchronized
    override fun discardMessage(message: MessageRef) {
        discard(message as OmemoMessage)
    }

    @Synchronized
    override fun decryptMessage(message: MessageRef, notify: Boolean): Boolean =
        decrypt(message as OmemoMessage, notify)

    @Synchronized
    fun discard(message: OmemoMessage) {
        this.messages.remove(message)
        this.pendingNotifications.remove(message)
    }

    fun giveUpCurrentDecryption() {
        val message = synchronized(this) {
            if (currentMessage != null) {
                return
            }
            val next = messages.peekFirst() ?: return
            discard(next)
            next
        }
        synchronized(message) {
            if (message.getEncryption() == OmemoMessage.ENCRYPTION_PGP) {
                message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTION_FAILED)
            }
        }
        store.updateMessage(message, false)
        continueDecryption(true)
    }

    @Synchronized
    protected fun decryptNext() {
        if (pendingIntent == null && getOpenPgpApi() != null) {
            val next = messages.poll()
            currentMessage = next
            if (next != null) {
                Thread {
                    executeApi(next)
                    decryptNext()
                }.start()
            }
        }
    }

    @Synchronized
    override fun continueDecryption(resetPending: Boolean) {
        if (resetPending) {
            this.pendingIntent = null
        }
        continueDecryption()
    }

    @Synchronized
    fun continueDecryption(userInteractionResult: Intent) {
        this.pendingIntent = null
        this.userInteractionResult = userInteractionResult
        continueDecryption()
    }

    @Synchronized
    fun continueDecryption() {
        if (currentMessage == null) {
            decryptNext()
        }
    }

    @Synchronized
    private fun getOpenPgpApi(): OpenPgpApi? {
        if (openPgpApi == null) {
            this.openPgpApi = service.getOpenPgpApi()
        }
        return this.openPgpApi
    }

    private fun executeApi(message: OmemoMessage) {
        var skipNotificationPush = false
        synchronized(message) {
            val params = userInteractionResult ?: Intent()
            params.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY)
            if (message.getType() == OmemoMessage.TYPE_TEXT) {
                val isStream: InputStream =
                    ByteArrayInputStream(message.getBody().toByteArray(Charset.defaultCharset()))
                val os: OutputStream = ByteArrayOutputStream()
                val api = getOpenPgpApi() ?: throw NullPointerException("openPgpApi")
                val result = api.executeApi(params, isStream, os)
                when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    OpenPgpApi.RESULT_CODE_SUCCESS -> {
                        try {
                            os.flush()
                            val body = os.toString()
                            message.setBody(body)
                            message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTED)
                            val autoAcceptFileSize = store.getAutoAcceptFileSize()
                            if (message.trusted() &&
                                message.treatAsDownloadable() &&
                                autoAcceptFileSize > 0
                            ) {
                                store.createNewDownloadConnection(message)
                            }
                        } catch (e: IOException) {
                            Log.d(Config.LOGTAG, "decryption failed", e)
                            message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTION_FAILED)
                        }
                        store.updateMessage(message)
                    }
                    OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                        synchronized(this@PgpDecryptionService) {
                            val pendingIntent =
                                result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
                            messages.addFirst(message)
                            currentMessage = null
                            storePendingIntent(pendingIntent)
                        }
                    }
                    OpenPgpApi.RESULT_CODE_ERROR -> {
                        Log.d(Config.LOGTAG, "decryption failed (api error)")
                        message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTION_FAILED)
                        store.updateMessage(message)
                    }
                }
            } else if (message.isFileOrImage()) {
                try {
                    val inputFile = store.getFile(message, false)
                    val outputFile = store.getFile(message, true)
                    if (outputFile.getParentFile().mkdirs()) {
                        Log.d(
                            Config.LOGTAG,
                            "created parent directories for " + outputFile.getAbsolutePath()
                        )
                    }
                    outputFile.createNewFile()
                    val isStream: InputStream = FileInputStream(inputFile)
                    val os: OutputStream = FileOutputStream(outputFile)
                    val api = getOpenPgpApi() ?: throw NullPointerException("openPgpApi")
                    val result = api.executeApi(params, isStream, os)
                    when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                        OpenPgpApi.RESULT_CODE_SUCCESS -> {
                            val openPgpMetadata =
                                result.getParcelableExtra<OpenPgpMetadata>(OpenPgpApi.RESULT_METADATA)
                                    ?: throw NullPointerException("openPgpMetadata")
                            val originalFilename = openPgpMetadata.getFilename()
                            val originalExtension =
                                if (originalFilename == null) null
                                else store.relevantExtension(originalFilename)
                            if (originalExtension != null &&
                                store.relevantExtension(outputFile.getName()) == null
                            ) {
                                Log.d(
                                    Config.LOGTAG,
                                    "detected original filename during pgp decryption"
                                )
                                val mime = store.mimeForExtension(originalExtension)
                                val filename = outputFile.getName() + "." + originalExtension
                                val fixedFile =
                                    store.getStorageLocation(message, filename, mime)
                                if (fixedFile.getParentFile().mkdirs()) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "created parent directories for " + fixedFile.getAbsolutePath()
                                    )
                                }
                                store.ignoreDeletion(outputFile.getAbsolutePath())
                                if (outputFile.renameTo(fixedFile)) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "renamed " + outputFile.getAbsolutePath() +
                                            " to " + fixedFile.getAbsolutePath()
                                    )
                                    message.setRelativeFilePath(fixedFile.getAbsolutePath())
                                }
                            }
                            val url = message.getFileUrl()
                            message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTED)
                            store.updateFileParams(message, url)
                            store.updateMessage(message)
                            if (!inputFile.delete()) {
                                Log.w(
                                    Config.LOGTAG,
                                    "unable to delete pgp encrypted source file " +
                                        inputFile.getAbsolutePath()
                                )
                            }
                            skipNotificationPush = true
                            store.updateMediaScanner(
                                outputFile,
                                Runnable { notifyIfPending(message) }
                            )
                        }
                        OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                            synchronized(this@PgpDecryptionService) {
                                val pendingIntent =
                                    result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
                                messages.addFirst(message)
                                currentMessage = null
                                storePendingIntent(pendingIntent)
                            }
                        }
                        OpenPgpApi.RESULT_CODE_ERROR -> {
                            message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTION_FAILED)
                            store.updateMessage(message)
                        }
                    }
                } catch (e: IOException) {
                    message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTION_FAILED)
                    store.updateMessage(message)
                }
            }
        }
        if (!skipNotificationPush) {
            notifyIfPending(message)
        }
    }

    @Synchronized
    private fun notifyIfPending(message: OmemoMessage) {
        if (pendingNotifications.remove(message)) {
            store.pushNotification(message)
        }
    }

    private fun storePendingIntent(pendingIntent: PendingIntent?) {
        this.pendingIntent = pendingIntent
        store.updateConversationUi()
    }

    @Synchronized
    fun hasPendingIntent(conversation: OmemoConversation): Boolean {
        if (pendingIntent == null) {
            return false
        } else {
            for (message in messages) {
                if (message.getOmemoConversation() === conversation) {
                    return true
                }
            }
            return false
        }
    }

    fun getPendingIntent(): PendingIntent? = pendingIntent

    fun isConnected(): Boolean = getOpenPgpApi() != null
}
