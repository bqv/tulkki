package uk.xa0.tulkki.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicReference
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.imageeditor.ImageEditorActivity
import uk.xa0.tulkki.ui.stories.CreateStoryDialog
import uk.xa0.tulkki.ui.stories.CreateStoryRequest
import uk.xa0.tulkki.ui.stories.NavBadges
import uk.xa0.tulkki.ui.stories.NavIcons
import uk.xa0.tulkki.ui.stories.NavTab
import uk.xa0.tulkki.ui.stories.StoriesScreen
import uk.xa0.tulkki.ui.stories.StoriesScreenState
import uk.xa0.tulkki.ui.stories.StoryRow
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.xmpp.Config

/**
 * The stories list: each contact's newest story, the add button, and the tab bar.
 *
 * <p>**The layout is gone.** `activity_stories.xml` held the toolbar, the `stories_list`, the
 * `placeholder`, the `fab_add_story` and the `BottomNavigationView` bound to
 * `bottom_navigation_menu_stories.xml`, and all three files are deleted. The bar is the shared chrome
 * ([TulkkiChrome]) now, the body is [StoriesScreen] and the deleted `RecyclerView`'s adapter
 * (`adapter/StoryAdapter.kt`, which inflated `list_item_story.xml`) is the host half below:
 * `setSupportActionBar`, `configureActionBar`, `setTitle`,
 * `Activities.setStatusAndNavigationBarColors`, the `activity_stories` menu and the bottom-navigation
 * listener went with the views they belonged to.
 *
 * <p>**What the adapter did, and why it is here.** `StoryAdapter` resolved each row's best contact,
 * its display name and its conversation on every bind, and loaded the row's avatar and preview into
 * views. Neither can be a Composable read - the avatar service and the image library are the host's -
 * so this class does the first half in [rebuildRows] and the two loads off the main thread
 * ([warmAvatars], [loadPreviews]), and hands [StoriesScreen] plain rows. The scoring rule is the
 * deleted `onBindViewHolder`'s, unchanged: online counts two, a real (non-`TextAvatar`) avatar counts
 * one, the first best wins.
 *
 * <p>The one behaviour that belonged to the adapter's own `Handler` survives as [tick]: the rows'
 * relative times are re-worded once a minute while the screen is started.
 */
class StoriesActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnStoriesUpdate {

    private var mSelectedAccount: Account? = null
    private val pendingTakePhotoUri = PendingItem<Uri>()

    // Tulkki: 3.7 pair 9, part 15 - the island's story ref, written fully qualified with no import;
    // the four stream reads below only ask for `getPublished()`/`getContact()`, both on the ref.
    private val stories: MutableList<uk.xa0.tulkki.libs.StoryRef> = ArrayList()

    /** The rows before the avatar, the preview and the relative time are attached. */
    private var baseRows: List<BaseRow> = emptyList()

    /** The previews resolved so far, keyed by the story's url. */
    private var previews: Map<String, Bitmap> = emptyMap()

    private var screen by mutableStateOf(StoriesScreenState())
    private var createStory by mutableStateOf<CreateStoryRequest?>(null)

    private val ticker = Handler(Looper.getMainLooper())
    private val tick =
        object : Runnable {
            override fun run() {
                publish()
                ticker.postDelayed(this, TIME_TICK_MS)
            }
        }

    /** One row's resolved facts, before its images land. */
    private data class BaseRow(
        val jid: Jid,
        val accountUuid: String?,
        val avatarKey: String?,
        val title: String,
        val url: String?,
        val published: Long,
    )

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.stories),
                // `configureActionBar` switched the arrow on, and `refreshUiReal` switched it off
                // again while the tab bar was showing; `finish()` is what the arrow ran.
                onUp = if (screen.showNavBar) null else ({ finish() }),
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.action_settings)) {
                            startActivity(
                                Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)
                            )
                        }
                    ),
            ) {
                StoriesScreen(
                    state = screen,
                    avatarShape = conversationAvatars().shape(),
                    icons = navIcons(),
                    onTab = { navigate(it) },
                    onStory = { openViewer(it) },
                    onAddStory = { selectAccountToPublishStory() },
                )
            }
            val request = createStory
            if (request != null) {
                CreateStoryDialog(
                    request = request,
                    image = { uri -> storyPreviewImage(uri) },
                    video = { uri -> storyPreviewVideo(uri) },
                    onPublish = { title -> publishStory(request, title) },
                    onDismiss = { createStory = null },
                )
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        screen =
            screen.copy(
                showNavBar =
                    getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) &&
                        intent.getBooleanExtra("show_nav_bar", false)
            )
        val service = xmppConnectionService
        if (service != null) {
            service.setOnStoriesUpdateListener(this)
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putLong("last_read_story_timestamp", System.currentTimeMillis())
                .apply()
            refresh()
        }
        ticker.postDelayed(tick, TIME_TICK_MS)
    }

    protected override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tick)
        xmppConnectionService?.removeOnStoriesUpdateListener(this)
    }

    protected override fun onBackendConnected() {
        val service = xmppConnectionService
        if (service != null) {
            service.setOnStoriesUpdateListener(this)
            refresh()
        }
        refreshUiReal()
    }

    public override fun onBackPressed() {
        if (screen.showNavBar) {
            val intent = Intent(this, ConversationListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
        }

        super.onBackPressed()
    }

    /** The four tabs' destinations, exactly the deleted `OnItemSelectedListener`'s. */
    private fun navigate(tab: NavTab) {
        when (tab) {
            NavTab.CHATS -> {
                startActivity(Intent(applicationContext, ConversationListActivity::class.java))
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.FEEDS -> {
                val i = Intent(applicationContext, PostsActivity::class.java)
                i.putExtra("show_nav_bar", true)
                startActivity(i)
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.CALLS -> {
                val callsIntent = Intent(applicationContext, CallsActivity::class.java)
                callsIntent.putExtra("show_nav_bar", true)
                startActivity(callsIntent)
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.STORIES -> Unit
        }
    }

    /**
     * The tab bar's icons, resolved from the eight theme attributes the deleted
     * `bottom_navigation_menu_stories.xml` named - `?attr/ic_chat_unselected` and its siblings, whose
     * `values-night/themes.xml` pair the white set. `StoriesScreen` takes them already resolved
     * because a theme attribute belongs to this window's theme, which is where `Theme.Tulkki` is
     * applied.
     */
    private fun navIcons(): NavIcons =
        NavIcons(
            chatsUnselected =
                themeDrawable(R.attr.ic_chat_unselected, R.drawable.outline_chat_black_24),
            chatsSelected = themeDrawable(R.attr.ic_chat_selected, R.drawable.chat_selected_black_24),
            callsUnselected =
                themeDrawable(R.attr.ic_calls_unselected, R.drawable.calls_unselected_black_24dp),
            callsSelected =
                themeDrawable(R.attr.ic_calls_selected, R.drawable.calls_selected_black_24dp),
            storiesUnselected =
                themeDrawable(R.attr.ic_stories_unselected, R.drawable.stories_unselected_black_24),
            storiesSelected =
                themeDrawable(R.attr.ic_stories_selected, R.drawable.stories_selected_black_24),
            feedsUnselected =
                themeDrawable(R.attr.feed_unselected, R.drawable.feed_unselected_black_24dp),
            feedsSelected = themeDrawable(R.attr.feed_selected, R.drawable.feed_selected_black_24dp),
        )

    /** One theme attribute, as the drawable the deleted menu named, with the light value as the floor. */
    private fun themeDrawable(@AttrRes attr: Int, @DrawableRes fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.resourceId else fallback
    }

    private fun publish(uri: Uri?, mimeType: String) {
        if (uri == null || mSelectedAccount == null) {
            return
        }
        val subscriberCount =
            (
                mSelectedAccount ?: throw NullPointerException()
            ).getRoster().getContacts().count { it.getOption(Contact.Options.FROM) }
        createStory = CreateStoryRequest(uri, mimeType, subscriberCount)
    }

    private fun publishStory(request: CreateStoryRequest, title: String) {
        val account = mSelectedAccount ?: return
        // The deleted dialog's positive button dismissed before it uploaded; the whole upload is
        // unchanged.
        createStory = null
        Toast.makeText(this, R.string.uploading_story, Toast.LENGTH_SHORT).show()
        xmppConnectionService.uploadFileForUrl(
            account,
            request.uri,
            request.mimeType,
            object : UiCallback<String> {
                override fun success(url: String) {
                    xmppConnectionService.publishStory(
                        account,
                        url,
                        request.mimeType,
                        title,
                        object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<Void?> {
                            override fun success(obj: Void?) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@StoriesActivity,
                                        R.string.story_published,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }

                            override fun error(errorCode: Int, obj: Void?) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@StoriesActivity,
                                        errorCode,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }

                            override fun userInputRequired(pi: PendingIntent?, obj: Void?) {
                                // not used
                            }
                        },
                    )
                }

                override fun error(errorCode: Int, obj: String?) {
                    runOnUiThread {
                        Toast.makeText(this@StoriesActivity, errorCode, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun userInputRequired(pi: PendingIntent?, obj: String) {
                    // not used
                }
            },
        )
    }

    public override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CHOOSE_STORY_IMAGE) {
                val uri: Uri
                if (data != null && data.data != null) {
                    uri = data.data ?: throw NullPointerException()
                } else if (pendingTakePhotoUri.peek() != null) {
                    uri = pendingTakePhotoUri.pop() ?: throw NullPointerException()
                } else {
                    return
                }
                val account = mSelectedAccount
                if (account != null) {
                    val mimeType = contentResolver.getType(uri)
                    if (mimeType != null && mimeType.startsWith("video/")) {
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(this, uri)
                            val time =
                                retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                                )
                            var durationMs = 0L
                            if (time != null) {
                                durationMs = time.toLong()
                            }
                            retriever.release()
                            if (durationMs > 60000) {
                                Toast.makeText(
                                    this,
                                    R.string.video_too_long_for_story,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                publish(uri, mimeType)
                            }
                        } catch (e: Exception) {
                            Log.d(Config.LOGTAG, "Could not determine video duration", e)
                            Toast.makeText(
                                this,
                                R.string.error_retrieving_video_duration,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        val intent = Intent(this, ImageEditorActivity::class.java)
                        intent.data = uri
                        startActivityForResult(intent, REQUEST_EDIT_STORY_IMAGE)
                    }
                }
            } else if (requestCode == REQUEST_EDIT_STORY_IMAGE) {
                var uri: Uri? =
                    if (data != null) {
                        data.getParcelableExtra(ImageEditorActivity.KEY_EDITED_URI)
                    } else {
                        null
                    }
                if (uri == null && data != null) {
                    uri = data.data
                }
                if (uri != null) {
                    var mimeType = (data ?: throw NullPointerException()).type
                    if (mimeType == null) {
                        mimeType = contentResolver.getType(uri)
                    }
                    if (mimeType == null) {
                        mimeType = "image/jpeg"
                    }
                    publish(uri, mimeType)
                }
            }
        } else {
            pendingTakePhotoUri.pop()
        }
    }

    private fun selectAccountToPublishStory() {
        val accounts =
            AccountRegistry.get().getAccounts().filter { account ->
                account.getStatus() == Account.State.ONLINE
            }
        if (accounts.isEmpty()) {
            Toast.makeText(
                this,
                uk.xa0.tulkki.xmpp.R.string.no_active_account,
                Toast.LENGTH_SHORT,
            ).show()
        } else if (accounts.size == 1) {
            openStoryImagePicker(accounts[0])
        } else {
            val selectedAccount = AtomicReference(accounts[0])
            val alertDialogBuilder = MaterialAlertDialogBuilder(this)
            alertDialogBuilder.setTitle(R.string.choose_account)
            val asStrings =
                accounts.map { a -> a.getJid().asBareJid().toString() }.toTypedArray()
            alertDialogBuilder.setSingleChoiceItems(asStrings, 0) { _, which ->
                selectedAccount.set(accounts[which])
            }
            alertDialogBuilder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            alertDialogBuilder.setPositiveButton(R.string.ok) { _, _ ->
                openStoryImagePicker(selectedAccount.get())
            }
            alertDialogBuilder.create().show()
        }
    }

    private fun openStoryImagePicker(account: Account) {
        this.mSelectedAccount = account
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION,
            )
        } else {
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT)
            galleryIntent.type = "image/*,video/*"
            galleryIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val takePhotoUri = FileBackends.get().getTakePhotoUri()
            pendingTakePhotoUri.push(takePhotoUri)
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, takePhotoUri)

            val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)

            val chooserIntent =
                Intent.createChooser(galleryIntent, getString(R.string.perform_action_with))
            chooserIntent.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf(cameraIntent, videoIntent),
            )

            try {
                startActivityForResult(chooserIntent, REQUEST_CHOOSE_STORY_IMAGE)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_application_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                openStoryImagePicker(mSelectedAccount ?: throw NullPointerException())
            }
        }
    }

    protected override fun refreshUiReal() {
        val service = xmppConnectionService ?: return

        // The deleted `refreshUiReal`'s four badges, read exactly as it read them.
        val lastRead = getPreferences().getLong("last_read_story_timestamp", 0)
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
        val hasNewStories =
            service.getStories().any { s ->
                s.getPublished() > lastRead && s.getPublished() >= twentyFourHoursAgo
            }
        val lastReadPosts = getPreferences().getLong("last_read_post_timestamp", 0)
        val hasNewPosts =
            DatabaseBackend.get().getPosts().any { p ->
                p.published != null &&
                    (p.published ?: throw NullPointerException()).time > lastReadPosts
            }
        val hasNewMissedCalls = service.getNotificationService().hasNewMissedCalls()
        screen =
            screen.copy(
                badges =
                    NavBadges(
                        chats = service.unreadCount(),
                        stories = hasNewStories,
                        feeds = hasNewPosts,
                        calls = hasNewMissedCalls,
                    )
            )

        refresh()
    }

    private fun refresh() {
        val service = xmppConnectionService ?: return
        this.stories.clear()
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
        val byContact = HashMap<Jid, uk.xa0.tulkki.libs.StoryRef>()
        for (story in service.getStories()) {
            if (story.getPublished() >= twentyFourHoursAgo) {
                val bareJid = story.getContact().asBareJid()
                val existing = byContact[bareJid]
                if (existing == null || story.getPublished() > existing.getPublished()) {
                    byContact[bareJid] = story
                }
            }
        }
        this.stories.addAll(byContact.values)
        this.stories.sortWith(compareByDescending { story -> story.getPublished() })

        rebuildRows()

        val hasOnlineAccounts =
            AccountRegistry.get().getAccounts().any { account ->
                account.getStatus() == Account.State.ONLINE
            }
        screen = screen.copy(hasOnlineAccounts = hasOnlineAccounts)
    }

    /**
     * The deleted `StoryAdapter`'s `onBindViewHolder`, for every row at once: the best contact, the
     * display name, the conversation whose avatar the row draws, and the viewer's account extra.
     */
    private fun rebuildRows() {
        val service = xmppConnectionService ?: return
        val avatarSize = resources.getDimensionPixelSize(R.dimen.avatar_story_size)
        val rows = ArrayList<BaseRow>(stories.size)
        val conversations = ArrayList<Conversation>()
        for (story in stories) {
            val jid = story.getContact()

            var contact: Contact? = null
            var storyAccount: Account? = null

            // Tulkki: 3.7 pair 9, part 15 - `findContacts` answers the island's refs, so the type is
            // inferred and the body casts back once per element: everything below reads model-only
            // surface (`getDisplayName`, `getAccount().getStatus()`).
            val contacts = service.findContacts(jid, null)
            if (!contacts.isEmpty()) {
                var bestContact: Contact? = null
                var bestScore = -1
                for (cRef in contacts) {
                    val c = cRef as Contact
                    var score = 0
                    if (c.getAccount().getStatus() == Account.State.ONLINE) {
                        score += 2
                    }
                    val d: Drawable? = service.getAvatarService().get(c, avatarSize)
                    if (d !is uk.xa0.tulkki.libs.TextAvatar) {
                        score += 1
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestContact = c
                    }
                }
                contact = bestContact
            }

            if (contact != null) {
                storyAccount = contact.getAccount()
            } else if (AccountRegistry.get().findAccountByJid(jid) != null) {
                storyAccount = AccountRegistry.get().findAccountByJid(jid)
                if (storyAccount != null) {
                    contact = storyAccount.getSelfContact()
                }
            }

            val displayName: String
            var avatarKey: String? = null
            if (contact != null) {
                displayName = contact.getDisplayName()
                val conversation =
                    service.findOrCreateConversation(
                        storyAccount ?: throw NullPointerException(),
                        contact.getJid(),
                        false,
                        false,
                    ) as Conversation
                avatarKey = conversation.getUuid()
                conversations.add(conversation)
            } else {
                displayName = jid.asBareJid().toString()
            }

            rows.add(
                BaseRow(
                    jid = jid.asBareJid(),
                    accountUuid = storyAccount?.getUuid(),
                    avatarKey = avatarKey,
                    title = displayName,
                    url = story.getUrl(),
                    published = story.getPublished(),
                )
            )
        }
        baseRows = rows
        publish()
        loadPreviews(rows)
        warmAvatars(conversations)
    }

    /** The rows as the screen draws them: the avatar and the preview attached, the time worded. */
    private fun publish() {
        val avatars = conversationAvatars()
        val now = System.currentTimeMillis()
        val rows =
            baseRows.map { base ->
                StoryRow(
                    jid = base.jid.toString(),
                    accountUuid = base.accountUuid,
                    avatar = base.avatarKey?.let { avatars.of(it) },
                    title = base.title,
                    time =
                        DateUtils.getRelativeTimeSpanString(
                                base.published,
                                now,
                                DateUtils.MINUTE_IN_MILLIS,
                            )
                            .toString(),
                    preview = base.url?.let { previews[it] }?.asImageBitmap(),
                )
            }
        screen = screen.copy(rows = rows)
    }

    /** The deleted adapter's `Glide.with(activity).load(story.getUrl()).into(storyPreview)`. */
    private fun loadPreviews(rows: List<BaseRow>) {
        val urls = rows.mapNotNull { it.url }.filter { !previews.containsKey(it) }
        if (urls.isEmpty()) {
            return
        }
        Thread {
                val resolved = HashMap(previews)
                for (url in urls) {
                    val bitmap =
                        try {
                            Glide.with(applicationContext)
                                .asBitmap()
                                .load(url)
                                .submit()
                                .get()
                        } catch (e: Exception) {
                            null
                        }
                    if (bitmap != null) {
                        resolved[url] = bitmap
                    }
                }
                runOnUiThread {
                    previews = resolved
                    publish()
                }
            }
            .start()
    }

    /** The deleted adapter's `AvatarWorkerTask.loadAvatar(conversation, storyImage, …)`, warmed. */
    private fun warmAvatars(conversations: List<Conversation>) {
        if (conversations.isEmpty()) {
            return
        }
        Thread {
                warmConversationAvatars(conversations)
                runOnUiThread { publish() }
            }
            .start()
    }

    /**
     * The deleted `StoryAdapter`'s click listener: every story the tapped row's owner published in
     * the last 24 hours, oldest first, handed to the viewer with the same four parallel lists.
     */
    private fun openViewer(row: StoryRow) {
        val service = xmppConnectionService ?: return
        val jid =
            try {
                Jid.of(row.jid)
            } catch (e: Exception) {
                return
            }
        val intent = Intent(this, StoryViewActivity::class.java)
        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        val storyIds = ArrayList<String>()
        val mimeTypes = ArrayList<String>()
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
        val contactStories = ArrayList<uk.xa0.tulkki.libs.StoryRef>()
        for (s in service.getStories()) {
            if (s.getContact().asBareJid().equals(jid.asBareJid()) &&
                s.getPublished() >= twentyFourHoursAgo
            ) {
                contactStories.add(s)
            }
        }
        // Show oldest first so the viewer plays them in chronological order
        contactStories.sortWith { a, b -> a.getPublished().compareTo(b.getPublished()) }
        for (s in contactStories) {
            urls.add(s.getUrl().orEmpty())
            titles.add(s.getTitle().orEmpty())
            storyIds.add(s.getUuid().orEmpty())
            mimeTypes.add(s.getType().orEmpty())
        }
        intent.putStringArrayListExtra(StoryViewActivity.EXTRA_URLS, urls)
        intent.putStringArrayListExtra(StoryViewActivity.EXTRA_TITLES, titles)
        intent.putStringArrayListExtra(StoryViewActivity.EXTRA_STORY_IDS, storyIds)
        intent.putStringArrayListExtra(StoryViewActivity.EXTRA_MIME_TYPES, mimeTypes)
        intent.putExtra(StoryViewActivity.EXTRA_CONTACT, row.jid)
        if (row.accountUuid != null) {
            intent.putExtra(StoryViewActivity.EXTRA_ACCOUNT, row.accountUuid)
        }
        startActivity(intent)
    }

    public override fun onStoriesUpdate() {
        runOnUiThread { refresh() }
    }

    /**
     * The deleted `dialog_create_story.xml`'s image preview: one `ImageView`, `fitCenter`, loaded by
     * the same `Glide.with(this).load(uri).into(...)`.
     */
    @Composable
    private fun storyPreviewImage(uri: Uri) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    Glide.with(this@StoriesActivity).load(uri).into(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** The deleted layout's video preview: one looping `VideoView`, exactly as it was started. */
    @Composable
    private fun storyPreviewVideo(uri: Uri) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    companion object {
        private const val REQUEST_CHOOSE_STORY_IMAGE = 0x2b01
        private const val REQUEST_CAMERA_PERMISSION = 0x2b03
        private const val REQUEST_EDIT_STORY_IMAGE = 0x2b04

        /** The deleted `StoryAdapter`'s own minute: `handler.postDelayed(this, 60000)`. */
        private const val TIME_TICK_MS = 60000L
    }
}
