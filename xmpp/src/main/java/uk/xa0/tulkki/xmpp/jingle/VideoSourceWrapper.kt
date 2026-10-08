package uk.xa0.tulkki.xmpp.jingle

import android.content.Context
import android.util.Log
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.Collections
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import uk.xa0.tulkki.xmpp.Config

private const val CAPTURING_RESOLUTION = 1920
private const val CAPTURING_MAX_FRAME_RATE = 30

/**
 * The camera capture behind a local video track, and the `Factory` that picks a camera.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The class is `internal`** (Java's package-private `class VideoSourceWrapper`) and stays
 *    Java-callable because Kotlin's `internal` is public in bytecode; `WebRTCWrapper`, in the same
 *    package, keeps `new VideoSourceWrapper.Factory(...)` and every method call unchanged.
 * 2. **`stopCapture` carries `@Throws(InterruptedException::class)`.** Java declared the checked
 *    exception and `WebRTCWrapper:398,479` catches it; without the annotation javac would report the
 *    catch as unreachable.
 * 3. **The two constants are file-private top-level `const val`s**, which is the same static field
 *    Java's private ones were, and keeps them reachable from the nested `Factory`.
 * 4. **`isFrontCamera` stays a private property read and written by the nested `Factory` and by the
 *    `CameraSwitchHandler` anonymous object inside `switchCamera`** — both are inside the class, so
 *    Kotlin's private is intact (it is a *nested* reader, which Kotlin permits; only the outer
 *    reading a nested private would not compile).
 * 5. **`Factory.of` and `Factory.isFrontFacing` are private members** rather than Java's
 *    `private static`; both are called only from `Factory.create`, so nothing observable moves.
 */
internal class VideoSourceWrapper internal constructor(
    private val cameraVideoCapturer: CameraVideoCapturer,
    private val captureFormat: CameraEnumerationAndroid.CaptureFormat,
    private val availableCameras: Set<String>,
) {
    private var isFrontCamera = false
    private var videoSource: VideoSource? = null

    private fun getFrameRate(): Int =
        Math.max(
            captureFormat.framerate.min,
            Math.min(CAPTURING_MAX_FRAME_RATE, captureFormat.framerate.max),
        )

    fun initialize(
        peerConnectionFactory: PeerConnectionFactory,
        context: Context,
        eglBaseContext: EglBase.Context,
    ) {
        val surfaceTextureHelper = SurfaceTextureHelper.create("webrtc", eglBaseContext)
        if (surfaceTextureHelper == null) {
            throw IllegalStateException("Could not create SurfaceTextureHelper")
        }
        val videoSource = peerConnectionFactory.createVideoSource(false)
        this.videoSource = videoSource
        this.cameraVideoCapturer.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver,
        )
    }

    fun getVideoSource(): VideoSource {
        val videoSource =
            this.videoSource ?: throw IllegalStateException("VideoSourceWrapper was not initialized")
        return videoSource
    }

    fun startCapture() {
        val frameRate = getFrameRate()
        Log.d(
            Config.LOGTAG,
            String.format(
                "start capturing at %dx%d@%d",
                captureFormat.width,
                captureFormat.height,
                frameRate,
            ),
        )
        this.cameraVideoCapturer.startCapture(captureFormat.width, captureFormat.height, frameRate)
    }

    @Throws(InterruptedException::class)
    fun stopCapture() {
        this.cameraVideoCapturer.stopCapture()
    }

    fun dispose() {
        this.cameraVideoCapturer.dispose()
        val videoSource = this.videoSource
        if (videoSource != null) {
            dispose(videoSource)
        }
    }

    private fun dispose(videoSource: VideoSource) {
        try {
            videoSource.dispose()
        } catch (e: IllegalStateException) {
            Log.e(Config.LOGTAG, "unable to dispose video source", e)
        }
    }

    fun switchCamera(): ListenableFuture<Boolean> {
        val future = SettableFuture.create<Boolean>()
        this.cameraVideoCapturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    this@VideoSourceWrapper.isFrontCamera = isFrontCamera
                    future.set(isFrontCamera)
                }

                override fun onCameraSwitchError(message: String) {
                    future.setException(
                        IllegalStateException(
                            String.format("Unable to switch camera %s", message),
                        ),
                    )
                }
            },
        )
        return future
    }

    fun isFrontCamera(): Boolean = this.isFrontCamera

    fun isCameraSwitchable(): Boolean = this.availableCameras.size > 1

    internal class Factory(private val context: Context) {

        fun create(): VideoSourceWrapper? {
            val enumerator: CameraEnumerator = Camera2Enumerator(context)
            val deviceNames: Set<String> = ImmutableSet.copyOf(enumerator.deviceNames)
            for (deviceName in deviceNames) {
                if (isFrontFacing(enumerator, deviceName)) {
                    val videoSourceWrapper = of(enumerator, deviceName, deviceNames)
                    if (videoSourceWrapper == null) {
                        return null
                    }
                    videoSourceWrapper.isFrontCamera = true
                    return videoSourceWrapper
                }
            }
            return if (deviceNames.isEmpty()) {
                null
            } else {
                of(enumerator, Iterables.get(deviceNames, 0), deviceNames)
            }
        }

        private fun of(
            enumerator: CameraEnumerator,
            deviceName: String,
            availableCameras: Set<String>,
        ): VideoSourceWrapper? {
            val capturer = enumerator.createCapturer(deviceName, null)
            if (capturer == null) {
                return null
            }
            val choices =
                ArrayList(enumerator.getSupportedFormats(deviceName))
            Collections.sort(choices) { a, b -> b.width - a.width }
            for (captureFormat in choices) {
                if (captureFormat.width <= CAPTURING_RESOLUTION) {
                    return VideoSourceWrapper(capturer, captureFormat, availableCameras)
                }
            }
            return null
        }

        private fun isFrontFacing(cameraEnumerator: CameraEnumerator, deviceName: String): Boolean =
            try {
                cameraEnumerator.isFrontFacing(deviceName)
            } catch (e: NullPointerException) {
                false
            }
    }
}
