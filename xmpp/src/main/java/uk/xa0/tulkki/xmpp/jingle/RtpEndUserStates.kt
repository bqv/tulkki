package uk.xa0.tulkki.xmpp.jingle

import org.webrtc.PeerConnection
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection.State
import uk.xa0.tulkki.xmpp.services.CallIntegrationPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: the two vocabularies `JingleRtpConnection`'s state talks to, out of that class.
 *
 * Java kept four methods here - the public `getEndUserState`, its private
 * `getPeerConnectionStateAsEndUserState`, the private `isPeerConnectionConnected` and the private
 * `updateCallIntegrationState`. The mappings move: the session state plus the facts they decided on
 * (who initiated, the pending content-add, the peer connection's state) are parameters, and the
 * connection keeps its own `webRTCWrapper` probes and the delegating entry points.
 *
 * The three facts are passed as `() -> ...`, not as values, because **Java read each of them only in
 * the branch that wanted it**: `getPendingContentAddition()` throws a `NullPointerException` on a
 * transport-info-only content map and is read only in `SESSION_ACCEPTED`, `zeroDuration()` only in
 * the `TERMINATED_CONNECTIVITY_ERROR` branch and the peer-connection `default`, and
 * `webRTCWrapper.getState()` only in `SESSION_ACCEPTED`. A value parameter would read all three on
 * every call and move that failure to states Java never failed in.
 *
 * `@JvmStatic` members of an `internal object`, the shape this package already uses for Java's
 * statics, so the four Java call sites keep plain static calls and the class is public only in
 * bytecode.
 *
 * A null `peerConnectionState` is Java's `PeerConnectionNotInitialized` catch: `ENDING_CALL`, the
 * answer it returned. The `when`s keep Java's `default -> throw`, which today's enum constants make
 * unreachable - the failure shape survives a constant being added rather than silently answering.
 */
internal object RtpEndUserStates {

    @JvmStatic
    fun of(
        state: State,
        isInitiator: Boolean,
        pendingContentAddition: () -> ContentAddition?,
        peerConnectionState: () -> PeerConnection.PeerConnectionState?,
        zeroDuration: () -> Boolean,
    ): RtpEndUserState =
        when (state) {
            State.NULL, State.PROPOSED, State.SESSION_INITIALIZED ->
                if (isInitiator) RtpEndUserState.RINGING else RtpEndUserState.INCOMING_CALL
            State.PROCEED ->
                if (isInitiator) RtpEndUserState.RINGING else RtpEndUserState.ACCEPTING_CALL
            State.SESSION_INITIALIZED_PRE_APPROVED ->
                if (isInitiator) RtpEndUserState.RINGING else RtpEndUserState.CONNECTING
            State.SESSION_ACCEPTED -> {
                val ca = pendingContentAddition()
                if (ca != null && ca.direction == ContentAddition.Direction.INCOMING) {
                    RtpEndUserState.INCOMING_CONTENT_ADD
                } else {
                    ofPeerConnection(peerConnectionState(), zeroDuration)
                }
            }
            State.REJECTED, State.REJECTED_RACED, State.TERMINATED_DECLINED_OR_BUSY ->
                if (isInitiator) RtpEndUserState.DECLINED_OR_BUSY else RtpEndUserState.ENDED
            State.TERMINATED_SUCCESS,
            State.ACCEPTED,
            State.RETRACTED,
            State.TERMINATED_CANCEL_OR_TIMEOUT -> RtpEndUserState.ENDED
            State.RETRACTED_RACED ->
                if (isInitiator) RtpEndUserState.ENDED else RtpEndUserState.RETRACTED
            State.TERMINATED_CONNECTIVITY_ERROR ->
                if (zeroDuration()) {
                    RtpEndUserState.CONNECTIVITY_ERROR
                } else {
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR
                }
            State.TERMINATED_APPLICATION_FAILURE -> RtpEndUserState.APPLICATION_ERROR
            State.TERMINATED_SECURITY_ERROR -> RtpEndUserState.SECURITY_ERROR
            else ->
                throw IllegalStateException(
                    String.format("%s has no equivalent EndUserState", state)
                )
        }

    @JvmStatic
    fun ofPeerConnection(
        peerConnectionState: PeerConnection.PeerConnectionState?,
        zeroDuration: () -> Boolean,
    ): RtpEndUserState {
        if (peerConnectionState == null) {
            // We usually close the WebRTCWrapper *before* transitioning so we might still
            // be in SESSION_ACCEPTED even though the peerConnection has been torn down
            return RtpEndUserState.ENDING_CALL
        }
        return when (peerConnectionState) {
            PeerConnection.PeerConnectionState.CONNECTED -> RtpEndUserState.CONNECTED
            PeerConnection.PeerConnectionState.NEW,
            PeerConnection.PeerConnectionState.CONNECTING -> RtpEndUserState.CONNECTING
            PeerConnection.PeerConnectionState.CLOSED -> RtpEndUserState.ENDING_CALL
            else ->
                if (zeroDuration()) {
                    RtpEndUserState.CONNECTIVITY_ERROR
                } else {
                    RtpEndUserState.RECONNECTING
                }
        }
    }

    @JvmStatic
    fun updateCallIntegration(
        state: State,
        isInitiator: Boolean,
        peerConnectionConnected: Boolean,
        callIntegration: CallIntegrationPort,
    ) {
        when (state) {
            State.NULL, State.PROPOSED, State.SESSION_INITIALIZED -> {
                if (isInitiator) {
                    callIntegration.setDialing()
                } else {
                    callIntegration.setRinging()
                }
            }
            State.PROCEED, State.SESSION_INITIALIZED_PRE_APPROVED -> {
                if (isInitiator) {
                    callIntegration.setDialing()
                } else {
                    callIntegration.setInitialized()
                }
            }
            State.SESSION_ACCEPTED -> {
                if (peerConnectionConnected) {
                    callIntegration.setActive()
                } else {
                    callIntegration.setInitialized()
                }
            }
            State.REJECTED, State.REJECTED_RACED, State.TERMINATED_DECLINED_OR_BUSY -> {
                if (isInitiator) {
                    callIntegration.busy()
                } else {
                    callIntegration.rejected()
                }
            }
            State.TERMINATED_SUCCESS -> callIntegration.success()
            State.ACCEPTED -> callIntegration.accepted()
            State.RETRACTED, State.RETRACTED_RACED, State.TERMINATED_CANCEL_OR_TIMEOUT ->
                callIntegration.retracted()
            State.TERMINATED_CONNECTIVITY_ERROR,
            State.TERMINATED_APPLICATION_FAILURE,
            State.TERMINATED_SECURITY_ERROR -> callIntegration.error()
            else ->
                throw IllegalStateException(String.format("%s is not handled", state))
        }
    }
}
