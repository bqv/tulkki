package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import com.google.common.base.CaseFormat
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import java.util.UUID
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import uk.xa0.tulkki.xmpp.Config

/**
 * A local media track and the sender that carries it, plus the direction bookkeeping libwebrtc
 * needs before the track is handed to the peer.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The class is `internal`**, Kotlin's mapping for Java's package-private `class TrackWrapper`;
 *    `WebRTCWrapper` is in the same package and reaches it. The four statics are `@JvmStatic` so
 *    `TrackWrapper.addTrack/get/getTransceiver/id(...)` keep their Java spelling.
 * 2. **`track` and `rtpSender` stay fields** (`@JvmField`), because `WebRTCWrapper:727,730` reads
 *    `localAudioTrack.rtpSender` directly.
 * 3. **`get` keeps the two null-tolerant parameters**: Java tested `trackWrapper == null` and
 *    `peerConnection == null` (the latter inside `getTransceiver`'s own guard).
 * 4. **`getTransceiver` is `@NonNull` in Java only**, so its `peerConnection` stays non-null here;
 *    `get` passes the already-guarded value.
 * 5. The constructor stays private: Kotlin lets the class's own companion reach it.
 */
internal class TrackWrapper<T : MediaStreamTrack> private constructor(
    @JvmField val track: T,
    @JvmField val rtpSender: RtpSender,
) {

    companion object {
        @JvmStatic
        fun <T : MediaStreamTrack> addTrack(
            peerConnection: PeerConnection,
            mediaStreamTrack: T,
        ): TrackWrapper<T> {
            val rtpSender = peerConnection.addTrack(mediaStreamTrack)
            return TrackWrapper(mediaStreamTrack, rtpSender)
        }

        @JvmStatic
        fun <T : MediaStreamTrack> get(
            peerConnection: PeerConnection?,
            trackWrapper: TrackWrapper<T>?,
        ): Optional<T> {
            if (trackWrapper == null) {
                return Optional.absent()
            }
            val transceiver =
                if (peerConnection == null) {
                    null
                } else {
                    getTransceiver(peerConnection, trackWrapper)
                }
            if (transceiver == null) {
                val id: String
                try {
                    id = trackWrapper.rtpSender.id()
                } catch (e: IllegalStateException) {
                    return Optional.absent()
                }
                Log.w(Config.LOGTAG, "unable to detect transceiver for $id")
                return Optional.of(trackWrapper.track)
            }
            val direction = transceiver.direction
            return if (direction == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY ||
                direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            ) {
                Optional.of(trackWrapper.track)
            } else {
                Log.d(Config.LOGTAG, "withholding track because transceiver is $direction")
                Optional.absent()
            }
        }

        @JvmStatic
        fun <T : MediaStreamTrack> getTransceiver(
            peerConnection: PeerConnection,
            trackWrapper: TrackWrapper<T>,
        ): RtpTransceiver? {
            val rtpSender = trackWrapper.rtpSender
            val rtpSenderId: String
            try {
                rtpSenderId = rtpSender.id()
            } catch (e: IllegalStateException) {
                return null
            }
            for (transceiver in peerConnection.transceivers) {
                try {
                    if (transceiver.sender.id() == rtpSenderId) {
                        return transceiver
                    }
                } catch (e: IllegalStateException) {
                    // ignored
                }
            }
            return null
        }

        @JvmStatic
        fun id(clazz: Class<out MediaStreamTrack>): String =
            String.format(
                "%s-%s",
                CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, clazz.simpleName),
                UUID.randomUUID().toString(),
            )
    }

    init {
        Preconditions.checkNotNull(track)
        Preconditions.checkNotNull(rtpSender)
    }
}
