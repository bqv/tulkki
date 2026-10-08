package uk.xa0.tulkki.app.extras

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.StatusHints
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableSet
import com.intentfilter.androidpermissions.NotificationSettings
import com.intentfilter.androidpermissions.PermissionManager
import com.intentfilter.androidpermissions.models.DeniedPermissions
import io.michaelrocks.libphonenumber.android.NumberParseException
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.HashSet
import java.util.Stack
import java.util.Vector
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.app.receiver.SystemEventReceiver
import uk.xa0.tulkki.app.services.AvatarService
import uk.xa0.tulkki.app.services.CallIntegrationConnectionService
import uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.RtpSessionActivity
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.JingleRtpConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

@RequiresApi(Build.VERSION_CODES.M)
class ConnectionService : android.telecom.ConnectionService() {

    @JvmField var xmppConnectionService: XmppConnectionService? = null

    protected val mConnection: ServiceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as uk.xa0.tulkki.xmpp.services.XmppConnectionBinder
                    xmppConnectionService = binder.getService()
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    xmppConnectionService = null
                }
            }

    override fun onCreate() {
        // From XmppActivity.connectToBackend
        val intent = Intent(this, XmppConnectionService::class.java)
        intent.action = uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_STARTING_CALL
        intent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true)
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Log.w("ConnectionService", "unable to start service from " + javaClass.simpleName)
        }
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(mConnection)
    }

    override fun onCreateOutgoingConnection(
            phoneAccountHandle: PhoneAccountHandle,
            request: ConnectionRequest
    ): Connection {
        val gateway = phoneAccountHandle.id.split("/", limit = 2)

        val rawTel = request.address?.schemeSpecificPart ?: ""
        val postDial = PhoneNumberUtils.extractPostDialPortion(rawTel)

        var tel = PhoneNumberUtils.extractNetworkPortion(rawTel)
        try {
            tel = PhoneNumberUtilWrapper.normalize(this, tel, true)
        } catch (e: IllegalArgumentException) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        } catch (e: NumberParseException) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        val service = xmppConnectionService
        if (service == null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        if (service.getJingleConnectionManager().isBusy()) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }

        val account = AccountRegistry.get().findAccountByJid(Jid.of(gateway[0]))
        if (account == null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        val with = Jid.ofLocalAndDomain(tel, gateway[1])
        val connection = JingleCallConnection(account, with, postDial)

        val permissionManager = PermissionManager.getInstance(this)
        permissionManager.setNotificationSettings(
                NotificationSettings.Builder()
                        .withMessage(R.string.microphone_permission_for_call)
                        .withSmallIcon(R.drawable.ic_notification)
                        .build())

        val permissions = HashSet<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissionManager.checkPermissions(
                permissions,
                object : PermissionManager.PermissionRequestListener {
                    override fun onPermissionGranted() {
                        if (connection.getState() == Connection.STATE_DISCONNECTED) return

                        connection.setSessionId(
                                service.getJingleConnectionManager()
                                        .proposeJingleRtpSession(
                                                account,
                                                with,
                                                ImmutableSet.of(Media.AUDIO))!!
                                        .sessionId)
                    }

                    override fun onPermissionDenied(deniedPermissions: DeniedPermissions) {
                        connection.close(DisconnectCause(DisconnectCause.ERROR))
                    }
                })

        connection.setInitializing()
        connection.setAddress(
                Uri.fromParts("tel", tel, null), // Normalized tel as tel: URI
                TelecomManager.PRESENTATION_ALLOWED)

        service.setOnRtpConnectionUpdateListener(connection)

        service.setDiallerIntegrationActive(true)
        return connection
    }

    override fun onCreateIncomingConnection(
            handle: PhoneAccountHandle,
            request: ConnectionRequest
    ): Connection {
        val extras: Bundle = request.extras
        val extraExtras = extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val accountJid = extraExtras?.getString("account")
        val withJid = extraExtras?.getString("with")
        val sessionId = extraExtras?.getString(CallIntegrationConnectionService.EXTRA_SESSION_ID)

        val service = xmppConnectionService
        if (service == null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }

        val account =
                AccountRegistry.get()
                        .findAccountByJid(Jid.of(accountJid ?: throw NullPointerException()))
                        ?: throw NullPointerException()
        val with = Jid.of(withJid ?: throw NullPointerException())

        val connection = JingleCallConnection(account, with, null)
        connection.setSessionId(sessionId)
        connection.setAddress(
                Uri.fromParts("tel", with.getLocal(), null), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(
                account.getRoster().getContact(with).getDisplayName(),
                TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()

        service.setOnRtpConnectionUpdateListener(connection)

        return connection
    }

    inner class JingleCallConnection(
            account: Account,
            with: Jid,
            postDialString: String?
    ) : Connection(), uk.xa0.tulkki.xmpp.services.OnJingleRtpConnectionUpdate {

        protected var account: Account = account
        protected var with: Jid = with

        @JvmField protected var sessionId: String? = null
        protected val postDial: Stack<String> = Stack()
        protected var gatewayIcon: Icon? = null
        protected var pendingState: CallAudioState? = null
        protected var rtpConnection: WeakReference<JingleRtpConnection>? = null
        protected var lastAudioChange: Long = 0

        init {
            val service = xmppConnectionService!!
            gatewayIcon =
                    Icon.createWithBitmap(
                            FileBackend.drawDrawable(
                                    service.getAvatarService()
                                            .get(
                                                    account.getRoster()
                                                            .getContact(Jid.of(with.getDomain())),
                                                    AvatarService.getSystemUiAvatarSize(service),
                                                    false)))

            if (postDialString != null) {
                for (i in postDialString.length - 1 downTo 0) {
                    postDial.push("" + postDialString[i])
                }
            }

            setCallerDisplayName(account.getDisplayName(), TelecomManager.PRESENTATION_ALLOWED)
            setAudioModeIsVoip(true)
            setConnectionCapabilities(
                    Connection.CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION or
                            Connection.CAPABILITY_MUTE)
            setRingbackRequested(true)
        }

        fun setSessionId(sessionId: String?) {
            this.sessionId = sessionId
        }

        override fun onJingleRtpConnectionUpdate(
                account: AccountRef,
                with: Jid,
                sessionId: String,
                state: RtpEndUserState
        ) {
            Log.d(
                    "uk.xa0.tulkki.app.extras.JingleCallConnection",
                    "onJingleRtpConnectionUpdate: " +
                            with +
                            " " +
                            sessionId +
                            " (== " +
                            this.sessionId +
                            " )? " +
                            state)
            if (sessionId == null || sessionId != this.sessionId) return
            if (rtpConnection == null) {
                this.with = with // Store full JID of connection
                findRtpConnection()
            }

            var statusLabel: String? = null

            if (state == RtpEndUserState.FINDING_DEVICE) {
                setInitialized()
            } else if (state == RtpEndUserState.RINGING) {
                setDialing()
            } else if (state == RtpEndUserState.INCOMING_CALL) {
                setRinging()
            } else if (state == RtpEndUserState.CONNECTING) {
                xmppConnectionService!!.setDiallerIntegrationActive(true)
                setActive()
                statusLabel = getString(R.string.rtp_state_connecting)
            } else if (state == RtpEndUserState.CONNECTED) {
                xmppConnectionService!!.setDiallerIntegrationActive(true)
                setActive()
                postDial()
            } else if (state == RtpEndUserState.DECLINED_OR_BUSY) {
                close(DisconnectCause(DisconnectCause.BUSY))
            } else if (state == RtpEndUserState.ENDED) {
                close(DisconnectCause(DisconnectCause.LOCAL))
            } else if (state == RtpEndUserState.RETRACTED) {
                close(DisconnectCause(DisconnectCause.CANCELED))
            } else if (RtpSessionActivity.END_CARD.contains(state)) {
                close(DisconnectCause(DisconnectCause.ERROR))
            }

            setStatusHints(StatusHints(statusLabel, gatewayIcon, null))
        }

        override fun onAudioDeviceChanged(
                selectedAudioDevice: AudioDevice,
                availableAudioDevices: Set<AudioDevice>
        ) {
            if (Build.VERSION.SDK_INT < 26) return

            val pending = pendingState
            if (pending != null) {
                Log.d(
                        "uk.xa0.tulkki.app.extras.JingleCallConnection",
                        "Try with pendingState: " + pending)
                onCallAudioStateChanged(pending)
                return
            }

            // Prevent feedback loop on some devices
            if (System.currentTimeMillis() - lastAudioChange < 5000) {
                return
            }

            lastAudioChange = System.currentTimeMillis()
            Log.d(
                    "uk.xa0.tulkki.app.extras.JingleCallConnection",
                    "onAudioDeviceChanged: " + selectedAudioDevice)

            when (selectedAudioDevice) {
                AudioDevice.SPEAKER_PHONE -> setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                AudioDevice.WIRED_HEADSET -> setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
                AudioDevice.EARPIECE -> setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                AudioDevice.BLUETOOTH -> setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                else -> setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
            }
        }

        override fun onCallAudioStateChanged(state: CallAudioState) {
            pendingState = null
            val rtp = rtpConnection?.get()
            if (rtp == null) {
                pendingState = state
                return
            }

            Log.d(
                    "uk.xa0.tulkki.app.extras.JingleCallConnection",
                    "onCallAudioStateChanged: " + state)
            lastAudioChange = System.currentTimeMillis()
            rtp.callIntegration.onCallAudioStateChanged(state)

            try {
                rtp.setMicrophoneEnabled(!state.isMuted)
            } catch (e: IllegalStateException) {
                pendingState = state
                Log.w(
                        "uk.xa0.tulkki.app.extras.JingleCallConnection",
                        "Could not set microphone mute to " +
                                (if (state.isMuted) "true" else "false") +
                                ": " +
                                e.toString())
            }
        }

        override fun onAnswer() {
            // For incoming calls, a connection update may not have been triggered before answering
            // so we have to acquire the rtp connection object here
            findRtpConnection()
            val rtp = rtpConnection?.get()
            if (rtp == null) {
                close(DisconnectCause(DisconnectCause.CANCELED))
            } else {
                rtp.acceptCall()
            }
        }

        override fun onReject() {
            findRtpConnection()
            val rtp = rtpConnection?.get()
            if (rtp != null) {
                try {
                    rtp.rejectCall()
                } catch (e: IllegalStateException) {
                    Log.w("uk.xa0.tulkki.app.extras.JingleCallConnection", e.toString())
                }
            }
            close(DisconnectCause(DisconnectCause.LOCAL))
        }

        // Set the connection to the disconnected state and clean up the resources
        // Note that we cannot do this from onStateChanged() because calling destroy
        // there seems to trigger a deadlock somewhere in the telephony stack.
        fun close(reason: DisconnectCause) {
            setDisconnected(reason)
            destroy()
            val service = xmppConnectionService!!
            service.setDiallerIntegrationActive(false)
            service.removeRtpConnectionUpdateListener(this)
        }

        override fun onDisconnect() {
            val rtp = rtpConnection?.get()
            if (rtp == null) {
                xmppConnectionService!!
                        .getJingleConnectionManager()
                        .retractSessionProposal(account, with.asBareJid())
                close(DisconnectCause(DisconnectCause.LOCAL))
            } else {
                rtp.endCall()
            }
        }

        override fun onAbort() {
            onDisconnect()
        }

        override fun onPlayDtmfTone(c: Char) {
            val rtp = rtpConnection?.get()
            if (rtp == null) {
                postDial.push("" + c)
                return
            }

            rtp.applyDtmfTone("" + c)
        }

        override fun onPostDialContinue(c: Boolean) {
            if (c) postDial()
        }

        protected fun findRtpConnection() {
            if (rtpConnection != null) return

            rtpConnection =
                    xmppConnectionService!!
                            .getJingleConnectionManager()
                            .findJingleRtpConnection(
                                    account, with, sessionId ?: throw NullPointerException())
        }

        protected fun sleep(ms: Int) {
            try {
                Thread.sleep(ms.toLong())
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        protected fun postDial() {
            while (!postDial.empty()) {
                val next = postDial.pop()
                if (next == ";") {
                    val v = Vector(postDial)
                    Collections.reverse(v)
                    setPostDialWait(Joiner.on("").join(v))
                    return
                } else if (next == ",") {
                    sleep(2000)
                } else {
                    rtpConnection!!.get()!!.applyDtmfTone(next)
                    sleep(100)
                }
            }
        }
    }
}
