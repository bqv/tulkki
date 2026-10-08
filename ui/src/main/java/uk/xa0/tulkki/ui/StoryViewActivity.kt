package uk.xa0.tulkki.ui

import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.stories.StoryPage
import uk.xa0.tulkki.ui.stories.StoryViewerScreen
import uk.xa0.tulkki.ui.stories.StoryViewerSession
import uk.xa0.tulkki.xml.Element

/**
 * The story viewer: one contact's last day of stories, full screen.
 *
 * <p>**The layout is gone.** `activity_story_view.xml` held a `ViewPager2` of `StoryFragment`s
 * (`fragment_story.xml`), a `progress_bar_container` of `story_progress_bar.xml` instances, the
 * `bottom_panel` with `story_title_view`, and the toolbar with the avatar, the name and the published
 * time; all three layouts and [StoryFragment] are deleted. The bar is the shared chrome
 * ([TulkkiChrome]) now, the player is [StoryViewerScreen], and `setSupportActionBar`, the
 * `ViewPager2` with its `FragmentStateAdapter`, the progress-bar loop, `hideSystemUi`/`showSystemUi`
 * and the two menu inflations went with them.
 *
 * <p>**What moved out of the bar.** The chrome's title slot is a string, so the author's avatar
 * (`toolbar_avatar`, loaded through `AvatarWorkerTask.loadAvatar`) is not carried; the published time
 * that sat under the name is drawn in the player's panel instead. The two menu items are the chrome's
 * overflow now, in the same order and under the same conditions - `reply` always, `delete_story` only
 * while the story's owner is one of this device's accounts and online, which is exactly what the
 * deleted `onCreateOptionsMenu` made the second icon visible for.
 *
 * <p>**The dialogs stay builders.** Neither the delete confirmation nor the reply editor ever had a
 * layout file - the Java built both with `MaterialAlertDialogBuilder` - so neither is a file to
 * delete, and both keep their `pauseStory`/`resumeStory` pair as [StoryViewerSession.paused].
 */
class StoryViewActivity : XmppActivity() {

    private lateinit var urls: ArrayList<String>
    private lateinit var titles: ArrayList<String>
    private var storyIds: ArrayList<String>? = null
    private lateinit var mimeTypes: ArrayList<String>
    private var contact: Jid? = null
    private var mAccount: Account? = null

    private val session = StoryViewerSession()
    private lateinit var pages: List<StoryPage>
    private var displayName by mutableStateOf("")
    private var chromeMenu by mutableStateOf<List<ChromeMenuItem>>(emptyList())

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: throw NullPointerException()
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: throw NullPointerException()
        storyIds = intent.getStringArrayListExtra(EXTRA_STORY_IDS)
        mimeTypes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES) ?: throw NullPointerException()

        try {
            contact = Jid.of(intent.getStringExtra(EXTRA_CONTACT) ?: throw NullPointerException())
        } catch (e: Exception) {
            // ignore
        }

        pages = urls.indices.map { StoryPage(urls[it], mimeTypes[it], titles[it]) }

        updateDisplayName()
        updateMenu()

        setTulkkiContent(darkTheme = isDark()) {
            // The deleted `updateUiForPosition` read the position the pager was on; the session is
            // that position now, and reading it here is what re-words the panel and the title.
            val index = session.index
            TulkkiChrome(
                title = displayName,
                onUp = { finish() },
                menu = chromeMenu,
            ) {
                StoryViewerScreen(
                    pages = pages,
                    session = session,
                    subtitle = storySubtitle(index),
                    loadImage = { url -> loadStoryImage(url) },
                    video = { page -> storyVideo(page) },
                    onFinish = { finish() },
                )
            }
        }
    }

    /**
     * The deleted `updateUiForPosition`'s first half: the roster contact the author belongs to, and
     * the name the toolbar drew.
     */
    private fun updateDisplayName() {
        var storyContact: Contact? = null
        val jid = contact
        val service = xmppConnectionService
        if (jid != null && service != null) {
            for (account in AccountRegistry.get().getAccounts()) {
                val c = account.getRoster().getContact(jid)
                if (c != null) {
                    storyContact = c
                    if (account.getStatus() == Account.State.ONLINE) {
                        break
                    }
                }
            }
        }
        val resolved = storyContact
        displayName =
            when {
                resolved != null -> resolved.getDisplayName()
                jid != null -> jid.asBareJid().toString()
                else -> ""
            }
    }

    /** The two menu items, under the deleted `onCreateOptionsMenu`'s own condition. */
    private fun updateMenu() {
        val items = ArrayList<ChromeMenuItem>(2)
        items.add(ChromeMenuItem(getString(R.string.reply)) { replyToStory() })
        val jid = contact
        val service = xmppConnectionService
        if (jid != null && service != null) {
            val storyOwner = AccountRegistry.get().findAccountByJid(jid)
            if (storyOwner != null && storyOwner.isOnlineAndConnected()) {
                mAccount = storyOwner
                items.add(ChromeMenuItem(getString(R.string.delete_story)) { confirmDelete() })
            }
        }
        chromeMenu = items
    }

    /**
     * The deleted `updateUiForPosition`'s second half: the story's own published time, worded
     * relatively, looked up by the id the viewer was handed.
     */
    private fun storySubtitle(position: Int): String {
        val ids = storyIds ?: return ""
        if (position < 0 || position >= ids.size) {
            return ""
        }
        val service = xmppConnectionService ?: return ""
        val currentStoryId = ids[position]
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
        for (story in service.getStories()) {
            if (story.getUuid() == currentStoryId && story.getPublished() >= twentyFourHoursAgo) {
                return DateUtils.getRelativeTimeSpanString(
                        story.getPublished(),
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    )
                    .toString()
            }
        }
        return ""
    }

    /**
     * The deleted `StoryFragment`'s still half: `Glide.with(this).load(url).into(imageView)` with the
     * spinner shown until it lands, and the same `download_failed_file_not_found` toast when it does
     * not.
     */
    private suspend fun loadStoryImage(url: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            try {
                Glide.with(applicationContext).asBitmap().load(url).submit().get().asImageBitmap()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                            this@StoryViewActivity,
                            uk.xa0.tulkki.xmpp.R.string.download_failed_file_not_found,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
                null
            }
        }

    /**
     * The deleted `StoryFragment`'s video half: the same `Glide.asFile` fetch, the same
     * `Uri.fromFile` `VideoView`, not looping, started on prepared, the same
     * `setOnCompletionListener { onNextStory() }`, and the same 50 ms poll of
     * `currentPosition / duration` - frame-paced here instead of handler-paced.
     */
    @Composable
    private fun storyVideo(page: StoryPage) {
        var file by remember(page.url) { mutableStateOf<File?>(null) }
        val holder = remember(page.url) { VideoHolder() }
        val paused = session.paused

        LaunchedEffect(page.url) {
            file =
                withContext(Dispatchers.IO) {
                    try {
                        Glide.with(applicationContext).asFile().load(page.url).submit().get()
                    } catch (e: Exception) {
                        null
                    }
                }
            if (file == null) {
                Toast.makeText(
                        this@StoryViewActivity,
                        uk.xa0.tulkki.xmpp.R.string.download_failed_file_not_found,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }

        val videoFile = file
        if (videoFile == null) {
            // The slot has no BoxScope of its own, so the centring is the box's own.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            return
        }

        LaunchedEffect(videoFile) {
            while (true) {
                withFrameNanos { }
                val view = holder.view ?: continue
                val duration = view.duration
                if (duration > 0) {
                    session.progress =
                        (view.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                }
            }
        }

        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    holder.view = this
                    setVideoURI(Uri.fromFile(videoFile))
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        if (!session.paused) {
                            start()
                        }
                    }
                    setOnCompletionListener { advance() }
                }
            },
            update = { view ->
                if (paused) {
                    if (view.isPlaying) {
                        view.pause()
                    }
                } else if (!view.isPlaying) {
                    view.start()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** The deleted `onNextStory()`: one page on, or out of the viewer at the last one. */
    private fun advance() {
        if (session.index + 1 < pages.size) {
            session.index += 1
            session.progress = 0f
        } else {
            finish()
        }
    }

    private fun confirmDelete() {
        // The deleted `action_delete_story` paused the story before it opened the dialog.
        session.paused = true
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_story_dialog_title)
                .setMessage(R.string.delete_story_dialog_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val ids = storyIds ?: throw NullPointerException()
                    xmppConnectionService.retractStory(
                        mAccount ?: throw NullPointerException(),
                        ids[session.index],
                        object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<Void?> {
                            override fun success(obj: Void?) {
                                runOnUiThread {
                                    Toast.makeText(
                                            this@StoryViewActivity,
                                            R.string.story_deleted,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                    setResult(RESULT_OK)
                                    finish()
                                }
                            }

                            override fun error(errorCode: Int, obj: Void?) {
                                runOnUiThread {
                                    Toast.makeText(
                                            this@StoryViewActivity,
                                            errorCode,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            }

                            override fun userInputRequired(pi: PendingIntent?, obj: Void?) {}
                        },
                    )
                }
                .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
                .create()
        dialog.setOnDismissListener { session.paused = false }
        dialog.show()
    }

    private fun replyToStory() {
        val currentPos = session.index
        val account = mAccount
        if (account != null) {
            val conversation =
                xmppConnectionService.findOrCreateConversation(
                    account,
                    contact ?: throw NullPointerException(),
                    false,
                    false,
                ) as Conversation
            val storyTitle: String? = titles[currentPos]
            if (conversation.getNextEncryption() != Message.ENCRYPTION_NONE) {
                val storyMessage =
                    Message(
                        conversation,
                        getString(R.string.reply_to_story) +
                            " " +
                            "\"" +
                            titles[currentPos] +
                            "\"",
                        conversation.getNextEncryption(),
                        Message.STATUS_RECEIVED,
                    )
                conversation.replyTo = storyMessage
                switchToConversation(conversation)
            } else {
                // The deleted `action_reply_to_story` paused the story before showing the dialog.
                session.paused = true

                val container = LinearLayout(this)
                container.orientation = LinearLayout.VERTICAL
                val params =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                val margin = (16 * resources.displayMetrics.density).toInt()
                params.setMargins(margin, 0, margin, 0)
                val input = TextInputEditText(this)
                input.layoutParams = params
                input.setHint(R.string.compose_message_hint)
                container.addView(input)

                val dialog =
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.reply_to_story)
                        .setView(container)
                        .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
                        .setPositiveButton(R.string.send) { _, _ ->
                            val text = input.text?.toString() ?: ""
                            val ids = storyIds ?: throw NullPointerException()
                            val storyId: String? = ids[currentPos]
                            if (storyId == null) {
                                return@setPositiveButton
                            }
                            val storyUri =
                                "xmpp:" +
                                    (contact ?: throw NullPointerException())
                                        .asBareJid()
                                        .toString() +
                                    "?;node=urn:xmpp:pubsub-social-feed:stories:0;item=" +
                                    storyId
                            val messageBody =
                                if (text.isEmpty()) {
                                    storyTitle ?: getString(R.string.reply_to_story)
                                } else {
                                    text
                                }

                            val storyMessage =
                                Message(
                                    conversation,
                                    messageBody,
                                    conversation.getNextEncryption(),
                                    Message.STATUS_SEND,
                                )
                            val reference = Element("reference", "urn:xmpp:reference:0")
                            reference.setAttribute("type", "data")
                            reference.setAttribute("uri", storyUri)
                            storyMessage.addPayload(reference)
                            storyMessage.setType(Message.TYPE_STORY)

                            val storyParams = Message.FileParams()
                            storyParams.url = storyUri
                            storyMessage.setFileParams(storyParams)

                            xmppConnectionService.sendMessage(storyMessage)
                            switchToConversation(conversation)
                        }
                        .create()

                dialog.setOnDismissListener { session.paused = false }
                dialog.show()
            }
        }
    }

    protected override fun onBackendConnected() {
        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT)
        if (accountUuid != null) {
            mAccount = AccountRegistry.get().findAccountByUuid(accountUuid)
        }
        // The deleted `invalidateOptionsMenu`, which is what made the delete item visible.
        updateMenu()
        refreshUi()
    }

    protected override fun refreshUiReal() {
        updateDisplayName()
        updateMenu()
    }

    /** One `VideoView`, held outside snapshot state so the factory writes nothing to a composition. */
    private class VideoHolder {
        var view: VideoView? = null
    }

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_STORY_IDS = "story_ids"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_MIME_TYPES = "story_mime_types"
    }
}
