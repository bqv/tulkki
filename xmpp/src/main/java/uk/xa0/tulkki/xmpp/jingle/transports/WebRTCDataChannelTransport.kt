package uk.xa0.tulkki.xmpp.jingle.transports

import android.content.Context
import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.io.Closeables
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.Collections
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.jingle.IceServers
import uk.xa0.tulkki.xmpp.jingle.WebRTCWrapper
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.WebRTCDataChannelTransportInfo
import uk.xa0.tulkki.xmpp.models.disco.external.Services
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The DTLS/SCTP data-channel transport (`urn:xmpp:jingle:transports:dtls-sctp:1`).
 *
 * Ported from Java by lane B. Decisions taken rather than inherited:
 *
 * 1. **`setLocalDescription`/`setRemoteDescriptionFuture`/`iceCandidatesOf`'s helper is `internal`
 *    with an explicit `@JvmName`.** Kotlin's `internal` is the module mapping of Java's
 *    package-private, but it mangles the JVM name (`setLocalDescription$xmpp`), and this class's
 *    members are still reached from Java in the same module, so the name is pinned back.
 *    `setRemoteDescriptionFuture` answers `ListenableFuture<Void?>` because Java completed its
 *    future with `set(null)`; `Void?` erases to the same `ListenableFuture<Void>` on the JVM.
 * 2. **The two nullable fields Java dereferenced without a guard are reached through
 *    `?: throw NullPointerException()`**: `dataChannelWriter` in `getOutputStream` and the two
 *    Guava futures in `setLocalDescription`/`setRemoteDescriptionFuture`. `!!` is not used.
 * 3. **`onIceConnectionFailed` keeps Java's null *check*** (it logs and returns), so that one is a
 *    `?.let` rather than a throw — the difference is Java's, not a style choice.
 * 4. **`Futures.submit` is given an explicit `Callable` SAM constructor**, because Guava still
 *    declares `submit(Runnable, Executor)` beside it and a bare lambda is ambiguous there.
 * 5. **`Futures.transform(asInitialTransportInfo(), …, …)` casts the identity lambda to
 *    `Transport.TransportInfo`**, because Kotlin's `ListenableFuture<T>` is invariant and the
 *    Java lambda's inferred `InitialTransportInfo` would not satisfy the declared return type.
 * 6. **`DataChannelWriter.pipedInputStreamLatch` is not private**: the outer class counts it down,
 *    and Kotlin forbids an outer class reading a nested class's private member (the
 *    `IceUdpTransportInfo.Fingerprint` precedent).
 * 7. **`terminate(DataChannel?)`/`terminate(PeerConnection)` and `closeQuietly` are file-private
 *    top-level functions**, the shape this tree uses for Java's private statics; `DataChannelWriter`
 *    and the two observer bases stay private nested classes.
 * 8. **`onIceCandidate(mid: String, sdp: String)` keeps Java's declared parameter types.** Java's
 *    only caller hands it `IceCandidate.sdpMid`/`.sdp` and the body dereferences `sdp` through
 *    `IceUdpTransportInfo.Candidate.fromSdpAttribute`, which is non-null.
 */
class WebRTCDataChannelTransport(
    context: Context,
    private val xmppConnection: XmppConnection,
    private val account: AccountRef,
    initiator: Boolean,
) : Transport {

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val localDescriptionExecutorService: ExecutorService = Executors.newSingleThreadExecutor()

    private val readyToSentIceCandidates = AtomicBoolean(false)
    private val pendingOutgoingIceCandidates: Queue<IceCandidate> = LinkedList()

    private val pipedOutputStream = PipedOutputStream()
    private val writableByteChannel: WritableByteChannel = Channels.newChannel(pipedOutputStream)
    private val pipedInputStream = PipedInputStream(BUFFER_SIZE)

    private val connected = AtomicBoolean(false)

    private val terminationLatch = CountDownLatch(1)

    private val stateHistory: Queue<PeerConnection.PeerConnectionState> = LinkedList()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnectionFuture: ListenableFuture<PeerConnection>? = null

    private var localDescriptionFuture: ListenableFuture<SessionDescription>? = null

    private var dataChannel: DataChannel? = null

    private var transportCallback: Transport.Callback? = null

    private val peerConnectionObserver: PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(Config.LOGTAG, "onSignalChange(" + signalingState + ")")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                stateHistory.add(state)
                Log.d(Config.LOGTAG, "onConnectionChange(" + state + ")")
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    if (connected.compareAndSet(false, true)) {
                        executorService.execute { onIceConnectionConnected() }
                    }
                }
                if (state == PeerConnection.PeerConnectionState.FAILED) {
                    val neverConnected =
                        !stateHistory.contains(PeerConnection.PeerConnectionState.CONNECTED)
                    // we want to terminate the connection a) to properly fail if a connection
                    // drops during a transfer and b) to avoid race conditions if we find a
                    // connection after failure while waiting for the initiator to replace
                    // transport
                    executorService.execute { terminate() }
                    if (neverConnected) {
                        executorService.execute { onIceConnectionFailed() }
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}

            override fun onIceConnectionReceivingChange(b: Boolean) {}

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(Config.LOGTAG, "onIceGatheringChange(" + iceGatheringState + ")")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                if (readyToSentIceCandidates.get()) {
                    this@WebRTCDataChannelTransport.onIceCandidate(
                        iceCandidate.sdpMid,
                        iceCandidate.sdp,
                    )
                } else {
                    pendingOutgoingIceCandidates.add(iceCandidate)
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}

            override fun onAddStream(mediaStream: MediaStream) {}

            override fun onRemoveStream(mediaStream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(Config.LOGTAG, "onDataChannel()")
                this@WebRTCDataChannelTransport.setDataChannel(dataChannel)
            }

            override fun onRenegotiationNeeded() {
                Log.d(Config.LOGTAG, "onRenegotiationNeeded")
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote)
                Log.d(Config.LOGTAG, "local candidate selected: " + event.local)
            }
        }

    private var dataChannelWriter: DataChannelWriter? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                .createInitializationOptions()
        )
        this.peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val appSettings = XmppConnectionService.dataStatics().settings(context)
        this.peerConnectionFuture =
            Futures.transform(
                getIceServers(),
                { iceServers -> createPeerConnection(iceServers, true, appSettings.isUseRelays()) },
                MoreExecutors.directExecutor(),
            )
        if (initiator) {
            this.localDescriptionFuture = setLocalDescription()
        }
    }

    private fun onIceConnectionConnected() {
        (this.transportCallback ?: throw NullPointerException()).onTransportEstablished()
    }

    private fun onIceConnectionFailed() {
        val callback = this.transportCallback
        if (callback == null) {
            Log.d(
                Config.LOGTAG,
                "not calling onTransportSetupFailed(). Transport likely has been replaced",
            )
            return
        }
        callback.onTransportSetupFailed()
    }

    private fun setDataChannel(dataChannel: DataChannel) {
        Log.d(Config.LOGTAG, "the 'receiving' data channel has id " + dataChannel.id())
        this.dataChannel = dataChannel
        dataChannel.registerObserver(
            object : OnMessageObserver() {
                override fun onMessage(buffer: DataChannel.Buffer) {
                    try {
                        writableByteChannel.write(buffer.data)
                    } catch (e: IOException) {
                        Log.d(Config.LOGTAG, "error writing to output stream")
                    }
                }
            }
        )
    }

    protected fun onIceCandidate(mid: String, sdp: String) {
        val candidate = IceUdpTransportInfo.Candidate.fromSdpAttribute(sdp, null)
        (this.transportCallback ?: throw NullPointerException()).onAdditionalCandidate(mid, candidate)
    }

    private fun getIceServers(): ListenableFuture<Collection<PeerConnection.IceServer>> {
        if (Config.DISABLE_PROXY_LOOKUP) {
            return Futures.immediateFuture(Collections.emptySet<PeerConnection.IceServer>())
        }
        if (xmppConnection.getFeatures().externalServiceDiscovery()) {
            val iceServerFuture = SettableFuture.create<Collection<PeerConnection.IceServer>>()
            val request = Iq(Iq.Type.GET)
            request.setTo(this.account.getDomain())
            request.addExtension(Services())
            xmppConnection.sendIqPacket(request) { response ->
                val iceServers = IceServers.parse(response)
                if (iceServers.isEmpty()) {
                    Log.w(
                        Config.LOGTAG,
                        "" +
                            account.getJid().asBareJid() +
                            ": no ICE server found " +
                            response,
                    )
                }
                iceServerFuture.set(iceServers)
            }
            return iceServerFuture
        } else {
            return Futures.immediateFuture(Collections.emptySet<PeerConnection.IceServer>())
        }
    }

    private fun createPeerConnection(
        iceServers: Collection<PeerConnection.IceServer>,
        trickle: Boolean,
        useRelays: Boolean,
    ): PeerConnection {
        val rtcConfig = WebRTCWrapper.buildConfiguration(iceServers, trickle, useRelays)
        val peerConnection =
            requirePeerConnectionFactory().createPeerConnection(rtcConfig, peerConnectionObserver)
        if (peerConnection == null) {
            throw IllegalStateException("Unable to create PeerConnection")
        }
        val dataChannelInit = DataChannel.Init()
        dataChannelInit.protocol = "xmpp-jingle"
        val dataChannel = peerConnection.createDataChannel("test", dataChannelInit)
        val writer = DataChannelWriter(this.pipedInputStream, dataChannel)
        this.dataChannelWriter = writer
        Log.d(Config.LOGTAG, "the 'sending' data channel has id " + dataChannel.id())
        Thread(writer).start()
        return peerConnection
    }

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream {
        val outputStream = PipedOutputStream()
        this.pipedInputStream.connect(outputStream)
        (this.dataChannelWriter ?: throw NullPointerException()).pipedInputStreamLatch.countDown()
        return outputStream
    }

    @Throws(IOException::class)
    override fun getInputStream(): InputStream {
        val inputStream = PipedInputStream(BUFFER_SIZE)
        this.pipedOutputStream.connect(inputStream)
        return inputStream
    }

    override fun asTransportInfo(): ListenableFuture<Transport.TransportInfo> {
        Preconditions.checkState(
            this.localDescriptionFuture != null,
            "Make sure you are setting initiator description first",
        )
        return Futures.transform(
            asInitialTransportInfo(),
            { info -> info as Transport.TransportInfo },
            MoreExecutors.directExecutor(),
        )
    }

    override fun asInitialTransportInfo(): ListenableFuture<Transport.InitialTransportInfo> =
        Futures.transform(
            this.localDescriptionFuture ?: throw NullPointerException(),
            { sdp ->
                WebRTCDataChannelTransportInfo.of(
                    uk.xa0.tulkki.xmpp.jingle.SessionDescription.parse(sdp.description)
                )
            },
            MoreExecutors.directExecutor(),
        )

    override fun readyToSentAdditionalCandidates() {
        readyToSentIceCandidates.set(true)
        while (this.pendingOutgoingIceCandidates.peek() != null) {
            val candidate = pendingOutgoingIceCandidates.poll()
            if (candidate == null) {
                continue
            }
            onIceCandidate(candidate.sdpMid, candidate.sdp)
        }
    }

    override fun terminate() {
        terminate(this.dataChannel)
        this.dataChannel = null
        val dataChannelWriter = this.dataChannelWriter
        if (dataChannelWriter != null) {
            dataChannelWriter.close()
        }
        this.dataChannelWriter = null
        val future = this.peerConnectionFuture
        if (future != null) {
            future.cancel(true)
        }
        try {
            val peerConnection = requirePeerConnection()
            terminate(peerConnection)
        } catch (e: WebRTCWrapper.PeerConnectionNotInitialized) {
            Log.d(Config.LOGTAG, "peer connection was not initialized during termination")
        }
        this.peerConnectionFuture = null
        val peerConnectionFactory = this.peerConnectionFactory
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose()
        }
        this.peerConnectionFactory = null
        closeQuietly(this.pipedOutputStream)
        this.terminationLatch.countDown()
        Log.d(Config.LOGTAG, WebRTCDataChannelTransport::class.java.simpleName + " terminated")
    }

    override fun setTransportCallback(callback: Transport.Callback) {
        this.transportCallback = callback
    }

    override fun connect() {}

    override fun getTerminationLatch(): CountDownLatch = this.terminationLatch

    @Synchronized
    @JvmName("setLocalDescription")
    internal fun setLocalDescription(): ListenableFuture<SessionDescription> =
        Futures.transformAsync(
            this.peerConnectionFuture ?: throw NullPointerException(),
            { peerConnection ->
                if (peerConnection == null) {
                    Futures.immediateFailedFuture<SessionDescription>(
                        IllegalStateException("PeerConnection was null")
                    )
                } else {
                    val future = SettableFuture.create<SessionDescription>()
                    peerConnection.setLocalDescription(
                        object : WebRTCWrapper.SetSdpObserver() {
                            override fun onSetSuccess() {
                                future.setFuture(getLocalDescriptionFuture(peerConnection))
                            }

                            override fun onSetFailure(message: String) {
                                future.setException(
                                    WebRTCWrapper.FailureToSetDescriptionException(message)
                                )
                            }
                        }
                    )
                    future
                }
            },
            MoreExecutors.directExecutor(),
        )

    private fun getLocalDescriptionFuture(
        peerConnection: PeerConnection
    ): ListenableFuture<SessionDescription> =
        Futures.submit(
            Callable {
                val description = peerConnection.getLocalDescription()
                WebRTCWrapper.logDescription(description)
                description
            },
            localDescriptionExecutorService,
        )

    private fun requirePeerConnectionFactory(): PeerConnectionFactory {
        val peerConnectionFactory = this.peerConnectionFactory
        if (peerConnectionFactory == null) {
            throw IllegalStateException("Make sure PeerConnectionFactory is initialized")
        }
        return peerConnectionFactory
    }

    private fun requirePeerConnection(): PeerConnection {
        val future = this.peerConnectionFuture
        if (future != null && future.isDone()) {
            try {
                return future.get()
            } catch (e: InterruptedException) {
                throw WebRTCWrapper.PeerConnectionNotInitialized()
            } catch (e: ExecutionException) {
                throw WebRTCWrapper.PeerConnectionNotInitialized()
            }
        } else {
            throw WebRTCWrapper.PeerConnectionNotInitialized()
        }
    }

    fun addIceCandidates(iceCandidates: List<IceCandidate>) {
        try {
            for (candidate in iceCandidates) {
                requirePeerConnection().addIceCandidate(candidate)
            }
        } catch (e: WebRTCWrapper.PeerConnectionNotInitialized) {
            Log.w(Config.LOGTAG, "could not add ice candidate. peer connection is not initialized")
        }
    }

    fun setInitiatorDescription(
        sessionDescription: uk.xa0.tulkki.xmpp.jingle.SessionDescription
    ) {
        val sdp = SessionDescription(SessionDescription.Type.OFFER, sessionDescription.toString())
        val setFuture = setRemoteDescriptionFuture(sdp)
        this.localDescriptionFuture =
            Futures.transformAsync(
                setFuture,
                { setLocalDescription() },
                MoreExecutors.directExecutor(),
            )
    }

    fun setResponderDescription(
        sessionDescription: uk.xa0.tulkki.xmpp.jingle.SessionDescription
    ) {
        Log.d(Config.LOGTAG, "setResponder description")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sessionDescription.toString())
        WebRTCWrapper.logDescription(sdp)
        setRemoteDescriptionFuture(sdp)
    }

    @Synchronized
    @JvmName("setRemoteDescriptionFuture")
    internal fun setRemoteDescriptionFuture(
        sessionDescription: SessionDescription
    ): ListenableFuture<Void?> =
        Futures.transformAsync(
            this.peerConnectionFuture ?: throw NullPointerException(),
            { peerConnection ->
                if (peerConnection == null) {
                    Futures.immediateFailedFuture<Void?>(
                        IllegalStateException("PeerConnection was null")
                    )
                } else {
                    val future = SettableFuture.create<Void?>()
                    peerConnection.setRemoteDescription(
                        object : WebRTCWrapper.SetSdpObserver() {
                            override fun onSetSuccess() {
                                future.set(null)
                            }

                            override fun onSetFailure(message: String) {
                                future.setException(
                                    WebRTCWrapper.FailureToSetDescriptionException(message)
                                )
                            }
                        },
                        sessionDescription,
                    )
                    future
                }
            },
            MoreExecutors.directExecutor(),
        )

    private class DataChannelWriter(
        private val inputStream: InputStream,
        private val dataChannel: DataChannel,
    ) : Runnable {

        val pipedInputStreamLatch = CountDownLatch(1)
        private val dataChannelLatch = CountDownLatch(1)
        private val isSending = AtomicBoolean(true)

        init {
            val stateChangeObserver =
                object : StateChangeObserver() {
                    override fun onStateChange() {
                        if (dataChannel.state() == DataChannel.State.OPEN) {
                            dataChannelLatch.countDown()
                        }
                    }
                }
            this.dataChannel.registerObserver(stateChangeObserver)
        }

        override fun run() {
            try {
                this.pipedInputStreamLatch.await()
                this.dataChannelLatch.await()
                val buffer = ByteArray(4096)
                while (isSending.get()) {
                    val bufferedAmount = dataChannel.bufferedAmount()
                    if (bufferedAmount > MAX_SENT_BUFFER) {
                        Thread.sleep(50)
                        continue
                    }
                    val count = this.inputStream.read(buffer)
                    if (count < 0) {
                        Log.d(Config.LOGTAG, "DataChannelWriter reached EOF")
                        return
                    }
                    send(ByteBuffer.wrap(buffer, 0, count))
                }
            } catch (e: InterruptedException) {
                if (isSending.get()) {
                    Log.w(Config.LOGTAG, "DataChannelWriter got interrupted while sending", e)
                }
            } catch (e: InterruptedIOException) {
                if (isSending.get()) {
                    Log.w(Config.LOGTAG, "DataChannelWriter got interrupted while sending", e)
                }
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "DataChannelWriter terminated", e)
            } finally {
                Closeables.closeQuietly(inputStream)
            }
        }

        @Throws(IOException::class)
        private fun send(byteBuffer: ByteBuffer) {
            try {
                dataChannel.send(DataChannel.Buffer(byteBuffer, true))
            } catch (e: IllegalStateException) {
                // dataChannel can be 'disposed' if we waited too long between `isSending` check and
                // actually trying to send
                throw IOException(e)
            }
        }

        fun close() {
            this.isSending.set(false)
            terminate(this.dataChannel)
        }
    }

    private abstract class StateChangeObserver : DataChannel.Observer {

        override fun onBufferedAmountChange(change: Long) {}

        override fun onMessage(buffer: DataChannel.Buffer) {}
    }

    private abstract class OnMessageObserver : DataChannel.Observer {

        override fun onBufferedAmountChange(l: Long) {}

        override fun onStateChange() {}
    }

    companion object {
        private const val BUFFER_SIZE = 16_384
        private const val MAX_SENT_BUFFER = 256 * 1024

        @JvmStatic
        fun iceCandidatesOf(
            contentName: String,
            credentials: IceUdpTransportInfo.Credentials,
            candidates: List<IceUdpTransportInfo.Candidate>,
        ): List<IceCandidate> {
            val iceCandidateBuilder = ImmutableList.builder<IceCandidate>()
            for (candidate in candidates) {
                val sdp: String
                try {
                    sdp = candidate.toSdpAttribute(credentials.ufrag)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                // TODO mLneIndex should probably not be hard coded
                iceCandidateBuilder.add(IceCandidate(contentName, 0, sdp))
            }
            return iceCandidateBuilder.build()
        }
    }
}

private fun closeQuietly(outputStream: OutputStream) {
    try {
        outputStream.close()
    } catch (ignored: IOException) {
        // ignored
    }
}

private fun terminate(dataChannel: DataChannel?) {
    if (dataChannel == null) {
        Log.d(Config.LOGTAG, "nothing to terminate. data channel is already null")
        return
    }
    try {
        dataChannel.close()
    } catch (e: IllegalStateException) {
        Log.w(Config.LOGTAG, "could not close data channel")
    }
    try {
        dataChannel.dispose()
    } catch (e: IllegalStateException) {
        Log.w(Config.LOGTAG, "could not dispose data channel")
    }
}

private fun terminate(peerConnection: PeerConnection?) {
    if (peerConnection == null) {
        return
    }
    try {
        peerConnection.dispose()
        Log.d(Config.LOGTAG, "terminated peer connection!")
    } catch (e: IllegalStateException) {
        Log.w(Config.LOGTAG, "could not dispose of peer connection")
    }
}
