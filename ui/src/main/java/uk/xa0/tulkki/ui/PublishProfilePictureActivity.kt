package uk.xa0.tulkki.ui

import android.app.Activity
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnAvatarPublication
import uk.xa0.tulkki.xmpp.Config

/**
 * The publish-avatar screen: the account's picture, a tap to choose a new one and a long press to go
 * back to the default, the contacts-only switch, and the publish button.
 *
 * <p>**The layout is gone from this screen's side.** `activity_publish_profile_picture.xml` held a
 * toolbar, the avatar frame with its `ImageView`, the two hints, the `MaterialSwitch`, the warning
 * line and the cancel/publish bar; this screen composes the same tree and
 * `setSupportActionBar`/`Activities.setStatusAndNavigationBarColors` went with the bar, so the
 * chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) draws it now. The menu
 * (`menu/activity_publish_profile_picture.xml`, one "Delete avatar" item) is the chrome's overflow.
 *
 * <p>**Both screens that inflated it are Compose now.** `PublishGroupChatProfilePictureActivity`,
 * reached from `ConferenceDetailsActivity`, drew the group chat's picture from the same layout; it
 * hangs the same [PublishProfilePictureScreen] from the chrome with the state's group shape - the
 * contacts-only row and the long-press hint are `View.GONE` there, where this screen blanks the hint
 * in place - so `activity_publish_profile_picture.xml` and its one-item menu are deleted and no
 * screen inflates either.
 *
 * <p>**The avatar stays a view.** The picture is a `Drawable` from the avatar service or the file
 * backend, and there is no Compose painter for an arbitrary drawable here, so [avatar] is an
 * `ImageView` hosted through `AndroidView` - the screen's slot, exactly as the add-reaction and
 * viewer screens slot their platform views. `cropUri`'s animated-image check reads the drawable the
 * Activity last set rather than the view's, because the view is the composition's now.
 *
 * <p>Everything else is the old code with `binding` replaced by [state]; the publication callbacks,
 * the crop launcher, the save/restore handshake and the two intent flags are unchanged.
 */
class PublishProfilePictureActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    OnAvatarPublication {

    private val cropImageLauncher: ActivityResultLauncher<CropImageContractOptions> =
        registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                this.avatarUri = result.uriContent
                if (xmppConnectionServiceBound) {
                    loadImageIntoPreview(this.avatarUri)
                }
            } else {
                val error = result.error
                if (error != null) {
                    Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** Everything the deleted layout drew, read by [PublishProfilePictureScreen]. */
    private var state by mutableStateOf(PublishProfilePictureScreenState())

    /** The drawable the avatar view shows; the field `cropUri` inspects and the view is given. */
    private var avatarDrawable by mutableStateOf<Drawable?>(null)

    /**
     * The up arrow: `configureActionBar(bar, !mInitialAccountSetup && !handledExternalUri)`. It
     * starts down because the layout's toolbar carried no navigation icon of its own and `onStart`'s
     * external-URI branch returns before the call that switched it on - so that path has no arrow.
     */
    private var upVisible by mutableStateOf(false)

    private var avatarUri: Uri? = null
    private var defaultUri: Uri? = null
    private var account: Account? = null
    private var support = false
    private var publishing = false
    private val handledExternalUri = AtomicBoolean(false)
    private val backToDefaultListener =
        android.view.View.OnLongClickListener {
            avatarUri = defaultUri
            loadImageIntoPreview(defaultUri)
            true
        }
    private var mInitialAccountSetup = false

    public override fun onAvatarPublicationSucceeded() {
        runOnUiThread {
            if (mInitialAccountSetup) {
                val intent =
                    Intent(getApplicationContext(), StartConversationActivity::class.java)
                StartConversationActivity.addInviteUri(intent, getIntent())
                intent.putExtra("init", true)
                intent.putExtra(
                    XmppActivity.EXTRA_ACCOUNT,
                    (account ?: throw NullPointerException()).getJid().asBareJid().toString(),
                )
                startActivity(intent)
            }
            Toast.makeText(
                this,
                R.string.avatar_has_been_published,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    public override fun onAvatarPublicationFailed(@StringRes res: Int) {
        runOnUiThread {
            state = state.copy(warningRes = res)
            this.publishing = false
            togglePublishButton(true, R.string.publish)
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
                title = stringResource(R.string.mgmt_account_publish_avatar),
                onUp = if (upVisible) ({ finish() }) else null,
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.delete_avatar)) {
                            val current = account
                            if (current != null) {
                                deleteAvatar(current)
                            }
                        },
                    ),
            ) {
                PublishProfilePictureScreen(
                    state = state,
                    avatar = {
                        AndroidView(
                            factory = { ImageView(it) },
                            update = { view ->
                                view.setImageDrawable(avatarDrawable)
                                view.setOnClickListener {
                                    chooseAvatar(this, cropImageLauncher)
                                }
                                view.setOnLongClickListener(
                                    if (state.secondaryHint == SecondaryHintVisibility.SHOWN) {
                                        backToDefaultListener
                                    } else {
                                        null
                                    },
                                )
                            },
                            modifier =
                                Modifier.size(
                                    dimensionResource(R.dimen.publish_avatar_size),
                                ),
                        )
                    },
                    onContactOnlyChange = { checked ->
                        state = state.copy(contactOnlyChecked = checked)
                    },
                    onCancel = { onCancelPressed() },
                    onPublish = { publish() },
                )
            }
        }

        this.defaultUri = UiHost.installed().profilePictureUri(getApplicationContext())
        if (savedInstanceState != null) {
            this.avatarUri = savedInstanceState.getParcelable("uri")
            this.handledExternalUri.set(
                savedInstanceState.getBoolean("handle_external_uri", false),
            )
        }
    }

    /** The publish button's branch, `publishButton.setOnClickListener` unchanged. */
    private fun publish() {
        val open = !state.contactOnlyChecked
        val uri = this.avatarUri
        if (uri == null) {
            return
        }
        publishing = true
        togglePublishButton(false, R.string.publishing)
        xmppConnectionService.publishAvatarAsync(
            account ?: throw NullPointerException(),
            uri,
            open,
            this,
        )
    }

    /** The cancel button's branch, `cancelButton.setOnClickListener` unchanged. */
    private fun onCancelPressed() {
        if (mInitialAccountSetup) {
            val intent =
                Intent(getApplicationContext(), StartConversationActivity::class.java)
            intent.putExtra("init", true)
            StartConversationActivity.addInviteUri(intent, getIntent())
            if (account != null) {
                intent.putExtra(
                    XmppActivity.EXTRA_ACCOUNT,
                    (account ?: throw NullPointerException()).getJid().asBareJid().toString(),
                )
            }
            startActivity(intent)
        }
        finish()
    }

    private fun deleteAvatar(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_avatar)
            .setMessage(R.string.delete_avatar_message)
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (xmppConnectionService != null) {
                    xmppConnectionService.deleteAvatar(account)
                }
            }
            .create()
            .show()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        if (this.avatarUri != null) {
            outState.putParcelable("uri", this.avatarUri)
        }
        outState.putBoolean("handle_external_uri", handledExternalUri.get())
        super.onSaveInstanceState(outState)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHOOSE_PICTURE) {
            if (resultCode == RESULT_OK) {
                cropUri(this, (data ?: throw NullPointerException()).getData())
            }
        }
    }

    protected override fun onBackendConnected() {
        this.account = extractAccount(getIntent())
        if (this.account != null) {
            reloadAvatar()
        }
    }

    private fun reloadAvatar() {
        val account = this.account ?: throw NullPointerException()
        val connection = account.getXmppConnection()
        this.support = connection != null && connection.getFeatures().pep()
        if (this.avatarUri == null) {
            if (account.getAvatar() != null || this.defaultUri == null) {
                loadImageIntoPreview(null)
            } else {
                this.avatarUri = this.defaultUri
                loadImageIntoPreview(this.defaultUri)
            }
        } else {
            loadImageIntoPreview(avatarUri)
        }
    }

    public override fun onStart() {
        super.onStart()
        val intent = getIntent()
        this.mInitialAccountSetup = intent != null && intent.getBooleanExtra("setup", false)

        val uri = if (intent != null) intent.getData() else null

        if (uri != null && handledExternalUri.compareAndSet(false, true)) {
            cropUri(this, uri)
            return
        }

        if (this.mInitialAccountSetup) {
            state = state.copy(cancelLabelRes = R.string.skip)
        }
        upVisible = !this.mInitialAccountSetup && !handledExternalUri.get()
    }

    public fun cropUri(activity: Activity, uri: Uri?) {
        if (Build.VERSION.SDK_INT >= 28) {
            loadImageIntoPreview(uri)
            val drawable = avatarDrawable
            if (drawable is AnimatedImageDrawable || drawable is FileBackend.SVGDrawable) {
                this.avatarUri = uri
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

    protected fun loadImageIntoPreview(uri: Uri?) {
        var bm: Drawable? = null
        if (uri == null) {
            bm =
                avatarService()
                    .getAccountAvatar(
                        // The Java's parameter was an unannotated `AccountRef` - a platform type - so
                        // this nullable field was passed and the cache read it (`account.getUuid()`);
                        // a null was the NPE the Java threw.
                        account ?: throw NullPointerException(),
                        resources.getDimension(R.dimen.publish_avatar_size).toInt(),
                    )
        } else {
            try {
                bm =
                    FileBackends.get()
                        .cropCenterSquareDrawable(
                            uri,
                            resources.getDimension(R.dimen.publish_avatar_size).toInt(),
                        )
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "unable to load bitmap into image view", e)
            }
        }

        if (bm == null) {
            togglePublishButton(false, R.string.publish)
            state = state.copy(warningRes = uk.xa0.tulkki.xmpp.R.string.error_publish_avatar_converting)
            return
        }
        this.avatarDrawable = bm
        if (Build.VERSION.SDK_INT >= 28 && bm is AnimatedImageDrawable) {
            bm.start()
        }
        if (support) {
            togglePublishButton(uri != null, R.string.publish)
            state = state.copy(warningRes = null)
        } else {
            togglePublishButton(false, R.string.publish)
            // The Java read `account.getStatus()` here; the `?.` the Kotlin port wrote was a
            // tolerance the Java never had, so the dereference comes back with it.
            if ((account ?: throw NullPointerException()).getStatus() == Account.State.ONLINE) {
                state =
                    state.copy(
                        warningRes = uk.xa0.tulkki.xmpp.R.string.error_publish_avatar_no_server_support,
                    )
            } else {
                state = state.copy(warningRes = R.string.error_publish_avatar_offline)
            }
        }
        state =
            state.copy(
                secondaryHint =
                    if (this.defaultUri == null || this.defaultUri == uri) {
                        SecondaryHintVisibility.BLANKED
                    } else {
                        SecondaryHintVisibility.SHOWN
                    },
            )
    }

    protected fun togglePublishButton(enabled: Boolean, @StringRes res: Int) {
        val status = enabled && !publishing
        state =
            state.copy(
                publishLabelRes = if (publishing) R.string.publishing else res,
                publishEnabled = status,
            )
    }

    public override fun refreshUiReal() {
        if (this.account != null) {
            reloadAvatar()
        }
    }

    public override fun onAccountUpdate() {
        refreshUi()
    }

    companion object {
        const val REQUEST_CHOOSE_PICTURE = 0x1337

        @JvmStatic
        fun chooseAvatar(
            activity: Activity,
            launcher: ActivityResultLauncher<CropImageContractOptions>,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "image/*"
                activity.startActivityForResult(
                    Intent.createChooser(
                        intent,
                        activity.getString(R.string.attach_choose_picture),
                    ),
                    REQUEST_CHOOSE_PICTURE,
                )
            } else {
                val options = CropImageOptions()
                options.outputCompressFormat = Bitmap.CompressFormat.PNG
                options.aspectRatioX = 1
                options.aspectRatioY = 1
                options.fixAspectRatio = true
                options.minCropResultWidth = Config.AVATAR_SIZE
                options.minCropResultHeight = Config.AVATAR_SIZE
                launcher.launch(CropImageContractOptions(null, options))
            }
        }
    }
}

/**
 * What [PublishProfilePictureScreen] draws: the publish button's label and enabled state, the
 * warning line, whether the long-press hint is shown, the cancel label and the contacts-only switch.
 *
 * <p>Each value is a view state the deleted layout held - `togglePublishButton`'s two writes,
 * the `hint_or_warning` text and visibility, `secondary_hint`'s visibility, the cancel button's
 * `setText`, and `contact_only`'s `isChecked` - with the layout's own defaults. The two hosts
 * differ in two of them: `PublishGroupChatProfilePictureActivity` puts the long-press hint and the
 * contacts-only row at `View.GONE`, so the defaults here describe
 * [PublishProfilePictureActivity]'s shape.
 */
data class PublishProfilePictureScreenState(
    val publishEnabled: Boolean = false,
    @StringRes val publishLabelRes: Int = R.string.publish,
    @StringRes val warningRes: Int? = null,
    /**
     * The long-press hint: drawn, blanked in place (`View.INVISIBLE`) or collapsed (`View.GONE`).
     */
    val secondaryHint: SecondaryHintVisibility = SecondaryHintVisibility.SHOWN,
    @StringRes val cancelLabelRes: Int = uk.xa0.tulkki.data.R.string.cancel,
    val contactOnlyChecked: Boolean = false,
    /** The contacts-only row, which the group-chat host never draws (`View.GONE`). */
    val contactOnlyVisible: Boolean = true,
)

/** The three states the deleted layout's `secondary_hint` view was put in, one per host. */
enum class SecondaryHintVisibility {
    /** Drawn: the picture is not the account's default one. */
    SHOWN,

    /**
     * `View.INVISIBLE`: the line keeps its space, as the account screen's `loadImageIntoPreview`
     * did.
     */
    BLANKED,

    /** `View.GONE`: the line and its space are gone, as the group-chat screen left it. */
    GONE,
}

/**
 * The publish-avatar body: the framed picture, the two hints, the contacts-only switch and the
 * warning, over the cancel/publish bar. The group-chat host draws the same body without the switch
 * and without the long-press hint's line, so those two are the state's business and everything else
 * is shared.
 *
 * <p>Every size and colour is the deleted layout's: the `activity_*_margin` outer margins, the
 * `publish_avatar_top_margin` above the frame and its 8 dp under it, the frame's
 * `background_account_profile_picture` (a 4 dp corner in `?colorOutline` with a 1.5 dp padding), the
 * `publish_avatar_size` square inside it, the switch's 12 dp vertical margin, the warning in
 * `?colorError`, and the bar's 16 dp by 8 dp with the cancel a `Widget.Material3.Button.TextButton`
 * and the default publish button. A blanked `secondary_hint` and the warning keep their space while
 * hidden, as `View.INVISIBLE` did.
 *
 * <p>The picture is a slot because an arbitrary `Drawable` has no Compose painter here; the
 * screenshot cells pass a placeholder-free empty frame, as the viewer and add-reaction cells do.
 */
@Composable
fun PublishProfilePictureScreen(
    state: PublishProfilePictureScreenState,
    avatar: @Composable () -> Unit,
    onContactOnlyChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                        vertical = dimensionResource(R.dimen.activity_vertical_margin),
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier.padding(
                            top = dimensionResource(R.dimen.publish_avatar_top_margin),
                            bottom = 8.dp,
                        )
                        .background(
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(1.5.dp),
            ) {
                avatar()
            }
            Text(
                text = stringResource(R.string.touch_to_choose_picture),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.secondaryHint != SecondaryHintVisibility.GONE) {
                Text(
                    text = stringResource(R.string.or_long_press_for_default),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier.alpha(
                            if (state.secondaryHint == SecondaryHintVisibility.SHOWN) 1f else 0f,
                        ),
                )
            }
            if (state.contactOnlyVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.padding(vertical = 12.dp)
                            .toggleable(
                                value = state.contactOnlyChecked,
                                role = Role.Switch,
                                onValueChange = onContactOnlyChange,
                            ),
                ) {
                    Switch(
                        checked = state.contactOnlyChecked,
                        onCheckedChange = null,
                    )
                    Text(text = stringResource(R.string.show_to_contacts_only))
                }
            }
            val warning = state.warningRes
            Text(
                text = if (warning == null) "" else stringResource(warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.padding(vertical = 12.dp)
                        .alpha(if (warning == null) 0f else 1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(state.cancelLabelRes)) }
            Button(onClick = onPublish, enabled = state.publishEnabled) {
                Text(stringResource(state.publishLabelRes))
            }
        }
    }
}
