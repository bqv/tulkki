package uk.xa0.tulkki.app.services

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.util.MainThreadExecutor
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The platform's self-managed call, routed to the app's own audio stack.
 *
 * <p>**A static field is not reachable through a subclass in Kotlin.** The Java wrote the inherited
 * `PROPERTY_SELF_MANAGED`, `CAPABILITY_MUTE`, `CAPABILITY_RESPOND_VIA_TEXT`, `STATE_DIALING`,
 * `STATE_ACTIVE` and `STATE_DISCONNECTED` bare, because Java inherits its superclass's statics; every
 * one of them now names `Connection`, which is where it is declared.
 *
 * <p>**Three fields had to be renamed, not translated.** The Java's `isMicrophoneEnabled` and
 * `isDestroyed` fields shared their names with the interface's `isMicrophoneEnabled()`/`isDestroyed()`
 * methods, and a Kotlin `Boolean` property named `isX` compiles to that same getter - a platform
 * declaration clash. The backing fields are `microphoneEnabled` and `destroyed`; the public methods
 * are the interface's.
 *
 * <p>**The callback is nullable and the Java dereferenced it.** `callback!!` is that dereference:
 * an unset callback is the same `NullPointerException` the Java would have thrown, not a silent skip.
 *
 * <p>`initialAudioDevice` is a mutable field, so Kotlin cannot smart-cast it;
 * `configureInitialAudioDevice` binds the Java's two locals and adds the `target != null` test the
 * Java got for free - `ImmutableSet.contains(null)` answers false, so the branch is the same.
 * `setAudioDeviceUpsideDownCake`'s `OutcomeReceiver` is a SAM (its `onError` has a default), so the
 * Java's lambda stays one. `AppRTCAudioManager.AudioManagerEvents` is a `fun interface`, so the Java's
 * `this::onAudioDeviceChanged` method reference becomes a lambda.
 */
class CallIntegration(rawContext: Context) :
        Connection(),
        UiHost.CallAudio,
        uk.xa0.tulkki.xmpp.services.CallIntegrationPort {

    private val context: Context = rawContext.applicationContext

    private val appRTCAudioManager: AppRTCAudioManager?
    private var initialAudioDevice: AudioDevice? = null

    private var isAudioRoutingRequested = false
    private val initialAudioDeviceConfigured = AtomicBoolean(false)
    private val delayedDestructionInitiated = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var microphoneEnabled = true

    private var callback: uk.xa0.tulkki.xmpp.services.CallIntegrationPort.Callback? = null

    init {
        if (selfManaged()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
            } else {
                throw AssertionError(
                        "Trying to set connection properties on unsupported version")
            }
            appRTCAudioManager = null
        } else {
            appRTCAudioManager = AppRTCAudioManager(rawContext)
            appRTCAudioManager.setAudioManagerEvents { selectedAudioDevice, availableAudioDevices ->
                onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices)
            }
        }
        setRingbackRequested(true)
        setConnectionCapabilities(
                Connection.CAPABILITY_MUTE or Connection.CAPABILITY_RESPOND_VIA_TEXT)
    }

    override fun setCallback(callback: uk.xa0.tulkki.xmpp.services.CallIntegrationPort.Callback) {
        this.callback = callback
    }

    override fun onShowIncomingCallUi() {
        Log.d(Config.LOGTAG, "onShowIncomingCallUi")
        callback!!.onCallIntegrationShowIncomingCallUi()
    }

    override fun onAnswer() {
        callback!!.onCallIntegrationAnswer()
    }

    override fun onDisconnect() {
        Log.d(Config.LOGTAG, "onDisconnect()")
        callback!!.onCallIntegrationDisconnect()
    }

    override fun onReject() {
        callback!!.onCallIntegrationReject()
    }

    override fun onReject(replyMessage: String) {
        Log.d(Config.LOGTAG, "onReject(" + replyMessage + ")")
        callback!!.onCallIntegrationReject()
    }

    override fun onPlayDtmfTone(c: Char) {
        callback!!.applyDtmfTone("" + c)
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        Log.d(Config.LOGTAG, "onAvailableCallEndpointsChanged(" + availableEndpoints + ")")
        this.availableEndpoints = availableEndpoints
        this.onAudioDeviceChanged(
                getAudioDeviceUpsideDownCake(getCurrentCallEndpoint()),
                ImmutableSet.copyOf(
                        Lists.transform(availableEndpoints) { e ->
                            getAudioDeviceUpsideDownCake(e)
                        }))
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        Log.d(Config.LOGTAG, "onCallEndpointChanged()")
        this.onAudioDeviceChanged(
                getAudioDeviceUpsideDownCake(callEndpoint),
                ImmutableSet.copyOf(
                        Lists.transform(this.availableEndpoints) { e ->
                            getAudioDeviceUpsideDownCake(e)
                        }))
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (selfManaged() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(Config.LOGTAG, "ignoring onCallAudioStateChange() on Upside Down Cake")
            return
        }
        setMicrophoneEnabled(!state.isMuted)
        Log.d(Config.LOGTAG, "onCallAudioStateChange(" + state + ")")
        this.onAudioDeviceChanged(getAudioDeviceOreo(state), getAudioDevicesOreo(state))
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        Log.d(Config.LOGTAG, "onMuteStateChanged(" + isMuted + ")")
        setMicrophoneEnabled(!isMuted)
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        this.microphoneEnabled = enabled
        callback!!.onCallIntegrationMicrophoneEnabled(enabled)
    }

    override fun isMicrophoneEnabled(): Boolean = this.microphoneEnabled

    override fun getAudioDevices(): Set<AudioDevice> {
        return if (notSelfManaged(context)) {
            getAudioDevicesFallback()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getAudioDevicesUpsideDownCake()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getAudioDevicesOreo()
        } else {
            throw AssertionError("Trying to get audio devices on unsupported version")
        }
    }

    override fun getSelectedAudioDevice(): AudioDevice {
        return if (notSelfManaged(context)) {
            getAudioDeviceFallback()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getAudioDeviceUpsideDownCake()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getAudioDeviceOreo()
        } else {
            throw AssertionError("Trying to get selected audio device on unsupported version")
        }
    }

    override fun setAudioDevice(audioDevice: AudioDevice) {
        if (notSelfManaged(context)) {
            setAudioDeviceFallback(audioDevice)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setAudioDeviceUpsideDownCake(audioDevice)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setAudioDeviceOreo(audioDevice)
        } else {
            throw AssertionError("Trying to set audio devices on unsupported version")
        }
    }

    override fun setAudioDeviceWhenAvailable(audioDevice: AudioDevice) {
        val available = getAudioDevices()
        if (available.contains(audioDevice) && !available.contains(AudioDevice.BLUETOOTH)) {
            this.setAudioDevice(audioDevice)
        } else {
            Log.d(
                    Config.LOGTAG,
                    "application requested to switch to "
                            + audioDevice
                            + " but we won't because available devices are "
                            + available)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getAudioDevicesUpsideDownCake(): Set<AudioDevice> =
            ImmutableSet.copyOf(
                    Lists.transform(this.availableEndpoints) { e ->
                        getAudioDeviceUpsideDownCake(e)
                    })

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getAudioDeviceUpsideDownCake(): AudioDevice =
            getAudioDeviceUpsideDownCake(getCurrentCallEndpoint())

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun setAudioDeviceUpsideDownCake(audioDevice: AudioDevice) {
        val callEndpointOptional =
                Iterables.tryFind(this.availableEndpoints) { e ->
                    getAudioDeviceUpsideDownCake(e) == audioDevice
                }
        if (callEndpointOptional.isPresent) {
            val endpoint = callEndpointOptional.get()
            requestCallEndpointChange(
                    endpoint,
                    MainThreadExecutor.getInstance(),
                    { _ -> Log.d(Config.LOGTAG, "switched to endpoint " + endpoint) })
        } else {
            Log.w(Config.LOGTAG, "no endpoint found matching " + audioDevice)
        }
    }

    private fun getAudioDevicesOreo(): Set<AudioDevice> {
        val audioState = getCallAudioState()
        if (audioState == null) {
            Log.d(
                    Config.LOGTAG,
                    "no CallAudioState available. returning empty set for audio devices")
            return emptySet()
        }
        return getAudioDevicesOreo(audioState)
    }

    private fun getAudioDeviceOreo(): AudioDevice {
        val audioState = getCallAudioState()
        if (audioState == null) {
            Log.d(Config.LOGTAG, "no CallAudioState available. returning NONE as audio device")
            return AudioDevice.NONE
        }
        return getAudioDeviceOreo(audioState)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun setAudioDeviceOreo(audioDevice: AudioDevice) {
        when (audioDevice) {
            AudioDevice.EARPIECE -> setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            AudioDevice.BLUETOOTH -> setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            AudioDevice.WIRED_HEADSET -> setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
            AudioDevice.SPEAKER_PHONE -> setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            else -> Unit
        }
    }

    private fun getAudioDevicesFallback(): Set<AudioDevice> =
            requireAppRtcAudioManager().getAudioDevices()

    private fun getAudioDeviceFallback(): AudioDevice {
        val audioDevice = requireAppRtcAudioManager().getSelectedAudioDevice()
        return if (audioDevice == null) AudioDevice.NONE else audioDevice
    }

    private fun setAudioDeviceFallback(audioDevice: AudioDevice) {
        val audioManager = requireAppRtcAudioManager()
        audioManager.executeOnMain { audioManager.setDefaultAudioDevice(audioDevice) }
    }

    private fun requireAppRtcAudioManager(): AppRTCAudioManager =
            appRTCAudioManager
                    ?: throw IllegalStateException(
                            "You are trying to access the fallback audio manager on a modern"
                                    + " device")

    override fun onSilence() {
        callback!!.onCallIntegrationSilence()
    }

    override fun onStateChanged(state: Int) {
        Log.d(Config.LOGTAG, "onStateChanged(" + state + ")")
        if (notSelfManaged(context)) {
            if (state == Connection.STATE_DIALING) {
                requireAppRtcAudioManager().startRingBack()
            } else {
                requireAppRtcAudioManager().stopRingBack()
            }
        }
        if (state == Connection.STATE_ACTIVE) {
            startTone(DEFAULT_TONE_VOLUME, ToneGenerator.TONE_CDMA_ANSWER, 100)
        } else if (state == Connection.STATE_DISCONNECTED) {
            val audioManager = this.appRTCAudioManager
            if (audioManager != null) {
                audioManager.executeOnMain { audioManager.stop() }
            }
        }
    }

    override fun success() {
        Log.d(Config.LOGTAG, "CallIntegration.success()")
        startTone(DEFAULT_TONE_VOLUME, ToneGenerator.TONE_CDMA_CONFIRM, 600)
        this.destroyWithDelay(DisconnectCause(DisconnectCause.LOCAL, null), 600)
    }

    override fun accepted() {
        Log.d(Config.LOGTAG, "CallIntegration.accepted()")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            this.destroyWith(DisconnectCause(DisconnectCause.ANSWERED_ELSEWHERE, null))
        } else {
            this.destroyWith(DisconnectCause(DisconnectCause.CANCELED, null))
        }
    }

    override fun error() {
        Log.d(Config.LOGTAG, "CallIntegration.error()")
        startTone(DEFAULT_TONE_VOLUME, ToneGenerator.TONE_CDMA_CONFIRM, 600)
        this.destroyWithDelay(DisconnectCause(DisconnectCause.ERROR, null), 600)
    }

    override fun retracted() {
        Log.d(Config.LOGTAG, "CallIntegration.retracted()")
        // an alternative cause would be LOCAL
        this.destroyWith(DisconnectCause(DisconnectCause.CANCELED, null))
    }

    override fun rejected() {
        Log.d(Config.LOGTAG, "CallIntegration.rejected()")
        this.destroyWith(DisconnectCause(DisconnectCause.REJECTED, null))
    }

    override fun busy() {
        Log.d(Config.LOGTAG, "CallIntegration.busy()")
        startTone(80, ToneGenerator.TONE_CDMA_NETWORK_BUSY, 2500)
        this.destroyWithDelay(DisconnectCause(DisconnectCause.BUSY, null), 2500)
    }

    private fun destroyWithDelay(disconnectCause: DisconnectCause, delay: Int) {
        if (this.delayedDestructionInitiated.compareAndSet(false, true)) {
            JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(
                    {
                        this.setDisconnected(disconnectCause)
                        this.destroyCallIntegration()
                    },
                    delay.toLong(),
                    TimeUnit.MILLISECONDS)
        } else {
            Log.w(Config.LOGTAG, "CallIntegration destruction has already been scheduled!")
        }
    }

    private fun destroyWith(disconnectCause: DisconnectCause) {
        if (this.getState() == Connection.STATE_DISCONNECTED ||
                this.delayedDestructionInitiated.get()) {
            Log.d(Config.LOGTAG, "CallIntegration has already been destroyed")
            return
        }
        this.setDisconnected(disconnectCause)
        this.destroyCallIntegration()
        Log.d(Config.LOGTAG, "destroyed!")
    }

    private fun startTone(volume: Int, toneType: Int, durationMs: Int) {
        val toneGenerator: ToneGenerator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume)
        } catch (e: RuntimeException) {
            Log.e(Config.LOGTAG, "could not initialize tone generator", e)
            return
        }
        toneGenerator.startTone(toneType, durationMs)
    }

    override fun verifyDisconnected() {
        if (this.getState() == Connection.STATE_DISCONNECTED ||
                this.delayedDestructionInitiated.get()) {
            return
        }
        throw AssertionError("CallIntegration has not been disconnected")
    }

    private fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice,
            availableAudioDevices: Set<AudioDevice>
    ) {
        if (isAudioRoutingRequested) {
            configureInitialAudioDevice(availableAudioDevices)
        }
        val callback = this.callback
        if (callback == null) {
            return
        }
        callback.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices)
    }

    private fun configureInitialAudioDevice(availableAudioDevices: Set<AudioDevice>) {
        val initialAudioDevice = this.initialAudioDevice
        if (initialAudioDevice == null) {
            Log.d(Config.LOGTAG, "skipping configureInitialAudioDevice()")
            return
        }
        val target = this.initialAudioDevice
        if (this.initialAudioDeviceConfigured.compareAndSet(false, true)) {
            if (target != null &&
                    availableAudioDevices.contains(target) &&
                    !availableAudioDevices.contains(AudioDevice.BLUETOOTH)) {
                setAudioDevice(target)
                Log.d(Config.LOGTAG, "configured initial audio device: " + target)
            } else {
                Log.d(
                        Config.LOGTAG,
                        "not setting initial audio device. available devices: "
                                + availableAudioDevices)
            }
        }
    }

    private fun selfManaged(): Boolean = selfManaged(context)

    override fun setInitialAudioDevice(audioDevice: AudioDevice) {
        Log.d(Config.LOGTAG, "setInitialAudioDevice(" + audioDevice + ")")
        this.initialAudioDevice = audioDevice
    }

    override fun startAudioRouting() {
        this.isAudioRoutingRequested = true
        if (selfManaged()) {
            val devices = getAudioDevices()
            if (devices.isEmpty()) {
                return
            }
            configureInitialAudioDevice(devices)
            return
        }
        val audioManager = requireAppRtcAudioManager()
        audioManager.executeOnMain {
            audioManager.start()
            this.onAudioDeviceChanged(
                    audioManager.getSelectedAudioDevice()!!, audioManager.getAudioDevices())
        }
    }

    private fun destroyCallIntegration() {
        super.destroy()
        this.destroyed.set(true)
    }

    override fun isDestroyed(): Boolean = this.destroyed.get()

    companion object {

        /**
         * Samsung Galaxy Tab A claims to have FEATURE_CONNECTION_SERVICE but then throws
         * SecurityException when invoking placeCall(). Both Stock and LineageOS have this problem.
         *
         * <p>Lenovo Yoga Smart Tab YT-X705F claims to have FEATURE_CONNECTION_SERVICE but throws
         * SecurityException
         */
        private val BROKEN_DEVICE_MODELS: List<String> =
                listOf("gtaxlwifi", "a5y17lte", "YT-X705F", "HWAGS2")

        /**
         * all Realme devices at least up to and including Android 11 are broken
         *
         * <p>we are relatively sure that old Oppo devices are broken too. We get reports of 'number
         * not sent' from Oppo R15x (Android 10)
         *
         * <p>OnePlus 6 (Android 8.1-11) Device is buggy and always starts the OS call screen even
         * though we want to be self managed
         *
         * <p>a bunch of OnePlus devices are broken in other ways
         */
        private val BROKEN_MANUFACTURES_UP_TO_11: List<String> =
                listOf("realme", "oppo", "oneplus")

        const val DEFAULT_TONE_VOLUME = 60

        @JvmStatic
        fun selfManagedAvailable(context: Context): Boolean =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        Build.VERSION.SDK_INT < 35 &&
                        hasSystemFeature(context) &&
                        isDeviceModelSupported()

        fun selfManaged(context: Context): Boolean =
                selfManagedAvailable(context) && AppSettings(context).isCallIntegration()

        @JvmStatic
        fun hasSystemFeature(context: Context): Boolean {
            val packageManager = context.packageManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
            } else {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
            }
        }

        private fun isDeviceModelSupported(): Boolean {
            val manufacturer =
                    Strings.nullToEmpty(Build.MANUFACTURER).lowercase(Locale.ROOT)
            if (BROKEN_DEVICE_MODELS.contains(Build.DEVICE)) {
                return false
            }
            if (BROKEN_MANUFACTURES_UP_TO_11.contains(manufacturer) &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                return false
            }
            // we only know of one Umidigi device (BISON_GT2_5G) that doesn't work (audio is not
            // being routed properly) However with those devices being extremely rare it's impossible
            // to gauge how many might be effected and no Naomi Wu around to clarify with the company
            // directly
            if ("umidigi" == manufacturer && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
                return false
            }
            // SailfishOS's AppSupport do not support Call Integration
            if (Build.MODEL.endsWith("(AppSupport)")) {
                return false
            }
            return true
        }

        fun notSelfManaged(context: Context): Boolean = !selfManaged(context)

        @JvmStatic
        fun address(contact: Jid): Uri = Uri.parse(String.format("xmpp:%s", contact.toString()))

        @JvmStatic
        fun initialAudioDevice(media: Set<Media>): AudioDevice =
                if (Media.audioOnly(media)) {
                    AudioDevice.EARPIECE
                } else {
                    AudioDevice.SPEAKER_PHONE
                }

        @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun getAudioDeviceUpsideDownCake(callEndpoint: CallEndpoint?): AudioDevice {
            if (callEndpoint == null) {
                return AudioDevice.NONE
            }
            val endpointType = callEndpoint.endpointType
            return when (endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> AudioDevice.BLUETOOTH
                CallEndpoint.TYPE_EARPIECE -> AudioDevice.EARPIECE
                CallEndpoint.TYPE_SPEAKER -> AudioDevice.SPEAKER_PHONE
                CallEndpoint.TYPE_WIRED_HEADSET -> AudioDevice.WIRED_HEADSET
                CallEndpoint.TYPE_STREAMING -> AudioDevice.STREAMING
                CallEndpoint.TYPE_UNKNOWN -> AudioDevice.NONE
                else -> throw IllegalStateException("Unknown endpoint type " + endpointType)
            }
        }

        private fun getAudioDevicesOreo(callAudioState: CallAudioState): Set<AudioDevice> {
            val supportedAudioDevicesBuilder = ImmutableSet.Builder<AudioDevice>()
            val supportedRouteMask = callAudioState.supportedRouteMask
            if ((supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) ==
                    CallAudioState.ROUTE_BLUETOOTH) {
                supportedAudioDevicesBuilder.add(AudioDevice.BLUETOOTH)
            }
            if ((supportedRouteMask and CallAudioState.ROUTE_EARPIECE) ==
                    CallAudioState.ROUTE_EARPIECE) {
                supportedAudioDevicesBuilder.add(AudioDevice.EARPIECE)
            }
            if ((supportedRouteMask and CallAudioState.ROUTE_SPEAKER) ==
                    CallAudioState.ROUTE_SPEAKER) {
                supportedAudioDevicesBuilder.add(AudioDevice.SPEAKER_PHONE)
            }
            if ((supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET) ==
                    CallAudioState.ROUTE_WIRED_HEADSET) {
                supportedAudioDevicesBuilder.add(AudioDevice.WIRED_HEADSET)
            }
            return supportedAudioDevicesBuilder.build()
        }

        private fun getAudioDeviceOreo(audioState: CallAudioState): AudioDevice {
            // technically we get a mask here; maybe we should query the mask instead
            return when (audioState.route) {
                CallAudioState.ROUTE_BLUETOOTH -> AudioDevice.BLUETOOTH
                CallAudioState.ROUTE_EARPIECE -> AudioDevice.EARPIECE
                CallAudioState.ROUTE_SPEAKER -> AudioDevice.SPEAKER_PHONE
                CallAudioState.ROUTE_WIRED_HEADSET -> AudioDevice.WIRED_HEADSET
                else -> AudioDevice.NONE
            }
        }
    }
}
