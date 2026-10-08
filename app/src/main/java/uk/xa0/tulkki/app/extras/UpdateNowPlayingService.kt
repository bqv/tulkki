package uk.xa0.tulkki.app.extras

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.preference.PreferenceManager
import uk.xa0.tulkki.app.receiver.SystemEventReceiver
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Publishes the phone's currently playing track as a user tune, whenever a notification arrives.
 *
 * <p>**Two portable facts about Kotlin and Java, both decided per site.**
 *
 * <p>*Java inherits its superclass's statics into scope; Kotlin does not.* So the Java's bare
 * `MEDIA_SESSION_SERVICE` and `BIND_AUTO_CREATE` are written `Context.MEDIA_SESSION_SERVICE` and
 * `Context.BIND_AUTO_CREATE` here - the same constants, reached through the class that declares them.
 * The inherited *methods* (`startService`, `bindService`, `getSystemService`) are unaffected.
 *
 * <p>*Kotlin cannot smart-cast a mutable field*, and `xmppConnectionService` is a public field
 * because Java reads it. `queryActiveSessions` therefore binds it once to a local after the Java's
 * own null check - the field is only ever assigned by the service connection below, on the same
 * thread, so the local is the same object the Java would have re-read three times.
 *
 * <p>The `xmppConnectionService` field stays a `@JvmField` (the Java field was public and not
 * volatile, and neither is this), and `mConnection` stays `protected val`.
 */
class UpdateNowPlayingService : NotificationListenerService() {

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
            Log.w(TAG, "unable to start service from " + javaClass.simpleName)
        }
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        queryActiveSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        queryActiveSessions()
    }

    private fun queryActiveSessions() {
        val service = xmppConnectionService
        if (service == null) {
            return
        }

        val mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, UpdateNowPlayingService::class.java)

        val context = applicationContext
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("load_now_playing_from_system", false)) {
            // If the feature is disabled, we should also stop any currently published tune.
            service.stopPublishingUserTuneAsync()
            return
        }

        val trackLongerThan =
                Integer.parseInt(
                        prefs.getString(
                                "update_track_longer_than",
                                context.resources
                                        .getInteger(R.integer.update_track_longer_than_secs)
                                        .toString()))

        val controllers: List<MediaController>
        try {
            controllers = mediaSessionManager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification access not granted")
            return
        }

        var isAnythingPlaying = false
        for (controller in controllers) {
            val metadata: MediaMetadata? = controller.metadata
            val playbackState: PlaybackState? = controller.playbackState

            if (playbackState != null && playbackState.state == PlaybackState.STATE_PLAYING) {
                isAnythingPlaying = true
                if (metadata == null) {
                    continue
                }

                val durationSecs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000
                if (durationSecs <= trackLongerThan) {
                    continue
                }

                if (service.publishUserTuneAsync(metadata)) {
                    // We found and published a playing track, so we can stop looking.
                    return
                }
            }
        }

        // If we looped through all controllers and nothing was playing, send the stop command.
        if (!isAnythingPlaying) {
            service.stopPublishingUserTuneAsync()
        }
    }

    companion object {

        private const val TAG = "UpdateNowPlayingService"
    }
}
