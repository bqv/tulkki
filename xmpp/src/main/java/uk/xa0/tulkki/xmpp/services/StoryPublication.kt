package uk.xa0.tulkki.xmpp.services

import android.app.PendingIntent
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Element
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tulkki: the vCard4 publication, a story's publication and the HTTP upload they share, lifted out
 * of `XmppConnectionService`.
 *
 * The chunk owns no field. Two values travel in: C09's public static `FILE_ATTACHMENT_EXECUTOR`
 * (passed as the `Executor` the Java read by name) and the two C74 port slots the Java reached
 * through its own private accessors, `attachFile()` and `transcoderStrategies()`. Everything else is
 * a public member - `getIqGenerator()`, `getFileBackend()`, `getHttpConnectionManager()`,
 * `sendIqPacket`, `fetchVcard4`, the two foreground-notification toggles and the public static
 * `dataStatics()`.
 *
 * The Java's order is kept: `publishStory`'s single `retry` and its `preconditionNotMet` create-then-
 * republish arm, the transcoder's high-quality decision off
 * `attachFile().videoCompression(...)`, the "not smaller, use the original" fallback, and the
 * wrapper callback that deletes the temporary file in a `finally` on all three answers. Nullability
 * is read off the behaviour: `publishStory`'s and `publishStory(retry)`'s callback is guarded (the
 * recursive call passes the caller's value through), while `uploadFileForUrl`'s is dereferenced
 * unguarded and is non-null; the wrapper's `error`/`userInputRequired` objects accept the `null` the
 * port declares nullable, while its `success` object is the port's non-null `T` at `String`.
 */
object StoryPublication {

    @JvmStatic
    fun publishVCard4(service: XmppConnectionService, account: AccountRef, vcardElement: Element) {
        if (account.getStatusRef() != AccountRef.StateRef.ONLINE) {
            return
        }

        // 1. Create IQ-SET
        val packet = Iq(Iq.Type.SET)

        // 2. IMPORTANT: PEP requests must be addressed to the User's OWN Bare JID
        // (e.g. user@example.com), NOT left blank (which targets the server).
        packet.setTo(account.getJid().asBareJid())

        // 3. Create PubSub structure
        val pubsub = packet.addChild("pubsub", "http://jabber.org/protocol/pubsub")

        // 4. Publish to the specific node 'urn:xmpp:vcard4' defined in XEP-0292
        val publish = pubsub.addChild("publish")
        publish.setAttribute("node", "urn:xmpp:vcard4")

        // 5. Add Item with ID (XEP suggests 'current', though random UUIDs are allowed)
        val item = publish.addChild("item")
        item.setAttribute("id", "current")

        // 6. Ensure the vcard element has the correct namespace
        // The inner content MUST be <vcard xmlns='urn:ietf:params:xml:ns:vcard-4.0'>
        if ("vcard" != vcardElement.getName()) {
            // Wrap if it's not already a vcard element
            val wrapper = Element("vcard")
            wrapper.setAttribute("xmlns", "urn:ietf:params:xml:ns:vcard-4.0")
            wrapper.addChild(vcardElement)
            item.addChild(wrapper)
        } else {
            // Ensure namespace is set if missing
            if (vcardElement.getAttribute("xmlns") == null) {
                vcardElement.setAttribute("xmlns", "urn:ietf:params:xml:ns:vcard-4.0")
            }
            item.addChild(vcardElement)
        }

        // 7. Send packet
        service.sendIqPacket(account, packet) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, account.getJid().toString() + ": Successfully published VCard4")
                // Optional: Reload to reflect changes
                service.fetchVcard4(account, account.getSelfContact(), null)
            } else {
                // Log.d(Config.LOGTAG, account.getJid() + ": Failed to publish VCard4: " + response.toString());
            }
        }
    }

    @JvmStatic
    fun publishStory(
        service: XmppConnectionService,
        account: AccountRef,
        url: String,
        type: String,
        title: String,
        callback: UiCallbackPort<Void?>?,
    ) {
        publishStoryWithRetry(service, account, url, type, title, true, callback)
    }

    private fun publishStoryWithRetry(
        service: XmppConnectionService,
        account: AccountRef,
        url: String,
        type: String,
        title: String,
        retry: Boolean,
        callback: UiCallbackPort<Void?>?,
    ) {
        if (account.getStatusRef() != AccountRef.StateRef.ONLINE) {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null)
            }
            return
        }
        // This is the corrected publish request. It sends NO configuration options.
        val packet = service.getIqGenerator().publishStory(account, url, type, title, null)
        service.sendIqPacket(account, packet) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                if (callback != null) {
                    callback.success(null)
                }
            } else if (retry && PublishOptions.preconditionNotMet(response)) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": stories node does not exist. creating it",
                )
                // The node does not exist. Now we create it with the correct configuration.
                val createRequest = service.getIqGenerator().createStoriesNode()
                createRequest.setTo(account.getJid().asBareJid())
                service.sendIqPacket(account, createRequest) { createResponse ->
                    if (createResponse.getType() == Iq.Type.RESULT) {
                        // Node created. Now, retry publishing the story ONE more time, without the
                        // retry/create logic.
                        publishStoryWithRetry(service, account, url, type, title, false, callback)
                    } else {
                        Log.e(
                            Config.LOGTAG,
                            "Failed to create stories node: " + createResponse,
                        )
                        if (callback != null) {
                            callback.error(
                                R.string.error_publish_avatar_server_reject,
                                null,
                            )
                        }
                    }
                }
            } else {
                Log.e(Config.LOGTAG, "Failed to publish story: " + response)
                if (callback != null) {
                    callback.error(R.string.error_publish_avatar_server_reject, null)
                }
            }
        }
    }

    @JvmStatic
    fun uploadFileForUrl(
        service: XmppConnectionService,
        account: AccountRef?,
        uri: android.net.Uri,
        mimeType: String?,
        callback: UiCallbackPort<String>,
        attachFilePort: AttachFilePort,
        transcoderStrategiesPort: TranscoderStrategiesPort,
        fileAttachmentExecutor: Executor,
    ) {
        if (account == null) {
            callback.error(R.string.no_active_account, null)
            return
        }

        // Tulkki: C5-E3 - a widening, not a cast: `getTemporaryFile`'s declared return is still the
        // model. The body then needs the JDK `File` half at the eight calls that take a `File`
        // (`copyFileToPrivateStorage` x4, `copyImageToPrivateStorage`, `deleteFile` x3), so each of
        // those reads `asFile()`; `getAbsolutePath`, `getSize`, `exists` and `delete` are on the ref.
        val file = service.getFileBackend().getTemporaryFile(mimeType)

        val runnable =
            Runnable {
                try {
                    if (mimeType != null && mimeType.startsWith("video/")) {
                        service.startOngoingVideoTranscodingForegroundNotification()
                        try {
                            val transcodeFailed = AtomicBoolean(false)
                            val listener =
                                object : TranscoderListener {
                                    override fun onTranscodeProgress(progress: Double) {}

                                    override fun onTranscodeCompleted(successCode: Int) {
                                        Log.d(
                                            Config.LOGTAG,
                                            "transcoding successful for story",
                                        )
                                    }

                                    override fun onTranscodeCanceled() {
                                        Log.d(Config.LOGTAG, "transcoding canceled for story")
                                        transcodeFailed.set(true)
                                    }

                                    override fun onTranscodeFailed(exception: Throwable) {
                                        Log.w(
                                            Config.LOGTAG,
                                            "video transcoding failed for story",
                                            exception,
                                        )
                                        transcodeFailed.set(true)
                                    }
                                }
                            try {
                                Log.d(
                                    Config.LOGTAG,
                                    "Attempting to transcode video for story",
                                )
                                val highQuality =
                                    "720" == attachFilePort.videoCompression(service)
                                val future: Future<Void> =
                                    Transcoder.into(file.getAbsolutePath())
                                        .addDataSource(service, uri)
                                        .setVideoTrackStrategy(
                                            if (highQuality) {
                                                transcoderStrategiesPort.video720p()
                                            } else {
                                                transcoderStrategiesPort.video360p()
                                            },
                                        )
                                        .setAudioTrackStrategy(
                                            if (highQuality) {
                                                transcoderStrategiesPort.audioHq()
                                            } else {
                                                transcoderStrategiesPort.audioMq()
                                            },
                                        )
                                        .setListener(listener)
                                        .transcode()
                                future.get()
                            } catch (e: Exception) {
                                Log.w(
                                    Config.LOGTAG,
                                    "video transcoding setup failed for story",
                                    e,
                                )
                                transcodeFailed.set(true)
                            }

                            if (transcodeFailed.get() || file.getSize() == 0L) {
                                Log.d(
                                    Config.LOGTAG,
                                    "transcoding failed. falling back to copying original for story",
                                )
                                if (file.exists() && !file.delete()) {
                                    Log.w(
                                        Config.LOGTAG,
                                        "could not delete failed transcode file",
                                    )
                                }
                                service
                                    .getFileBackend()
                                    .copyFileToPrivateStorage(file.asFile(), uri)
                            } else {
                                val originalSize = XmppConnectionService.dataStatics().fileSize(service, uri)
                                if (originalSize > 0 && file.getSize() >= originalSize) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "transcoded file was not smaller. using original for story",
                                    )
                                    if (file.exists() && !file.delete()) {
                                        Log.w(
                                            Config.LOGTAG,
                                            "could not delete failed transcode file",
                                        )
                                    }
                                    service
                                        .getFileBackend()
                                        .copyFileToPrivateStorage(file.asFile(), uri)
                                } else {
                                    Log.d(
                                        Config.LOGTAG,
                                        "using transcoded video for story upload",
                                    )
                                }
                            }
                        } finally {
                            service.stopOngoingVideoTranscodingForegroundNotification()
                        }
                    } else if (mimeType != null && mimeType.startsWith("image/")) {
                        service.getFileBackend().copyImageToPrivateStorage(file.asFile(), uri)
                    } else {
                        service.getFileBackend().copyFileToPrivateStorage(file.asFile(), uri)
                    }
                } catch (e: FileBackendRef.ImageCompressionException) {
                    Log.d(Config.LOGTAG, "unable to decode image for story, uploading raw", e)
                    try {
                        service.getFileBackend().copyFileToPrivateStorage(file.asFile(), uri)
                    } catch (ex: FileBackendRef.FileCopyException) {
                        callback.error(ex.resId, null)
                        return@Runnable
                    }
                } catch (e: FileBackendRef.FileCopyException) {
                    callback.error(e.resId, null)
                    return@Runnable
                }

                val wrapperCallback =
                    object : UiCallbackPort<String> {
                        override fun success(url: String) {
                            try {
                                callback.success(url)
                            } finally {
                                service.getFileBackend().deleteFile(file.asFile())
                            }
                        }

                        override fun error(errorCode: Int, `object`: String?) {
                            try {
                                callback.error(errorCode, `object`)
                            } finally {
                                service.getFileBackend().deleteFile(file.asFile())
                            }
                        }

                        override fun userInputRequired(pi: PendingIntent?, `object`: String) {
                            try {
                                callback.userInputRequired(pi, `object`)
                            } finally {
                                service.getFileBackend().deleteFile(file.asFile())
                            }
                        }
                    }
                service
                    .getHttpConnectionManager()
                    .createNewUploadConnection(file, account, false, wrapperCallback)
            }

        fileAttachmentExecutor.execute(runnable)
    }
}
