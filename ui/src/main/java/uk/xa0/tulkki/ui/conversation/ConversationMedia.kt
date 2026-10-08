package uk.xa0.tulkki.ui.conversation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.service.AudioPlayer
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.ViewUtil

/**
 * Tulkki: the conversation's media verbs, rehomed off the `GONE` Java list's adapter.
 *
 * <p>`MessageAdapter` used to carry three jobs that were never about drawing a row: the audio
 * player's stop/unregister/play-pause surface, opening a downloadable message (with the storage
 * permission round-trip that holds the request until the answer arrives), and that pending request
 * itself. They lived on the adapter only because the adapter was what the Java list handed them to,
 * and the adapter is a `GONE` stub now - so the typing was the only thing keeping them there, and
 * `MessageAdapter`'s reference to `ConversationFragment` was one of the two that would break the
 * fragment's deletion.
 *
 * <p>**This is a home, not a redesign.** Each verb is the adapter's own body, moved: the permission
 * branch keeps its ordering and its `requestCode` (`ConversationListActivity.REQUEST_OPEN_MESSAGE`),
 * the pending request keeps the one-slot `PendingItem` it had as the fragment's field, and the audio
 * surface keeps `AudioPlayer`, which was already a stub - `unregisterListener` and `startStopPending`
 * answer nothing, and `stop` only releases a proximity wake lock that nothing acquires. What has
 * *not* happened is the Compose list gaining a real player: the rows are Compose's and playback is a
 * surface the screenshot set does not draw yet, so this object carries the old state rather than
 * claiming a feature.
 *
 * <p>**The player is lazy and application-scoped.** The adapter built it once per conversation with
 * the activity as its context; here it is built on the first play-pause with the application context,
 * because a wake lock outliving a rotation must not hold an Activity. A stop or an unregister before
 * anything was built is a no-op rather than a second construction, which is the honest reading of
 * "nothing was playing".
 */
object ConversationMedia {

    /** The downloadable message waiting on the storage permission, the fragment's field once. */
    private val pendingDownload = PendingItem<Message>()

    /** The one player, or `null` until a play-pause asks for it. */
    private var audioPlayer: AudioPlayer? = null

    /**
     * Hold [message] until the owner has answered the storage permission: its download was asked
     * for, and the answer re-enters through [openPendingDownload].
     */
    @JvmStatic
    fun registerPendingDownload(message: Message) {
        pendingDownload.push(message)
    }

    /**
     * The permission answer arrived: open whatever was held. Nothing held is the ordinary case (the
     * request was granted outright, or it belonged to another account) and answers nothing.
     */
    @JvmStatic
    fun openPendingDownload(activity: Activity) {
        val message = pendingDownload.pop() ?: return
        openDownloadable(activity, message)
    }

    /**
     * Show [message]'s file, asking for the storage permission first on the platforms that need it
     * (`<` TIRAMISU) and holding the message across the answer. The body is the adapter's, moved
     * whole - including the `name ?: file.name` fallback and the two uuids `ViewUtil.view` needs.
     */
    @JvmStatic
    fun openDownloadable(activity: Activity, message: Message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            registerPendingDownload(message)
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                ConversationListActivity.REQUEST_OPEN_MESSAGE,
            )
            return
        }
        val file: DownloadableFile = FileBackends.get().getFile(message)
        val name = message.getFileParams().getName()
        val displayName = name ?: file.name
        ViewUtil.view(
            activity,
            file,
            displayName,
            (message.getConversation() ?: throw NullPointerException()).getUuid(),
            message.getUuid(),
        )
    }

    /** Nothing can be playing a row that is not drawn; only the lock may need releasing. */
    @JvmStatic
    fun stopAudioPlayer() {
        audioPlayer?.stop()
    }

    /** No sensor was ever registered for a row that is not drawn; the fragment's call stays answered. */
    @JvmStatic
    fun unregisterListenerInAudioPlayer() {
        audioPlayer?.unregisterListener()
    }

    /** The play-pause request: nothing is playing, so the player is only built if it is not there. */
    @JvmStatic
    fun startStopPending(activity: Activity) {
        audioPlayer(activity).startStopPending()
    }

    /** The one player, built on first use with the application context. */
    private fun audioPlayer(activity: Activity): AudioPlayer {
        var player = audioPlayer
        if (player == null) {
            player = AudioPlayer(activity.applicationContext)
            audioPlayer = player
        }
        return player
    }
}
