/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package uk.xa0.tulkki.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.common.collect.ImmutableSet
import java.util.HashSet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.webrtc.ThreadUtils
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.app.utils.AppRTCUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager

/**
 * `AppRTCAudioManager` manages all audio related parts of the AppRTC demo.
 *
 * <p>**`audioManager` stays nullable and every Java dereference keeps `!!`.** The field is annotated
 * `@Nullable` in the Java and dereferenced without a check eleven times; `AudioManager?` plus `!!` is
 * that pair, and `!!` is the NPE the Java would have thrown.
 *
 * <p>**The two device fields stay nullable too.** Java's `AudioDevice selectedAudioDevice` and
 * `userSelectedAudioDevice` start as `null` and are only given a value in `start()`, so a non-null
 * Kotlin property would have to invent an initial value the Java never had; only the two places the
 * Java passed `selectedAudioDevice` on unchecked take `!!`.
 *
 * <p>**The four constants move out of the inner class.** `WiredHeadsetReceiver` was a Java inner class
 * with `private static final int` fields, and a Kotlin `inner class` can hold neither a companion nor
 * a static - the `EmojiSearch` `DIFF` treatment. They are private `const val`s in the outer companion
 * and the receiver reads them unqualified; nothing outside the receiver ever named them.
 *
 * <p>The property `hasWiredHeadset` and the function `hasWiredHeadset()` coexist exactly as the Java
 * field and method did - `getHasWiredHeadset()` against `hasWiredHeadset()` on the JVM, so the
 * assignment `hasWiredHeadset = hasWiredHeadset()` resolves to the function, as the Java's did.
 * `@Deprecated` needs a message in Kotlin, so the Java's bare marker carries one that says what the
 * platform deprecated; `@Suppress("DEPRECATION")` replaces `@SuppressWarnings("deprecation")` on
 * `start`/`stop`.
 *
 * <p>Name-string audit: 0 hits for `AppRTCAudioManager`, `AudioManagerState` or `AudioManagerEvents`
 * in the manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and the ProGuard rules.
 */
class AppRTCAudioManager(context: Context) {

    private val apprtcContext: Context = context
    private val bluetoothManager: AppRTCBluetoothManager
    private val audioManager: AudioManager?
    private var audioManagerEvents: AudioManagerEvents? = null
    private var amState: AudioManagerState
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var hasWiredHeadset = false
    private var defaultAudioDevice: AudioDevice
    private var selectedAudioDevice: AudioDevice? = null
    private var userSelectedAudioDevice: AudioDevice? = null

    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private var audioDevices: MutableSet<AudioDevice> = HashSet()

    // Broadcast receiver for wired headset intent broadcasts.
    private val wiredHeadsetReceiver: BroadcastReceiver

    // Callback method for changes in audio focus.
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var ringBackFuture: ScheduledFuture<*>? = null

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bluetoothManager = AppRTCBluetoothManager.create(context, this)
        wiredHeadsetReceiver = WiredHeadsetReceiver()
        amState = AudioManagerState.UNINITIALIZED
        // CallIntegration / Connection uses Earpiece as default too
        if (hasEarpiece()) {
            defaultAudioDevice = AudioDevice.EARPIECE
        } else {
            defaultAudioDevice = AudioDevice.SPEAKER_PHONE
        }
        Log.d(Config.LOGTAG, "defaultAudioDevice: " + defaultAudioDevice)
        AppRTCUtils.logDeviceInfo(Config.LOGTAG)
    }

    fun setAudioManagerEvents(audioManagerEvents: AudioManagerEvents) {
        this.audioManagerEvents = audioManagerEvents
    }

    @Suppress("DEPRECATION")
    fun start() {
        Log.d(Config.LOGTAG, AppRTCAudioManager::class.java.getName() + ".start()")
        ThreadUtils.checkIsOnMainThread()
        if (amState == AudioManagerState.RUNNING) {
            Log.e(Config.LOGTAG, "AudioManager is already active")
            return
        }
        amState = AudioManagerState.RUNNING
        // Store current audio state so we can restore it when stop() is called.
        savedIsSpeakerPhoneOn = audioManager!!.isSpeakerphoneOn()
        savedIsMicrophoneMute = audioManager!!.isMicrophoneMute()
        hasWiredHeadset = hasWiredHeadset()
        // Create an AudioManager.OnAudioFocusChangeListener instance.
        audioFocusChangeListener =
                object : AudioManager.OnAudioFocusChangeListener {
                    // Called on the listener to notify if the audio focus for this listener has
                    // been changed.
                    // The |focusChange| value indicates whether the focus was gained, whether the
                    // focus was lost,
                    // and whether that loss is transient, or whether the new focus holder will hold
                    // it for an
                    // unknown amount of time.
                    // TODO(henrika): possibly extend support of handling audio-focus changes. Only
                    // contains
                    // logging for now.
                    override fun onAudioFocusChange(focusChange: Int) {
                        val typeOfChange =
                                when (focusChange) {
                                    AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ->
                                            "AUDIOFOCUS_GAIN_TRANSIENT"
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE ->
                                            "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK ->
                                            "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                                    AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                                            "AUDIOFOCUS_LOSS_TRANSIENT"
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                                            "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                                    else -> "AUDIOFOCUS_INVALID"
                                }
                        Log.d(Config.LOGTAG, "onAudioFocusChange: " + typeOfChange)
                    }
                }
        // Request audio playout focus (without ducking) and install listener for changes in focus.
        val result =
                audioManager!!.requestAudioFocus(
                        audioFocusChangeListener!!,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(Config.LOGTAG, "Audio focus request granted for VOICE_CALL streams")
        } else {
            Log.e(Config.LOGTAG, "Audio focus request failed")
        }
        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        audioManager!!.setMode(AudioManager.MODE_IN_COMMUNICATION)
        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false)
        // Set initial device states.
        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        audioDevices.clear()
        // Initialize and start Bluetooth if a BT device is available or initiate
        // detection of new (enabled) BT devices.
        bluetoothManager.start()
        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState()
        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        Log.d(Config.LOGTAG, "AudioManager started")
    }

    @Suppress("DEPRECATION")
    fun stop() {
        Log.d(Config.LOGTAG, "appRtpAudioManager.stop()")
        Log.d(Config.LOGTAG, AppRTCAudioManager::class.java.getName() + ".stop()")
        ThreadUtils.checkIsOnMainThread()
        if (amState != AudioManagerState.RUNNING) {
            Log.e(Config.LOGTAG, "Trying to stop AudioManager in incorrect state: " + amState)
            return
        }
        amState = AudioManagerState.UNINITIALIZED
        unregisterReceiver(wiredHeadsetReceiver)
        bluetoothManager.stop()
        // Restore previously stored audio states.
        setSpeakerphoneOn(savedIsSpeakerPhoneOn)
        setMicrophoneMute(savedIsMicrophoneMute)
        try {
            audioManager!!.setMode(AudioManager.MODE_NORMAL)
        } catch (e: SecurityException) {
            Log.e(Config.LOGTAG, "Could not set mode on audio manager: " + audioManager)
        }
        // Abandon audio focus. Gives the previous focus owner, if any, focus.
        audioManager!!.abandonAudioFocus(audioFocusChangeListener)
        audioFocusChangeListener = null
        audioManagerEvents = null
        Log.d(Config.LOGTAG, "appRtpAudioManager.stopped()")
    }

    /** Changes selection of the currently active audio device. */
    private fun setAudioDeviceInternal(device: AudioDevice) {
        Log.d(Config.LOGTAG, "setAudioDeviceInternal(device=" + device + ")")
        AppRTCUtils.assertIsTrue(audioDevices.contains(device))
        when (device) {
            AudioDevice.SPEAKER_PHONE -> setSpeakerphoneOn(true)
            AudioDevice.EARPIECE,
            AudioDevice.WIRED_HEADSET,
            AudioDevice.BLUETOOTH -> setSpeakerphoneOn(false)
            else -> Log.e(Config.LOGTAG, "Invalid audio device selection")
        }
        selectedAudioDevice = device
    }

    /**
     * Changes default audio device. TODO(henrika): add usage of this method in the AppRTCMobile
     * client.
     */
    fun setDefaultAudioDevice(defaultDevice: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        when (defaultDevice) {
            AudioDevice.SPEAKER_PHONE -> defaultAudioDevice = defaultDevice
            AudioDevice.EARPIECE -> {
                if (hasEarpiece()) {
                    defaultAudioDevice = defaultDevice
                } else {
                    defaultAudioDevice = AudioDevice.SPEAKER_PHONE
                }
            }
            else -> Log.e(Config.LOGTAG, "Invalid default audio device selection")
        }
        Log.d(Config.LOGTAG, "setDefaultAudioDevice(device=" + defaultAudioDevice + ")")
        updateAudioDeviceState()
    }

    /** Changes selection of the currently active audio device. */
    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!audioDevices.contains(device)) {
            Log.e(Config.LOGTAG, "Can not select " + device + " from available " + audioDevices)
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    /** Returns current set of available/selectable audio devices. */
    fun getAudioDevices(): Set<AudioDevice> {
        return ImmutableSet.copyOf(audioDevices)
    }

    /** Returns the currently selected audio device. */
    fun getSelectedAudioDevice(): AudioDevice? {
        return selectedAudioDevice
    }

    /** Helper method for receiver registration. */
    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    /** Helper method for unregistration of an existing receiver. */
    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        apprtcContext.unregisterReceiver(receiver)
    }

    /** Sets the speaker phone mode. */
    private fun setSpeakerphoneOn(on: Boolean) {
        val wasOn = audioManager!!.isSpeakerphoneOn()
        if (wasOn == on) {
            return
        }
        audioManager!!.setSpeakerphoneOn(on)
    }

    /** Sets the microphone mute state. */
    private fun setMicrophoneMute(on: Boolean) {
        val wasMuted = audioManager!!.isMicrophoneMute()
        if (wasMuted == on) {
            return
        }
        audioManager!!.setMicrophoneMute(on)
    }

    /** Gets the current earpiece state. */
    private fun hasEarpiece(): Boolean {
        return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Checks whether a wired headset is connected or not. This is not a valid indication that audio
     * playback is actually over the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached wired headset.
     */
    @Deprecated("the platform deprecated the wired-headset query this reads")
    private fun hasWiredHeadset(): Boolean {
        val devices = audioManager!!.getDevices(AudioManager.GET_DEVICES_ALL)
        for (device in devices) {
            val type = device.getType()
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                Log.d(Config.LOGTAG, "hasWiredHeadset: found wired headset")
                return true
            } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                Log.d(Config.LOGTAG, "hasWiredHeadset: found USB audio device")
                return true
            }
        }
        return false
    }

    /**
     * Updates list of possible audio devices and make new device selection. TODO(henrika): add unit
     * test to verify all state transitions.
     */
    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(
                Config.LOGTAG,
                "--- updateAudioDeviceState: " +
                        "wired headset=" +
                        hasWiredHeadset +
                        ", " +
                        "BT state=" +
                        bluetoothManager.getState())
        Log.d(
                Config.LOGTAG,
                "Device status: " +
                        "available=" +
                        audioDevices +
                        ", " +
                        "selected=" +
                        selectedAudioDevice +
                        ", " +
                        "user selected=" +
                        userSelectedAudioDevice)
        // Check if any Bluetooth headset is connected. The internal BT state will
        // change accordingly.
        // TODO(henrika): perhaps wrap required state into BT manager.
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice()
        }
        // Update the set of available audio devices.
        val newAudioDevices = HashSet<AudioDevice>()
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AudioDevice.BLUETOOTH)
        }
        if (hasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE)
            }
        }
        // Store state which is set to true if the device list has changed.
        var audioDeviceSetUpdated = audioDevices != newAudioDevices
        // Update the existing audio device set.
        audioDevices = newAudioDevices
        // Correct user selected audio devices if needed.
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE &&
                userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            // If BT is not available, it can't be the user selection.
            userSelectedAudioDevice = AudioDevice.NONE
        }
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }
        // Need to start Bluetooth if it is available and user either selected it explicitly or
        // user did not select any output device.
        val needBluetoothAudioStart =
                bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE &&
                        (userSelectedAudioDevice == AudioDevice.NONE ||
                                userSelectedAudioDevice == AudioDevice.BLUETOOTH)
        // Need to stop Bluetooth audio if user selected different device and
        // Bluetooth SCO connection is established or in the process.
        val needBluetoothAudioStop =
                (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED ||
                        bluetoothManager.getState() ==
                                AppRTCBluetoothManager.State.SCO_CONNECTING) &&
                        (userSelectedAudioDevice != AudioDevice.NONE &&
                                userSelectedAudioDevice != AudioDevice.BLUETOOTH)
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING ||
                bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            Log.d(
                    Config.LOGTAG,
                    "Need BT audio: start=" +
                            needBluetoothAudioStart +
                            ", " +
                            "stop=" +
                            needBluetoothAudioStop +
                            ", " +
                            "BT state=" +
                            bluetoothManager.getState())
        }
        // Start or stop Bluetooth SCO connection given states set earlier.
        if (needBluetoothAudioStop) {
            bluetoothManager.stopScoAudio()
            bluetoothManager.updateDevice()
        }
        if (needBluetoothAudioStart && !needBluetoothAudioStop) {
            // Attempt to start Bluetooth SCO audio (takes a few second to start).
            if (!bluetoothManager.startScoAudio()) {
                // Remove BLUETOOTH from list of available devices since SCO failed.
                audioDevices.remove(AudioDevice.BLUETOOTH)
                audioDeviceSetUpdated = true
            }
        }
        // Update selected audio device.
        val newAudioDevice: AudioDevice
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            // If a Bluetooth is connected, then it should be used as output audio
            // device. Note that it is not sufficient that a headset is available;
            // an active SCO channel must also be up and running.
            newAudioDevice = AudioDevice.BLUETOOTH
        } else if (hasWiredHeadset) {
            // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
            // audio device.
            newAudioDevice = AudioDevice.WIRED_HEADSET
        } else {
            // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            // |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or
            // AudioDevice.EARPIECE
            // depending on the user's selection.
            newAudioDevice = defaultAudioDevice
        }
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            // Do the required device switch.
            setAudioDeviceInternal(newAudioDevice)
            Log.d(
                    Config.LOGTAG,
                    "New device status: " +
                            "available=" +
                            audioDevices +
                            ", " +
                            "selected=" +
                            newAudioDevice)
            if (audioManagerEvents != null) {
                // Notify a listening client that audio device has been changed.
                audioManagerEvents!!.onAudioDeviceChanged(selectedAudioDevice!!, audioDevices)
            }
        }
        Log.d(Config.LOGTAG, "--- updateAudioDeviceState done")
    }

    fun executeOnMain(runnable: Runnable) {
        ContextCompat.getMainExecutor(apprtcContext).execute(runnable)
    }

    fun startRingBack() {
        this.ringBackFuture =
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(
                        Runnable {
                            val toneGenerator =
                                    ToneGenerator(
                                            AudioManager.STREAM_MUSIC,
                                            CallIntegration.DEFAULT_TONE_VOLUME)
                            toneGenerator.startTone(ToneGenerator.TONE_CDMA_DIAL_TONE_LITE, 750)
                        },
                        0,
                        3,
                        TimeUnit.SECONDS)
    }

    fun stopRingBack() {
        val future = this.ringBackFuture
        if (future == null || future.isDone) {
            return
        }
        future.cancel(true)
    }

    /** AudioManager state. */
    enum class AudioManagerState {
        UNINITIALIZED,
        PREINITIALIZED,
        RUNNING,
    }

    /** Selected audio device change event. */
    fun interface AudioManagerEvents {
        // Callback fired once audio device is changed or list of available audio devices changed.
        fun onAudioDeviceChanged(
                selectedAudioDevice: AudioDevice,
                // `@JvmSuppressWildcards` is load-bearing, and the reason is the type argument's
                // finality, not its language (kotlinc 2.3.21, lane E, 2026-10-08): an enum
                // argument - `AudioDevice` is a Kotlin `enum class` now - still gets
                // `Set<? extends AudioDevice>`, and only a Java `final class` argument drops the
                // wildcard. Without the annotation the parameter compiles to
                // `Set<? extends AudioDevice>` and `CallIntegration`'s `this::onAudioDeviceChanged` -
                // whose own parameter is Java's invariant `Set<AudioDevice>` - stops being an
                // applicable method reference. The Java interface's `Set<AudioDevice>` had no
                // wildcard, so neither may this one.
                availableAudioDevices: Set<@JvmSuppressWildcards AudioDevice>
        )
    }

    /* Receiver which handles changes in wired headset availability. */
    private inner class WiredHeadsetReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra("state", STATE_UNPLUGGED)
            val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
            val name = intent.getStringExtra("name")
            Log.d(
                    Config.LOGTAG,
                    "WiredHeadsetReceiver.onReceive" +
                            AppRTCUtils.getThreadInfo() +
                            ": " +
                            "a=" +
                            intent.getAction() +
                            ", s=" +
                            (if (state == STATE_UNPLUGGED) "unplugged" else "plugged") +
                            ", m=" +
                            (if (microphone == HAS_MIC) "mic" else "no mic") +
                            ", n=" +
                            name +
                            ", sb=" +
                            isInitialStickyBroadcast())
            hasWiredHeadset = (state == STATE_PLUGGED)
            updateAudioDeviceState()
        }
    }

    companion object {

        // The receiver's own four, which a Kotlin `inner class` cannot hold: it may neither declare a
        // companion nor keep a static, and these were `private static final int` in the Java.
        private const val STATE_UNPLUGGED = 0
        private const val STATE_PLUGGED = 1
        private const val HAS_NO_MIC = 0
        private const val HAS_MIC = 1
    }
}
