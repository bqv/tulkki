package uk.xa0.tulkki.xmpp.services

import android.net.Uri
import android.telecom.CallAudioState
import uk.xa0.tulkki.libs.AudioDevice

/**
 * Tulkki: the telecom call integration, in island vocabulary.
 *
 * <p>The last two `:app` names this island carried, and the reason pair 4 existed: the connection
 * service and the {@code Connection} subclass it builds. {@link Callback} is the continuation the
 * two jingle classes implement - {@code CallIntegration.Callback} extends it, so neither of them
 * moves a method - and the factory members are the statics the call path asks for. The
 * audio-device type is not here: pair 3 moved it to {@code uk.xa0.tulkki.libs.AudioDevice}, which an
 * island may name, so it needed no port at all.
 */
interface CallIntegrationPort {

    interface Callback {

        fun onCallIntegrationShowIncomingCallUi()

        fun onCallIntegrationDisconnect()

        @JvmSuppressWildcards
        fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice,
            availableAudioDevices: Set<AudioDevice>,
        )

        fun onCallIntegrationReject()

        fun onCallIntegrationAnswer()

        fun onCallIntegrationSilence()

        fun onCallIntegrationMicrophoneEnabled(enabled: Boolean)

        fun applyDtmfTone(dtmf: String): Boolean
    }

    fun setCallback(callback: Callback)

    fun setAddress(address: Uri, presentation: Int)

    fun setCallerDisplayName(name: String, presentation: Int)

    fun setInitialized()

    fun setDialing()

    fun setRinging()

    fun setActive()

    fun setVideoState(videoState: Int)

    fun setInitialAudioDevice(audioDevice: AudioDevice)

    fun setAudioDeviceWhenAvailable(audioDevice: AudioDevice)

    fun startAudioRouting()

    fun isMicrophoneEnabled(): Boolean

    fun isDestroyed(): Boolean

    fun busy()

    fun error()

    fun retracted()

    fun accepted()

    fun rejected()

    fun success()

    fun verifyDisconnected()

    // The audio half. `:ui`'s call screen and `:app`'s connection service both read it off the
    // object this island hands out, so the port carries it as well; pair 3 declares the same
    // three for its own side as `uk.xa0.tulkki.ui.host.UiHost.CallAudio`, and `CallIntegration`
    // implements both.
    fun getSelectedAudioDevice(): AudioDevice

    fun getAudioDevices(): Set<AudioDevice>

    fun setAudioDevice(audioDevice: AudioDevice)

    /** The platform's own audio-state callback, which {@code ConnectionService} forwards. */
    fun onCallAudioStateChanged(state: CallAudioState)
}
