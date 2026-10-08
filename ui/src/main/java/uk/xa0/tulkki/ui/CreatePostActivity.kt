package uk.xa0.tulkki.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import java.util.ArrayList
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.posts.CreatePostEvents
import uk.xa0.tulkki.ui.posts.CreatePostHost
import uk.xa0.tulkki.ui.posts.CreatePostScreen
import uk.xa0.tulkki.ui.posts.CreatePostState
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The composer: a new post, an edit of one, or the answer the feed's comment button opens. It owns
 * every launcher, permission, validation and intent it always did; [CreatePostScreen] draws and
 * names no string of its own.
 *
 * <p>**The layout is gone.** `ui/src/main/res/layout/activity_create_post.xml` is deleted; the bar
 * is the shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]), the three `TextInputLayout`s are
 * three fields in the screen, and `setSupportActionBar`/`configureActionBar`/
 * `Activities.setStatusAndNavigationBarColors` went with the bar. The Activity's
 * `onCreateOptionsMenu` was already empty and its `onOptionsItemSelected` handled only the home
 * item the bar drew, so both are gone with the bar.
 *
 * <p>**The publish path is unchanged.** `publishPost` still validates the same four emptinesses,
 * still picks the account the intent named or the persisted spinner position, still refuses an
 * offline account, and still takes the same three branches (a reply, an attachment - uploading it
 * first unless it is already an http url - or a plain post). What moved is the button: it is
 * disabled and re-enabled through [CreatePostState], like the view's own `setEnabled`.
 */
class CreatePostActivity : XmppActivity() {

    private var inReplyToId: String? = null
    private var inReplyToNode: String? = null
    private var postId: String? = null
    private var attachmentUri: Uri? = null
    private var attachmentType: String? = null
    private var mCameraUri: Uri? = null
    private var accountUuid: String? = null
    private var postTitle: String? = null
    private var postContent: String? = null

    private var initialTitle = ""
    private var initialContent = ""
    private var initialLink = ""
    private var publishEnabled = true

    private val onlineAccounts: MutableList<Account> = ArrayList()

    private val session = CreatePostHost.Session()
    private val events: CreatePostEvents = Form()

    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                openCamera()
            } else {
                Toast.makeText(
                    this,
                    R.string.no_camera_permission,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    private val takePictureLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                attachmentUri = mCameraUri
                attachmentType = "image/jpeg"
                render()
            }
        }

    private val takeVideoLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                attachmentUri = mCameraUri
                attachmentType = "video/mp4"
                render()
            }
        }

    private val attachFileLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                this.attachmentUri = uri
                val type = contentResolver.getType(uri)
                this.attachmentType = type
                render()
            }
        }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        // The XML window kept `SOFT_INPUT_ADJUST_RESIZE`; the screen's own `imePadding` is the
        // Compose half of it, and the window flag still decides what the keyboard does to it.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val intent = getIntent() ?: throw NullPointerException()
        inReplyToId = intent.getStringExtra("in_reply_to_id")
        inReplyToNode = intent.getStringExtra("in_reply_to_node")
        postId = intent.getStringExtra("post_id")
        postTitle = intent.getStringExtra("post_title")
        postContent = intent.getStringExtra("post_content")
        accountUuid = intent.getStringExtra("account")
        if (postId != null) {
            initialTitle = intent.getStringExtra("title") ?: ""
            initialContent = intent.getStringExtra("content") ?: ""
            initialLink = intent.getStringExtra("link_url") ?: ""
            val attachmentUrl = intent.getStringExtra("attachment_url")
            this.attachmentType = intent.getStringExtra("attachment_type")
            val currentType = this.attachmentType
            if (attachmentUrl != null && currentType != null) {
                this.attachmentUri = Uri.parse(attachmentUrl)
            }
        }

        // Before the first composition: the screen's fields take their values once, from
        // `rememberSaveable`, so the session must already hold the intent's own when it starts.
        render()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // `setTitle(R.string.add_a_comment)` when answering, the manifest's label otherwise.
                title =
                    if (inReplyToNode != null) {
                        stringResource(R.string.add_a_comment)
                    } else {
                        stringResource(R.string.create_post)
                    },
                // The arrow `configureActionBar` and `setDisplayHomeAsUpEnabled(true)` switched on.
                onUp = { finish() },
            ) {
                CreatePostScreen(state = session.state, events = events)
            }
        }
    }

    private fun openCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(
                this,
                uk.xa0.tulkki.xmpp.R.string.not_connected_try_again,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        mCameraUri = FileBackends.get().getTakePhotoUri()
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri)
        takePictureLauncher.launch(takePictureIntent)
    }

    private fun openVideoCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(
                this,
                uk.xa0.tulkki.xmpp.R.string.not_connected_try_again,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        mCameraUri = FileBackends.get().getTakeVideoUri()
        val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri)
        takeVideoLauncher.launch(takeVideoIntent)
    }

    protected override fun refreshUiReal() {}

    public override fun onBackendConnected() {
        if (xmppConnectionService != null && accountUuid == null) {
            onlineAccounts.clear()
            for (account in AccountRegistry.get().getAccounts()) {
                if (account.isOnlineAndConnected()) {
                    onlineAccounts.add(account)
                }
            }
            render()
        }
    }

    /** What the composer draws now: the intent's values and the accounts connected so far. */
    private fun render() {
        session.update(
            CreatePostState(
                replyMode = inReplyToNode != null,
                // The XML hid the spinner only for an edit that already named its account.
                accountVisible = postId == null || accountUuid == null,
                accounts = ArrayList(onlineAccounts),
                selectedAccount = getPersistedItem(),
                initialTitle = initialTitle,
                initialContent = initialContent,
                initialLink = initialLink,
                previewTitle = postTitle,
                previewContent = postContent,
                attachmentUri = attachmentUri,
                attachmentType = attachmentType,
                publishEnabled = publishEnabled,
            ),
        )
    }

    private fun publishPost(title: String, content: String, linkUrl: String) {
        if (title.isEmpty() && content.isEmpty() && attachmentUri == null && linkUrl.isEmpty()) {
            Toast.makeText(
                this,
                R.string.title_or_content_or_attachment_required,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (xmppConnectionService != null) {
            val selectedAccount: Account
            val uuid = accountUuid
            if (uuid != null) {
                val found = AccountRegistry.get().findAccountByUuid(uuid)
                if (found == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.account_not_found),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                selectedAccount = found
            } else {
                val position = getPersistedItem()
                if (position < 0 || position >= onlineAccounts.size) {
                    Toast.makeText(
                        this,
                        uk.xa0.tulkki.xmpp.R.string.no_active_account,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                selectedAccount = onlineAccounts[position]
            }

            if (!selectedAccount.isOnlineAndConnected()) {
                Toast.makeText(this, R.string.account_not_connected, Toast.LENGTH_SHORT).show()
                return
            }

            publishEnabled = false
            render()

            val currentAttachment = attachmentUri
            if (inReplyToNode != null) {
                xmppConnectionService.publishComment(
                    selectedAccount,
                    inReplyToNode,
                    content,
                    object : XmppConnectionService.OnPostPublished {
                        override fun onPostPublished() {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CreatePostActivity,
                                    R.string.comment_published,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        }

                        override fun onPostPublishFailed() {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CreatePostActivity,
                                    R.string.error_publish_comment,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                publishEnabled = true
                                render()
                            }
                        }
                    },
                )
            } else if (currentAttachment != null) {
                val mimeType =
                    this.attachmentType ?: contentResolver.getType(currentAttachment)
                val scheme = currentAttachment.getScheme()
                if (scheme != null && (scheme == "http" || scheme == "https")) {
                    publish(
                        selectedAccount,
                        title,
                        content,
                        currentAttachment.toString(),
                        mimeType,
                        linkUrl,
                    )
                } else {
                    Toast.makeText(this, R.string.uploading_attachment, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                    xmppConnectionService.uploadFileForUrl(
                        selectedAccount,
                        currentAttachment,
                        mimeType,
                        object : UiCallback<String> {
                            override fun success(obj: String) {
                                publish(selectedAccount, title, content, obj, mimeType, linkUrl)
                            }

                            override fun error(errorCode: Int, obj: String?) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@CreatePostActivity,
                                        errorCode,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    publishEnabled = true
                                    render()
                                }
                            }

                            override fun userInputRequired(pi: PendingIntent?, obj: String) {
                                runOnUiThread {
                                    publishEnabled = true
                                    render()
                                }
                            }
                        },
                    )
                }
            } else {
                publish(selectedAccount, title, content, null, null, linkUrl)
            }
        }
    }

    private fun publish(
        account: Account,
        title: String,
        content: String,
        attachmentUrl: String?,
        attachmentType: String?,
        linkUrl: String?,
    ) {
        xmppConnectionService.publishPost(
            account,
            title,
            content,
            attachmentUrl,
            attachmentType,
            postId,
            linkUrl,
            object : XmppConnectionService.OnPostPublished {
                override fun onPostPublished() {
                    runOnUiThread {
                        Toast.makeText(
                            this@CreatePostActivity,
                            R.string.post_published,
                            Toast.LENGTH_SHORT,
                        ).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }

                override fun onPostPublishFailed() {
                    runOnUiThread {
                        Toast.makeText(
                            this@CreatePostActivity,
                            R.string.error_publish_post,
                            Toast.LENGTH_SHORT,
                        ).show()
                        publishEnabled = true
                        render()
                    }
                }
            },
        )
    }

    private fun getPersistedItem(): Int {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(makePersistedItemKeyName(), 0)
    }

    protected fun setPersistedItem(position: Int) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putInt(makePersistedItemKeyName(), position)
            .apply()
    }

    private fun makePersistedItemKeyName(): String {
        return "create_post_selected_account_position"
    }

    /** The form's six gestures, each one the listener the XML view had. */
    private inner class Form : CreatePostEvents {

        override fun onAccountSelected(position: Int) {
            setPersistedItem(position)
            render()
        }

        override fun onAttachFile() {
            attachFileLauncher.launch("*/*")
        }

        override fun onAttachImage() {
            if (ContextCompat.checkSelfPermission(
                    this@CreatePostActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        override fun onAttachVideo() {
            if (ContextCompat.checkSelfPermission(
                    this@CreatePostActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openVideoCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        override fun onPublish(title: String, content: String, linkUrl: String) {
            publishPost(title, content, linkUrl)
        }
    }
}
