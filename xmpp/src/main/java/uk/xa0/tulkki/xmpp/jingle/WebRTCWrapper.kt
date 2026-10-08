package uk.xa0.tulkki.xmpp.jingle

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The `org.webrtc` peer connection the RTP and data-channel transports are driven through.
 *
 * Ported from Java by lane B. Decisions taken rather than inherited:
 *
 * 1. **The constructor is a class-body secondary constructor with no primary** (`WebRTCWrapper(
 *    eventCallback: EventCallback)`): Java's parameter type is this class's *own* nested
 *    `EventCallback`, which a Kotlin primary constructor's header cannot name (verified with the
 *    compiler). Java had a package-private constructor; Kotlin's `constructor(...)` here is
 *    `internal`, the module mapping for package-private, and `javac` reaches the public `<init>`.
 * 2. **Every package-private member Java callers reach is `internal` with an explicit `@JvmName`.**
 *    Kotlin's `internal` mangles the JVM name (`close$xmpp`) and `JingleRtpConnection` calls these by
 *    name; pinning the name keeps the surface while keeping the declaration out of other modules.
 *    The members with no Java caller (`reconfigurePeerConnection`, `restartIce`) keep their plain
 *    Kotlin names.
 * 3. **`setup` and `initializePeerConnection` keep `@Throws(InitializationException::class)`**,
 *    because `JingleRtpConnection:1378`/`:1862` catch it and `setupWebRTC` declares it.
 * 4. **The `peerConnectionObserver` reads the nullable `peerConnection` field into a local before
 *    dereferencing it**, so `onRenegotiationNeeded` needs no `!!`; and the observer is an object
 *    expression, which Kotlin lets reach the outer class's private members.
 * 5. **`iceGatheringComplete` and the two futures Java completed with `set(null)` are `Void?`**,
 *    not `Unit`: the JVM generic signature — what the Java callers of `setRemoteDescription(…).get()`
 *    see — is unchanged (`Void?` erases to `java.lang.Void`), and a non-null `Void` cannot hold the
 *    null those callbacks set. `!!` is not used. The `catching` lambda that Java wrote as
 *    `return null` keeps its explicit nullable type argument.
 * 6. **`isMicrophoneEnabled` keeps Java's null-and-catch shape**: `TrackWrapper.get` is called inside
 *    the `try` and its `Optional` is captured as a `val`, so the illegal-state path returns `false`
 *    exactly where Java did.
 * 7. **`TONE_CODES[tone]` answers `Int?`**, and the unboxing Java performed is spelled
 *    `?: throw NullPointerException()`; `!!` is not used. The main-looper `Handler` is still built
 *    per call, never at class load (the `TulkkiMainThread` lesson: the JVM suite runs against a
 *    mockable `android.jar`).
 * 8. **`dispose(PeerConnection)` and `setEnabled(AudioTrack, Boolean)` are file-private top-level
 *    functions**, the shape this tree uses for Java's private statics.
 * 9. **`InitializationException`'s two constructors are `internal`.** Java had them private and only
 *    this class constructs them; Kotlin forbids an outer class reaching a nested class's private
 *    member, and `internal` is the module mapping this tree uses.
 */
class WebRTCWrapper {

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val localDescriptionExecutorService: ExecutorService = Executors.newSingleThreadExecutor()

    private val eventCallback: EventCallback
    private val readyToReceivedIceCandidates = AtomicBoolean(false)
    private val iceCandidates: Queue<IceCandidate> = LinkedList()
    private var localAudioTrack: TrackWrapper<AudioTrack>? = null
    private var localVideoTrack: TrackWrapper<VideoTrack>? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val iceGatheringComplete = SettableFuture.create<Void?>()

    private val peerConnectionObserver: PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")")
                // this is called after removeTrack or addTrack
                // and should then trigger a content-add or content-remove or something
                // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                eventCallback.onConnectionChange(newState)
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(
                    EXTENDED_LOGGING_TAG,
                    "onIceConnectionChange(" + iceConnectionState + ")",
                )
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote)
                Log.d(Config.LOGTAG, "local candidate selected: " + event.local)
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {}

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")")
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete.set(null)
                }
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                if (readyToReceivedIceCandidates.get()) {
                    eventCallback.onIceCandidate(iceCandidate)
                } else {
                    iceCandidates.add(iceCandidate)
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(
                    EXTENDED_LOGGING_TAG,
                    "onAddStream(numAudioTracks=" +
                        mediaStream.audioTracks.size +
                        ",numVideoTracks=" +
                        mediaStream.videoTracks.size +
                        ")",
                )
            }

            override fun onRemoveStream(mediaStream: MediaStream) {}

            override fun onDataChannel(dataChannel: DataChannel) {}

            override fun onRenegotiationNeeded() {
                Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()")
                val currentState = this@WebRTCWrapper.peerConnection?.connectionState()
                if (currentState != null && currentState != PeerConnection.PeerConnectionState.NEW) {
                    eventCallback.onRenegotiationNeeded()
                }
            }

            override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                val track = rtpReceiver.track()
                Log.d(
                    EXTENDED_LOGGING_TAG,
                    "onAddTrack(kind=" +
                        (if (track == null) "null" else track.kind()) +
                        ",numMediaStreams=" +
                        mediaStreams.size +
                        ")",
                )
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d(
                    EXTENDED_LOGGING_TAG,
                    "onTrack(mid=" +
                        transceiver.getMid() +
                        ",media=" +
                        transceiver.getMediaType() +
                        ",direction=" +
                        transceiver.getDirection() +
                        ")",
                )
            }

            override fun onRemoveTrack(receiver: RtpReceiver) {
                Log.d(EXTENDED_LOGGING_TAG, "onRemoveTrack(" + receiver.id() + ")")
            }
        }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var context: Context? = null
    private var eglBase: EglBase? = null
    private var videoSourceWrapper: VideoSourceWrapper? = null

    internal constructor(eventCallback: EventCallback) {
        this.eventCallback = eventCallback
    }

    @Throws(InitializationException::class)
    fun setup(service: XmppConnectionService) {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(service)
                    .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                    .createInitializationOptions()
            )
        } catch (e: UnsatisfiedLinkError) {
            throw InitializationException("Unable to initialize PeerConnectionFactory", e)
        }
        try {
            this.eglBase = EglBase.create()
        } catch (e: RuntimeException) {
            throw InitializationException("Unable to create EGL base", e)
        }
        this.context = service
    }

    @Synchronized
    @JvmName("initializePeerConnection")
    @Throws(InitializationException::class)
    internal fun initializePeerConnection(
        media: Set<Media>,
        iceServers: Collection<PeerConnection.IceServer>,
        trickle: Boolean,
        useRelay: Boolean,
    ) {
        Preconditions.checkState(this.eglBase != null)
        Preconditions.checkNotNull(media)
        Preconditions.checkArgument(
            !media.isEmpty(),
            "media can not be empty when initializing peer connection",
        )
        val setUseHardwareAcousticEchoCanceler = !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL)
        Log.d(
            Config.LOGTAG,
            String.format(
                "setUseHardwareAcousticEchoCanceler(%s) model=%s",
                setUseHardwareAcousticEchoCanceler,
                Build.MODEL,
            ),
        )
        val eglBase = this.eglBase ?: throw NullPointerException()
        this.peerConnectionFactory =
            PeerConnectionFactory.builder()
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(eglBase.getEglBaseContext())
                )
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true)
                )
                .setAudioDeviceModule(
                    JavaAudioDeviceModule.builder(requireContext())
                        .setUseHardwareAcousticEchoCanceler(setUseHardwareAcousticEchoCanceler)
                        .createAudioDeviceModule()
                )
                .createPeerConnectionFactory()

        val rtcConfig = buildConfiguration(iceServers, trickle, useRelay)
        val peerConnection =
            requirePeerConnectionFactory().createPeerConnection(rtcConfig, peerConnectionObserver)
        if (peerConnection == null) {
            throw InitializationException("Unable to create PeerConnection")
        }

        if (media.contains(Media.VIDEO)) {
            addVideoTrack(peerConnection)
        }

        if (media.contains(Media.AUDIO)) {
            addAudioTrack(peerConnection)
        }
        peerConnection.setAudioPlayout(true)
        peerConnection.setAudioRecording(true)

        this.peerConnection = peerConnection
    }

    private fun initializeVideoSourceWrapper(): VideoSourceWrapper {
        val existingVideoSourceWrapper = this.videoSourceWrapper
        if (existingVideoSourceWrapper != null) {
            existingVideoSourceWrapper.startCapture()
            return existingVideoSourceWrapper
        }
        val videoSourceWrapper = VideoSourceWrapper.Factory(requireContext()).create()
        if (videoSourceWrapper == null) {
            throw IllegalStateException("Could not instantiate VideoSourceWrapper")
        }
        videoSourceWrapper.initialize(
            requirePeerConnectionFactory(),
            requireContext(),
            (eglBase ?: throw NullPointerException()).getEglBaseContext(),
        )
        videoSourceWrapper.startCapture()
        this.videoSourceWrapper = videoSourceWrapper
        return videoSourceWrapper
    }

    @Synchronized
    fun addTrack(media: Media): Boolean {
        if (media == Media.VIDEO) {
            return addVideoTrack(requirePeerConnection())
        } else if (media == Media.AUDIO) {
            return addAudioTrack(requirePeerConnection())
        }
        throw IllegalStateException(String.format("Could not add track for %s", media))
    }

    @Synchronized
    fun removeTrack(media: Media) {
        if (media == Media.VIDEO) {
            removeVideoTrack(requirePeerConnection())
        }
    }

    private fun addAudioTrack(peerConnection: PeerConnection): Boolean {
        val audioSource = requirePeerConnectionFactory().createAudioSource(MediaConstraints())
        val audioTrack =
            requirePeerConnectionFactory()
                .createAudioTrack(TrackWrapper.id(AudioTrack::class.java), audioSource)
        this.localAudioTrack = TrackWrapper.addTrack(peerConnection, audioTrack)
        return true
    }

    private fun addVideoTrack(peerConnection: PeerConnection): Boolean {
        val existing = this.localVideoTrack
        if (existing != null) {
            val transceiver = TrackWrapper.getTransceiver(peerConnection, existing)
            if (transceiver == null) {
                Log.w(EXTENDED_LOGGING_TAG, "unable to restart video transceiver")
                return false
            }
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            (this.videoSourceWrapper ?: throw NullPointerException()).startCapture()
            return true
        }
        val videoSourceWrapper: VideoSourceWrapper
        try {
            videoSourceWrapper = initializeVideoSourceWrapper()
        } catch (e: IllegalStateException) {
            Log.d(Config.LOGTAG, "could not add video track", e)
            return false
        }
        val videoTrack =
            requirePeerConnectionFactory()
                .createVideoTrack(
                    TrackWrapper.id(VideoTrack::class.java),
                    videoSourceWrapper.getVideoSource(),
                )
        this.localVideoTrack = TrackWrapper.addTrack(peerConnection, videoTrack)
        return true
    }

    private fun removeVideoTrack(peerConnection: PeerConnection) {
        val localVideoTrack = this.localVideoTrack
        if (localVideoTrack != null) {
            val exactTransceiver = TrackWrapper.getTransceiver(peerConnection, localVideoTrack)
            if (exactTransceiver == null) {
                throw IllegalStateException()
            }
            exactTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        }
        val videoSourceWrapper = this.videoSourceWrapper
        if (videoSourceWrapper != null) {
            try {
                videoSourceWrapper.stopCapture()
            } catch (e: InterruptedException) {
                Log.e(Config.LOGTAG, "could not stop capturing video source", e)
            }
        }
    }

    @JvmName("reconfigurePeerConnection")
    internal fun reconfigurePeerConnection(
        iceServers: Set<PeerConnection.IceServer>,
        trickle: Boolean,
        useRelay: Boolean,
    ) {
        requirePeerConnection().setConfiguration(buildConfiguration(iceServers, trickle, useRelay))
    }

    @JvmName("restartIceAsync")
    internal fun restartIceAsync() {
        this.execute { restartIce() }
    }

    private fun restartIce() {
        val peerConnection: PeerConnection
        try {
            peerConnection = requirePeerConnection()
        } catch (e: PeerConnectionNotInitialized) {
            Log.w(EXTENDED_LOGGING_TAG, "PeerConnection vanished before we could execute restart")
            return
        }
        setIsReadyToReceiveIceCandidates(false)
        peerConnection.restartIce()
    }

    fun setIsReadyToReceiveIceCandidates(ready: Boolean) {
        readyToReceivedIceCandidates.set(ready)
        val was = iceCandidates.size
        while (ready && iceCandidates.peek() != null) {
            eventCallback.onIceCandidate(iceCandidates.poll())
        }
        val isSize = iceCandidates.size
        Log.d(
            EXTENDED_LOGGING_TAG,
            "setIsReadyToReceiveCandidates(" + ready + ") was=" + was + " is=" + isSize,
        )
    }

    @Synchronized
    @JvmName("close")
    internal fun close() {
        val peerConnection = this.peerConnection
        val peerConnectionFactory = this.peerConnectionFactory
        val videoSourceWrapper = this.videoSourceWrapper
        val eglBase = this.eglBase
        if (peerConnection != null) {
            this.peerConnection = null
            dispose(peerConnection)
        }
        this.localVideoTrack = null
        this.remoteVideoTrack = null
        if (videoSourceWrapper != null) {
            this.videoSourceWrapper = null
            try {
                videoSourceWrapper.stopCapture()
            } catch (e: InterruptedException) {
                Log.e(Config.LOGTAG, "unable to stop capturing")
            }
            videoSourceWrapper.dispose()
        }
        if (eglBase != null) {
            eglBase.release()
            this.eglBase = null
        }
        if (peerConnectionFactory != null) {
            this.peerConnectionFactory = null
            peerConnectionFactory.dispose()
        }
    }

    @Synchronized
    @JvmName("verifyClosed")
    internal fun verifyClosed() {
        if (this.peerConnection != null ||
            this.eglBase != null ||
            this.localVideoTrack != null ||
            this.remoteVideoTrack != null
        ) {
            val e = AssertionError("WebRTCWrapper hasn't been closed properly")
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e)
            throw e
        }
    }

    @JvmName("isCameraSwitchable")
    internal fun isCameraSwitchable(): Boolean {
        val videoSourceWrapper = this.videoSourceWrapper
        return videoSourceWrapper != null && videoSourceWrapper.isCameraSwitchable()
    }

    @JvmName("isFrontCamera")
    internal fun isFrontCamera(): Boolean {
        val videoSourceWrapper = this.videoSourceWrapper
        return videoSourceWrapper == null || videoSourceWrapper.isFrontCamera()
    }

    @JvmName("switchCamera")
    internal fun switchCamera(): ListenableFuture<Boolean> {
        val videoSourceWrapper = this.videoSourceWrapper
        if (videoSourceWrapper == null) {
            return Futures.immediateFailedFuture(
                IllegalStateException("VideoSourceWrapper has not been initialized")
            )
        }
        return videoSourceWrapper.switchCamera()
    }

    @JvmName("isMicrophoneEnabled")
    internal fun isMicrophoneEnabled(): Boolean {
        val audioTrack: Optional<AudioTrack> =
            try {
                TrackWrapper.get(peerConnection, this.localAudioTrack)
            } catch (e: IllegalStateException) {
                Log.d(Config.LOGTAG, "unable to check microphone", e)
                // ignoring race condition in case sender has been disposed
                return false
            }
        if (audioTrack.isPresent) {
            try {
                return audioTrack.get().enabled()
            } catch (e: IllegalStateException) {
                // sometimes UI might still be rendering the buttons when a background thread has
                // already ended the call
                return false
            }
        } else {
            return false
        }
    }

    @JvmName("setMicrophoneEnabledOrThrow")
    internal fun setMicrophoneEnabledOrThrow(enabled: Boolean): Boolean {
        val audioTrack: Optional<AudioTrack> =
            TrackWrapper.get(peerConnection, this.localAudioTrack)
        if (audioTrack.isPresent) {
            return setEnabled(audioTrack.get(), enabled)
        } else {
            throw IllegalStateException("Local audio track does not exist (yet)")
        }
    }

    @JvmName("setMicrophoneEnabled")
    internal fun setMicrophoneEnabled(enabled: Boolean) {
        val audioTrack: Optional<AudioTrack> =
            TrackWrapper.get(peerConnection, this.localAudioTrack)
        if (audioTrack.isPresent) {
            setEnabled(audioTrack.get(), enabled)
        }
    }

    @JvmName("isVideoEnabled")
    internal fun isVideoEnabled(): Boolean {
        val videoTrack: Optional<VideoTrack> =
            TrackWrapper.get(peerConnection, this.localVideoTrack)
        if (videoTrack.isPresent) {
            return videoTrack.get().enabled()
        }
        return false
    }

    @JvmName("setVideoEnabled")
    internal fun setVideoEnabled(enabled: Boolean) {
        val videoTrack: Optional<VideoTrack> =
            TrackWrapper.get(peerConnection, this.localVideoTrack)
        if (videoTrack.isPresent) {
            videoTrack.get().setEnabled(enabled)
            return
        }
        throw IllegalStateException("Local video track does not exist")
    }

    @Synchronized
    @JvmName("setLocalDescription")
    internal fun setLocalDescription(
        waitForCandidates: Boolean
    ): ListenableFuture<SessionDescription> {
        this.setIsReadyToReceiveIceCandidates(false)
        return Futures.transformAsync(
            getPeerConnectionFuture(),
            { peerConnection ->
                if (peerConnection == null) {
                    Futures.immediateFailedFuture<SessionDescription>(
                        IllegalStateException("PeerConnection was null")
                    )
                } else {
                    val future = SettableFuture.create<SessionDescription>()
                    peerConnection.setLocalDescription(
                        object : SetSdpObserver() {
                            override fun onSetSuccess() {
                                if (waitForCandidates) {
                                    val delay = getIceGatheringCompleteOrTimeout()
                                    val delayedSessionDescription =
                                        Futures.transformAsync(
                                            delay,
                                            {
                                                iceCandidates.clear()
                                                getLocalDescriptionFuture()
                                            },
                                            MoreExecutors.directExecutor(),
                                        )
                                    future.setFuture(delayedSessionDescription)
                                } else {
                                    future.setFuture(getLocalDescriptionFuture())
                                }
                            }

                            override fun onSetFailure(message: String) {
                                future.setException(
                                    FailureToSetDescriptionException(message)
                                )
                            }
                        }
                    )
                    future
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun getIceGatheringCompleteOrTimeout(): ListenableFuture<Void?> =
        Futures.catching<Void?, TimeoutException>(
            Futures.withTimeout(
                iceGatheringComplete,
                2,
                TimeUnit.SECONDS,
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE,
            ),
            TimeoutException::class.java,
            { ex ->
                Log.d(
                    EXTENDED_LOGGING_TAG,
                    "timeout while waiting for ICE gathering to complete",
                )
                null
            },
            MoreExecutors.directExecutor(),
        )

    private fun getLocalDescriptionFuture(): ListenableFuture<SessionDescription> =
        Futures.submit(
            Callable {
                val description = requirePeerConnection().getLocalDescription()
                Log.d(EXTENDED_LOGGING_TAG, "local description:")
                logDescription(description)
                description
            },
            localDescriptionExecutorService,
        )

    @Synchronized
    @JvmName("setRemoteDescription")
    internal fun setRemoteDescription(
        sessionDescription: SessionDescription
    ): ListenableFuture<Void?> {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:")
        logDescription(sessionDescription)
        return Futures.transformAsync(
            getPeerConnectionFuture(),
            { peerConnection ->
                if (peerConnection == null) {
                    Futures.immediateFailedFuture<Void?>(
                        IllegalStateException("PeerConnection was null")
                    )
                } else {
                    val future = SettableFuture.create<Void?>()
                    peerConnection.setRemoteDescription(
                        object : SetSdpObserver() {
                            override fun onSetSuccess() {
                                future.set(null)
                            }

                            override fun onSetFailure(message: String) {
                                future.setException(
                                    FailureToSetDescriptionException(message)
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
    }

    private fun getPeerConnectionFuture(): ListenableFuture<PeerConnection> {
        val peerConnection = this.peerConnection
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(PeerConnectionNotInitialized())
        } else {
            return Futures.immediateFuture(peerConnection)
        }
    }

    private fun requirePeerConnection(): PeerConnection {
        val peerConnection = this.peerConnection
        if (peerConnection == null) {
            throw PeerConnectionNotInitialized()
        }
        return peerConnection
    }

    fun applyDtmfTone(tone: String): Boolean {
        val localAudioTrack = this.localAudioTrack
        if (localAudioTrack == null || localAudioTrack.rtpSender == null) return false

        try {
            (localAudioTrack.rtpSender.dtmf() ?: throw NullPointerException())
                .insertDtmf(tone, TONE_DURATION, 100)
        } catch (e: IllegalStateException) {
            // Race condition, DtmfSender has been disposed
            return false
        }
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val toneCode = TONE_CODES[tone]
            val toneGenerator =
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, DEFAULT_TONE_VOLUME)
            toneGenerator.startTone(toneCode ?: throw NullPointerException(), TONE_DURATION)
            handler.postDelayed({ toneGenerator.release() }, (TONE_DURATION + 2).toLong())
        }

        return true
    }

    private fun requirePeerConnectionFactory(): PeerConnectionFactory {
        val peerConnectionFactory = this.peerConnectionFactory
        if (peerConnectionFactory == null) {
            throw IllegalStateException("Make sure PeerConnectionFactory is initialized")
        }
        return peerConnectionFactory
    }

    @JvmName("addIceCandidate")
    internal fun addIceCandidate(iceCandidate: IceCandidate) {
        requirePeerConnection().addIceCandidate(iceCandidate)
    }

    @JvmName("getState")
    internal fun getState(): PeerConnection.PeerConnectionState =
        requirePeerConnection().connectionState()

    fun getSignalingState(): PeerConnection.SignalingState =
        try {
            requirePeerConnection().signalingState()
        } catch (e: IllegalStateException) {
            PeerConnection.SignalingState.CLOSED
        }

    @JvmName("getEglBaseContext")
    internal fun getEglBaseContext(): EglBase.Context =
        (this.eglBase ?: throw NullPointerException()).getEglBaseContext()

    @JvmName("getLocalVideoTrack")
    internal fun getLocalVideoTrack(): Optional<VideoTrack> =
        try {
            TrackWrapper.get(peerConnection, this.localVideoTrack)
        } catch (e: IllegalStateException) {
            Optional.absent()
        }

    @JvmName("getRemoteVideoTrack")
    internal fun getRemoteVideoTrack(): Optional<VideoTrack> =
        Optional.fromNullable(this.remoteVideoTrack)

    private fun requireContext(): Context {
        val context = this.context
        if (context == null) {
            throw IllegalStateException("call setup first")
        }
        return context
    }

    @JvmName("execute")
    internal fun execute(command: Runnable) {
        this.executorService.execute(command)
    }

    interface EventCallback {
        fun onIceCandidate(iceCandidate: IceCandidate)

        fun onConnectionChange(newState: PeerConnection.PeerConnectionState)

        fun onRenegotiationNeeded()
    }

    abstract class SetSdpObserver : SdpObserver {

        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            throw IllegalStateException("Not able to use SetSdpObserver")
        }

        override fun onCreateFailure(s: String) {
            throw IllegalStateException("Not able to use SetSdpObserver")
        }
    }

    class InitializationException : Exception {
        internal constructor(message: String, throwable: Throwable) : super(message, throwable)

        internal constructor(message: String) : super(message)
    }

    class PeerConnectionNotInitialized : IllegalStateException("initialize PeerConnection first")

    class FailureToSetDescriptionException(message: String) : IllegalArgumentException(message)

    companion object {

        private val EXTENDED_LOGGING_TAG: String = WebRTCWrapper::class.java.simpleName

        private const val TONE_DURATION = 400
        private const val DEFAULT_TONE_VOLUME = 60

        private val TONE_CODES: Map<String, Int> =
            ImmutableMap.builder<String, Int>()
                .put("0", ToneGenerator.TONE_DTMF_0)
                .put("1", ToneGenerator.TONE_DTMF_1)
                .put("2", ToneGenerator.TONE_DTMF_2)
                .put("3", ToneGenerator.TONE_DTMF_3)
                .put("4", ToneGenerator.TONE_DTMF_4)
                .put("5", ToneGenerator.TONE_DTMF_5)
                .put("6", ToneGenerator.TONE_DTMF_6)
                .put("7", ToneGenerator.TONE_DTMF_7)
                .put("8", ToneGenerator.TONE_DTMF_8)
                .put("9", ToneGenerator.TONE_DTMF_9)
                .put("*", ToneGenerator.TONE_DTMF_S)
                .put("#", ToneGenerator.TONE_DTMF_P)
                .build()

        private val HARDWARE_AEC_BLACKLIST: Set<String> =
            ImmutableSet.builder<String>()
                .add("Pixel")
                .add("Pixel XL")
                .add("Moto G5")
                .add("Moto G (5S) Plus")
                .add("Moto G4")
                .add("TA-1053")
                .add("Mi A1")
                .add("Mi A2")
                .add("E5823") // Sony z5 compact
                .add("Redmi Note 5")
                .add("FP2") // Fairphone FP2
                .add("FP4") // Fairphone FP4
                .add("MI 5")
                .add("GT-I9515") // Samsung Galaxy S4 Value Edition (jfvelte)
                .add("GT-I9515L") // Samsung Galaxy S4 Value Edition (jfvelte)
                .add("GT-I9505") // Samsung Galaxy S4 (jfltexx)
                .add("Nexus 7") // ASUS Nexus 7
                .build()

        @JvmStatic
        fun buildConfiguration(
            iceServers: Collection<PeerConnection.IceServer>,
            trickle: Boolean,
            useRelay: Boolean,
        ): PeerConnection.RTCConfiguration {
            val rtcConfig =
                PeerConnection.RTCConfiguration(ImmutableList.copyOf(iceServers))
            rtcConfig.tcpCandidatePolicy =
                PeerConnection.TcpCandidatePolicy.DISABLED // XEP-0176 doesn't support tcp
            if (trickle) {
                rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            } else {
                rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE
            rtcConfig.enableImplicitRollback = true
            rtcConfig.iceTransportsType =
                if (useRelay) {
                    PeerConnection.IceTransportsType.RELAY
                } else {
                    PeerConnection.IceTransportsType.ALL
                }
            return rtcConfig
        }

        @JvmStatic
        fun logDescription(sessionDescription: SessionDescription) {
            for (line in sessionDescription.description.split(
                uk.xa0.tulkki.xmpp.jingle.SessionDescription.LINE_DIVIDER
            )) {
                Log.d(EXTENDED_LOGGING_TAG, line)
            }
        }
    }
}

private fun dispose(peerConnection: PeerConnection) {
    try {
        peerConnection.dispose()
    } catch (e: IllegalStateException) {
        Log.e(Config.LOGTAG, "unable to dispose of peer connection", e)
    }
}

private fun setEnabled(audioTrack: AudioTrack, enabled: Boolean): Boolean {
    try {
        audioTrack.setEnabled(enabled)
        return true
    } catch (e: IllegalStateException) {
        Log.d(Config.LOGTAG, "unable to toggle audio track", e)
        // ignoring race condition in case MediaStreamTrack has been disposed
        return false
    }
}
