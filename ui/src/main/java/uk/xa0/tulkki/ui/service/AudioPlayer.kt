package uk.xa0.tulkki.ui.service

import android.content.Context
import android.os.PowerManager

/**
 * What is left of the audio player once its row is gone.
 *
 * <p>The four views the old `ViewHolder` found - `runtime`, `title`, `progress` and `play_pause` -
 * were declared by `item_message_content.xml`, the last of the eight message layouts. Two of them,
 * `runtime` and `play_pause`, were declared nowhere else, and the ViewHolder's only builder was
 * `MessageAdapter.displayAudioMessage`, which the Compose swap removed with the rows: the 436-line
 * `MessageAdapter` stub never inflates a message layout, so `ViewHolder.get` and the playback UI
 * built on it were already dead. Read against the tree, the seven other ids the layout declared were
 * either shared with a layout that has since gone (`title` in `activity_magic_create`, `progress` in
 * `activity_uri_handler`) or referenced nowhere at all; only these two pinned the file.
 *
 * <p>A missing half cannot be a ViewHolder, so the read and everything it fed went with it: no
 * `findViewById`, no `init`, no player, no seek bar and no sensor. `ConversationMedia` names this
 * class for the three lifecycle calls the fragment makes, and they are the three members below.
 * With no view bound there is nothing to click and nothing to register, so `unregisterListener` and
 * `startStopPending` answer without doing anything and `stop` can only release the proximity wake
 * lock the constructor takes - `stopAudioPlayer` was its release point, and the tree's own `stop`
 * released it even when nothing was playing.
 *
 * <p>**It takes a `Context` and not the adapter it used to take.** Its only use of `MessageAdapter`
 * was `getContext()` for the wake lock, and that reference was the last thing tying it to the `GONE`
 * list; the context is now handed in, by `ConversationMedia` with the application's.
 */
class AudioPlayer(context: Context) {

    private var wakeLock: PowerManager.WakeLock? = null

    init {
        initializeProximityWakeLock(context)
    }

    private fun initializeProximityWakeLock(context: Context) {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            wakeLock =
                powerManager?.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    AudioPlayer::class.java.getSimpleName())
            wakeLock?.setReferenceCounted(false)
        }
    }

    /** The fragment's own stop: nothing can be playing, so only the lock may need releasing. */
    fun stop() {
        val lock = wakeLock
        if (lock != null && lock.isHeld()) {
            lock.release()
        }
        wakeLock = null
    }

    /** No sensor was ever registered for a row that is not drawn; the fragment's unregister stays answered. */
    fun unregisterListener() = Unit

    /** No button can have been tapped, so the permission callback has nothing to resume. */
    fun startStopPending() = Unit
}
