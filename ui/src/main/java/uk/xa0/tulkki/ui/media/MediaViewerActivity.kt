package uk.xa0.tulkki.ui.media

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID
import me.drakeet.support.toast.ToastCompat
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.UiCallback
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnMediaLoaded
import uk.xa0.tulkki.ui.media.MediaViewerScreen
import uk.xa0.tulkki.ui.util.Rationals
import uk.xa0.tulkki.xmpp.Config

/**
 * The media viewer: images, videos and audio fullscreen, one per page, shareable, openable,
 * savable and deletable.
 *
 * <p>**Its two layouts are gone.** `activity_media_viewer.xml` was a `RelativeLayout` of a
 * `ViewPager2`, a `SpeedDialOverlayLayout` and a `SpeedDialView`, and `item_media_viewer.xml` was a
 * page's `PhotoView` and `PlayerView`; [MediaViewerScreen] is the pager and [ViewerPage] below is both
 * page surfaces, so both files are deleted and the screen is `setTulkkiContent` with no chrome - the
 * layout never had a toolbar, `onCreate` hid the action bar, and the Activity's theme is
 * `Theme.Tulkki.FullScreen`, so a top app bar would be an affordance this screen has never had.
 *
 * <p>**The class keeps everything a Composable cannot.** The `ExoPlayer` and its listener, the audio
 * focus, picture-in-picture, the orientation and brightness window flags, the rotation read, and the
 * four actions are all still here; the deleted `updateSpeedDialActions` became the screen's action
 * stack plus the two pieces of state that decide it - `actionsVisible`, which is what `showFAB` and
 * `hideFAB` meant, and `canDelete`, which is what `isDeletableFile(mFile)` decided. The page callback
 * is the old `onPageSelected`, so `onMediaItemSelected` still stops, loads and prepares the player for
 * the video or audio page and stops it for an image, and still re-reads the rotation.
 *
 * <p>**What is not reproduced.** The old `GestureDetector` never received an event (nothing called
 * `onTouchEvent`), so its `onDown` toggle is dropped and the `PhotoView`'s photo-tap listener - the one
 * that worked - is the screen's only toggle. `onMediaItemSelected`'s loop that detached the player from
 * every visible `ViewHolder` is unnecessary now that a page's player follows its own `isCurrent`.
 */
class MediaViewerActivity :
        XmppActivity(), OnMediaLoaded, AudioManager.OnAudioFocusChangeListener {

    var oldOrientation: Int? = null
    var player: ExoPlayer? = null
    var mFile: File? = null
    var height = 0
    var width = 0
    var aspect: Rational? = null
    var rotation = 0
    var isImage = false
    var isVideo = false
    var isAudio = false
    var mediaSession: MediaSession? = null

    // Tulkki: 3.7 C5-E3 - the pager holds the island's view of an attachment. `:app` may import the
    // ref directly (it has no ratchet), and `Attachment.of(...)` below still builds models, which are
    // refs.
    private val attachments = mutableStateListOf<AttachmentRef>()
    private var initialMessageUuid: String? = null

    /** The page the viewer opens on, as `setCurrentItem(position, false)` chose it. */
    private var initialPage by mutableStateOf(0)

    /** `showFAB`/`hideFAB`: whether the action stack is on screen at all. */
    private var actionsVisible by mutableStateOf(true)

    /** `isDeletableFile(mFile)`, for the action stack's Delete item. */
    private var canDelete by mutableStateOf(false)

    /** A second intent can hand over a list of the same size; this is what says it changed. */
    private var reloads by mutableStateOf(0)

    private var hasAudioFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF)
        exoPlayer.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            actionsVisible = false
                        } else {
                            if (UiHost.installed().runsTwentyFour() && isInPictureInPictureMode) {
                                actionsVisible = false
                            } else {
                                actionsVisible = true
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(Config.LOGTAG, "PlayerError: ", error)
                    }
                })

        oldOrientation = requestedOrientation

        val layout = window.attributes
        if (useMaxBrightness()) {
            layout.screenBrightness = 1f
        }
        window.attributes = layout
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // The fullscreen viewer has no bar - the theme is `Theme.Tulkki.FullScreen` and the deleted
        // layout never held a toolbar - so this is the host without the chrome. See MediaViewerScreen.
        setTulkkiContent(darkTheme = isDark()) {
            MediaViewerScreen(
                pageCount = attachments.size,
                initialPage = initialPage,
                actionsVisible = actionsVisible,
                canDelete = canDelete,
                reloadToken = reloads,
                onPageChange = { index ->
                    if (index >= 0 && index < attachments.size) {
                        onMediaItemSelected(attachments[index])
                    }
                },
                onToggleActions = { actionsVisible = !actionsVisible },
                onDelete = { deleteFile() },
                onOpen = { open() },
                onShare = { share() },
                onSave = { confirmSaveToDownloads() },
                page = { index, isCurrent ->
                    val attachment = attachments.getOrNull(index)
                    if (attachment != null) {
                        ViewerPage(
                            attachment = attachment,
                            player = player,
                            isCurrent = isCurrent,
                            onPhotoTap = { actionsVisible = !actionsVisible },
                        )
                    }
                },
            )
        }
    }

    /**
     * One page's surface, which is the deleted `item_media_viewer.xml`: the `PhotoView` it held for an
     * image, or its `PlayerView` for a video or an audio attachment, with that file's attributes set at
     * the setters that replaced them. It is here rather than in [MediaViewerScreen] because both are
     * Android views and a screenshot cell can then render the screen around them.
     *
     * <p>The file bound one of two visibilities per page; the mime picks the surface here, so an image
     * page never builds a player and the player is never attached to a page that is not current.
     * `getMime()` is nullable on the ref, where the old Java dereferenced it and threw; a null mime
     * takes the image arm, which is what every non-video, non-audio mime did.
     */
    @Composable
    private fun ViewerPage(
        attachment: AttachmentRef,
        player: Player?,
        isCurrent: Boolean,
        onPhotoTap: () -> Unit,
    ) {
        val mime = attachment.getMime().orEmpty()
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            val context = LocalContext.current
            val playedColor =
                MaterialColors.getColor(
                    context,
                    androidx.appcompat.R.attr.colorAccent,
                    MaterialTheme.colorScheme.primary.toArgb(),
                )
            AndroidView(
                factory = { factoryContext ->
                    PlayerView(factoryContext).apply {
                        setUseController(true)
                        setControllerAutoShow(false)
                        setShowShuffleButton(false)
                        setShowSubtitleButton(false)
                        setShowVrButton(false)
                        setUseArtwork(true)
                        setDefaultArtwork(
                            ContextCompat.getDrawable(
                                factoryContext, uk.xa0.tulkki.ui.R.drawable.ic_headphones_48dp))
                        val gray = ContextCompat.getColor(factoryContext, uk.xa0.tulkki.ui.R.color.gray_700)
                        findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
                            bar.setBufferedColor(gray)
                            bar.setUnplayedColor(gray)
                            bar.setPlayedColor(playedColor)
                            bar.setScrubberColor(playedColor)
                        }
                        hideController()
                    }
                },
                update = { view -> view.setPlayer(if (isCurrent) player else null) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { factoryContext ->
                    PhotoView(factoryContext).apply {
                        setOnPhotoTapListener { _, _, _ -> onPhotoTap() }
                    }
                },
                update = { view ->
                    Glide.with(this@MediaViewerActivity).load(attachment.getUri()).into(view)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun share() {
        val file = mFile!!
        val share = Intent(Intent.ACTION_SEND)
        share.setType(getMimeType(file.toString()))
        share.putExtra(Intent.EXTRA_STREAM, FileBackend.getUriForFile(this, file, file.name))
        try {
            startActivity(
                    Intent.createChooser(share, getText(uk.xa0.tulkki.ui.R.string.share_with)))
        } catch (e: ActivityNotFoundException) {
            ToastCompat.makeText(
                            this,
                            uk.xa0.tulkki.ui.R.string.no_application_found_to_open_file,
                            ToastCompat.LENGTH_SHORT)
                    .show()
        }
    }

    private fun deleteFile() {
        val builder = AlertDialog.Builder(this)
        builder.setNegativeButton(R.string.cancel, null)
        builder.setTitle(uk.xa0.tulkki.ui.R.string.delete_file_dialog)
        builder.setMessage(uk.xa0.tulkki.ui.R.string.delete_file_dialog_msg)
        builder.setPositiveButton(uk.xa0.tulkki.ui.R.string.confirm) { _, _ ->
            if (FileBackends.get().deleteFile(mFile!!)) {
                finish()
            }
        }
        builder.create().show()
    }

    private fun open() {
        val file = mFile!!
        val uri: Uri
        try {
            uri = FileBackend.getUriForFile(this, file, file.name)
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "No permission to access " + file.absolutePath, e)
            ToastCompat.makeText(
                            this,
                            getString(
                                    uk.xa0.tulkki.ui.R.string.no_permission_to_access_x,
                                    file.absolutePath),
                            ToastCompat.LENGTH_SHORT)
                    .show()
            return
        }
        val mime = MimeUtils.guessMimeTypeFromUri(this, uri)
        val openIntent = Intent(Intent.ACTION_VIEW)
        openIntent.setDataAndType(uri, mime)
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val p = player
        if (p != null && (isVideo || isAudio)) {
            openIntent.putExtra("position", p.getCurrentPosition())
        }
        try {
            this.startActivity(openIntent)
        } catch (e: ActivityNotFoundException) {
            ToastCompat.makeText(
                            this,
                            uk.xa0.tulkki.ui.R.string.no_application_found_to_open_file,
                            ToastCompat.LENGTH_SHORT)
                    .show()
        }
    }

    override fun refreshUiReal() {}

    /** The Save to Downloads confirmation the dial's item raised before it copied. */
    private fun confirmSaveToDownloads() {
        val file = mFile ?: return
        MaterialAlertDialogBuilder(this)
                .setTitle(uk.xa0.tulkki.ui.R.string.action_save_to_downloads)
                .setMessage(uk.xa0.tulkki.ui.R.string.save_to_downloads_warning)
                .setPositiveButton(uk.xa0.tulkki.ui.R.string.confirm) { _, _ ->
                    saveToDownloads(file)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun saveToDownloads(file: File) {
        xmppConnectionService.copyAttachmentToDownloadsFolder(
                file,
                object : UiCallback<Int> {
                    override fun success(obj: Int) {
                        runOnUiThread {
                            Toast.makeText(
                                            this@MediaViewerActivity,
                                            getString(
                                                    uk.xa0.tulkki.ui.R.string
                                                            .save_to_downloads_success),
                                            Toast.LENGTH_LONG)
                                    .show()
                        }
                    }

                    override fun error(errorCode: Int, obj: Int?) {
                        obj ?: return
                        runOnUiThread {
                            Toast.makeText(
                                            this@MediaViewerActivity,
                                            getString(obj),
                                            Toast.LENGTH_LONG)
                                    .show()
                        }
                    }

                    override fun userInputRequired(pi: PendingIntent?, obj: Int) {}
                })
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun PIPVideo() {
        try {
            actionsVisible = false
            if (supportsPIP()) {
                if (UiHost.installed().runsTwentySix()) {
                    val rational = Rational(width, height)
                    if (rational.getDenominator() == 0) {
                        Log.w(
                                Config.LOGTAG,
                                "Invalid aspect ratio for PIP: width=" + width + ", height=" + height)
                        return
                    }
                    val clippedRational = Rationals.clip(rational)
                    val params =
                            PictureInPictureParams.Builder()
                                    .setAspectRatio(clippedRational)
                                    .build()
                    this.enterPictureInPictureMode(params)
                } else {
                    this.enterPictureInPictureMode()
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e)
        } catch (e: IllegalArgumentException) {
            Log.w(
                    Config.LOGTAG,
                    "Illegal argument for picture in picture mode (aspect ratio?): " + e.message)
        }
    }

    override fun onPictureInPictureModeChanged(
            isInPictureInPictureMode: Boolean,
            newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            player!!.play()
            actionsVisible = false
        } else {
            actionsVisible = true
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (audioManager != null) {
            audioManager.abandonAudioFocus(this)
            hasAudioFocus = false
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (audioManager != null) {
            val result =
                    audioManager.requestAudioFocus(
                            this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    override fun onBackPressed() {
        if (isVideo && isPlaying() && supportsPIP()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PIPVideo()
            }
        } else {
            super.onBackPressed()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideo) {
            PIPVideo()
        }
    }

    private fun getRotation(image: Uri): Int {
        try {
            contentResolver.openInputStream(image).use { stream ->
                return if (stream == null) 0 else FileBackend.getRotation(stream)
            }
        } catch (e: Exception) {
            return 0
        }
    }

    private fun rotateScreen(width: Int, height: Int, rotation: Int) {
        if (width > height) {
            if (rotation == 0 || rotation == 180) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        }
    }

    private fun isPlaying(): Boolean {
        val p = player ?: return false
        return p.getPlaybackState() != Player.STATE_ENDED &&
                p.getPlaybackState() != Player.STATE_IDLE &&
                p.getPlayWhenReady()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val p = player
            if (p != null &&
                    !isInPictureInPictureMode &&
                    p.getPlaybackState() != Player.STATE_ENDED) {
                if (hasAudioFocus) {
                    p.play()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val p = player
            if (p != null && !isInPictureInPictureMode) {
                p.pause()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val p = player
        if (p != null) {
            p.release()
            player = null
        }
        val session = mediaSession
        if (session != null) {
            session.release()
            mediaSession = null
        }
        abandonAudioFocus()
    }

    override fun onStop() {
        val p = player
        if (p != null && (isVideo || isAudio)) {
            p.stop()
        }
        val layout = window.attributes
        if (useMaxBrightness()) {
            layout.screenBrightness = -1f
        }
        window.attributes = layout
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRequestedOrientation(oldOrientation!!)
        super.onStop()
    }

    override fun onBackendConnected() {
        val intent = getIntent()
        val convUuid = intent.getStringExtra("conversation_uuid")
        initialMessageUuid = intent.getStringExtra("message_uuid")

        val p = player
        if (p != null) {
            p.stop()
            p.clearMediaItems()
        }
        attachments.clear()

        if (convUuid != null) {
            val conversation =
                    xmppConnectionService.findConversationByUuid(convUuid) as Conversation?
            if (conversation != null) {
                xmppConnectionService.getAttachments(conversation, 0, this)
                return
            }
        }

        // Fallback for Media Browser: Try account and jid
        val accountUuid = intent.getStringExtra("account")
        val jidString = intent.getStringExtra("jid")
        if (accountUuid != null && jidString != null) {
            xmppConnectionService.getAttachments(accountUuid, Jid.of(jidString), 0, this)
            return
        }

        setupSingleMediaFallback(intent)
    }

    private fun setupSingleMediaFallback(intent: Intent) {
        var uri: Uri? = null
        var mime: String? = null
        if (intent.hasExtra("image")) {
            uri = intent.getParcelableExtra<Uri>("image")
            mime = "image/*"
        } else if (intent.hasExtra("video")) {
            uri = intent.getParcelableExtra<Uri>("video")
            mime = "video/*"
        } else if (intent.hasExtra("audio")) {
            uri = intent.getParcelableExtra<Uri>("audio")
            mime = "audio/*"
        }

        if (uri != null) {
            attachments.add(Attachment.of(UUID.randomUUID(), File(uri.path), mime))
        }
    }

    fun useMaxBrightness(): Boolean {
        return false
    }

    fun useAutoRotateScreen(): Boolean {
        return false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                val p = player
                if (p != null &&
                        (p.getPlayWhenReady() || p.getPlaybackState() == Player.STATE_READY)) {
                    p.play()
                    p.setVolume(1.0f)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = true
                player?.setVolume(0.3f)
            }
        }
    }

    private fun isDeletableFile(file: File?): Boolean {
        return (file == null || !file.toString().startsWith("/") || file.canWrite())
    }

    private fun supportsPIP(): Boolean {
        if (UiHost.installed().runsTwentyFour()) {
            return this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } else {
            return false
        }
    }

    override fun onMediaLoaded(loadedAttachments: List<AttachmentRef>) {
        runOnUiThread {
            attachments.clear()
            for (a in loadedAttachments) {
                val mime = a.getMime()
                if (mime != null &&
                        (mime.startsWith("image/") ||
                                mime.startsWith("video/") ||
                                mime.startsWith("audio/"))) {
                    attachments.add(a)
                }
            }
            // `setCurrentItem(position, false)`, once, instead of inside the search loop.
            var page = 0
            val wanted = initialMessageUuid
            if (wanted != null) {
                for (i in 0 until attachments.size) {
                    if (attachments[i].getUuid().toString() == wanted) {
                        page = i
                        break
                    }
                }
            }
            initialPage = page
            reloads++
        }
    }

    private fun updateRotation(attachment: AttachmentRef) {
        val uri = attachment.getUri()
        // `getMime()` is `String?` on the ref now; the Java dereferenced it here.
        val mime = attachment.getMime() ?: throw NullPointerException()
        if (mime.startsWith("image/")) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(File(uri.path).getAbsolutePath(), options)
            height = options.outHeight
            width = options.outWidth
            rotation = getRotation(uri)
        } else if (mime.startsWith("video/")) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(uri.path)
                height =
                        Integer.parseInt(
                                retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                width =
                        Integer.parseInt(
                                retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                rotation =
                        Integer.parseInt(
                                retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            } catch (e: Exception) {
                rotation = 0
            }
        } else if (mime.startsWith("audio/")) {
            height = 0
            width = 0
            rotation = 0
        }
        if (useAutoRotateScreen()) {
            rotateScreen(width, height, rotation)
        }
    }

    /**
     * The page callback: the old `onPageSelected`, minus the loop that detached the player from every
     * other visible page. A page's `PlayerView` now follows its own `isCurrent`, so only this page has
     * to be told what it is.
     */
    private fun onMediaItemSelected(attachment: AttachmentRef) {
        mFile = File(attachment.getUri().getPath())
        val p = player ?: return

        // `getMime()` is `String?` on the ref now; the Java dereferenced it here, four times.
        val mime = attachment.getMime() ?: throw NullPointerException()
        if (mime.startsWith("video/") ||
                mime.startsWith("audio/")) {
            isVideo = mime.startsWith("video/")
            isAudio = mime.startsWith("audio/")
            isImage = false
            p.stop()
            p.setMediaItem(MediaItem.fromUri(attachment.getUri()))
            p.prepare()
            p.setPlayWhenReady(true)
            requestAudioFocus()
        } else {
            isVideo = false
            isAudio = false
            isImage = true
            p.stop()
        }
        canDelete = isDeletableFile(mFile)
        updateRotation(attachment)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val p = player
        if (p != null) {
            p.stop()
            p.clearMediaItems()
        }
        attachments.clear()
        if (xmppConnectionService != null) {
            onBackendConnected()
        }
    }

    companion object {

        @JvmStatic
        fun getMimeType(path: String): String? {
            try {
                var type: String? = null
                val extension = path.substring(path.lastIndexOf(".") + 1, path.length)
                if (extension != null) {
                    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                return type
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }
}
