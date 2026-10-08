package uk.xa0.tulkki.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.opengl.GLException
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.ui.viewinterop.AndroidView

import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures

import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.rtp.RtpCallButton
import uk.xa0.tulkki.ui.rtp.RtpCallEvents
import uk.xa0.tulkki.ui.rtp.RtpCallScreen
import uk.xa0.tulkki.ui.rtp.RtpCallState
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.MainThreadExecutor
import uk.xa0.tulkki.ui.util.Rationals
import uk.xa0.tulkki.ui.utils.PermissionUtils
import uk.xa0.tulkki.ui.utils.TimeFrameUtils
import uk.xa0.tulkki.ui.widget.SurfaceViewRenderer
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.ContentAddition
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.JingleRtpConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.OngoingRtpSession
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import java.lang.ref.WeakReference

class RtpSessionActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnJingleRtpConnectionUpdate,
    uk.xa0.tulkki.ui.widget.SurfaceViewRenderer.OnAspectRatioChanged {

    private var rtpConnectionReference: WeakReference<JingleRtpConnection>? = null

    // The three platform surfaces the deleted layouts drew. A WebRTC `SurfaceViewRenderer` and the
    // `ShapeableImageView` have no Compose equivalent, so the views are built once here and handed
    // to `RtpCallScreen`'s slots, which wrap them in `AndroidView`. Everything else the layouts
    // held is Compose.
    private lateinit var remoteVideo: SurfaceViewRenderer
    private lateinit var localVideo: SurfaceViewRenderer
    private lateinit var contactPhoto: ShapeableImageView

    /** The contact the incoming-call screen draws, resolved when that screen updates. */
    private var shownContact: Contact? = null

    /** The screen's whole state; the `update*` methods below write it instead of a binding. */
    private val ui = RtpCallState()

    private val events =
        RtpCallEvents(
            onHelp = { launchHelpInBrowser() },
            onGotoChat = { switchToConversation() },
            onToggleDialpad = { toggleDialpadVisibility() },
            onSwitchToVideo = { requestPermissionAndSwitchToVideo() },
            onDigit = { tone -> rtpConnectionReference?.get()?.applyDtmfTone(tone) },
        )

    private var mProximityWakeLock: PowerManager.WakeLock? = null

    private val mHandler = Handler()

    private val mTickExecutor = object : Runnable {
        override fun run() {
            updateCallDuration()
            mHandler.postDelayed(this, CALL_DURATION_UPDATE_INTERVAL.toLong())
        }
    }

    private var buttonsHiddenAfterTimeout = false
    private val mVisibilityToggleExecutor = Runnable { updateButtonInVideoCallVisibility() }

    private fun onVideoScreenClick(view: View) {
        resetVisibilityExecutorShowButtons()
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        remoteVideo = SurfaceViewRenderer(this)
        localVideo = SurfaceViewRenderer(this)
        contactPhoto =
            ShapeableImageView(this).apply {
                // The deleted `contact_photo`'s `app:shapeAppearance="@style/ShapeAppearanceOverlay.IncomingCall"`,
                // read from the same style because the view is built here rather than inflated.
                shapeAppearanceModel =
                    ShapeAppearanceModel.builder(
                            this@RtpSessionActivity,
                            R.style.ShapeAppearanceOverlay_IncomingCall,
                            0)
                        .build()
            }
        remoteVideo.setOnClickListener { onVideoScreenClick(it) }
        localVideo.setOnClickListener { onVideoScreenClick(it) }

        if (savedInstanceState != null) {
            ui.dialpadVisible = savedInstanceState.getBoolean("dialpad_visible")
        }

        // The chrome draws its bar behind the system bars and owns the system-bar colours now, so
        // the window must not inset itself for them first - on every API level, which is what
        // `enableEdgeToEdge` settles. `setSupportActionBar` and
        // `Activities.setStatusAndNavigationBarColors` went with the XML bar. See TulkkiChrome.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            RtpCallScreen(
                state = ui,
                events = events,
                remoteVideo = { modifier ->
                    AndroidView(factory = { remoteVideo }, modifier = modifier)
                },
                localVideo = { modifier ->
                    AndroidView(factory = { localVideo }, modifier = modifier)
                },
                contactPhoto = { modifier ->
                    AndroidView(
                        factory = { contactPhoto },
                        update = { view ->
                            shownContact?.let {
                                AvatarWorkerTask.loadAvatar(
                                    it, view, R.dimen.publish_avatar_size)
                            }
                        },
                        modifier = modifier,
                    )
                },
            )
        }
    }

    /**
     * What `invalidateOptionsMenu()` plus the deleted `onCreateOptionsMenu` decided: the four items'
     * own visibility, re-read from the connection every time the old code asked the framework to
     * rebuild the menu. The items themselves are the chrome's overflow now, composed in
     * `RtpCallScreen` from these flags.
     */
    private fun updateMenuVisibility() {
        ui.menuHelpVisible = Config.HELP != null && isHelpButtonVisible()
        ui.menuGotoChatVisible = isSwitchToConversationVisible()
        ui.menuSwitchToVideoVisible = isSwitchToVideoVisible()
        ui.menuDialpadVisible = isAudioOnlyConversation()
    }

    public override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val service = xmppConnectionService
            if (service != null) {
                if (service.getNotificationService().stopSoundAndVibration()) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isHelpButtonVisible(): Boolean {
        return try {
            STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState())
        } catch (e: IllegalStateException) {
            val intent = getIntent()
            val state = intent?.getStringExtra(EXTRA_LAST_REPORTED_STATE)
            if (state != null) {
                STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state))
            } else {
                false
            }
        }
    }

    private fun isSwitchToConversationVisible(): Boolean {
        val connection = this.rtpConnectionReference?.get()
        return connection != null &&
            STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState())
    }

    private fun isAudioOnlyConversation(): Boolean {
        val connection = this.rtpConnectionReference?.get()
        return connection != null && !connection.getMedia().contains(Media.VIDEO)
    }

    private fun isSwitchToVideoVisible(): Boolean {
        val connection = this.rtpConnectionReference?.get() ?: return false
        return connection.isSwitchToVideoAvailable()
    }

    private fun switchToConversation() {
        val contact = getWith()
        // Tulkki: 3.7 C5-E4 - `getWith()` answers null when the session's account has been removed
        // from the registry; there is no conversation left to switch to.
        if (contact == null) {
            return
        }
        val conversation = xmppConnectionService.findOrCreateConversation(
            contact.getAccount(), contact.getJid(), false, true) as Conversation
        switchToConversation(conversation)
    }

    private fun toggleDialpadVisibility() {
        ui.dialpadVisible = !ui.dialpadVisible
    }

    private fun launchHelpInBrowser() {
        val intent = Intent(Intent.ACTION_VIEW, Config.HELP)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun endCall() {
        if (this.rtpConnectionReference == null) {
            retractSessionProposal()
            finish()
        } else {
            try {
                requireRtpConnection().endCall()
            } catch (e: IllegalStateException) {
                // No call, already done
                finish()
            }
        }
    }

    private fun retractSessionProposal() {
        val intent = getIntent()
        val action = intent.getAction()
        val lastAction = intent.getStringExtra(EXTRA_LAST_ACTION)
        val account = extractAccount(intent)
        val with = Jid.of(intent.getStringExtra(EXTRA_WITH) ?: throw NullPointerException())
        val state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE)
        if (Intent.ACTION_VIEW != action ||
            state == null ||
            !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            val media = actionToMedia(lastAction ?: action)
            resetIntent(account ?: throw NullPointerException(), with, RtpEndUserState.RETRACTED, media)
        }
        // Tulkki: 3.7 C5 - the Java handed this possibly-null `Account` to the island's non-null
        // `AccountRef`. `:data`'s `Account` implements that ref, so no boundary moves and nothing was
        // retyped model-to-ref; what Kotlin cannot spell is the Java's tolerance of null, where the
        // manager's own loop matched no proposal and did nothing. The only declaration-side fix is
        // widening the parameter to `AccountRef?`, which is a widening, so the null case is skipped
        // here instead - exactly what the Java did.
        if (account != null) {
            xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid())
        }
    }

    private fun rejectCall() {
        requireRtpConnection().rejectCall()
        finish()
    }

    private fun acceptContentAdd() {
        try {
            val pendingContentAddition = requireRtpConnection().getPendingContentAddition()
            if (pendingContentAddition == null) {
                Log.d(Config.LOGTAG, "content offer was gone after granting permission")
                return
            }
            requireRtpConnection().acceptContentAdd(pendingContentAddition.summary)
        } catch (e: IllegalStateException) {
            Toast.makeText(this, Strings.nullToEmpty(e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionAndSwitchToVideo() {
        val permissions = permissions(ImmutableSet.of(Media.VIDEO, Media.AUDIO))
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ADD_CONTENT)) {
            switchToVideo()
        }
    }

    private fun switchToVideo() {
        try {
            requireRtpConnection().addMedia(Media.VIDEO)
        } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun acceptContentAdd(contentAddition: ContentAddition?) {
        if (contentAddition == null ||
            contentAddition.direction != ContentAddition.Direction.INCOMING) {
            Log.d(Config.LOGTAG, "ignore press on content-accept button")
            return
        }
        requestPermissionAndAcceptContentAdd(contentAddition)
    }

    private fun requestPermissionAndAcceptContentAdd(contentAddition: ContentAddition) {
        val permissions = permissions(contentAddition.media())
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CONTENT)) {
            try {
                requireRtpConnection().acceptContentAdd(contentAddition.summary)
            } catch (e: IllegalStateException) {
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rejectContentAdd() {
        requireRtpConnection().rejectContentAdd()
    }

    private fun requestPermissionsAndAcceptCall() {
        val permissions = permissions(getMedia())
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode()
            acceptCall()
        }
    }

    private fun permissions(media: Set<Media>): List<String> {
        val permissions = ImmutableList.builder<String>()
        if (media.contains(Media.VIDEO)) {
            permissions.add(Manifest.permission.CAMERA).add(Manifest.permission.RECORD_AUDIO)
        } else {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.build()
    }

    private fun acceptCall() {
        try {
            requireRtpConnection().acceptCall()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia())
    }

    private fun putScreenInCallMode(media: Set<Media>) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Media.audioOnly(media)) {
            val rtpConnection = rtpConnectionReference?.get()
            val callIntegration = rtpConnection?.getCallIntegration()
            if (callIntegration == null ||
                callIntegration.getSelectedAudioDevice() == AudioDevice.EARPIECE) {
                acquireProximityWakeLock()
            }
        }
        lockOrientation(media)
    }

    private fun lockOrientation(media: Set<Media>) {
        if (Media.audioOnly(media)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireProximityWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available")
            return
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing")
            return
        }
        var wakeLock = this.mProximityWakeLock
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG)
            this.mProximityWakeLock = wakeLock
        }
        if (!wakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "acquiring proximity wake lock")
            wakeLock.acquire()
        }
    }

    private fun releaseProximityWakeLock() {
        val wakeLock = this.mProximityWakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "releasing proximity wake lock")
            wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            this.mProximityWakeLock = null
        }
    }

    private fun putProximityWakeLockInProperState(audioDevice: AudioDevice) {
        if (audioDevice == AudioDevice.EARPIECE) {
            acquireProximityWakeLock()
        } else {
            releaseProximityWakeLock()
        }
    }

    protected override fun refreshUiReal() {}

    public override fun onNewIntent(intent: Intent) {
        Log.d(Config.LOGTAG, this.javaClass.name + ".onNewIntent()")
        super.onNewIntent(intent)
        setIntent(intent)
        if (xmppConnectionService == null) {
            Log.d(
                Config.LOGTAG,
                "RtpSessionActivity: background service wasn't bound in onNewIntent()")
            return
        }
        initializeWithIntent(Event.ON_NEW_INTENT, intent)
    }

    protected override fun onBackendConnected() {
        val intent = getIntent() ?: return
        initializeWithIntent(Event.ON_BACKEND_CONNECTED, intent)
    }

    private fun initializeWithIntent(event: Event, intent: Intent) {
        val action = intent.getAction()
        Log.d(Config.LOGTAG, "initializeWithIntent(" + event + "," + action + ")")
        val account = extractAccount(intent)
        val extraWith = intent.getStringExtra(EXTRA_WITH)
        val with = if (extraWith.isNullOrEmpty()) null else Jid.of(extraWith)
        if (with == null || account == null) {
            Log.e(Config.LOGTAG, "intent is missing extras (account or with)")
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return
            }
            if (ACTION_ACCEPT_CALL == intent.getAction()) {
                Log.d(Config.LOGTAG, "intent action was accept")
                requestPermissionsAndAcceptCall()
                resetIntent(intent.getExtras())
            }
        } else if (Intent.ACTION_VIEW == action) {
            val proposedSessionId = intent.getStringExtra(EXTRA_PROPOSED_SESSION_ID)
            // The Java passed a missing id straight in and got a miss: the callee takes a nullable
            // id, a null key is never stored, so an absent extra is no terminated session here.
            val terminatedRtpSession =
                xmppConnectionService
                    .getJingleConnectionManager()
                    .getTerminalSessionState(with, proposedSessionId)
            if (terminatedRtpSession != null) {
                // termination (due to message error or 'busy' was faster than opening the activity
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession)
                return
            }
            val extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE)
            val state = if (extraLastState == null) null else RtpEndUserState.valueOf(extraLastState)
            val contact = account.getRoster().getContact(with)
            if (state != null) {
                Log.d(Config.LOGTAG, "restored last state from intent extra")
                updateButtonConfiguration(state)
                updateVerifiedShield(false)
                updateStateDisplay(state)
                updateIncomingCallScreen(state)
                updateSupportWarning(state, contact)
                updateMenuVisibility()
            }
            setWith(state, contact)
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return
            }
            if (state != null && END_CARD.contains(state)) {
                return
            }
            val lastAction = intent.getStringExtra(EXTRA_LAST_ACTION)
            val media = actionToMedia(lastAction)
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .hasMatchingProposal(account, with)) {
                putScreenInCallMode(media)
                return
            }
            Log.d(Config.LOGTAG, "restored state (" + state + ") was not an end card. finishing")
            finish()
        }
    }

    private fun setWith(state: RtpEndUserState?) {
        setWith(state, getWith())
    }

    private fun setWith(state: RtpEndUserState?, contact: Contact?) {
        // Tulkki: 3.7 C5-E4 - null when the session's account has been removed from the registry.
        if (contact == null) {
            return
        }
        ui.withName = contact.getDisplayName()
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            ui.withJid = contact.getJid().asBareJid().toString()
        } else {
            ui.withJid = null
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionResult = PermissionUtils.removeBluetoothConnect(permissions, grantResults)
        if (PermissionUtils.allGranted(permissionResult.grantResults)) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                acceptCall()
            } else if (requestCode == REQUEST_ACCEPT_CONTENT) {
                acceptContentAdd()
            } else if (requestCode == REQUEST_ADD_CONTENT) {
                switchToVideo()
            }
        } else {
            val firstDenied =
                PermissionUtils.getFirstDenied(permissionResult.grantResults, permissionResult.permissions)
            if (firstDenied == null) {
                return
            }
            val res: Int
            if (Manifest.permission.RECORD_AUDIO == firstDenied) {
                res = R.string.no_microphone_permission
            } else if (Manifest.permission.CAMERA == firstDenied) {
                res = R.string.no_camera_permission
            } else {
                throw IllegalStateException("Invalid permission result request")
            }
            Toast.makeText(this, getString(res, BuildConfig.APP_NAME), Toast.LENGTH_SHORT).show()
        }
    }

    public override fun onStart() {
        super.onStart()
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL.toLong())
        mHandler.postDelayed(mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT.toLong())
        remoteVideo.setOnAspectRatioChanged(this)
    }

    public override fun onResume() {
        super.onResume()
        resetVisibilityExecutorShowButtons()
    }

    public override fun onStop() {
        mHandler.removeCallbacks(mTickExecutor)
        mHandler.removeCallbacks(mVisibilityToggleExecutor)
        remoteVideo.release()
        remoteVideo.setOnAspectRatioChanged(null)
        localVideo.release()
        val weakReference = this.rtpConnectionReference
        val jingleRtpConnection = weakReference?.get()
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection)
        }
        releaseProximityWakeLock()
        super.onStop()
    }

    private fun releaseVideoTracks(jingleRtpConnection: JingleRtpConnection) {
        val remoteTrack = jingleRtpConnection.getRemoteVideoTrack()
        if (remoteTrack.isPresent) {
            remoteTrack.get().removeSink(remoteVideo)
        }
        val localTrack = jingleRtpConnection.getLocalVideoTrack()
        if (localTrack.isPresent) {
            localTrack.get().removeSink(localVideo)
        }
    }

    public override fun onBackPressed() {
        if (isConnected()) {
            if (switchToPictureInPicture()) {
                return
            }
        } else {
            endCall()
        }
        super.onBackPressed()
    }

    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (switchToPictureInPicture()) {
            return
        }
        // TODO apparently this method is not getting called on Android 10 when using the task
        // switcher
        if (emptyReference(rtpConnectionReference) && xmppConnectionService != null) {
            retractSessionProposal()
        }
    }

    private fun isConnected(): Boolean {
        val connection = this.rtpConnectionReference?.get()
        val endUserState = connection?.getEndUserState()
        return (endUserState != null && STATES_CONSIDERED_CONNECTED.contains(endUserState)) ||
            endUserState == RtpEndUserState.INCOMING_CONTENT_ADD
    }

    private fun switchToPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture()
                return true
            }
        }
        return false
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun startPictureInPicture() {
        try {
            val rational = remoteVideo.getAspectRatio()
            val clippedRational = Rationals.clip(rational)
            Log.d(
                Config.LOGTAG,
                "suggested rational " + rational + ". clipped to " + clippedRational)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(clippedRational).build())
        } catch (e: IllegalStateException) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e)
        }
    }

    public override fun onAspectRatioChanged(rational: Rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            val clippedRational = Rationals.clip(rational)
            Log.d(
                Config.LOGTAG,
                "suggested rational after aspect ratio change " +
                    rational +
                    ". clipped to " +
                    clippedRational)
            setPictureInPictureParams(
                PictureInPictureParams.Builder().setAspectRatio(clippedRational).build())
        }
    }

    private fun deviceSupportsPictureInPicture(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } else {
            false
        }
    }

    private fun shouldBePictureInPicture(): Boolean {
        return try {
            val rtpConnection = requireRtpConnection()
            rtpConnection.getMedia().contains(Media.VIDEO) &&
                listOf(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED)
                    .contains(rtpConnection.getEndUserState())
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun isInConnectedVideoCall(): Boolean {
        val rtpConnection = try {
            requireRtpConnection()
        } catch (e: IllegalStateException) {
            return false
        }
        return rtpConnection.getMedia().contains(Media.VIDEO) &&
            rtpConnection.getEndUserState() == RtpEndUserState.CONNECTED
    }

    private fun initializeActivityWithRunningRtpSession(
        account: Account,
        with: Jid,
        sessionId: String,
    ): Boolean {
        val reference = xmppConnectionService
            .getJingleConnectionManager()
            .findJingleRtpConnection(account, with, sessionId)
        if (reference == null || reference.get() == null) {
            val terminatedRtpSession = xmppConnectionService
                .getJingleConnectionManager()
                .getTerminalSessionState(with, sessionId)
            if (terminatedRtpSession == null) {
                throw IllegalStateException(
                    "failed to initialize activity with running rtp session. session not" +
                        " found")
            }
            initializeWithTerminatedSessionState(account, with, terminatedRtpSession)
            return true
        }
        this.rtpConnectionReference = reference
        val currentState = requireRtpConnection().getEndUserState()
        val verified = requireRtpConnection().isVerified()
        if (currentState == RtpEndUserState.ENDED) {
            finish()
            return true
        }
        val media = getMedia()
        val contentAddition = getPendingContentAddition()
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode()
        }
        setWith(currentState)
        updateVideoViews(currentState)
        updateStateDisplay(currentState, media, contentAddition)
        updateVerifiedShield(verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(currentState))
        updateButtonConfiguration(currentState, media, contentAddition)
        updateIncomingCallScreen(currentState)
        updateMenuVisibility()
        return false
    }

    private fun initializeWithTerminatedSessionState(
        account: Account,
        with: Jid,
        terminatedRtpSession: JingleConnectionManager.TerminatedRtpSession,
    ) {
        Log.d(Config.LOGTAG, "initializeWithTerminatedSessionState()")
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish()
            return
        }
        val state = terminatedRtpSession.state
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media)
        updateButtonConfiguration(state)
        updateStateDisplay(state)
        updateIncomingCallScreen(state)
        updateCallDuration()
        updateVerifiedShield(false)
        updateMenuVisibility()
        val contact = account.getRoster().getContact(with)
        setWith(state, contact)
        updateSupportWarning(state, contact)
    }

    private fun reInitializeActivityWithRunningRtpSession(
        account: Account,
        with: Jid,
        sessionId: String,
    ) {
        runOnUiThread { initializeActivityWithRunningRtpSession(account, with, sessionId) }
        resetIntent(account, with, sessionId)
    }

    private fun resetIntent(account: Account, with: Jid, sessionId: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString())
        intent.putExtra(EXTRA_WITH, with.toString())
        intent.putExtra(EXTRA_SESSION_ID, sessionId)
        setIntent(intent)
    }

    private fun ensureSurfaceViewRendererIsSetup(
        surfaceViewRenderer: org.webrtc.SurfaceViewRenderer,
    ) {
        surfaceViewRenderer.setVisibility(View.VISIBLE)
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null)
        } catch (ignored: IllegalStateException) {
            // SurfaceViewRenderer was already initialized
        } catch (e: RuntimeException) {
            val rootCause = Throwables.getRootCause(e)
            if (rootCause is GLException) {
                Log.w(Config.LOGTAG, "could not set up hardware renderer", rootCause)
            }
        }
        surfaceViewRenderer.setEnableHardwareScaler(true)
    }

    private fun updateStateDisplay(state: RtpEndUserState) {
        updateStateDisplay(state, emptySet(), null)
    }

    private fun updateStateDisplay(
        state: RtpEndUserState,
        media: Set<Media>,
        contentAddition: ContentAddition?,
    ) {
        when (state) {
            RtpEndUserState.INCOMING_CALL -> {
                Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty")
                if (media.contains(Media.VIDEO)) {
                    ui.title = getString(R.string.rtp_state_incoming_video_call)
                } else {
                    ui.title = getString(R.string.rtp_state_incoming_call)
                }
            }
            RtpEndUserState.INCOMING_CONTENT_ADD -> {
                if (contentAddition != null && contentAddition.media().contains(Media.VIDEO)) {
                    ui.title = getString(R.string.rtp_state_content_add_video)
                } else {
                    ui.title = getString(R.string.rtp_state_content_add)
                }
            }
            RtpEndUserState.CONNECTING -> ui.title = getString(R.string.rtp_state_connecting)
            RtpEndUserState.CONNECTED ->
                ui.title = getString(uk.xa0.tulkki.data.R.string.rtp_state_connected)
            RtpEndUserState.RECONNECTING -> ui.title = getString(R.string.rtp_state_reconnecting)
            RtpEndUserState.ACCEPTING_CALL -> ui.title = getString(R.string.rtp_state_accepting_call)
            RtpEndUserState.ENDING_CALL -> ui.title = getString(R.string.rtp_state_ending_call)
            RtpEndUserState.FINDING_DEVICE -> ui.title = getString(R.string.rtp_state_finding_device)
            RtpEndUserState.RINGING -> ui.title = getString(R.string.rtp_state_ringing)
            RtpEndUserState.DECLINED_OR_BUSY ->
                ui.title = getString(R.string.rtp_state_declined_or_busy)
            RtpEndUserState.CONTACT_OFFLINE ->
                ui.title = getString(R.string.rtp_state_contact_offline)
            RtpEndUserState.CONNECTIVITY_ERROR ->
                ui.title = getString(R.string.rtp_state_connectivity_error)
            RtpEndUserState.CONNECTIVITY_LOST_ERROR ->
                ui.title = getString(R.string.rtp_state_connectivity_lost_error)
            RtpEndUserState.RETRACTED -> ui.title = getString(R.string.rtp_state_retracted)
            RtpEndUserState.APPLICATION_ERROR ->
                ui.title = getString(R.string.rtp_state_application_failure)
            RtpEndUserState.SECURITY_ERROR -> ui.title = getString(R.string.rtp_state_security_error)
            RtpEndUserState.ENDED ->
                throw IllegalStateException(
                    "Activity should have called finishAndReleaseWakeLock();")
            else ->
                throw IllegalStateException(
                    String.format("State %s has not been handled in UI", state))
        }
    }

    private fun updateVerifiedShield(verified: Boolean) {
        // `INVISIBLE` and `GONE` both mean "not shown" to the state, which is what the XML's two
        // visibility values did to a shield nobody can tap.
        ui.verified = !isPictureInPicture() && verified
    }

    private fun updateIncomingCallScreen(state: RtpEndUserState) {
        updateIncomingCallScreen(state, null)
    }

    private fun updateIncomingCallScreen(state: RtpEndUserState, contact: Contact?) {
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            val show = resources.getBoolean(R.bool.is_portrait_mode)
            // Tulkki: 3.7 C5-E4 - `getWith()` answers null when the session's account has been removed
            // from the registry, so it is resolved once here and the two contact-dependent lines are
            // skipped rather than dereferenced.
            val shown = contact ?: getWith()
            shownContact = shown
            // The XML made the photo VISIBLE and then loaded a picture only when it had a contact;
            // with none the view stayed an empty box, which is what a null contact draws here too.
            ui.contactPhoto = show
            // The XML set the line VISIBLE and then set its text only when a contact was resolved;
            // with none it kept showing the resource's own literal, `%s` and all, which is what the
            // second arm keeps.
            ui.usingAccount =
                if (shown != null) {
                    getString(
                        R.string.using_account,
                        shown.getAccount().getJid().asBareJid().toString())
                } else {
                    getString(R.string.using_account)
                }
        } else {
            ui.usingAccount = null
            ui.contactPhoto = false
            shownContact = null
        }
    }

    // Tulkki: 3.7 C5 - the Java declared this `final Contact contact`; the port's `Contact?` was a
    // widening, and the island's `RtpCapability.check` takes a non-null `ContactRef`, which `Contact`
    // implements. Every caller passes a non-null `Contact` (`Roster.getContact` is non-null, and
    // `getWith()` is guarded), so the Java's own parameter is restored rather than the ref widened.
    private fun updateSupportWarning(state: RtpEndUserState, contact: Contact) {
        ui.supportWarning =
            state == RtpEndUserState.CONNECTIVITY_ERROR &&
                resources.getBoolean(R.bool.is_portrait_mode) &&
                RtpCapability.check(contact) == RtpCapability.Capability.NONE
    }

    private fun getMedia(): Set<Media> {
        return requireRtpConnection().getMedia()
    }

    fun getPendingContentAddition(): ContentAddition? {
        return requireRtpConnection().getPendingContentAddition()
    }

    private fun updateButtonConfiguration(state: RtpEndUserState) {
        updateButtonConfiguration(state, emptySet(), null)
    }

    @SuppressLint("RestrictedApi")
    private fun updateButtonConfiguration(
        state: RtpEndUserState,
        media: Set<Media>,
        contentAddition: ContentAddition?,
    ) {
        if (state == RtpEndUserState.ENDING_CALL ||
            isPictureInPicture() ||
            this.buttonsHiddenAfterTimeout) {
            ui.rejectCall = RtpCallButton()
            ui.endCall = RtpCallButton()
            ui.acceptCall = RtpCallButton()
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            ui.rejectCall =
                callButton(R.drawable.ic_call_end_24dp, getString(R.string.dismiss_call)) {
                    rejectCall()
                }
            ui.endCall = RtpCallButton()
            ui.acceptCall =
                callButton(R.drawable.ic_call_24dp, getString(R.string.answer_call)) { acceptCall() }
        } else if (state == RtpEndUserState.INCOMING_CONTENT_ADD) {
            ui.rejectCall =
                callButton(R.drawable.ic_clear_24dp, getString(R.string.reject_switch_to_video)) {
                    rejectContentAdd()
                }
            ui.endCall = RtpCallButton()
            ui.acceptCall =
                callButton(R.drawable.ic_check_24dp, getString(R.string.accept)) {
                    acceptContentAdd(contentAddition)
                }
        } else if (listOf(RtpEndUserState.DECLINED_OR_BUSY, RtpEndUserState.CONTACT_OFFLINE)
                .contains(state)) {
            ui.rejectCall =
                callButton(R.drawable.ic_clear_24dp, getString(R.string.exit)) { exit() }
            ui.endCall = RtpCallButton()
            ui.acceptCall =
                callButton(R.drawable.ic_voicemail_24dp, getString(R.string.record_voice_mail)) {
                    recordVoiceMail()
                }
        } else if (listOf(
                RtpEndUserState.CONNECTIVITY_ERROR,
                RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                RtpEndUserState.APPLICATION_ERROR,
                RtpEndUserState.RETRACTED,
                RtpEndUserState.SECURITY_ERROR)
                .contains(state)) {
            ui.rejectCall =
                callButton(R.drawable.ic_clear_24dp, getString(R.string.exit)) { exit() }
            ui.endCall = RtpCallButton()
            ui.acceptCall =
                callButton(R.drawable.ic_replay_24dp, getString(R.string.try_again)) { retry() }
        } else {
            ui.rejectCall = RtpCallButton()
            ui.endCall =
                callButton(R.drawable.ic_call_end_24dp, getString(R.string.hang_up)) { endCall() }
            ui.acceptCall = RtpCallButton()
        }
        updateInCallButtonConfiguration(state, media)
    }

    /**
     * A drawn button: the XML's `setImageResource` + `setContentDescription` +
     * `setOnClickListener` + `setVisibleAndShow`, in one value.
     */
    private fun callButton(
        icon: Int,
        description: String?,
        action: () -> Unit,
    ): RtpCallButton =
        RtpCallButton(icon = icon, description = description, visible = true, onClick = action)

    /**
     * `setContentDescription` + `setOnClickListener(null)` + `setClickable(false)`: the route the
     * call already uses is drawn, named, and does nothing when tapped.
     */
    private fun inertButton(icon: Int, description: String): RtpCallButton =
        RtpCallButton(icon = icon, description = description, visible = true, onClick = null)

    private fun isPictureInPicture(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isInPictureInPictureMode
        } else {
            false
        }
    }

    private fun updateInCallButtonConfiguration() {
        updateInCallButtonConfiguration(
            requireRtpConnection().getEndUserState(), requireRtpConnection().getMedia())
    }

    @SuppressLint("RestrictedApi")
    private fun updateInCallButtonConfiguration(state: RtpEndUserState, media: Set<Media>) {
        val showButtons = !isPictureInPicture() && !buttonsHiddenAfterTimeout
        if (STATES_CONSIDERED_CONNECTED.contains(state) && showButtons) {
            Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty")
            if (media.contains(Media.VIDEO)) {
                val rtpConnection = requireRtpConnection()
                updateInCallButtonConfigurationVideo(
                    rtpConnection.isVideoEnabled(), rtpConnection.isCameraSwitchable())
            } else {
                val callIntegration = requireRtpConnection().getCallIntegration()
                updateInCallButtonConfigurationSpeaker(
                    callIntegration.getSelectedAudioDevice(),
                    callIntegration.getAudioDevices().size)
                ui.actionFarRight = ui.actionFarRight.copy(visible = false)
            }
            if (media.contains(Media.AUDIO)) {
                updateInCallButtonConfigurationMicrophone(
                    requireRtpConnection().isMicrophoneEnabled())
            } else {
                ui.actionLeft = ui.actionLeft.copy(visible = false)
            }
        } else if (STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state) &&
            showButtons &&
            Media.audioOnly(media)) {
            val callIntegration: UiHost.CallAudio
            try {
                callIntegration = requireCallIntegration()
            } catch (e: IllegalStateException) {
                Log.e(Config.LOGTAG, "can not update InCallButtonConfiguration in state " + state)
                return
            }
            updateInCallButtonConfigurationSpeaker(
                callIntegration.getSelectedAudioDevice(),
                callIntegration.getAudioDevices().size)
            ui.actionFarRight = ui.actionFarRight.copy(visible = false)
        } else {
            ui.actionLeft = ui.actionLeft.copy(visible = false)
            ui.actionRight = ui.actionRight.copy(visible = false)
            ui.actionFarRight = ui.actionFarRight.copy(visible = false)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun updateInCallButtonConfigurationSpeaker(
        selectedAudioDevice: AudioDevice,
        numberOfChoices: Int,
    ) {
        val chosen =
            when (selectedAudioDevice) {
                AudioDevice.EARPIECE ->
                    if (numberOfChoices >= 2) {
                        callButton(
                            R.drawable.ic_volume_off_24dp,
                            getString(R.string.call_is_using_earpiece_tap_to_switch_to_speaker),
                        ) {
                            switchToSpeaker()
                        }
                    } else {
                        inertButton(
                            R.drawable.ic_volume_off_24dp,
                            getString(R.string.call_is_using_earpiece),
                        )
                    }
                AudioDevice.WIRED_HEADSET ->
                    inertButton(
                        R.drawable.ic_headset_mic_24dp,
                        getString(R.string.call_is_using_wired_headset),
                    )
                AudioDevice.SPEAKER_PHONE ->
                    if (numberOfChoices >= 2) {
                        callButton(
                            R.drawable.ic_volume_up_24dp,
                            getString(R.string.call_is_using_speaker_tap_to_switch_to_earpiece),
                        ) {
                            switchToEarpiece()
                        }
                    } else {
                        inertButton(
                            R.drawable.ic_volume_up_24dp,
                            getString(R.string.call_is_using_speaker),
                        )
                    }
                AudioDevice.BLUETOOTH ->
                    inertButton(
                        R.drawable.ic_bluetooth_audio_24dp,
                        getString(R.string.call_is_using_bluetooth),
                    )
                // The XML's `else -> Unit` kept whatever icon and name the button last had.
                else -> ui.actionRight
            }
        ui.actionRight = chosen.copy(visible = true)
    }

    @SuppressLint("RestrictedApi")
    private fun updateInCallButtonConfigurationVideo(
        videoEnabled: Boolean,
        isCameraSwitchable: Boolean,
    ) {
        ui.actionRight =
            if (videoEnabled) {
                callButton(
                    R.drawable.ic_videocam_24dp,
                    getString(R.string.video_is_enabled_tap_to_disable),
                ) {
                    disableVideo()
                }
            } else {
                callButton(
                    R.drawable.ic_videocam_off_24dp,
                    getString(R.string.video_is_disabled_tap_to_enable),
                ) {
                    enableVideo()
                }
            }
        ui.actionFarRight =
            if (isCameraSwitchable) {
                callButton(
                    R.drawable.ic_flip_camera_android_24dp,
                    getString(R.string.flip_camera),
                ) {
                    switchCamera()
                }
            } else {
                ui.actionFarRight.copy(visible = false)
            }
    }

    private fun switchCamera() {
        resetVisibilityToggleExecutor()
        Futures.addCallback(
            requireRtpConnection().switchCamera(),
            object : FutureCallback<Boolean> {
                // Gradle's Guava marks the callback's value non-null, so the Java's
                // `Boolean.TRUE.equals(isFrontCamera)` null tolerance cannot be kept here.
                override fun onSuccess(isFrontCamera: Boolean) {
                    localVideo.setMirror(isFrontCamera)
                }

                override fun onFailure(throwable: Throwable) {
                    Log.d(
                        Config.LOGTAG,
                        "could not switch camera",
                        Throwables.getRootCause(throwable))
                    Toast.makeText(
                        this@RtpSessionActivity,
                        R.string.could_not_switch_camera,
                        Toast.LENGTH_LONG)
                        .show()
                }
            },
            MainThreadExecutor.getInstance())
        // TODO ^ replace with ContextCompat.getMainExecutor(getApplication())
    }

    private fun enableVideo() {
        resetVisibilityToggleExecutor()
        try {
            requireRtpConnection().setVideoEnabled(true)
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show()
            return
        }
        updateInCallButtonConfigurationVideo(true, requireRtpConnection().isCameraSwitchable())
    }

    private fun disableVideo() {
        resetVisibilityToggleExecutor()
        val rtpConnection = requireRtpConnection()
        val pending = rtpConnection.getPendingContentAddition()
        if (pending != null && pending.direction == ContentAddition.Direction.OUTGOING) {
            rtpConnection.retractContentAdd()
            return
        }
        try {
            requireRtpConnection().setVideoEnabled(false)
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.could_not_disable_video, Toast.LENGTH_SHORT).show()
            return
        }
        updateInCallButtonConfigurationVideo(false, requireRtpConnection().isCameraSwitchable())
    }

    @SuppressLint("RestrictedApi")
    private fun updateInCallButtonConfigurationMicrophone(microphoneEnabled: Boolean) {
        // The deleted layout gave the microphone no accessible name; it still has none.
        ui.actionLeft =
            if (microphoneEnabled) {
                callButton(R.drawable.ic_mic_24dp, null) { disableMicrophone() }
            } else {
                callButton(R.drawable.ic_mic_off_24dp, null) { enableMicrophone() }
            }
    }

    private fun updateCallDuration() {
        val connection = this.rtpConnectionReference?.get()
        if (connection == null || connection.getMedia().contains(Media.VIDEO)) {
            ui.duration = null
            return
        }
        ui.duration =
            if (connection.zeroDuration()) {
                null
            } else {
                TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false)
            }
    }

    private fun resetVisibilityToggleExecutor() {
        mHandler.removeCallbacks(this.mVisibilityToggleExecutor)
        mHandler.postDelayed(this.mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT.toLong())
    }

    private fun updateButtonInVideoCallVisibility() {
        if (isInConnectedVideoCall()) {
            if (isPictureInPicture()) {
                return
            }
            Log.d(Config.LOGTAG, "hiding in-call buttons after timeout was reached")
            hideInCallButtons()
        }
    }

    private fun hideInCallButtons() {
        // The Material FAB's `hide()`: scale to nothing, which is the state's `visible = false`.
        ui.actionLeft = ui.actionLeft.copy(visible = false)
        ui.endCall = ui.endCall.copy(visible = false)
        ui.actionRight = ui.actionRight.copy(visible = false)
        ui.actionFarRight = ui.actionFarRight.copy(visible = false)
    }

    private fun showInCallButtons() {
        this.buttonsHiddenAfterTimeout = false
        val rtpConnection = try {
            requireRtpConnection()
        } catch (e: IllegalStateException) {
            return
        }
        updateButtonConfiguration(
            rtpConnection.getEndUserState(),
            rtpConnection.getMedia(),
            rtpConnection.getPendingContentAddition())
    }

    private fun resetVisibilityExecutorShowButtons() {
        resetVisibilityToggleExecutor()
        showInCallButtons()
    }

    private fun updateVideoViews(state: RtpEndUserState) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            ui.localVideo = false
            localVideo.release()
            ui.remoteVideo = false
            remoteVideo.release()
            ui.micOffIndicator = false
            if (isPictureInPicture()) {
                ui.appBarVisible = false
                ui.pipPlaceholder = true
                if (listOf(
                        RtpEndUserState.APPLICATION_ERROR,
                        RtpEndUserState.CONNECTIVITY_ERROR,
                        RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    ui.pipWarning = true
                    ui.pipWaiting = false
                } else {
                    ui.pipWarning = false
                    ui.pipWaiting = false
                }
            } else {
                ui.appBarVisible = true
                ui.pipPlaceholder = false
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            return
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            ui.localVideo = false
            ui.remoteVideo = false
            ui.appBarVisible = false
            ui.pipPlaceholder = true
            ui.pipWarning = false
            ui.pipWaiting = true
            ui.micOffIndicator = false
            return
        }
        val localVideoTrack = getLocalVideoTrack()
        if (localVideoTrack.isPresent && !isPictureInPicture()) {
            ui.localVideo = true
            ensureSurfaceViewRendererIsSetup(localVideo)
            // paint local view over remote view
            localVideo.setZOrderMediaOverlay(true)
            localVideo.setMirror(requireRtpConnection().isFrontCamera())
            addSink(localVideoTrack.get(), localVideo)
        } else {
            ui.localVideo = false
        }
        val remoteVideoTrack = getRemoteVideoTrack()
        if (remoteVideoTrack.isPresent) {
            ensureSurfaceViewRendererIsSetup(remoteVideo)
            addSink(remoteVideoTrack.get(), remoteVideo)
            remoteVideo.setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            if (state == RtpEndUserState.CONNECTED) {
                ui.appBarVisible = false
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                ui.remoteVideo = true
            } else {
                ui.appBarVisible = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                ui.remoteVideo = false
            }
            ui.micOffIndicator =
                isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            ui.remoteVideo = false
            ui.micOffIndicator = false
        }
    }

    private fun getLocalVideoTrack(): Optional<VideoTrack> {
        val connection = this.rtpConnectionReference?.get() ?: return Optional.absent()
        return connection.getLocalVideoTrack()
    }

    private fun getRemoteVideoTrack(): Optional<VideoTrack> {
        val connection = this.rtpConnectionReference?.get() ?: return Optional.absent()
        return connection.getRemoteVideoTrack()
    }

    private fun disableMicrophone() {
        setMicrophoneEnabled(false)
    }

    private fun enableMicrophone() {
        setMicrophoneEnabled(true)
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        resetVisibilityExecutorShowButtons()
        try {
            val rtpConnection = requireRtpConnection()
            if (rtpConnection.setMicrophoneEnabled(enabled)) {
                updateInCallButtonConfiguration()
            }
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToEarpiece() {
        try {
            requireCallIntegration().setAudioDevice(AudioDevice.EARPIECE)
            acquireProximityWakeLock()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToSpeaker() {
        try {
            requireCallIntegration().setAudioDevice(AudioDevice.SPEAKER_PHONE)
            releaseProximityWakeLock()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show()
        }
    }

    private fun retry() {
        val intent = getIntent()
        val account = extractAccount(intent)
        val with = Jid.of(intent.getStringExtra(EXTRA_WITH) ?: throw NullPointerException())
        val lastAction = intent.getStringExtra(EXTRA_LAST_ACTION)
        val action = intent.getAction()
        actionToMedia(lastAction ?: action)
        this.rtpConnectionReference = null
        Log.d(Config.LOGTAG, "attempting retry with " + with.toString())
        UiHost.installed()
            .placeCall(
                xmppConnectionService,
                account ?: throw NullPointerException(),
                with,
                lastAction ?: action ?: throw NullPointerException())
    }

    private fun exit() {
        finish()
    }

    private fun recordVoiceMail() {
        val intent = getIntent()
        val account = extractAccount(intent) ?: throw NullPointerException()
        val with = Jid.of(intent.getStringExtra(EXTRA_WITH) ?: throw NullPointerException())
        val conversation =
            xmppConnectionService.findOrCreateConversation(account, with, false, true) as Conversation
        val launchIntent = Intent(this, ConversationListActivity::class.java)
        launchIntent.setAction(ConversationListActivity.ACTION_VIEW_CONVERSATION)
        launchIntent.putExtra(ConversationListActivity.EXTRA_CONVERSATION, conversation.getUuid())
        launchIntent.setFlags(intent.getFlags() or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launchIntent.putExtra(
            ConversationListActivity.EXTRA_POST_INIT_ACTION,
            ConversationListActivity.POST_ACTION_RECORD_VOICE)
        startActivity(launchIntent)
        finish()
    }

    private fun getWith(): Contact? {
        val id = requireRtpConnection().getId()
        // Tulkki: 3.7 C5-E4 - `Id.account` is the island's `AccountRef` now, and this file must not
        // name a ref (that would be one more `ui-reaches-island`, which no `allow` can legalise), so
        // the model is fetched back through the registry. `findAccountByUuid` returns the element of
        // `List<Account>`, i.e. the same instance the ref wraps, which is what makes the identity
        // test in `updateRtpSession` (`account == id.account`) still true.
        val account = AccountRegistry.get().findAccountByUuid(id.account.getUuid())
        if (account == null) {
            // Null is possible: the account can be removed while a jingle session is still live, and
            // this screen outlives the registry entry. A call screen for an account that no longer
            // exists has nothing to show, so every caller skips the update instead of dereferencing.
            Log.e(Config.LOGTAG, "rtp session's account is gone from the registry")
            return null
        }
        return account.getRoster().getContact(id.with)
    }

    private fun requireRtpConnection(): JingleRtpConnection {
        val connection = this.rtpConnectionReference?.get()
            ?: throw IllegalStateException("No RTP connection found")
        return connection
    }

    private fun requireCallIntegration(): UiHost.CallAudio {
        // The connection is an :app object; this screen only ever wants the audio half of it, which
        // is the interface it declares itself. No import of the class appears here.
        val callIntegration = requireOngoingRtpSession().getCallIntegration()
        return callIntegration as UiHost.CallAudio
    }

    private fun requireOngoingRtpSession(): OngoingRtpSession {
        val connection = this.rtpConnectionReference?.get()
        if (connection != null) {
            return connection
        }
        val currentIntent = getIntent()
        val withExtra = currentIntent?.getStringExtra(EXTRA_WITH)
        val account = extractAccount(currentIntent)
        if (withExtra == null) {
            throw IllegalStateException("Current intent has no EXTRA_WITH")
        }
        // Tulkki: 3.7 C5 - the same shape as `retractSessionProposal` above. The Java handed this
        // possibly-null `Account` to the island's non-null `AccountRef`, and a null matched no
        // proposal, so it fell through to the throw below; `Account` implements the ref, so no
        // boundary moves, and the null case is skipped here rather than the parameter widened.
        if (account != null) {
            val matching = xmppConnectionService
                .getJingleConnectionManager()
                .matchingProposal(account, Jid.of(withExtra))
            if (matching.isPresent) {
                return matching.get()
            }
        }
        throw IllegalStateException("No matching session proposal")
    }

    // Tulkki: C5-C - `uk.xa0.tulkki.xmpp.services.OnJingleRtpConnectionUpdate`'s parameter is the island's
    // `AccountRef` now, so this override must name it. Fully qualified in the signature, imported
    // nowhere: a `:ui -> :xmpp` import here would move `ui-reaches-island` off its baselined 241
    // (rounds 151/161).
    //
    // The one narrowing below is the C5 idiom (`XmppConnectionService.sendIqPacket` does the same):
    // this class's own helpers still ask for the model, and three of them leave this commit's file
    // set (`JingleConnectionManager.findJingleRtpConnection(Account, …)`). It cannot fail as the tree
    // stands - `uk.xa0.tulkki.data.model.Account` is the **only** implementor of `AccountRef` in the
    // repository, measured by grep, so there is no ref value that is not already an `Account` - and
    // `:ui -> :data` is a declared edge, so the import already existed here.
    public override fun onJingleRtpConnectionUpdate(
        accountRef: uk.xa0.tulkki.xmpp.refs.AccountRef,
        with: Jid,
        sessionId: String,
        state: RtpEndUserState,
    ) {
        val account = accountRef as Account
        Log.d(Config.LOGTAG, "onJingleRtpConnectionUpdate(" + state + ")")
        if (END_CARD.contains(state)) {
            Log.d(Config.LOGTAG, "end card reached")
            releaseProximityWakeLock()
            runOnUiThread(
                { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) })
        }
        if (with.isBareJid()) {
            // TODO check for ENDED
            updateRtpSessionProposalState(account, with, state)
            return
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "not reinitializing session")
                return
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId)
            return
        }
        val id: AbstractJingleConnection.Id = requireRtpConnection().getId()
        val verified = requireRtpConnection().isVerified()
        val media = getMedia()
        lockOrientation(media)
        val contentAddition = getPendingContentAddition()
        // The Java's `account == id.account` is reference identity, not `equals`; `===` keeps it.
        if (account === id.account && id.with == with && id.sessionId == sessionId) {
            if (state == RtpEndUserState.ENDED) {
                finish()
                return
            }
            // Tulkki: 3.7 C5-E4 - resolved after the end-card close above, because `getWith()` answers
            // null when the session's account has been removed from the registry (the stale-event
            // case) and a null contact must not swallow the close.
            val contact = getWith() ?: return
            resetVisibilityToggleExecutor()
            runOnUiThread {
                updateStateDisplay(state, media, contentAddition)
                updateVerifiedShield(verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(state))
                updateButtonConfiguration(state, media, contentAddition)
                updateVideoViews(state)
                updateIncomingCallScreen(state, contact)
                updateSupportWarning(state, contact)
                updateMenuVisibility()
            }
            if (END_CARD.contains(state)) {
                val rtpConnection = requireRtpConnection()
                resetIntent(account, with, state, rtpConnection.getMedia())
                releaseVideoTracks(rtpConnection)
                this.rtpConnectionReference = null
            }
        } else {
            Log.d(Config.LOGTAG, "received update for other rtp session")
        }
    }

    @JvmSuppressWildcards
    public override fun onAudioDeviceChanged(
        selectedAudioDevice: AudioDevice,
        availableAudioDevices: Set<AudioDevice>,
    ) {
        Log.d(
            Config.LOGTAG,
            "onAudioDeviceChanged in activity: selected:" +
                selectedAudioDevice +
                ", available:" +
                availableAudioDevices)
        try {
            val ongoingRtpSession = requireOngoingRtpSession()
            val endUserState: RtpEndUserState
            if (ongoingRtpSession is JingleRtpConnection) {
                endUserState = ongoingRtpSession.getEndUserState()
            } else {
                // for session proposals all end user states are functionally the same
                endUserState = RtpEndUserState.RINGING
            }
            val media = ongoingRtpSession.getMedia()
            if (END_CARD.contains(endUserState)) {
                Log.d(
                    Config.LOGTAG,
                    "onAudioDeviceChanged() nothing to do because end card has been reached")
            } else {
                if (Media.audioOnly(media) &&
                    STATES_SHOWING_SPEAKER_CONFIGURATION.contains(endUserState)) {
                    val callIntegration = requireCallIntegration()
                    updateInCallButtonConfigurationSpeaker(
                        callIntegration.getSelectedAudioDevice(),
                        callIntegration.getAudioDevices().size)
                }
                Log.d(
                    Config.LOGTAG,
                    "put proximity wake lock into proper state after device update")
                putProximityWakeLockInProperState(selectedAudioDevice)
            }
        } catch (e: IllegalStateException) {
            Log.d(Config.LOGTAG, "RTP connection was not available when audio device changed")
        }
    }

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("dialpad_visible", ui.dialpadVisible)
    }

    private fun updateRtpSessionProposalState(
        account: Account,
        with: Jid,
        state: RtpEndUserState,
    ) {
        val currentIntent = getIntent() ?: return
        val withExtra = currentIntent.getStringExtra(EXTRA_WITH) ?: return
        val media = actionToMedia(currentIntent.getStringExtra(EXTRA_LAST_ACTION))
        if (Jid.of(withExtra).asBareJid() == with) {
            runOnUiThread {
                updateVerifiedShield(false)
                updateStateDisplay(state)
                updateButtonConfiguration(state, media, null)
                updateIncomingCallScreen(state)
                updateSupportWarning(state, account.getRoster().getContact(with))
                updateMenuVisibility()
            }
            resetIntent(account, with, state, media)
        }
    }

    private fun resetIntent(extras: Bundle?) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.putExtras(extras ?: throw NullPointerException("extras"))
        setIntent(intent)
    }

    private fun resetIntent(
        account: Account,
        with: Jid,
        state: RtpEndUserState,
        media: Set<Media>,
    ) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString())
        if (RtpCapability.jmiSupport(account.getRoster().getContact(with))) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toString())
        } else {
            intent.putExtra(EXTRA_WITH, with.toString())
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString())
        intent.putExtra(
            EXTRA_LAST_ACTION,
            if (media.contains(Media.VIDEO)) ACTION_MAKE_VIDEO_CALL else ACTION_MAKE_VOICE_CALL)
        setIntent(intent)
    }

    private enum class Event {
        ON_BACKEND_CONNECTED,
        ON_NEW_INTENT,
    }

    companion object {
        const val EXTRA_WITH = "with"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PROPOSED_SESSION_ID = "proposed_session_id"
        const val EXTRA_LAST_REPORTED_STATE = "last_reported_state"
        const val EXTRA_LAST_ACTION = "last_action"
        const val ACTION_ACCEPT_CALL = "action_accept_call"
        const val ACTION_MAKE_VOICE_CALL = "action_make_voice_call"
        const val ACTION_MAKE_VIDEO_CALL = "action_make_video_call"

        // Kotlin cannot reach `XmppActivity`'s inherited Java `EXTRA_ACCOUNT` through this class from
        // another module (`RtpSessionActivity.EXTRA_ACCOUNT` is unresolved), so it is declared here
        // with the superclass's value; Java callers see only a field hiding the inherited one.
        @JvmField
        val EXTRA_ACCOUNT: String = XmppActivity.EXTRA_ACCOUNT

        private const val CALL_DURATION_UPDATE_INTERVAL = 250
        private const val BUTTON_VISIBILITY_TIMEOUT = 10_000

        @JvmField
        val END_CARD: List<RtpEndUserState> = listOf(
            RtpEndUserState.APPLICATION_ERROR,
            RtpEndUserState.SECURITY_ERROR,
            RtpEndUserState.DECLINED_OR_BUSY,
            RtpEndUserState.CONTACT_OFFLINE,
            RtpEndUserState.CONNECTIVITY_ERROR,
            RtpEndUserState.CONNECTIVITY_LOST_ERROR,
            RtpEndUserState.RETRACTED)

        private val STATES_SHOWING_HELP_BUTTON: List<RtpEndUserState> = listOf(
            RtpEndUserState.APPLICATION_ERROR,
            RtpEndUserState.CONNECTIVITY_ERROR,
            RtpEndUserState.SECURITY_ERROR)

        private val STATES_SHOWING_SWITCH_TO_CHAT: List<RtpEndUserState> = listOf(
            RtpEndUserState.CONNECTING,
            RtpEndUserState.CONNECTED,
            RtpEndUserState.RECONNECTING,
            RtpEndUserState.INCOMING_CONTENT_ADD)

        private val STATES_CONSIDERED_CONNECTED: List<RtpEndUserState> = listOf(
            RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING)

        private val STATES_SHOWING_PIP_PLACEHOLDER: List<RtpEndUserState> = listOf(
            RtpEndUserState.ACCEPTING_CALL,
            RtpEndUserState.CONNECTING,
            RtpEndUserState.RECONNECTING)

        private val STATES_SHOWING_SPEAKER_CONFIGURATION: List<RtpEndUserState> =
            ImmutableList.Builder<RtpEndUserState>()
                .add(RtpEndUserState.FINDING_DEVICE)
                .add(RtpEndUserState.RINGING)
                .add(RtpEndUserState.ACCEPTING_CALL)
                .add(RtpEndUserState.CONNECTING)
                .addAll(STATES_CONSIDERED_CONNECTED)
                .build()

        private const val PROXIMITY_WAKE_LOCK_TAG = "Tulkki:in-rtp-session"
        private const val REQUEST_ACCEPT_CALL = 0x1111
        private const val REQUEST_ACCEPT_CONTENT = 0x1112
        private const val REQUEST_ADD_CONTENT = 0x1113

        @JvmStatic
        fun actionToMedia(action: String?): Set<Media> {
            return if (ACTION_MAKE_VIDEO_CALL == action) {
                ImmutableSet.of(Media.AUDIO, Media.VIDEO)
            } else if (ACTION_MAKE_VOICE_CALL == action) {
                ImmutableSet.of(Media.AUDIO)
            } else {
                Log.w(
                    Config.LOGTAG,
                    "actionToMedia can not get media set from unknown action " + action)
                emptySet()
            }
        }

        private fun addSink(videoTrack: VideoTrack, surfaceViewRenderer: org.webrtc.SurfaceViewRenderer) {
            try {
                videoTrack.addSink(surfaceViewRenderer)
            } catch (e: IllegalStateException) {
                Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e)
            }
        }

        private fun emptyReference(weakReference: WeakReference<*>?): Boolean {
            return weakReference == null || weakReference.get() == null
        }
    }
}
