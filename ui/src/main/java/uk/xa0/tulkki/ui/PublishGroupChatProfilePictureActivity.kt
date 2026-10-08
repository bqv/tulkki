package uk.xa0.tulkki.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.interfaces.OnAvatarPublication
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.xmpp.Config

/**
 * The group chat's publish-avatar screen, reached from
 * [ConferenceDetailsActivity]. It drew `activity_publish_profile_picture.xml` too, the same tree
 * [PublishProfilePictureActivity] did; that shared layout is now deleted and this screen hangs the
 * same [PublishProfilePictureScreen] from the chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]). `setSupportActionBar`, `configureActionBar` and
 * `Activities.setStatusAndNavigationBarColors` went with the bar.
 *
 * <p>**Its two differences are the state's.** The old layout's `contact_only` and `secondary_hint`
 * were both set to `View.GONE` here - a group chat has no contacts-only choice and no default
 * picture to long-press back to - so [PublishProfilePictureScreenState.contactOnlyVisible] is false
 * and [PublishProfilePictureScreenState.secondaryHint] is
 * [SecondaryHintVisibility.GONE]. It had no overflow menu, where the account host's one-item menu
 * is the chrome's overflow.
 *
 * <p>**The avatar stays a view.** The picture is a `Drawable` from the avatar service or the file
 * backend, and there is no Compose painter for an arbitrary drawable here, so the picture is an
 * `ImageView` hosted through `AndroidView` - the screen's slot, exactly as the account host and the
 * add-reaction and viewer screens slot their platform views. `cropUri`'s animated-image check reads
 * [avatarDrawable] rather than the view's own drawable, because the view is the composition's now.
 *
 * <p>Everything else is the old code with `binding` replaced by [state]: the crop launcher, the
 * `uuid` intent extra, the publication callbacks and the publish/cancel branches are unchanged.
 */
class PublishGroupChatProfilePictureActivity : XmppActivity(), OnAvatarPublication {

    private val cropImageLauncher: ActivityResultLauncher<CropImageContractOptions> =
        registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                this.uri = result.uriContent
                if (xmppConnectionServiceBound) {
                    reloadAvatar()
                }
            } else {
                val error = result.error
                if (error != null) {
                    Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val pendingConversationUuid = PendingItem<String>()

    /** Everything the deleted layout drew, read by [PublishProfilePictureScreen]. */
    private var state by mutableStateOf(
        PublishProfilePictureScreenState(
            secondaryHint = SecondaryHintVisibility.GONE,
            contactOnlyVisible = false,
        ),
    )

    /** The drawable the avatar view shows; the field `cropUri` inspects and the view is given. */
    private var avatarDrawable by mutableStateOf<Drawable?>(null)

    private var conversation: Conversation? = null
    private var uri: Uri? = null

    override fun refreshUiReal() {}

    override fun onBackendConnected() {
        val uuid = pendingConversationUuid.pop()
        if (uuid != null) {
            this.conversation = xmppConnectionService.findConversationByUuid(uuid) as Conversation?
        }
        if (this.conversation == null) {
            return
        }
        reloadAvatar()
    }

    private fun reloadAvatar() {
        if (xmppConnectionService == null) return // We will get called again in onBackendConnected
        val size = resources.getDimension(R.dimen.publish_avatar_size).toInt()
        val bitmap: Drawable?
        if (uri == null) {
            // The Java's parameter was an unannotated `ConversationRef` - a platform type - so this
            // nullable field was passed and the cache read it (`conversation.getMode()`); a null was
            // the NPE the Java threw. The port is strict now, so the null stays the caller's.
            bitmap =
                xmppConnectionService
                    .getAvatarService()
                    .getConversationAvatar(
                        conversation ?: throw NullPointerException(),
                        size,
                    )
        } else {
            Log.d(Config.LOGTAG, "loading " + uri + " into preview")
            val currentUri = uri ?: throw NullPointerException()
            bitmap = FileBackends.get().cropCenterSquareDrawable(currentUri, size)
        }
        this.avatarDrawable = bitmap
        state = state.copy(publishEnabled = uri != null)
        if (Build.VERSION.SDK_INT >= 28 && bitmap is AnimatedImageDrawable) {
            bitmap.start()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.group_chat_avatar),
                // `configureActionBar(supportActionBar)` switched the arrow on, and the bar's
                // `android.R.id.home` ran `finish()`.
                onUp = { finish() },
            ) {
                PublishProfilePictureScreen(
                    state = state,
                    avatar = {
                        AndroidView(
                            factory = { ImageView(it) },
                            update = { view ->
                                view.setImageDrawable(avatarDrawable)
                                view.setOnClickListener {
                                    PublishProfilePictureActivity.chooseAvatar(
                                        this,
                                        cropImageLauncher,
                                    )
                                }
                            },
                            modifier =
                                Modifier.size(
                                    dimensionResource(R.dimen.publish_avatar_size),
                                ),
                        )
                    },
                    onContactOnlyChange = {},
                    onCancel = { finish() },
                    onPublish = { publish() },
                )
            }
        }

        val uuid = intent?.getStringExtra("uuid")
        if (uuid != null) {
            pendingConversationUuid.push(uuid)
        }
    }

    private fun publish() {
        state = state.copy(publishLabelRes = R.string.publishing, publishEnabled = false)
        xmppConnectionService.publishMucAvatar(
            conversation ?: throw NullPointerException(),
            uri ?: throw NullPointerException(),
            this,
        )
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PublishProfilePictureActivity.REQUEST_CHOOSE_PICTURE) {
            if (resultCode == RESULT_OK) {
                // The Java dereferenced both nullables; both are the NPE it would have thrown.
                cropUri((data ?: throw NullPointerException()).data ?: throw NullPointerException())
            }
        }
    }

    public fun cropUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 28) {
            this.uri = uri
            reloadAvatar()
            val drawable = this.avatarDrawable
            if (drawable is AnimatedImageDrawable || drawable is FileBackend.SVGDrawable) {
                return
            }
        }

        val options = CropImageOptions()
        options.outputCompressFormat = Bitmap.CompressFormat.PNG
        options.aspectRatioX = 1
        options.aspectRatioY = 1
        options.fixAspectRatio = true
        options.minCropResultWidth = Config.AVATAR_SIZE
        options.minCropResultHeight = Config.AVATAR_SIZE
        cropImageLauncher.launch(CropImageContractOptions(uri, options))
    }

    public override fun onAvatarPublicationSucceeded() {
        runOnUiThread {
            Toast.makeText(this, R.string.avatar_has_been_published, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    public override fun onAvatarPublicationFailed(@StringRes res: Int) {
        runOnUiThread {
            Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
            state = state.copy(publishLabelRes = R.string.publish, publishEnabled = true)
        }
    }
}
