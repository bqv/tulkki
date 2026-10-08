package uk.xa0.tulkki.xmpp.services

import android.net.Uri
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import java.util.concurrent.Executor

/**
 * Tulkki: attaching a picked file or image, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk owns no field after the move. Its three fields stay on the Java service because other
 * members read them: `FILE_ATTACHMENT_EXECUTOR` is `public static` and named by `:app` by hand,
 * `VIDEO_COMPRESSION_EXECUTOR` is read by the same bodies the service still runs, and
 * `mStickerScanExecutor` is passed into `CacheHousekeeping.cleanupTemporaryStorage`. So the two
 * executors, the `AttachFilePort` slot (C74's private field, resolved on the Java side through the
 * service's own private `attachFile()` accessor) and the six-argument send (C25b's private choke
 * point, through the existing [OutgoingStanzaSender] seam) all travel in as values.
 *
 * The Java's guard order is kept: the image path's `never`/`auto`/gif/odd-bounds/`data:` chain
 * short-circuits left to right, the `mimeType` it hands the fallback is the guessed one and not the
 * original `type`, and the two catches under the image copy keep the Java's fall-back-then-error
 * order. `callback` is dereferenced unguarded on every path the Java reached, and the one
 * `callback != null` test the Java wrote is dropped because the value is now non-null at the seam.
 */
object AttachmentSending {

    @JvmStatic
    fun attachFileToConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uri: Uri,
        type: String?,
        subject: String?,
        callback: UiCallbackPort<MessageRef>,
        attachFilePort: AttachFilePort,
        fileAttachmentExecutor: Executor,
        videoCompressionExecutor: Executor,
        sender: OutgoingStanzaSender,
    ) {
        val dataStatics = XmppConnectionService.dataStatics()
        val replyTo = conversation.getReplyTo()
        val message: MessageRef =
            if (replyTo == null) {
                dataStatics.newMessage(conversation, "", conversation.getNextEncryption())
            } else {
                val reply = replyTo.reply()
                reply.setEncryption(conversation.getNextEncryption())
                reply
            }
        if (conversation.getCaption() != null) {
            message.appendBody(conversation.getCaption() + " ")
            message.setEncryption(conversation.getNextEncryption())
        }
        if (conversation.getNextEncryption() == MessageRef.ENCRYPTION_PGP) {
            message.setEncryption(MessageRef.ENCRYPTION_DECRYPTED)
        }
        if (subject != null && subject.length > 0) message.setSubject(subject)
        if (service.getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread())
        }
        if (!dataStatics.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart())
            message.setType(MessageRef.TYPE_FILE)
        }
        Log.d(Config.LOGTAG, "attachFile: type=" + message.getType())
        Log.d(Config.LOGTAG, "counterpart=" + message.getCounterpart())
        val runnable =
            attachFilePort.create(
                message,
                uri,
                type ?: throw NullPointerException(),
                callback,
            )
        if (runnable.isVideoMessage()) {
            videoCompressionExecutor.execute(runnable)
        } else {
            fileAttachmentExecutor.execute(runnable)
        }
    }

    @JvmStatic
    fun attachImageToConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uri: Uri,
        type: String?,
        subject: String?,
        callback: UiCallbackPort<MessageRef>,
        attachFilePort: AttachFilePort,
        fileAttachmentExecutor: Executor,
        videoCompressionExecutor: Executor,
        sender: OutgoingStanzaSender,
    ) {
        val dataStatics = XmppConnectionService.dataStatics()
        val mimeType: String? = dataStatics.guessMimeTypeFromUriAndMime(service, uri, type)
        val compressPictures = PresencePreferences.getCompressPicturesPreference(service)

        if ("never" == compressPictures ||
            ("auto" == compressPictures && service.getFileBackend().useImageAsIs(uri)) ||
            (mimeType != null && mimeType.endsWith("/gif")) ||
            service.getFileBackend().unusualBounds(uri) ||
            "data" == uri.getScheme()
        ) {
            Log.d(
                Config.LOGTAG,
                (conversation.getAccount()
                        ?: throw NullPointerException("conversation has no account"))
                    .getJid().asBareJid().toString() +
                    ": not compressing picture. sending as file",
            )
            attachFileToConversation(
                service,
                conversation,
                uri,
                mimeType,
                subject,
                callback,
                attachFilePort,
                fileAttachmentExecutor,
                videoCompressionExecutor,
                sender,
            )
            return
        }
        val replyTo = conversation.getReplyTo()
        val message: MessageRef =
            if (replyTo == null) {
                dataStatics.newMessage(conversation, "", conversation.getNextEncryption())
            } else {
                val reply = replyTo.reply()
                reply.setEncryption(conversation.getNextEncryption())
                reply
            }
        if (conversation.getCaption() != null) {
            message.appendBody(conversation.getCaption() + " ")
            message.setEncryption(conversation.getNextEncryption())
        }
        if (conversation.getNextEncryption() == MessageRef.ENCRYPTION_PGP) {
            message.setEncryption(MessageRef.ENCRYPTION_DECRYPTED)
        }
        if (subject != null && subject.length > 0) message.setSubject(subject)
        if (service.getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread())
        }
        if (!dataStatics.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart())
            message.setType(MessageRef.TYPE_IMAGE)
        }
        Log.d(Config.LOGTAG, "attachImage: type=" + message.getType())
        fileAttachmentExecutor.execute {
            try {
                service.getFileBackend().copyImageToPrivateStorage(message, uri)
            } catch (e: FileBackendRef.ImageCompressionException) {
                Log.d(
                    Config.LOGTAG,
                    "unable to compress image. fall back to file transfer",
                    e,
                )
                attachFileToConversation(
                    service,
                    conversation,
                    uri,
                    mimeType,
                    subject,
                    callback,
                    attachFilePort,
                    fileAttachmentExecutor,
                    videoCompressionExecutor,
                    sender,
                )
                return@execute
            } catch (e: FileBackendRef.FileCopyException) {
                callback.error(e.resId, message)
                return@execute
            }
            if (conversation.getNextEncryption() == MessageRef.ENCRYPTION_PGP) {
                val pgpEngine = service.getPgpEngine()
                if (pgpEngine != null) {
                    pgpEngine.encrypt(message, callback)
                } else {
                    callback.error(R.string.unable_to_connect_to_keychain, null)
                }
            } else {
                sender.sendMessage(
                    message,
                    false,
                    false,
                    false,
                    Runnable { callback.success(message) },
                    false,
                )
            }
        }
    }
}
