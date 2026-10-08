/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package uk.xa0.tulkki.app.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.common.collect.ImmutableList
import java.util.Collections
import org.webrtc.ThreadUtils
import uk.xa0.tulkki.app.utils.AppRTCUtils
import uk.xa0.tulkki.xmpp.Config

/**
 * `AppRTCProximitySensor` manages functions related to Bluetoth devices in the AppRTC demo.
 *
 * <p>**The class stays open, its constructor protected and its five stubs `open`.** The Java declared
 * them `protected` "for test mocks" - `getAudioManager`, `registerReceiver`, `unregisterReceiver`,
 * `getBluetoothProfileProxy` and `hasBluetoothConnectPermission` - and Kotlin's members are final
 * where Java's were virtual, so a subclass that wanted to override one could not. No test in this tree
 * subclasses it today; the shape is the Java's.
 *
 * <p>**Three nullable fields, and the Java's own null checks become locals.** `audioManager` is
 * `@Nullable` and dereferenced unchecked in `startScoAudio`/`stopScoAudio`/`isScoOn`, so those keep
 * `!!`. `bluetoothHeadset` is `@Nullable` and each reader tests it first, and Kotlin cannot smart-cast
 * a mutable field, so `updateDevice` and `bluetoothTimeout` bind it once to `headset` after the Java's
 * own test - the same object the Java would have re-read. `bluetoothDevice` is read straight after it
 * is assigned, so `updateDevice` binds that one to a local too.
 *
 * <p>The three `@Nullable` device fields stay nullable, `scoConnectionAttempts` stays the Java's
 * package-private field (Kotlin's default), and the two private statics are `const val`s in the
 * companion. `action.equals(...)` becomes structural `==`: the Java would have thrown on a null
 * action, and a receiver registered for these two actions is never handed one.
 *
 * <p>`create` is a companion function rather than `@JvmStatic`: the Java's was package-private and its
 * only caller, `AppRTCAudioManager`, is Kotlin now.
 *
 * <p>Name-string audit: 0 hits for `AppRTCBluetoothManager`, `State` or either inner receiver in the
 * manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and the ProGuard rules.
 */
open class AppRTCBluetoothManager protected constructor(
        context: Context,
        audioManager: AppRTCAudioManager
) {

    private val apprtcContext: Context = context
    private val apprtcAudioManager: AppRTCAudioManager = audioManager
    private val audioManager: AudioManager?
    private val handler: Handler
    private val bluetoothServiceListener: BluetoothProfile.ServiceListener
    private val bluetoothHeadsetReceiver: BroadcastReceiver

    var scoConnectionAttempts = 0

    private var bluetoothState: State
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var bluetoothDevice: BluetoothDevice? = null

    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private val bluetoothTimeoutRunnable = Runnable { bluetoothTimeout() }

    init {
        this.audioManager = getAudioManager(context)
        bluetoothState = State.UNINITIALIZED
        bluetoothServiceListener = BluetoothServiceListener()
        bluetoothHeadsetReceiver = BluetoothHeadsetBroadcastReceiver()
        handler = Handler(Looper.getMainLooper())
    }

    /** Returns the internal state. */
    fun getState(): State {
        ThreadUtils.checkIsOnMainThread()
        return bluetoothState
    }

    /**
     * Activates components required to detect Bluetooth devices and to enable BT SCO (audio is routed
     * via BT SCO) for the headset profile. The end state will be HEADSET_UNAVAILABLE but a state
     * machine has started which will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled. Example of state change sequence when start() is called while
     * BT device is connected and enabled: UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE
     * --> SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO. Note that the
     * AppRTCAudioManager is also involved in driving this state change.
     */
    fun start() {
        ThreadUtils.checkIsOnMainThread()
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(Config.LOGTAG, "Invalid BT state")
            return
        }
        bluetoothHeadset = null
        bluetoothDevice = null
        scoConnectionAttempts = 0
        // Get a handle to the default local Bluetooth adapter.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.w(Config.LOGTAG, "Device does not support Bluetooth")
            return
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (this.audioManager == null || !audioManager!!.isBluetoothScoAvailableOffCall()) {
            Log.e(Config.LOGTAG, "Bluetooth SCO audio is not available off call")
            return
        }
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(
                apprtcContext, bluetoothServiceListener, BluetoothProfile.HEADSET)) {
            Log.e(Config.LOGTAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed")
            return
        }
        // Register receivers for BluetoothHeadset change notifications.
        val bluetoothHeadsetFilter = IntentFilter()
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        registerReceiver(bluetoothHeadsetReceiver, bluetoothHeadsetFilter)
        if (hasBluetoothConnectPermission()) {
            Log.d(
                    Config.LOGTAG,
                    "HEADSET profile state: " +
                            stateToString(
                                    bluetoothAdapter!!.getProfileConnectionState(
                                            BluetoothProfile.HEADSET)))
        }
        Log.d(Config.LOGTAG, "Bluetooth proxy for headset profile has started")
        bluetoothState = State.HEADSET_UNAVAILABLE
        Log.d(Config.LOGTAG, "start done: BT state=" + bluetoothState)
    }

    /** Stops and closes all components related to Bluetooth audio. */
    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(Config.LOGTAG, "stop: BT state=" + bluetoothState)
        val adapter = bluetoothAdapter
        if (adapter == null) {
            return
        }
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio()
        // Close down remaining BT resources.
        if (bluetoothState == State.UNINITIALIZED) {
            return
        }
        unregisterReceiver(bluetoothHeadsetReceiver)
        cancelTimer()
        val headset = bluetoothHeadset
        if (headset != null) {
            adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset)
            bluetoothHeadset = null
        }
        bluetoothAdapter = null
        bluetoothDevice = null
        bluetoothState = State.UNINITIALIZED
        Log.d(Config.LOGTAG, "stop done: BT state=" + bluetoothState)
    }

    /**
     * Starts Bluetooth SCO connection with remote device. Note that the phone application always has
     * the priority on the usage of the SCO connection for telephony. If this method is called while
     * the phone is in call it will be ignored. Similarly, if a call is received or sent while an
     * application is using the SCO connection, the connection will be lost for the application and
     * NOT returned automatically when the call ends. Also note that: up to and including API version
     * JELLY_BEAN_MR1, this method initiates a virtual voice call to the Bluetooth headset. After API
     * version JELLY_BEAN_MR2 only a raw SCO audio connection is established. TODO(henrika): should we
     * add support for virtual voice call to BT headset also for JBMR2 and higher. It might be required
     * to initiates a virtual voice call since many devices do not accept SCO audio without a "call".
     */
    fun startScoAudio(): Boolean {
        ThreadUtils.checkIsOnMainThread()
        Log.d(
                Config.LOGTAG,
                "startSco: BT state=" +
                        bluetoothState +
                        ", " +
                        "attempts: " +
                        scoConnectionAttempts +
                        ", " +
                        "SCO is on: " +
                        isScoOn())
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.e(Config.LOGTAG, "BT SCO connection fails - no more attempts")
            return false
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Log.e(Config.LOGTAG, "BT SCO connection fails - no headset available")
            return false
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        Log.d(Config.LOGTAG, "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...")
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be
        // SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING
        audioManager!!.startBluetoothSco()
        audioManager!!.setBluetoothScoOn(true)
        scoConnectionAttempts++
        startTimer()
        Log.d(
                Config.LOGTAG,
                "startScoAudio done: BT state=" + bluetoothState + ", " + "SCO is on: " + isScoOn())
        return true
    }

    /** Stops Bluetooth SCO connection with remote device. */
    fun stopScoAudio() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(
                Config.LOGTAG,
                "stopScoAudio: BT state=" + bluetoothState + ", " + "SCO is on: " + isScoOn())
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return
        }
        cancelTimer()
        audioManager!!.stopBluetoothSco()
        audioManager!!.setBluetoothScoOn(false)
        bluetoothState = State.SCO_DISCONNECTING
        Log.d(
                Config.LOGTAG,
                "stopScoAudio done: BT state=" + bluetoothState + ", " + "SCO is on: " + isScoOn())
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset Service via IPC) to
     * update the list of connected devices for the HEADSET profile. The internal state will change to
     * HEADSET_UNAVAILABLE or to HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the
     * connected device if available.
     */
    @SuppressLint("MissingPermission")
    fun updateDevice() {
        if (bluetoothState == State.UNINITIALIZED) {
            return
        }
        val headset = bluetoothHeadset ?: return
        Log.d(Config.LOGTAG, "updateDevice")
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        val devices: List<BluetoothDevice>
        if (hasBluetoothConnectPermission()) {
            devices = headset.getConnectedDevices()
        } else {
            devices = ImmutableList.of()
        }
        if (devices.isEmpty()) {
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            Log.d(Config.LOGTAG, "No connected bluetooth headset")
        } else {
            // Always use first device in list. Android only supports one device.
            val device = devices.get(0)
            bluetoothDevice = device
            bluetoothState = State.HEADSET_AVAILABLE
            Log.d(
                    Config.LOGTAG,
                    "Connected bluetooth headset: " +
                            "name=" +
                            device.getName() +
                            ", " +
                            "state=" +
                            stateToString(headset.getConnectionState(device)) +
                            ", SCO audio=" +
                            headset.isAudioConnected(device))
        }
        Log.d(Config.LOGTAG, "updateDevice done: BT state=" + bluetoothState)
    }

    /** Stubs for test mocks. */
    protected open fun getAudioManager(context: Context): AudioManager? {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    protected open fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        apprtcContext.registerReceiver(receiver, filter)
    }

    protected open fun unregisterReceiver(receiver: BroadcastReceiver) {
        apprtcContext.unregisterReceiver(receiver)
    }

    protected open fun getBluetoothProfileProxy(
            context: Context,
            listener: BluetoothProfile.ServiceListener,
            profile: Int
    ): Boolean {
        return bluetoothAdapter!!.getProfileProxy(context, listener, profile)
    }

    protected open fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                            apprtcContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** Ensures that the audio manager updates its list of available audio devices. */
    private fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(Config.LOGTAG, "updateAudioDeviceState")
        apprtcAudioManager.updateAudioDeviceState()
    }

    /** Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds. */
    private fun startTimer() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(Config.LOGTAG, "startTimer")
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS.toLong())
    }

    /** Cancels any outstanding timer tasks. */
    private fun cancelTimer() {
        ThreadUtils.checkIsOnMainThread()
        Log.d(Config.LOGTAG, "cancelTimer")
        handler.removeCallbacks(bluetoothTimeoutRunnable)
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually happens when the BT device
     * has been turned on during an ongoing call.
     */
    @SuppressLint("MissingPermission")
    private fun bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread()
        if (bluetoothState == State.UNINITIALIZED) {
            return
        }
        val headset = bluetoothHeadset ?: return
        Log.d(
                Config.LOGTAG,
                "bluetoothTimeout: BT state=" +
                        bluetoothState +
                        ", " +
                        "attempts: " +
                        scoConnectionAttempts +
                        ", " +
                        "SCO is on: " +
                        isScoOn())
        if (bluetoothState != State.SCO_CONNECTING) {
            return
        }
        // Bluetooth SCO should be connecting; check the latest result.
        var scoConnected = false
        val devices: List<BluetoothDevice>
        if (hasBluetoothConnectPermission()) {
            devices = headset.getConnectedDevices()
        } else {
            devices = Collections.emptyList()
        }
        if (devices.size > 0) {
            val device = devices.get(0)
            bluetoothDevice = device
            if (headset.isAudioConnected(device)) {
                Log.d(Config.LOGTAG, "SCO connected with " + device.getName())
                scoConnected = true
            } else {
                Log.d(Config.LOGTAG, "SCO is not connected with " + device.getName())
            }
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED
            scoConnectionAttempts = 0
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(Config.LOGTAG, "BT failed to connect after timeout")
            stopScoAudio()
        }
        updateAudioDeviceState()
        Log.d(Config.LOGTAG, "bluetoothTimeout done: BT state=" + bluetoothState)
    }

    /** Checks whether audio uses Bluetooth SCO. */
    private fun isScoOn(): Boolean {
        return audioManager!!.isBluetoothScoOn()
    }

    /** Converts BluetoothAdapter states into local string representations. */
    private fun stateToString(state: Int): String {
        when (state) {
            BluetoothAdapter.STATE_DISCONNECTED -> return "DISCONNECTED"
            BluetoothAdapter.STATE_CONNECTED -> return "CONNECTED"
            BluetoothAdapter.STATE_CONNECTING -> return "CONNECTING"
            BluetoothAdapter.STATE_DISCONNECTING -> return "DISCONNECTING"
            BluetoothAdapter.STATE_OFF -> return "OFF"
            BluetoothAdapter.STATE_ON -> return "ON"
            // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
            // attempt graceful disconnection of any remote links.
            BluetoothAdapter.STATE_TURNING_OFF -> return "TURNING_OFF"
            // Indicates the local Bluetooth adapter is turning on. However local clients should wait
            // for STATE_ON before attempting to use the adapter.
            BluetoothAdapter.STATE_TURNING_ON -> return "TURNING_ON"
            else -> return "INVALID"
        }
    }

    // Bluetooth connection state.
    enum class State {
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,
        // Bluetooth error happened when trying to start Bluetooth.
        ERROR,
        // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,
        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,
        // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset
        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,
        // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,
        // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,
        // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private inner class BluetoothServiceListener : BluetoothProfile.ServiceListener {
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return
            }
            Log.d(
                    Config.LOGTAG,
                    "BluetoothServiceListener.onServiceConnected: BT state=" + bluetoothState)
            // Android only supports one connected Bluetooth Headset at a time.
            bluetoothHeadset = proxy as BluetoothHeadset
            updateAudioDeviceState()
            Log.d(Config.LOGTAG, "onServiceConnected done: BT state=" + bluetoothState)
        }

        /** Notifies the client when the proxy object has been disconnected from the service. */
        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return
            }
            Log.d(
                    Config.LOGTAG,
                    "BluetoothServiceListener.onServiceDisconnected: BT state=" + bluetoothState)
            stopScoAudio()
            bluetoothHeadset = null
            bluetoothDevice = null
            bluetoothState = State.HEADSET_UNAVAILABLE
            updateAudioDeviceState()
            Log.d(Config.LOGTAG, "onServiceDisconnected done: BT state=" + bluetoothState)
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private inner class BluetoothHeadsetBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (bluetoothState == State.UNINITIALIZED) {
                return
            }
            val action = intent.getAction()
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state =
                        intent.getIntExtra(
                                BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                Log.d(
                        Config.LOGTAG,
                        "BluetoothHeadsetBroadcastReceiver.onReceive: " +
                                "a=ACTION_CONNECTION_STATE_CHANGED, " +
                                "s=" +
                                stateToString(state) +
                                ", " +
                                "sb=" +
                                isInitialStickyBroadcast() +
                                ", " +
                                "BT state: " +
                                bluetoothState)
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    scoConnectionAttempts = 0
                    updateAudioDeviceState()
                } else if (state == BluetoothHeadset.STATE_CONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Bluetooth is probably powered off during the call.
                    stopScoAudio()
                    updateAudioDeviceState()
                }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
                val state =
                        intent.getIntExtra(
                                BluetoothHeadset.EXTRA_STATE,
                                BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
                Log.d(
                        Config.LOGTAG,
                        "BluetoothHeadsetBroadcastReceiver.onReceive: " +
                                "a=ACTION_AUDIO_STATE_CHANGED, " +
                                "s=" +
                                stateToString(state) +
                                ", " +
                                "sb=" +
                                isInitialStickyBroadcast() +
                                ", " +
                                "BT state: " +
                                bluetoothState)
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer()
                    if (bluetoothState == State.SCO_CONNECTING) {
                        Log.d(Config.LOGTAG, "+++ Bluetooth audio SCO is now connected")
                        bluetoothState = State.SCO_CONNECTED
                        scoConnectionAttempts = 0
                        updateAudioDeviceState()
                    } else {
                        Log.w(
                                Config.LOGTAG,
                                "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED")
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    Log.d(Config.LOGTAG, "+++ Bluetooth audio SCO is now connecting...")
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Log.d(Config.LOGTAG, "+++ Bluetooth audio SCO is now disconnected")
                    if (isInitialStickyBroadcast()) {
                        Log.d(
                                Config.LOGTAG,
                                "Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.")
                        return
                    }
                    updateAudioDeviceState()
                }
            }
            Log.d(Config.LOGTAG, "onReceive done: BT state=" + bluetoothState)
        }
    }

    companion object {

        // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
        private const val BLUETOOTH_SCO_TIMEOUT_MS = 4000

        // Maximum number of SCO connection attempts.
        private const val MAX_SCO_CONNECTION_ATTEMPTS = 2

        /** Construction. */
        fun create(context: Context, audioManager: AppRTCAudioManager): AppRTCBluetoothManager {
            Log.d(Config.LOGTAG, "create" + AppRTCUtils.getThreadInfo())
            return AppRTCBluetoothManager(context, audioManager)
        }
    }
}
