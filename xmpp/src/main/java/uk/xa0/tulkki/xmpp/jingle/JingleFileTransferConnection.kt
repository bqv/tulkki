package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.hash.Hashing
import com.google.common.primitives.Ints
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList
import java.util.Optional
import java.util.Queue
import java.util.concurrent.CountDownLatch
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.AEADBlockCipher
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.webrtc.IceCandidate
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.jingle.stanzas.FileTransferDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.IbbTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.jingle.stanzas.SocksByteStreamsTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.WebRTCDataChannelTransportInfo
import uk.xa0.tulkki.xmpp.jingle.transports.InbandBytestreamsTransport
import uk.xa0.tulkki.xmpp.jingle.transports.SocksByteStreamsTransport
import uk.xa0.tulkki.xmpp.jingle.transports.Transport
import uk.xa0.tulkki.xmpp.jingle.transports.WebRTCDataChannelTransport
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Namespace

/**
 * One Jingle file-transfer session (XEP-0234), the sender's and the receiver's side.
 *
 * Ported from Java by lane `H`. Decisions taken rather than inherited:
 *
 * 1. **The two package-private overrides are written plainly.** `AbstractJingleConnection`
 *    declares `abstract void deliverPacket(Iq)` and `abstract void notifyRebound()`
 *    package-private; Kotlin has no package-private, and javac's rule this way is that a Kotlin
 *    override emitting `protected` is *stronger* access, which is legal. Measured rather than
 *    assumed: a scratch Java base with those two package-private abstract members plus a Kotlin
 *    subclass compiles, and `javap` shows the overrides as `protected`. No declaration here is
 *    widened by source and no `internal` is involved.
 * 2. **Both constructors stay body secondaries over no primary constructor**, the `WebRTCWrapper`
 *    shape, because `message` is assigned differently in each and Java's order must hold: the
 *    `id`/`initiator` constructor runs `super`, then `findOrCreateConversation`. A private primary
 *    would have evaluated the conversation lookup before `super`.
 * 3. **`TransportSecurity`'s two fields are `ByteArray?`.** Java's unannotated `byte[]` stored
 *    whatever `KeyTransport.getKey()`/`getIv()` answered, both nullable, and the NPE happened later
 *    at the cipher; `?: throw NullPointerException()` at that site is the same NPE at the same
 *    moment.
 * 4. **`AbstractFileTransceiver`'s members are plain `val`/`var`, not `protected`.** Java's
 *    nest-mate access let the enclosing class read them; Kotlin's `protected` is subclass-only and
 *    the class is `private`, so nothing observable changes.
 * 5. **`closeTransport` and `configureTransportWithPeerInfo` are file-private top-level
 *    functions**, the shape this tree uses for Java's private statics.
 * 6. **A nullable field is read into a local before it is dereferenced** where Java assigned and
 *    then used it, so no `!!` appears; where Java dereferenced a field that could be null the NPE
 *    is spelled `?: throw NullPointerException()`.
 * 7. **The Java's unused `destination` local in `setupTransport(GenericTransportInfo)` is deleted**:
 *    `SocksByteStreamsTransportInfo.getDestinationAddress()` was read into a local nothing read.
 * 8. **`Jid + "text"` is `"" + jid + "text"`** at each of the nine sites, because Java compiled the
 *    `Jid` receiver through `String.valueOf` and Kotlin has no `plus` on the Java type.
 */
open class JingleFileTransferConnection :
    AbstractJingleConnection, Transport.Callback, Transferable {

    private lateinit var message: MessageRef

    private var initiatorFileTransferContentMap: FileTransferContentMap? = null
    private var responderFileTransferContentMap: FileTransferContentMap? = null

    private var transport: Transport? = null
    private var transportSecurity: TransportSecurity? = null
    private var fileTransceiver: AbstractFileTransceiver? = null

    private val pendingIncomingIceCandidates: Queue<IceCandidate> = LinkedList()
    private var acceptedAutomatically: Boolean = false

    constructor(jingleConnectionManager: JingleConnectionManager, message: MessageRef) : super(
        jingleConnectionManager,
        AbstractJingleConnection.Id.of(message),
        // Tulkki: the third super argument is the account's `Jid`. `master`'s d8860b8906 guarded
        // the two nullable receivers here but dropped the final `getJid()`, leaving an `AccountRef`
        // where the super constructor takes a `Jid`; this restores it.
        (message.getConversation() ?: throw NullPointerException("message has no conversation"))
            .getAccount()?.getJid()
            ?: throw NullPointerException("conversation has no account"),
    ) {
        Preconditions.checkArgument(
            message.isFileOrImage(),
            "only file or images messages can be transported via jingle",
        )
        this.message = message
        this.message.setTransferable(this)
        xmppConnectionService.markMessage(message, MessageRef.STATUS_WAITING)
    }

    constructor(
        jingleConnectionManager: JingleConnectionManager,
        id: AbstractJingleConnection.Id,
        initiator: Jid,
    ) : super(jingleConnectionManager, id, initiator) {
        val conversation =
            this.xmppConnectionService.findOrCreateConversation(
                id.account,
                id.with.asBareJid(),
                false,
                false,
            )
        this.message =
            XmppConnectionService.dataStatics()
                .newMessage(conversation, "", MessageRef.ENCRYPTION_NONE)
        this.message.setStatus(MessageRef.STATUS_RECEIVED)
        this.message.setErrorMessage(null)
        this.message.setTransferable(this)
    }

    override fun deliverPacket(iq: Iq) {
        val jingle = iq.getExtension(Jingle::class.java) ?: throw NullPointerException()
        // Java's `switch` threw on a null action; the `when` below would have fallen into `else`.
        val action = jingle.getAction() ?: throw NullPointerException()
        when (action) {
            Jingle.Action.SESSION_ACCEPT -> receiveSessionAccept(iq, jingle)
            Jingle.Action.SESSION_INITIATE -> receiveSessionInitiate(iq, jingle)
            Jingle.Action.SESSION_INFO -> receiveSessionInfo(iq, jingle)
            Jingle.Action.SESSION_TERMINATE -> receiveSessionTerminate(iq, jingle)
            Jingle.Action.TRANSPORT_ACCEPT -> receiveTransportAccept(iq, jingle)
            Jingle.Action.TRANSPORT_INFO -> receiveTransportInfo(iq, jingle)
            Jingle.Action.TRANSPORT_REPLACE -> receiveTransportReplace(iq, jingle)
            else -> {
                respondOk(iq)
                Log.d(
                    Config.LOGTAG,
                    String.format(
                        "%s: received unhandled jingle action %s",
                        id.account.getJid().asBareJid(),
                        jingle.getAction(),
                    ),
                )
            }
        }
    }

    fun sendSessionInitialize() {
        val keyTransportMessage: ListenableFuture<Optional<OmemoWire>>
        if (message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL) {
            keyTransportMessage =
                Futures.transform(
                    (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session")).prepareKeyTransportMessage(requireConversation()),
                    { Optional.of(it) },
                    MoreExecutors.directExecutor(),
                )
        } else {
            keyTransportMessage = Futures.immediateFuture(Optional.empty())
        }
        Futures.addCallback(
            keyTransportMessage,
            object : FutureCallback<Optional<OmemoWire>> {
                override fun onSuccess(xmppAxolotlMessage: Optional<OmemoWire>) {
                    sendSessionInitialize(xmppAxolotlMessage.orElse(null))
                }

                override fun onFailure(throwable: Throwable) {
                    Log.d(Config.LOGTAG, "can not send message")
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendSessionInitialize(xmppAxolotlMessage: OmemoWire?) {
        val transport = setupTransport()
        this.transport = transport
        transport.setTransportCallback(this)
        val file = xmppConnectionService.getFileBackend().getFile(message).asFile()
        val fileDescription =
            FileTransferDescription.File(
                file.length(),
                file.getName(),
                message.getMimeType(),
                emptyList(),
            )
        val transportInfoFuture = transport.asInitialTransportInfo()
        Futures.addCallback(
            transportInfoFuture,
            object : FutureCallback<Transport.InitialTransportInfo> {
                override fun onSuccess(initialTransportInfo: Transport.InitialTransportInfo) {
                    val contentMap = FileTransferContentMap.of(fileDescription, initialTransportInfo)
                    sendSessionInitialize(xmppAxolotlMessage, contentMap)
                }

                override fun onFailure(throwable: Throwable) {}
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun requireConversation(): ConversationRef {
        val conversational = message.getConversation()
        return if (conversational is ConversationRef) {
            conversational
        } else {
            throw IllegalStateException("Message had no proper conversation attached")
        }
    }

    private fun sendSessionInitialize(
        xmppAxolotlMessage: OmemoWire?,
        contentMap: FileTransferContentMap,
    ) {
        if (
            transition(
                State.SESSION_INITIALIZED,
                Runnable { this.initiatorFileTransferContentMap = contentMap },
            )
        ) {
            val iq = contentMap.toJinglePacket(Jingle.Action.SESSION_INITIATE, id.sessionId)
            val jingle = iq.getExtension(Jingle::class.java) ?: throw NullPointerException()
            if (xmppAxolotlMessage != null) {
                this.transportSecurity =
                    TransportSecurity(xmppAxolotlMessage.getInnerKey(), xmppAxolotlMessage.getIV())
                val contents = jingle.getJingleContents()
                val rawContent = contents[Iterables.getOnlyElement(contentMap.contents.keys)]
                if (rawContent != null) {
                    rawContent.setSecurity(xmppAxolotlMessage)
                }
            }
            iq.setTo(id.with)
            xmppConnectionService.sendIqPacket(
                id.account,
                iq,
            ) { response ->
                if (response.getType() == Iq.Type.RESULT) {
                    xmppConnectionService.markMessage(message, MessageRef.STATUS_OFFERED)
                } else if (response.getType() == Iq.Type.ERROR) {
                    handleIqErrorResponse(response)
                } else if (response.getType() == Iq.Type.TIMEOUT) {
                    handleIqTimeoutResponse(response)
                }
            }
            (this.transport ?: throw NullPointerException()).readyToSentAdditionalCandidates()
        }
    }

    private fun receiveSessionAccept(jinglePacket: Iq, jingle: Jingle) {
        Log.d(Config.LOGTAG, "receive file transfer session accept")
        if (isResponder()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_ACCEPT)
            return
        }
        val contentMap =
            try {
                val map = FileTransferContentMap.of(jingle)
                map.requireOnlyFileTransferDescription()
                map
            } catch (e: RuntimeException) {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                    Throwables.getRootCause(e),
                )
                respondOk(jinglePacket)
                terminateTransport()
                sendSessionTerminate(Reason.of(e), e.message)
                return
            }
        receiveSessionAccept(jinglePacket, contentMap)
    }

    private fun receiveSessionAccept(
        jinglePacket: Iq,
        contentMap: FileTransferContentMap,
    ) {
        if (
            transition(
                State.SESSION_ACCEPTED,
                Runnable { setRemoteContentMap(contentMap) },
            )
        ) {
            respondOk(jinglePacket)
            val transport = this.transport
            if (configureTransportWithPeerInfo(transport, contentMap)) {
                (transport ?: throw NullPointerException()).connect()
            } else {
                Log.e(
                    Config.LOGTAG,
                    "Transport in session accept did not match our session-initialize",
                )
                terminateTransport()
                sendSessionTerminate(
                    Reason.FAILED_APPLICATION,
                    "Transport in session accept did not match our session-initialize",
                )
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": receive out of order session-accept",
            )
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_ACCEPT)
        }
    }

    private fun receiveSessionInitiate(jinglePacket: Iq, jingle: Jingle) {
        if (isInitiator()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_INITIATE)
            return
        }
        Log.d(Config.LOGTAG, "receive session initiate $jinglePacket")
        val contentMap: FileTransferContentMap
        val file: FileTransferDescription.File
        try {
            contentMap = FileTransferContentMap.of(jingle)
            contentMap.requireContentDescriptions()
            file = contentMap.requireOnlyFile()
            // TODO check is offer
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        val keyTransportMessage: OmemoSessionPort.KeyTransport?
        val contents = jingle.getJingleContents()
        val rawContent = contents[Iterables.getOnlyElement(contentMap.contents.keys)]
        val security =
            rawContent?.getSecurity(
                jinglePacket.getFrom() ?: throw NullPointerException(),
                id.account.getOmemoSession() ?: throw NullPointerException("no omemo session"),
            )
        if (security != null) {
            Log.d(Config.LOGTAG, "found security element!")
            keyTransportMessage =
                (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session"))
                    .processReceivingKeyTransportMessage(security, false)
        } else {
            keyTransportMessage = null
        }
        receiveSessionInitiate(jinglePacket, contentMap, file, keyTransportMessage)
    }

    private fun receiveSessionInitiate(
        jinglePacket: Iq,
        contentMap: FileTransferContentMap,
        file: FileTransferDescription.File,
        keyTransportMessage: OmemoSessionPort.KeyTransport?,
    ) {
        if (
            transition(
                State.SESSION_INITIALIZED,
                Runnable { setRemoteContentMap(contentMap) },
            )
        ) {
            respondOk(jinglePacket)
            Log.d(
                Config.LOGTAG,
                "got file offer $file jet=" + (keyTransportMessage != null),
            )
            // TODO store hashes if there are any
            setFileOffer(file)
            if (keyTransportMessage != null) {
                this.transportSecurity =
                    TransportSecurity(keyTransportMessage.getKey(), keyTransportMessage.getIv())
                this.message.setFingerprint(keyTransportMessage.getFingerprint())
                this.message.setEncryption(MessageRef.ENCRYPTION_AXOLOTL)
            } else {
                this.transportSecurity = null
                this.message.setFingerprint(null)
            }
            val conversation = message.getConversation() as ConversationRef
            conversation.add(message)

            // make auto accept decision
            if (
                id.account.getRoster().getContact(id.with).showInContactList() &&
                    jingleConnectionManager.hasStoragePermission() &&
                    file.size <= this.jingleConnectionManager.getAutoAcceptFileSize() &&
                    xmppConnectionService.isDataSaverDisabled()
            ) {
                Log.d(Config.LOGTAG, "auto accepting file from " + id.with)
                this.acceptedAutomatically = true
                this.sendSessionAccept()
            } else {
                Log.d(
                    Config.LOGTAG,
                    "not auto accepting new file offer with size: " +
                        file.size +
                        " allowed size:" +
                        this.jingleConnectionManager.getAutoAcceptFileSize(),
                )
                message.markUnread()
                this.xmppConnectionService.updateConversationUi()
                this.xmppConnectionService.getNotificationService().push(message)
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": receive out of order session-initiate",
            )
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_INITIATE)
        }
    }

    private fun setFileOffer(file: FileTransferDescription.File) {
        val extension = AbstractConnectionManager.Extension.of(file.name)
        if (Transferable.VALID_CRYPTO_EXTENSIONS.contains(extension.main)) {
            this.message.setEncryption(MessageRef.ENCRYPTION_PGP)
        } else {
            this.message.setEncryption(MessageRef.ENCRYPTION_NONE)
        }
        val ext = extension.getExtension()
        val filename =
            if (ext.isNullOrEmpty()) {
                message.getUuid()
            } else {
                String.format("%s.%s", message.getUuid(), ext)
            }
        xmppConnectionService.getFileBackend().setupRelativeFilePath(message, filename ?: throw NullPointerException("message has no uuid"))
    }

    fun sendSessionAccept() {
        val contentMap = this.initiatorFileTransferContentMap
        val transport: Transport
        try {
            transport = setupTransport((contentMap ?: throw NullPointerException()).requireOnlyTransportInfo())
        } catch (e: RuntimeException) {
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        transitionOrThrow(State.SESSION_ACCEPTED)
        this.transport = transport
        transport.setTransportCallback(this)
        if (transport is WebRTCDataChannelTransport) {
            val sessionDescription = SessionDescription.of(contentMap ?: throw NullPointerException())
            transport.setInitiatorDescription(sessionDescription)
        }
        val transportInfoFuture = transport.asTransportInfo()
        Futures.addCallback(
            transportInfoFuture,
            object : FutureCallback<Transport.TransportInfo> {
                override fun onSuccess(transportInfo: Transport.TransportInfo) {
                    val responderContentMap = contentMap.withTransport(transportInfo)
                    sendSessionAccept(responderContentMap)
                }

                override fun onFailure(throwable: Throwable) {
                    failureToAcceptSession(throwable)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendSessionAccept(contentMap: FileTransferContentMap) {
        setLocalContentMap(contentMap)
        val iq = contentMap.toJinglePacket(Jingle.Action.SESSION_ACCEPT, id.sessionId)
        send(iq)
        // this needs to come after session-accept or else our candidate-error might arrive first
        val transport = this.transport ?: throw NullPointerException()
        transport.connect()
        transport.readyToSentAdditionalCandidates()
        if (transport is WebRTCDataChannelTransport) {
            drainPendingIncomingIceCandidates(transport)
        }
    }

    private fun drainPendingIncomingIceCandidates(
        webRTCDataChannelTransport: WebRTCDataChannelTransport,
    ) {
        while (this.pendingIncomingIceCandidates.peek() != null) {
            val candidate = this.pendingIncomingIceCandidates.poll()
            if (candidate == null) {
                continue
            }
            webRTCDataChannelTransport.addIceCandidates(ImmutableList.of(candidate))
        }
    }

    private fun setupTransport(transportInfo: GenericTransportInfo): Transport {
        val xmppConnection: XmppConnection =
            id.account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")
        val appSettings =
            XmppConnectionService.dataStatics()
                .settings(xmppConnectionService.getApplicationContext())
        val useTor = id.account.isOnion() || xmppConnectionService.useTorToConnect()
        val useI2P = id.account.isI2P() || xmppConnectionService.useI2PToConnect()
        val useRelays = appSettings.isUseRelays()
        if (transportInfo is IbbTransportInfo) {
            val streamId = transportInfo.getTransportId()
            val blockSize = transportInfo.getBlockSize()
            if (streamId == null || blockSize == null) {
                throw IllegalStateException("ibb transport is missing sid and/or block-size")
            }
            return InbandBytestreamsTransport(
                xmppConnection,
                id.with,
                isInitiator(),
                streamId,
                Ints.saturatedCast(blockSize),
            )
        } else if (transportInfo is SocksByteStreamsTransportInfo) {
            val streamId = transportInfo.getTransportId()
            val candidates = transportInfo.getCandidates()
            Log.d(Config.LOGTAG, "received socks candidates $candidates")
            return SocksByteStreamsTransport(
                xmppConnection,
                id,
                isInitiator(),
                useTor,
                useI2P,
                useRelays,
                streamId ?: throw NullPointerException(),
                candidates,
            )
        } else if (!useTor && !useI2P && transportInfo is WebRTCDataChannelTransportInfo) {
            return WebRTCDataChannelTransport(
                xmppConnectionService.getApplicationContext(),
                xmppConnection,
                id.account,
                isInitiator(),
            )
        } else {
            throw IllegalArgumentException("Do not know how to create transport")
        }
    }

    private fun setupTransport(): Transport {
        val xmppConnection: XmppConnection =
            id.account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")
        val appSettings =
            XmppConnectionService.dataStatics()
                .settings(xmppConnectionService.getApplicationContext())
        val useTor = id.account.isOnion() || xmppConnectionService.useTorToConnect()
        val useI2P = id.account.isI2P() || xmppConnectionService.useI2PToConnect()
        val useRelays = appSettings.isUseRelays()
        if (!useTor && remoteHasFeature(Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL)) {
            return WebRTCDataChannelTransport(
                xmppConnectionService.getApplicationContext(),
                xmppConnection,
                id.account,
                isInitiator(),
            )
        }
        // for connections we initialize we just don't use S5B when 'use relays' is enabled
        // for incoming connections we might as well try but stick to our proxy candidate
        if (!useRelays && remoteHasFeature(Namespace.JINGLE_TRANSPORTS_S5B)) {
            return SocksByteStreamsTransport(
                xmppConnection,
                id,
                isInitiator(),
                useTor,
                useI2P,
                true,
            )
        }
        return setupLastResortTransport()
    }

    private fun setupLastResortTransport(): Transport {
        val xmppConnection: XmppConnection =
            id.account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")
        return InbandBytestreamsTransport(xmppConnection, id.with, isInitiator())
    }

    private fun failureToAcceptSession(throwable: Throwable) {
        if (isTerminated()) {
            return
        }
        terminateTransport()
        val rootCause = Throwables.getRootCause(throwable)
        Log.d(Config.LOGTAG, "unable to send session accept", rootCause)
        sendSessionTerminate(Reason.ofThrowable(rootCause), rootCause.message)
    }

    private fun receiveSessionInfo(jinglePacket: Iq, jingle: Jingle) {
        respondOk(jinglePacket)
        val sessionInfo = FileTransferDescription.getSessionInfo(jingle)
        if (sessionInfo is FileTransferDescription.Checksum) {
            receiveSessionInfoChecksum(sessionInfo)
        } else if (sessionInfo is FileTransferDescription.Received) {
            receiveSessionInfoReceived(sessionInfo)
        }
    }

    private fun receiveSessionInfoChecksum(checksum: FileTransferDescription.Checksum) {
        Log.d(Config.LOGTAG, "received checksum $checksum")
        // TODO check that we are receiver
        // TODO store hashes
    }

    private fun receiveSessionInfoReceived(received: FileTransferDescription.Received) {
        Log.d(Config.LOGTAG, "peer confirmed received $received")
        // TODO check that we are sender
    }

    @Synchronized
    private fun receiveSessionTerminate(jinglePacket: Iq, jingle: Jingle) {
        respondOk(jinglePacket)
        val wrapper = jingle.getReason()
        val previous = this.state
        val text = wrapper.text
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": received session terminate reason=" +
                wrapper.reason +
                "(" +
                (text ?: "") +
                ") while in state " +
                previous,
        )
        if (TERMINATED.contains(previous)) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": ignoring session terminate because already in " +
                    previous,
            )
            return
        }
        if (isInitiator()) {
            this.message.setErrorMessage(
                if (text.isNullOrEmpty()) wrapper.reason.toString() else text,
            )
        }
        terminateTransport()
        val target = reasonToState(wrapper.reason)
        transitionOrThrow(target)
        finish()
    }

    private fun receiveTransportAccept(jinglePacket: Iq, jingle: Jingle) {
        if (isResponder()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.TRANSPORT_ACCEPT)
            return
        }
        Log.d(Config.LOGTAG, "receive transport accept $jinglePacket")
        val transportInfo =
            try {
                FileTransferContentMap.of(jingle).requireOnlyTransportInfo()
            } catch (e: RuntimeException) {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                    Throwables.getRootCause(e),
                )
                respondOk(jinglePacket)
                terminateTransport()
                sendSessionTerminate(Reason.of(e), e.message)
                return
            }
        if (isInState(State.SESSION_ACCEPTED)) {
            val group = jingle.getGroup()
            receiveTransportAccept(jinglePacket, Transport.TransportInfo(transportInfo, group))
        } else {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.TRANSPORT_ACCEPT)
        }
    }

    private fun receiveTransportAccept(
        jinglePacket: Iq,
        transportInfo: Transport.TransportInfo,
    ) {
        val remoteContentMap = getRemoteContentMap().withTransport(transportInfo)
        setRemoteContentMap(remoteContentMap)
        respondOk(jinglePacket)
        val transport = this.transport
        if (configureTransportWithPeerInfo(transport, remoteContentMap)) {
            (transport ?: throw NullPointerException()).connect()
        } else {
            Log.e(
                Config.LOGTAG,
                "Transport in transport-accept did not match our transport-replace",
            )
            terminateTransport()
            sendSessionTerminate(
                Reason.FAILED_APPLICATION,
                "Transport in transport-accept did not match our transport-replace",
            )
        }
    }

    private fun receiveTransportInfo(jinglePacket: Iq, jingle: Jingle) {
        val contentMap: FileTransferContentMap
        val transportInfo: GenericTransportInfo
        try {
            contentMap = FileTransferContentMap.of(jingle)
            transportInfo = contentMap.requireOnlyTransportInfo()
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            terminateTransport()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        respondOk(jinglePacket)
        val transport = this.transport
        if (transport is SocksByteStreamsTransport && transportInfo is SocksByteStreamsTransportInfo) {
            receiveTransportInfo(transport, transportInfo)
        } else if (
            transport is WebRTCDataChannelTransport &&
                transportInfo is WebRTCDataChannelTransportInfo
        ) {
            receiveTransportInfo(
                Iterables.getOnlyElement(contentMap.contents.keys),
                transport,
                transportInfo,
            )
        } else if (transportInfo is WebRTCDataChannelTransportInfo) {
            receiveTransportInfo(
                Iterables.getOnlyElement(contentMap.contents.keys),
                transportInfo,
            )
        } else {
            Log.d(Config.LOGTAG, "could not deliver transport-info to transport")
        }
    }

    private fun receiveTransportInfo(
        contentName: String,
        webRTCDataChannelTransport: WebRTCDataChannelTransport,
        webRTCDataChannelTransportInfo: WebRTCDataChannelTransportInfo,
    ) {
        val credentials =
            webRTCDataChannelTransportInfo.getCredentials() ?: throw NullPointerException()
        val iceCandidates =
            WebRTCDataChannelTransport.iceCandidatesOf(
                contentName,
                credentials,
                webRTCDataChannelTransportInfo.getCandidates(),
            )
        val localContentMap = getLocalContentMap()
        if (localContentMap == null) {
            Log.d(Config.LOGTAG, "transport not ready. add pending ice candidate")
            this.pendingIncomingIceCandidates.addAll(iceCandidates)
        } else {
            webRTCDataChannelTransport.addIceCandidates(iceCandidates)
        }
    }

    private fun receiveTransportInfo(
        contentName: String,
        webRTCDataChannelTransportInfo: WebRTCDataChannelTransportInfo,
    ) {
        val credentials =
            webRTCDataChannelTransportInfo.getCredentials() ?: throw NullPointerException()
        val iceCandidates =
            WebRTCDataChannelTransport.iceCandidatesOf(
                contentName,
                credentials,
                webRTCDataChannelTransportInfo.getCandidates(),
            )
        this.pendingIncomingIceCandidates.addAll(iceCandidates)
    }

    private fun receiveTransportInfo(
        socksBytestreamsTransport: SocksByteStreamsTransport,
        socksBytestreamsTransportInfo: SocksByteStreamsTransportInfo,
    ) {
        val transportInfo = socksBytestreamsTransportInfo.getTransportInfo()
        if (transportInfo is SocksByteStreamsTransportInfo.CandidateError) {
            socksBytestreamsTransport.setCandidateError()
        } else if (transportInfo is SocksByteStreamsTransportInfo.CandidateUsed) {
            if (!socksBytestreamsTransport.setCandidateUsed(transportInfo.cid)) {
                terminateTransport()
                sendSessionTerminate(
                    Reason.FAILED_TRANSPORT,
                    String.format(
                        "Peer is not connected to our candidate %s",
                        transportInfo.cid,
                    ),
                )
            }
        } else if (transportInfo is SocksByteStreamsTransportInfo.Activated) {
            socksBytestreamsTransport.setProxyActivated(transportInfo.cid ?: throw NullPointerException())
        } else if (transportInfo is SocksByteStreamsTransportInfo.ProxyError) {
            socksBytestreamsTransport.setProxyError()
        }
    }

    private fun receiveTransportReplace(jinglePacket: Iq, jingle: Jingle) {
        if (isInitiator()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.TRANSPORT_REPLACE)
            return
        }
        val transportInfo =
            try {
                FileTransferContentMap.of(jingle).requireOnlyTransportInfo()
            } catch (e: RuntimeException) {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                    Throwables.getRootCause(e),
                )
                respondOk(jinglePacket)
                terminateTransport()
                sendSessionTerminate(Reason.of(e), e.message)
                return
            }
        if (isInState(State.SESSION_ACCEPTED)) {
            receiveTransportReplace(jinglePacket, transportInfo)
        } else {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.TRANSPORT_REPLACE)
        }
    }

    private fun receiveTransportReplace(
        jinglePacket: Iq,
        transportInfo: GenericTransportInfo,
    ) {
        respondOk(jinglePacket)
        val currentTransport = this.transport
        if (currentTransport != null) {
            Log.d(
                Config.LOGTAG,
                "terminating " +
                    currentTransport.javaClass.simpleName +
                    " upon receiving transport-replace",
            )
            // The Java passed a null callback here. `Transport.setTransportCallback`'s parameter
            // is non-null, so the Java's call threw the NPE in the callee; Kotlin cannot pass null
            // at all, so the same throw is written where the Java's argument was.
            currentTransport.setTransportCallback(throw NullPointerException())
            currentTransport.terminate()
        }
        val nextTransport =
            try {
                setupTransport(transportInfo)
            } catch (e: RuntimeException) {
                sendSessionTerminate(Reason.of(e), e.message)
                return
            }
        this.transport = nextTransport
        Log.d(
            Config.LOGTAG,
            "replacing transport with " + nextTransport.javaClass.simpleName,
        )
        nextTransport.setTransportCallback(this)
        val transportInfoFuture = nextTransport.asTransportInfo()
        Futures.addCallback(
            transportInfoFuture,
            object : FutureCallback<Transport.TransportInfo> {
                override fun onSuccess(transportWrapper: Transport.TransportInfo) {
                    val contentMap =
                        (getLocalContentMap() ?: throw NullPointerException())
                            .withTransport(transportWrapper)
                    sendTransportAccept(contentMap)
                }

                override fun onFailure(throwable: Throwable) {
                    // transition into application failed (analogues to failureToAccept
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendTransportAccept(contentMap: FileTransferContentMap) {
        setLocalContentMap(contentMap)
        val iq =
            contentMap
                .transportInfo()
                .toJinglePacket(Jingle.Action.TRANSPORT_ACCEPT, id.sessionId)
        send(iq)
        (transport ?: throw NullPointerException()).connect()
    }

    protected fun sendSessionTerminate(reason: Reason, text: String?) {
        if (isInitiator()) {
            this.message.setErrorMessage(if (text.isNullOrEmpty()) reason.toString() else text)
        }
        sendSessionTerminate(reason, text, null)
    }

    private fun getLocalContentMap(): FileTransferContentMap? =
        if (isInitiator()) {
            this.initiatorFileTransferContentMap
        } else {
            this.responderFileTransferContentMap
        }

    private fun getRemoteContentMap(): FileTransferContentMap =
        (
            if (isInitiator()) {
                this.responderFileTransferContentMap
            } else {
                this.initiatorFileTransferContentMap
            }
        ) ?: throw NullPointerException()

    private fun setLocalContentMap(contentMap: FileTransferContentMap) {
        if (isInitiator()) {
            this.initiatorFileTransferContentMap = contentMap
        } else {
            this.responderFileTransferContentMap = contentMap
        }
    }

    private fun setRemoteContentMap(contentMap: FileTransferContentMap) {
        if (isInitiator()) {
            this.responderFileTransferContentMap = contentMap
        } else {
            this.initiatorFileTransferContentMap = contentMap
        }
    }

    fun getTransport(): Transport? = this.transport

    override fun terminateTransport() {
        val transport = this.transport
        if (transport == null) {
            return
        }
        // TODO consider setting transport callback to null. requires transport to handle null
        // callback
        // transport.setTransportCallback(null);
        transport.terminate()
        this.transport = null
    }

    override fun notifyRebound() {}

    override fun onTransportEstablished() {
        Log.d(Config.LOGTAG, "transport established")
        val fileTransceiver =
            try {
                setupTransceiver(isResponder())
            } catch (e: Exception) {
                terminateTransport()
                if (isTerminated()) {
                    Log.d(
                        Config.LOGTAG,
                        "failed to set up file transceiver but session has already been" +
                            " terminated",
                    )
                } else {
                    Log.d(Config.LOGTAG, "failed to set up file transceiver", e)
                    sendSessionTerminate(Reason.ofThrowable(e), e.message)
                }
                return
            }
        this.fileTransceiver = fileTransceiver
        val fileTransceiverThread = Thread(fileTransceiver)
        fileTransceiverThread.start()
        Futures.addCallback(
            fileTransceiver.complete,
            object : FutureCallback<List<FileTransferDescription.Hash>> {
                override fun onSuccess(hashes: List<FileTransferDescription.Hash>) {
                    onFileTransmissionComplete(hashes)
                }

                override fun onFailure(throwable: Throwable) {
                    // The state transition in here should be synchronized to not race with the
                    // state transition in receiveSessionTerminate
                    synchronized(this@JingleFileTransferConnection) {
                        onFileTransmissionFailed(throwable)
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun onFileTransmissionComplete(hashes: List<FileTransferDescription.Hash>) {
        // TODO if we ever support receiving files this should become isSending(); isReceiving()
        if (isInitiator()) {
            sendSessionInfoChecksum(hashes)
        } else {
            Log.d(Config.LOGTAG, "file transfer complete $hashes")
            // TODO compare with stored file hashes
            sendFileSessionInfoReceived()
            terminateTransport()
            messageReceivedSuccess()
            sendSessionTerminate(Reason.SUCCESS, null)
        }
    }

    private fun messageReceivedSuccess() {
        this.message.setTransferable(null)
        xmppConnectionService.getFileBackend().updateFileParams(message)
        (xmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message)
        val file = xmppConnectionService.getFileBackend().getFile(message).asFile()
        if (acceptedAutomatically) {
            message.markUnread()
            if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
                (id.account.getPgpDecryptionService() ?: throw NullPointerException("no pgp decryption service")).decryptMessage(message, true)
            } else {
                xmppConnectionService
                    .getFileBackend()
                    .updateMediaScanner(
                        file,
                        Runnable {
                            xmppConnectionService.getNotificationService().push(message)
                        },
                    )
            }
        } else if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
            (id.account.getPgpDecryptionService() ?: throw NullPointerException("no pgp decryption service")).decryptMessage(message, false)
        } else {
            xmppConnectionService.getFileBackend().updateMediaScanner(file)
        }
    }

    private fun onFileTransmissionFailed(throwable: Throwable) {
        if (isTerminated()) {
            Log.d(
                Config.LOGTAG,
                "file transfer failed but session is already terminated",
                throwable,
            )
        } else {
            terminateTransport()
            Log.d(Config.LOGTAG, "on file transmission failed", throwable)
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR, null)
        }
    }

    private fun setupTransceiver(receiving: Boolean): AbstractFileTransceiver {
        val transport = this.transport ?: throw IOException("No transport configured")
        val fileDescription =
            (getLocalContentMap() ?: throw NullPointerException()).requireOnlyFile()
        val file = xmppConnectionService.getFileBackend().getFile(message).asFile()
        val updateRunnable = Runnable { jingleConnectionManager.updateConversationUi(false) }
        return if (receiving) {
            FileReceiver(
                file,
                this.transportSecurity,
                transport.getInputStream(),
                transport.getTerminationLatch(),
                fileDescription.size,
                updateRunnable,
            )
        } else {
            FileTransmitter(
                file,
                this.transportSecurity,
                transport.getOutputStream(),
                transport.getTerminationLatch(),
                fileDescription.size,
                updateRunnable,
            )
        }
    }

    private fun sendFileSessionInfoReceived() {
        val contentMap = getLocalContentMap() ?: throw NullPointerException()
        val name = Iterables.getOnlyElement(contentMap.contents.keys)
        sendSessionInfo(FileTransferDescription.Received(name))
    }

    private fun sendSessionInfoChecksum(hashes: List<FileTransferDescription.Hash>) {
        val contentMap = getLocalContentMap() ?: throw NullPointerException()
        val name = Iterables.getOnlyElement(contentMap.contents.keys)
        sendSessionInfo(FileTransferDescription.Checksum(name, hashes))
    }

    private fun sendSessionInfo(sessionInfo: FileTransferDescription.SessionInfo) {
        val iq = Iq(Iq.Type.SET)
        val jinglePacket =
            iq.addExtension(Jingle(Jingle.Action.SESSION_INFO, this.id.sessionId))
        jinglePacket.addChild(sessionInfo.asElement())
        send(iq)
    }

    override fun onTransportSetupFailed() {
        val transport = this.transport
        if (transport == null) {
            synchronized(this) {
                // this can happen on IQ timeouts
                if (isTerminated()) {
                    return
                }
                sendSessionTerminate(Reason.FAILED_APPLICATION, null)
            }
            return
        }
        Log.d(Config.LOGTAG, "onTransportSetupFailed")
        val isTransportInBand = transport is InbandBytestreamsTransport
        if (isTransportInBand) {
            terminateTransport()
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR, "Failed to setup IBB transport")
            return
        }
        // terminate the current transport
        transport.terminate()
        if (isInitiator()) {
            val lastResort = setupLastResortTransport()
            this.transport = lastResort
            Log.d(
                Config.LOGTAG,
                "replacing transport with " + lastResort.javaClass.simpleName,
            )
            lastResort.setTransportCallback(this)
            val transportInfoFuture = lastResort.asTransportInfo()
            Futures.addCallback(
                transportInfoFuture,
                object : FutureCallback<Transport.TransportInfo> {
                    override fun onSuccess(transportWrapper: Transport.TransportInfo) {
                        val contentMap = getLocalContentMap() ?: throw NullPointerException()
                        sendTransportReplace(contentMap.withTransport(transportWrapper))
                    }

                    override fun onFailure(throwable: Throwable) {
                        // TODO send application failure;
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } else {
            Log.d(Config.LOGTAG, "transport setup failed. waiting for initiator to replace")
        }
    }

    private fun sendTransportReplace(contentMap: FileTransferContentMap) {
        setLocalContentMap(contentMap)
        val iq =
            contentMap
                .transportInfo()
                .toJinglePacket(Jingle.Action.TRANSPORT_REPLACE, id.sessionId)
        send(iq)
    }

    override fun onAdditionalCandidate(contentName: String, candidate: Transport.Candidate?) {
        if (candidate is IceUdpTransportInfo.Candidate) {
            sendTransportInfo(contentName, candidate)
        }
    }

    fun sendTransportInfo(
        contentName: String,
        candidate: IceUdpTransportInfo.Candidate,
    ) {
        val transportInfo: FileTransferContentMap
        try {
            val rtpContentMap = getLocalContentMap() ?: throw NullPointerException()
            transportInfo = rtpContentMap.transportInfo(contentName, candidate)
        } catch (e: Exception) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to prepare transport-info from candidate for content=" +
                    contentName,
            )
            return
        }
        val iq = transportInfo.toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        send(iq)
    }

    override fun onCandidateUsed(
        streamId: String,
        candidate: SocksByteStreamsTransport.Candidate,
    ) {
        val contentMap = getLocalContentMap()
        if (contentMap == null) {
            Log.e(Config.LOGTAG, "local content map is null on candidate used")
            return
        }
        val iq =
            contentMap
                .candidateUsed(streamId, candidate.cid)
                .toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        Log.d(Config.LOGTAG, "sending candidate used $iq")
        send(iq)
    }

    override fun onCandidateError(streamId: String) {
        val contentMap = getLocalContentMap()
        if (contentMap == null) {
            Log.e(Config.LOGTAG, "local content map is null on candidate used")
            return
        }
        val iq =
            contentMap
                .candidateError(streamId)
                .toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        Log.d(Config.LOGTAG, "sending candidate error $iq")
        send(iq)
    }

    override fun onProxyActivated(
        streamId: String,
        candidate: SocksByteStreamsTransport.Candidate,
    ) {
        val contentMap = getLocalContentMap()
        if (contentMap == null) {
            Log.e(Config.LOGTAG, "local content map is null on candidate used")
            return
        }
        val iq =
            contentMap
                .proxyActivated(streamId, candidate.cid)
                .toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        send(iq)
    }

    override fun transition(target: State, runnable: Runnable?): Boolean {
        val transitioned = super.transition(target, runnable)
        if (transitioned && isInitiator()) {
            Log.d(Config.LOGTAG, "running mark message hooks")
            if (target == State.SESSION_ACCEPTED) {
                xmppConnectionService.markMessage(message, MessageRef.STATUS_UNSEND)
            } else if (target == State.TERMINATED_SUCCESS) {
                xmppConnectionService.markMessage(message, MessageRef.STATUS_SEND_RECEIVED)
            } else if (TERMINATED.contains(target)) {
                xmppConnectionService.markMessage(
                    message,
                    MessageRef.STATUS_SEND_FAILED,
                    message.getErrorMessage(),
                )
            } else {
                xmppConnectionService.updateConversationUi()
            }
        } else {
            if (
                listOf(
                    State.TERMINATED_CANCEL_OR_TIMEOUT,
                    State.TERMINATED_DECLINED_OR_BUSY,
                ).contains(target)
            ) {
                // Tulkki: C5-C - the placeholder is `:data`'s class and an interface cannot be
                // `new`ed, so its construction is a `DataStatics` member, the same device
                // `newMessage` uses. The class itself stays where it is: `:ui`'s
                // `ConversationFragment` tests `instanceof TransferablePlaceholder`, which is a
                // legal `:ui -> :data` read.
                this.message.setTransferable(
                    XmppConnectionService.dataStatics()
                        .newTransferablePlaceholder(Transferable.STATUS_CANCELLED),
                )
            } else if (target != State.TERMINATED_SUCCESS && TERMINATED.contains(target)) {
                this.message.setTransferable(
                    XmppConnectionService.dataStatics()
                        .newTransferablePlaceholder(Transferable.STATUS_FAILED),
                )
            }
            xmppConnectionService.updateConversationUi()
        }
        return transitioned
    }

    override fun finish() {
        if (transport != null) {
            throw AssertionError(
                "finish MUST not be called without terminating the transport first",
            )
        }
        // Tulkki: C5-C - the comment used to name `TransferablePlaceholder` to say "do not null the
        // placeholder the message carries". It cannot say that by name any more (this file no
        // longer imports the class), and it does not need to: the guard is an `instanceof` on
        // *this* class, so a placeholder - whatever built it - is left alone and only our own
        // transferable is cleared. Same code, same meaning, and the name now lives only where the
        // class is tested.
        if (message.getTransferable() is JingleFileTransferConnection) {
            Log.d(Config.LOGTAG, "nulling transferable on message")
            this.message.setTransferable(null)
        }
        super.finish()
    }

    private fun getTransferableStatus(): Int {
        // status in file transfer is a bit weird. for sending it is mostly handled via
        // Message.STATUS_* (offered, unsend (sic) send_received) the transferable status is just
        // uploading
        // for receiving the message status remains at 'received' but Transferable goes through
        // various status
        if (isInitiator()) {
            return Transferable.STATUS_UPLOADING
        }
        val state = getState()
        return when (state) {
            State.NULL, State.SESSION_INITIALIZED, State.SESSION_INITIALIZED_PRE_APPROVED ->
                Transferable.STATUS_OFFER
            State.TERMINATED_APPLICATION_FAILURE,
            State.TERMINATED_CONNECTIVITY_ERROR,
            State.TERMINATED_DECLINED_OR_BUSY,
            State.TERMINATED_SECURITY_ERROR -> Transferable.STATUS_FAILED
            State.TERMINATED_CANCEL_OR_TIMEOUT -> Transferable.STATUS_CANCELLED
            State.SESSION_ACCEPTED -> Transferable.STATUS_DOWNLOADING
            else -> Transferable.STATUS_UNKNOWN
        }
    }

    // these methods are for interacting with 'Transferable' - we might want to remove the concept
    // at some point

    override fun start(): Boolean {
        Log.d(Config.LOGTAG, "user pressed start()")
        // TODO there is a 'connected' check apparently?
        if (isInState(State.SESSION_INITIALIZED)) {
            sendSessionAccept()
        }
        return true
    }

    override fun getStatus(): Int = getTransferableStatus()

    override fun getFileSize(): Long? {
        val transceiver = this.fileTransceiver
        if (transceiver != null) {
            return transceiver.total
        }
        val contentMap = this.initiatorFileTransferContentMap
        if (contentMap != null) {
            return contentMap.requireOnlyFile().size
        }
        return null
    }

    override fun getProgress(): Int {
        val transceiver = this.fileTransceiver
        return if (transceiver != null) transceiver.getProgress() else 0
    }

    override fun cancel() {
        if (stopFileTransfer()) {
            Log.d(Config.LOGTAG, "user has stopped file transfer")
        } else {
            Log.d(Config.LOGTAG, "user pressed cancel but file transfer was already terminated?")
        }
    }

    private fun stopFileTransfer(): Boolean =
        if (isInitiator()) {
            stopFileTransfer(Reason.CANCEL)
        } else {
            stopFileTransfer(Reason.DECLINE)
        }

    private fun stopFileTransfer(reason: Reason): Boolean {
        val target = reasonToState(reason)
        if (transition(target)) {
            // we change state before terminating transport so we don't consume the following
            // IOException and turn it into a connectivity error

            if (isInitiator() && reason == Reason.CANCEL) {
                // message hooks have already run so we need to mark to persist the 'cancelled'
                // status
                xmppConnectionService.markMessage(
                    message,
                    MessageRef.STATUS_SEND_FAILED,
                    MessageRef.ERROR_MESSAGE_CANCELLED,
                )
            }
            terminateTransport()
            val iq = Iq(Iq.Type.SET)
            val jingle =
                iq.addExtension(Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId))
            jingle.setReason(reason, "User requested to stop file transfer")
            send(iq)
            finish()
            return true
        } else {
            return false
        }
    }

    private abstract class AbstractFileTransceiver(
        val file: File,
        val transportSecurity: TransportSecurity?,
        val transportTerminationLatch: CountDownLatch,
        total: Long,
        private val updateRunnable: Runnable,
    ) : Runnable {

        val complete: SettableFuture<List<FileTransferDescription.Hash>> =
            SettableFuture.create<List<FileTransferDescription.Hash>>()

        val total: Long

        var transmitted: Long = 0
        private var progress: Int = Int.MIN_VALUE

        init {
            this.total = if (transportSecurity == null) total else total + 16
        }

        fun getProgress(): Int =
            Ints.saturatedCast(Math.round((1.0 * transmitted / total) * 100))

        fun updateProgress() {
            val current = getProgress()
            val update: Boolean
            synchronized(this) {
                if (this.progress != current) {
                    this.progress = current
                    update = true
                } else {
                    update = false
                }
                if (update) {
                    this.updateRunnable.run()
                }
            }
        }

        fun awaitTransportTermination() {
            try {
                this.transportTerminationLatch.await()
            } catch (ignored: InterruptedException) {
                return
            }
            Log.d(Config.LOGTAG, javaClass.simpleName + " says Goodbye!")
        }
    }

    private class FileTransmitter(
        file: File,
        transportSecurity: TransportSecurity?,
        private val outputStream: OutputStream,
        transportTerminationLatch: CountDownLatch,
        total: Long,
        updateRunnable: Runnable,
    ) : AbstractFileTransceiver(
        file,
        transportSecurity,
        transportTerminationLatch,
        total,
        updateRunnable,
    ) {

        @Throws(FileNotFoundException::class)
        private fun openFileInputStream(): InputStream {
            val fileInputStream = FileInputStream(this.file)
            return if (this.transportSecurity == null) {
                fileInputStream
            } else {
                val cipher: AEADBlockCipher = GCMBlockCipher(AESEngine())
                cipher.init(
                    true,
                    AEADParameters(
                        KeyParameter(this.transportSecurity.key ?: throw NullPointerException()),
                        128,
                        this.transportSecurity.iv ?: throw NullPointerException(),
                    ),
                )
                Log.d(Config.LOGTAG, "setting up CipherInputStream")
                CipherInputStream(fileInputStream, cipher)
            }
        }

        override fun run() {
            Log.d(Config.LOGTAG, "file transmitter attempting to send $total bytes")
            val sha1Hasher = Hashing.sha1().newHasher()
            val sha256Hasher = Hashing.sha256().newHasher()
            try {
                openFileInputStream().use { fileInputStream ->
                    val buffer = ByteArray(4096)
                    while (total - transmitted > 0) {
                        val count = fileInputStream.read(buffer)
                        if (count == -1) {
                            throw EOFException("reached EOF after $transmitted/$total")
                        }
                        outputStream.write(buffer, 0, count)
                        sha1Hasher.putBytes(buffer, 0, count)
                        sha256Hasher.putBytes(buffer, 0, count)
                        transmitted += count
                        updateProgress()
                    }
                    outputStream.flush()
                    Log.d(
                        Config.LOGTAG,
                        "transmitted $transmitted bytes from " + file.getAbsolutePath(),
                    )
                    val hashes: List<FileTransferDescription.Hash> =
                        ImmutableList.of(
                            FileTransferDescription.Hash(
                                sha1Hasher.hash().asBytes(),
                                FileTransferDescription.Algorithm.SHA_1,
                            ),
                            FileTransferDescription.Hash(
                                sha256Hasher.hash().asBytes(),
                                FileTransferDescription.Algorithm.SHA_256,
                            ),
                        )
                    complete.set(hashes)
                }
            } catch (e: Exception) {
                complete.setException(e)
            }
            // the transport implementations backed by PipedOutputStreams do not like it when
            // the writing Thread (this thread) goes away. so we just wait until the other peer
            // has received our file and we are shutting down the transport
            Log.d(Config.LOGTAG, "waiting for transport to terminate before stopping thread")
            awaitTransportTermination()
            closeTransport(outputStream)
        }
    }

    private class FileReceiver(
        file: File,
        transportSecurity: TransportSecurity?,
        private val inputStream: InputStream,
        transportTerminationLatch: CountDownLatch,
        total: Long,
        updateRunnable: Runnable,
    ) : AbstractFileTransceiver(
        file,
        transportSecurity,
        transportTerminationLatch,
        total,
        updateRunnable,
    ) {

        @Throws(FileNotFoundException::class)
        private fun openFileOutputStream(): OutputStream {
            val directory = this.file.getParentFile()
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created directory " + directory.getAbsolutePath())
            }
            val fileOutputStream = FileOutputStream(this.file)
            return if (this.transportSecurity == null) {
                fileOutputStream
            } else {
                val cipher: AEADBlockCipher = GCMBlockCipher(AESEngine())
                cipher.init(
                    false,
                    AEADParameters(
                        KeyParameter(this.transportSecurity.key ?: throw NullPointerException()),
                        128,
                        this.transportSecurity.iv ?: throw NullPointerException(),
                    ),
                )
                Log.d(Config.LOGTAG, "setting up CipherOutputStream")
                CipherOutputStream(fileOutputStream, cipher)
            }
        }

        override fun run() {
            Log.d(Config.LOGTAG, "file receiver attempting to receive $total bytes")
            val sha1Hasher = Hashing.sha1().newHasher()
            val sha256Hasher = Hashing.sha256().newHasher()
            try {
                openFileOutputStream().use { fileOutputStream ->
                    val buffer = ByteArray(4096)
                    while (total - transmitted > 0) {
                        val count = inputStream.read(buffer)
                        if (count == -1) {
                            throw EOFException("reached EOF after $transmitted/$total")
                        }
                        fileOutputStream.write(buffer, 0, count)
                        sha1Hasher.putBytes(buffer, 0, count)
                        sha256Hasher.putBytes(buffer, 0, count)
                        transmitted += count
                        updateProgress()
                    }
                    Log.d(
                        Config.LOGTAG,
                        "written $transmitted bytes to " + file.getAbsolutePath(),
                    )
                    val hashes: List<FileTransferDescription.Hash> =
                        ImmutableList.of(
                            FileTransferDescription.Hash(
                                sha1Hasher.hash().asBytes(),
                                FileTransferDescription.Algorithm.SHA_1,
                            ),
                            FileTransferDescription.Hash(
                                sha256Hasher.hash().asBytes(),
                                FileTransferDescription.Algorithm.SHA_256,
                            ),
                        )
                    complete.set(hashes)
                }
            } catch (e: Exception) {
                complete.setException(e)
            }
            Log.d(Config.LOGTAG, "waiting for transport to terminate before stopping thread")
            awaitTransportTermination()
            closeTransport(inputStream)
        }
    }

    private class TransportSecurity(val key: ByteArray?, val iv: ByteArray?)
}

private fun closeTransport(stream: Closeable) {
    try {
        stream.close()
    } catch (e: IOException) {
        Log.d(Config.LOGTAG, "transport has already been closed. good")
    }
}

private fun configureTransportWithPeerInfo(
    transport: Transport?,
    contentMap: FileTransferContentMap,
): Boolean {
    val transportInfo = contentMap.requireOnlyTransportInfo()
    if (
        transport is WebRTCDataChannelTransport &&
            transportInfo is WebRTCDataChannelTransportInfo
    ) {
        transport.setResponderDescription(SessionDescription.of(contentMap))
        return true
    } else if (
        transport is SocksByteStreamsTransport && transportInfo is SocksByteStreamsTransportInfo
    ) {
        transport.setTheirCandidates(transportInfo.getCandidates())
        return true
    } else if (
        transport is InbandBytestreamsTransport && transportInfo is IbbTransportInfo
    ) {
        val peerBlockSize = transportInfo.getBlockSize()
        if (peerBlockSize != null) {
            transport.setPeerBlockSize(peerBlockSize)
        }
        return true
    } else {
        return false
    }
}
