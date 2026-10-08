package uk.xa0.tulkki.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Comment
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.data.model.StubConversation
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.posts.CommentRow
import uk.xa0.tulkki.ui.posts.PostRow
import uk.xa0.tulkki.ui.posts.PostsBadges
import uk.xa0.tulkki.ui.posts.PostsEvents
import uk.xa0.tulkki.ui.posts.PostsHost
import uk.xa0.tulkki.ui.posts.PostsListItem
import uk.xa0.tulkki.ui.posts.PostsScreen
import uk.xa0.tulkki.ui.posts.PostsState
import uk.xa0.tulkki.ui.posts.showAccountSelection
import uk.xa0.tulkki.ui.posts.showImagePreview
import uk.xa0.tulkki.ui.posts.showLikes
import uk.xa0.tulkki.ui.posts.showVideoPreview
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xml.XmlReader

/**
 * The feed: the microblog posts the accounts follow, their comments and their likes. It reads the
 * database, talks to the service and decides what a gesture means; [PostsScreen] draws and names no
 * string of its own.
 *
 * <p>**The layouts and the adapters are gone.**
 * `ui/src/main/res/layout/activity_posts.xml`, `item_post.xml`, `item_comment.xml` and
 * `item_follow_suggestion.xml` are deleted, and so are `adapter/PostsAdapter.kt`,
 * `adapter/CommentsAdapter.kt` and `adapter/FollowSuggestionAdapter.kt`; the bar is the shared
 * chrome and the screen line above is the whole view. `menu/activity_posts.xml` is deleted with
 * them: its search item is the chrome's search action and its refresh item its overflow.
 * `menu/bottom_navigation_menu_feeds.xml` goes too, because this layout was its only binding, and
 * the four destinations are a `NavigationBar` in [PostsScreen] now. The two preview dialogs
 * `PostsAdapter` opened keep their own layouts, because those rows belong to the dialogs lane: see
 * [uk.xa0.tulkki.ui.posts.PostPreviews].
 *
 * <p>**The reads are unchanged.** `loadPosts` still unions the database's posts with every followed
 * contact's pubsub feed, `filterAndDisplayPosts` still filters and sorts the same way, and a post
 * that arrives or is retracted still lands through the same service listeners. What moved is where
 * the answer goes: `postList`, the expanded set, the per-post comments and likes are assembled into
 * a [PostsState] the screen draws, instead of being pushed into adapters. The comment listener that
 * `CommentsAdapter` registered per expanded post is registered once here for the whole screen, with
 * the same dedupe-and-sort effect.
 *
 * <p>**One dead branch did not survive verbatim.** The deleted row drew nothing at all when
 * `xmppConnectionService` was `null` - no author, no body, no title - because every write sat in the
 * other half of that `if`. No row can exist in that state (`loadPosts` returns before reading
 * anything, so the list is empty), and this screen draws the post regardless: the KDoc records the
 * liberty rather than reproducing an unreachable blank card.
 */
class PostsActivity :
    XmppActivity(),
    XmppConnectionService.OnPostReceived,
    XmppConnectionService.OnPostRetracted,
    OnSearchPerformed {

    /** The posts the last read found, filtered by [mCurrentQuery] and sorted, in display order. */
    private val postList: MutableList<Post> = ArrayList()

    /** Every post the last read found, before the query filter. */
    private val allPosts: MutableList<Post> = ArrayList()

    /** The unfolded posts. Identity here is [Post]'s own id equality, exactly as the adapter had it. */
    private val expandedPosts: MutableSet<Post> = HashSet()

    /** The loaded comments of each unfolded post, in ascending publication order. */
    private val comments: MutableMap<Post, MutableList<Comment>> = HashMap()

    /** The loaded likes of each unfolded post, kept for the count, the list and the retract. */
    private val likes: MutableMap<Post, MutableList<Comment>> = HashMap()

    /** The contacts the owner could follow; empty hides the strip. */
    private val mFollowSuggestions: MutableList<Contact> = ArrayList()

    private var mCurrentQuery = ""
    private var searchActive = false
    private var mSuggestionsVisible = true
    private var navVisible = false
    private var badges = PostsBadges()

    private val session = PostsHost.Session()
    private val markwon: Markwon by lazy { Markwon.create(this) }

    /** The one comment listener, where the deleted adapter had one per unfolded post. */
    private val commentReceivedListener: uk.xa0.tulkki.xmpp.services.OnCommentReceived =
        uk.xa0.tulkki.xmpp.services.OnCommentReceived { postUuid, commentRef ->
            // The lambda's parameter type is inferred and nothing island-side is imported: the body
            // is the model's, and Comment is CommentRef's sole implementor.
            val comment = commentRef as Comment
            runOnUiThread { addComment(postUuid, comment) }
        }

    private val events: PostsEvents = Feed()

    private val postResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                loadPosts()
            }
        }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mSuggestionsVisible =
            getPreferences(Context.MODE_PRIVATE).getBoolean("suggestions_visible", true)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            val state = session.state
            TulkkiChrome(
                // `setTitle` went with the action bar, and the label was the manifest's.
                title = stringResource(R.string.feeds),
                // The up arrow the old bar enabled only while the bottom bar was hidden; `finish`
                // is what it ran.
                onUp = if (state.navVisible) null else ({ finish() }),
                actions = {
                    IconButton(onClick = { events.onSearchToggle() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                },
                menu = listOf(ChromeMenuItem(stringResource(R.string.refresh)) { events.onRefresh() }),
            ) {
                PostsScreen(state = state, events = events)
            }
        }

        handleIntent(intent)
    }

    public override fun onStart() {
        super.onStart()
        val service = xmppConnectionService
        if (service != null) {
            service.addOnPostReceivedListener(this)
            service.addOnPostRetractedListener(this)
            service.addOnCommentReceivedListener(commentReceivedListener)
        }
        if (postList.isEmpty()) {
            loadPosts()
        }
        navVisible =
            getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) &&
            intent.getBooleanExtra("show_nav_bar", false)

        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putLong("last_read_post_timestamp", System.currentTimeMillis())
            .apply()
        refreshUi()
        render()
    }

    public override fun onStop() {
        super.onStop()
        val service = xmppConnectionService
        if (service != null) {
            service.removeOnPostReceivedListener(this)
            service.removeOnPostRetractedListener(this)
            service.removeOnCommentReceivedListener(commentReceivedListener)
        }
    }

    public override fun onBackendConnected() {
        val service = xmppConnectionService
        if (service != null) {
            service.addOnPostReceivedListener(this)
            service.addOnPostRetractedListener(this)
            service.addOnCommentReceivedListener(commentReceivedListener)
        }
        refreshUiReal()
    }

    /**
     * Tulkki: 3.7 C5-D - the listener parameter is the island's `PostRef` now, so the override
     * names it **fully qualified in the signature and imports nothing**: an import of an island
     * type here would be one more `ui-reaches-island` site. The hub's Kotlin `OnPostReceived`
     * hands the listener the ref the Java body had already null-guarded, so the parameter is the
     * non-null `PostRef` the interface declares; the body keeps the Java's own null test, and
     * `Post` is `PostRef`'s sole implementor.
     */
    public override fun onPostReceived(postRef: uk.xa0.tulkki.libs.PostRef) {
        val post = postRef as Post?
        runOnUiThread {
            if (post == null || post.id == null) {
                return@runOnUiThread
            }

            var existingPostIndex = -1
            for (i in allPosts.indices) {
                val existingPost = allPosts[i]
                if (post.id == existingPost.id) {
                    existingPostIndex = i
                    break
                }
            }

            if (existingPostIndex != -1) {
                allPosts[existingPostIndex] = post
            } else {
                allPosts.add(0, post)
            }
            filterAndDisplayPosts(mCurrentQuery)
        }
    }

    protected override fun refreshUiReal() {
        val service = xmppConnectionService ?: return

        if (allPosts.isEmpty()) {
            loadPosts()
        }

        // The four bottom-bar destinations' badges, read off the service and the database exactly
        // as the view's own badge pass read them.
        val unreadCount = service.unreadCount()
        val lastRead = getPreferences().getLong("last_read_story_timestamp", 0)
        val hasNewStories = service.getStories().any { s -> s.getPublished() > lastRead }
        val lastReadPosts = getPreferences().getLong("last_read_post_timestamp", 0)
        val hasNewPosts =
            DatabaseBackend.get().getPosts().any { p ->
                p.published != null &&
                    (p.published ?: throw NullPointerException()).time > lastReadPosts
            }
        val hasNewMissedCalls = service.getNotificationService().hasNewMissedCalls()
        badges = PostsBadges(unreadCount, hasNewStories, hasNewPosts, hasNewMissedCalls)
        render()
    }

    public fun loadPosts() {
        val service = xmppConnectionService ?: return
        allPosts.clear()
        allPosts.addAll(DatabaseBackend.get().getPosts())
        filterAndDisplayPosts(mCurrentQuery)

        mFollowSuggestions.clear()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isOnlineAndConnected()) {
                val sourcesToFetch = ArrayList<Jid>()
                sourcesToFetch.add(account.getJid().asBareJid())
                for (contact in account.getRoster().getContacts()) {
                    if (contact.isFollowed()) {
                        sourcesToFetch.add(contact.getJid().asBareJid())
                    } else if (contact.showInRoster() &&
                        contact.getOption(Contact.Options.TO) &&
                        contact.getOption(Contact.Options.FROM)
                    ) {
                        mFollowSuggestions.add(contact)
                    }
                }

                for (source in sourcesToFetch) {
                    service.fetchPubsubItems(
                        source,
                        Namespace.MICROBLOG,
                        object : uk.xa0.tulkki.xmpp.services.OnPubsubItemsFetched {
                            override fun onPubsubItemsFetched(feedXml: String) {
                                try {
                                    val reader = XmlReader()
                                    reader.setInputStream(
                                        ByteArrayInputStream(feedXml.toByteArray()),
                                    )
                                    val pubsub =
                                        reader.readElement(reader.readTag() ?: throw NullPointerException())
                                    val newPosts = ArrayList<Post>()
                                    if (pubsub != null) {
                                        val items = pubsub.findChild("items")
                                        if (items != null) {
                                            for (item in items.getChildren()) {
                                                if ("item" == item.getName()) {
                                                    val entry =
                                                        item.findChild(
                                                            "entry",
                                                            Namespace.ATOM,
                                                        )
                                                    if (entry != null) {
                                                        // `fromElement` repeats this same entry
                                                        // lookup, so a null here is the Java's own
                                                        // impossible branch, named rather than
                                                        // forced.
                                                        val p = Post.fromElement(item) ?: continue
                                                        newPosts.add(p)
                                                        DatabaseBackend.get()
                                                            .createPost(p, account)
                                                    }
                                                }
                                            }
                                            runOnUiThread {
                                                for (newPost in newPosts) {
                                                    var found = false
                                                    for (existingPost in allPosts) {
                                                        if (existingPost.id != null &&
                                                            existingPost.id == newPost.id
                                                        ) {
                                                            found = true
                                                            break
                                                        }
                                                    }
                                                    if (!found) {
                                                        allPosts.add(newPost)
                                                    }
                                                }
                                                filterAndDisplayPosts(mCurrentQuery)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@PostsActivity,
                                            "Error parsing posts.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        Log.e(Config.LOGTAG, "error parsing posts", e)
                                    }
                                }
                            }

                            override fun onPubsubItemsFetchFailed() {
                                // Silently ignore for now
                            }
                        },
                    )
                }
            }
        }
        render()
    }

    public override fun onPostRetracted(postId: String) {
        runOnUiThread {
            allPosts.removeAll { p -> (p.id ?: throw NullPointerException()) == postId }
            // What CommentsAdapter's own listener did: a retracted comment leaves every open post.
            for (list in comments.values) {
                list.removeAll { c -> c.id == postId }
            }
            filterAndDisplayPosts(mCurrentQuery)
        }
    }

    public override fun onPostRetractionFailed() {
        runOnUiThread {
            Toast.makeText(this, R.string.error_retract_post, Toast.LENGTH_SHORT).show()
        }
    }

    public override fun onSearchPerformed(query: String) {
        searchActive = true
        filterAndDisplayPosts(query)
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    public override fun onBackPressed() {
        if (searchActive) {
            searchActive = false
            filterAndDisplayPosts("")
            return
        }
        if (mCurrentQuery.isNotEmpty()) {
            filterAndDisplayPosts("")
            return
        }

        if (navVisible) {
            val intent = Intent(this, ConversationListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
        }

        super.onBackPressed()
    }

    // -- the read the screen draws ----------------------------------------------------------------

    private fun filterAndDisplayPosts(query: String) {
        this.mCurrentQuery = query
        postList.clear()
        if (query.isEmpty()) {
            postList.addAll(allPosts)
        } else {
            postList.addAll(
                allPosts.filter { p ->
                    // `Post` is compiled in another module, so its properties cannot be smart
                    // cast; the locals carry Java's own two null checks.
                    val content = p.content
                    val title = p.title
                    (content != null && content.contains(query)) ||
                        (title != null && title.contains(query))
                },
            )
        }
        java.util.Collections.sort(postList) { p1, p2 ->
            if (p1.published == null && p2.published == null) {
                0
            } else if (p1.published == null) {
                1
            } else if (p2.published == null) {
                -1
            } else {
                (p2.published ?: throw NullPointerException()).compareTo(
                    p1.published ?: throw NullPointerException(),
                )
            }
        }
        render()
    }

    /** Assemble what the screen draws from the fields the last read left behind. */
    private fun render() {
        val items = ArrayList<PostsListItem>()
        for (post in postList) {
            items.add(PostsListItem.PostItem(rowFor(post)))
            if (expandedPosts.contains(post)) {
                val postComments = comments[post] ?: continue
                for (comment in postComments) {
                    items.add(PostsListItem.CommentItem(post, commentRowFor(post, comment)))
                }
            }
        }
        session.update(
            PostsState(
                items = items,
                suggestions = ArrayList(mFollowSuggestions),
                suggestionsVisible = mSuggestionsVisible,
                searchActive = searchActive,
                query = mCurrentQuery,
                navVisible = navVisible,
                badges = badges,
            ),
        )
    }

    /**
     * One post's card. It is `PostsAdapter.setupPostView`'s decisions, in the same order: the same
     * author resolution (`ownAccount` first, then a connected account that knows the author, then
     * the first enabled account), the same Markwon body, the same attachment classification, the
     * same edit/delete gate, and the same like state the adapter's `setupLikeButton` left behind.
     */
    private fun rowFor(post: Post): PostRow {
        val service = xmppConnectionService
        val expanded = expandedPosts.contains(post)
        val attachmentUrl = post.attachmentUrl
        val attachmentType = post.attachmentType
        val hasAttachment = attachmentUrl != null
        val isImage = hasAttachment && attachmentType != null && attachmentType.startsWith("image/")
        val isVideo = hasAttachment && attachmentType != null && attachmentType.startsWith("video/")

        val author = post.author
        val postAccounts = accountsFor(post)
        val ownAccount =
            if (author != null) AccountRegistry.get().findAccountByJid(author.asBareJid()) else null
        var authorName: String? = null
        var authorAvatar: Avatarable? = null
        if (author != null) {
            var displayAccount = ownAccount
            if (displayAccount == null && postAccounts.size > 0) {
                displayAccount = postAccounts[0]
            }
            if (displayAccount == null) {
                // C5-D: `getFirstEnabled` answers AccountRef and `displayAccount` is a model. The
                // cast is total because `getAccounts()` is declared `List<Account>`.
                displayAccount = AccountUtils.getFirstEnabled(AccountRegistry.get().getAccounts()) as Account?
            }
            if (displayAccount != null) {
                if (author.asBareJid() == displayAccount.getJid().asBareJid()) {
                    val displayName = displayAccount.getDisplayName()
                    authorName =
                        if (displayName != null && displayName.isNotEmpty()) {
                            displayName
                        } else {
                            displayAccount.getJid().asBareJid().toString()
                        }
                    authorAvatar = displayAccount
                } else {
                    val contact = displayAccount.getRoster().getContact(author)
                    val displayName = contact.getDisplayName()
                    authorName =
                        if (displayName.isNotEmpty()) displayName else contact.getJid().asBareJid().toString()
                    authorAvatar = contact
                }
            } else {
                authorName = author.asBareJid().toString()
            }
        }

        val content = post.content
        // Markwon answers a `Spanned` - what the deleted `TextView` was handed - and
        // [PostRow.content] is the body as text, because the screen draws it with Compose's
        // `Text(String)`. The conversion is therefore here, at the one boundary, and it keeps the
        // words: the row's own link walker still makes the urls and hashtags tappable. Carrying the
        // markdown's emphasis across would mean an `AnnotatedString` in the state, a change of shape
        // rather than of type.
        val markdown = content?.let { markwon.toMarkdown(it).toString() }
        val loaded = likes.containsKey(post)
        val postLikes = likes[post] ?: emptyList()
        val onlineAccounts =
            AccountRegistry.get().getAccounts().filter { it.isOnlineAndConnected() }
        val likedByMe =
            loaded &&
                onlineAccounts.any { account ->
                    postLikes.any { like ->
                        like.author?.asBareJid() == account.getJid().asBareJid()
                    }
                }

        return PostRow(
            post = post,
            expanded = expanded,
            title = post.title,
            content = markdown,
            timestamp = post.published?.let { DateFormat.getDateTimeInstance().format(it) },
            authorName = authorName,
            authorAvatar = authorAvatar,
            hasAttachment = hasAttachment,
            attachmentUrl = attachmentUrl,
            attachmentIsImage = isImage,
            attachmentIsVideo = isVideo,
            linkUrl = post.linkUrl,
            actionsVisible = service != null,
            editVisible = ownAccount != null && ownAccount.isOnlineAndConnected(),
            authorKnown = author != null,
            likeCount = if (loaded) postLikes.size.toString() else "",
            likeEnabled = loaded && onlineAccounts.isNotEmpty(),
            likedByMe = likedByMe,
        )
    }

    /** One comment's row: `CommentsAdapter.bind`'s author resolution and retract gate. */
    private fun commentRowFor(post: Post, comment: Comment): CommentRow {
        val authorJid = comment.author
        var authorName: String? = null
        var authorAvatar: Avatarable? = null
        var retractVisible = false
        if (authorJid != null && xmppConnectionService != null) {
            val postAuthorJid = post.author ?: throw NullPointerException()
            val postAuthorAccount = AccountRegistry.get().findAccountByJid(postAuthorJid)
            val contextAccount: Account? =
                postAuthorAccount ?: AccountUtils.getFirstEnabled(AccountRegistry.get().getAccounts()) as Account?
            if (contextAccount != null) {
                if (authorJid.asBareJid() == contextAccount.getJid().asBareJid()) {
                    authorName =
                        contextAccount.getDisplayName() ?: authorJid.asBareJid().toString()
                    authorAvatar = contextAccount
                } else {
                    // `Roster.getContact` answers non-null, so Java's null branch was dead.
                    val contact = contextAccount.getRoster().getContact(authorJid)
                    authorName = contact.getDisplayName()
                    authorAvatar = contact
                }
                val commentAuthorAccount = AccountRegistry.get().findAccountByJid(authorJid)
                val iAmPostAuthor = postAuthorAccount != null && postAuthorAccount.isOnlineAndConnected()
                val iAmCommentAuthor =
                    commentAuthorAccount != null && commentAuthorAccount.isOnlineAndConnected()
                retractVisible = iAmPostAuthor || iAmCommentAuthor
            } else {
                authorName = authorJid.asBareJid().toString()
            }
        }
        return CommentRow(
            comment = comment,
            authorName = authorName,
            authorAvatar = authorAvatar,
            content = comment.title,
            timestamp = comment.published?.let { DateFormat.getDateTimeInstance().format(it) },
            retractVisible = retractVisible,
        )
    }

    /** The connected accounts that know a post's author - the adapter's own `postAccounts`. */
    private fun accountsFor(post: Post): List<Account> {
        val author = post.author
        val service = xmppConnectionService
        val result = ArrayList<Account>()
        if (author != null && service != null) {
            for (account in AccountRegistry.get().getAccounts()) {
                if (account.isOnlineAndConnected()) {
                    if (account.getJid().asBareJid() == author.asBareJid() ||
                        account.getRoster().getContact(author.asBareJid()) != null
                    ) {
                        result.add(account)
                    }
                }
            }
        }
        return result
    }

    // -- the likes and the comments ---------------------------------------------------------------

    /** Fetch one post's comments and likes, exactly as the expanded row's adapter did. */
    private fun loadCommentsAndLikes(post: Post) {
        val service = xmppConnectionService ?: return
        try {
            val uri = XmppUri(post.commentsNode ?: throw NullPointerException())
            val jid = uri.getJid()
            val node = uri.getParameter("node")
            if (jid != null && node != null) {
                service.fetchPubsubItems(
                    jid,
                    node,
                    object : uk.xa0.tulkki.xmpp.services.OnPubsubItemsFetched {
                        override fun onPubsubItemsFetched(feedXml: String) {
                            try {
                                val reader = XmlReader()
                                reader.setInputStream(
                                    ByteArrayInputStream(feedXml.toByteArray()),
                                )
                                val pubsub: Element? =
                                    reader.readElement(reader.readTag() ?: throw NullPointerException())
                                val postComments = ArrayList<Comment>()
                                val postLikes = ArrayList<Comment>()
                                if (pubsub != null) {
                                    val items: Element? = pubsub.findChild("items")
                                    if (items != null) {
                                        for (item in items.getChildren()) {
                                            if ("item" == item.getName()) {
                                                val comment = Comment.fromElement(item)
                                                if (comment != null) {
                                                    if ("♥" == comment.title) {
                                                        postLikes.add(comment)
                                                    } else {
                                                        postComments.add(comment)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                runOnUiThread {
                                    // The adapter's own guard: the answer is dropped when the
                                    // post has been folded again.
                                    if (!expandedPosts.contains(post)) {
                                        return@runOnUiThread
                                    }
                                    if (postComments.isNotEmpty()) {
                                        postComments.sortWith(COMMENT_ORDER)
                                        comments[post] = postComments
                                    } else {
                                        comments.remove(post)
                                    }
                                    // The like state is set whether or not there were comments,
                                    // which is the order the adapter had.
                                    likes[post] = postLikes
                                    render()
                                }
                            } catch (e: Exception) {
                                Log.e(Config.LOGTAG, "error parsing comments", e)
                            }
                        }

                        override fun onPubsubItemsFetchFailed() {
                            Log.e(Config.LOGTAG, "failed to fetch comments for post " + post.id)
                        }
                    },
                )
            }
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "error parsing comments node uri", e)
        }
    }

    /**
     * A comment the service pushed into an open post: dedupe, sort, redraw.
     *
     * `postUuid` is nullable because `OnCommentReceived`'s own parameter is - the Java handed over
     * the `ref` attribute either parser read and never tested it. The deleted adapter's own test was
     * `mPost.id == postUuid`, so this is the equality of two optionals as well: a post with no id
     * answers a null uuid, exactly as it did.
     */
    private fun addComment(postUuid: String?, comment: Comment) {
        val post = expandedPosts.firstOrNull { it.id == postUuid } ?: return
        val list = comments[post] ?: return
        if (list.any { it.id == comment.id }) {
            return
        }
        list.add(comment)
        list.sortWith(COMMENT_ORDER)
        render()
    }

    /** The like button: retract the owner's own like, or add one, choosing an account if needed. */
    private fun toggleLike(post: Post) {
        val postLikes = likes[post] ?: return
        val onlineAccounts =
            AccountRegistry.get().getAccounts().filter { it.isOnlineAndConnected() }
        if (onlineAccounts.isEmpty()) {
            return
        }
        val mine =
            onlineAccounts.firstNotNullOfOrNull { account ->
                postLikes
                    .firstOrNull { like -> like.author?.asBareJid() == account.getJid().asBareJid() }
                    ?.let { like -> account to like }
            }
        if (mine != null) {
            retractLike(mine.first, mine.second, post)
        } else if (onlineAccounts.size > 1) {
            showAccountSelection(
                this,
                getString(R.string.choose_account_for_like),
                onlineAccounts,
            ) { publishLike(it, post) }
        } else {
            publishLike(onlineAccounts[0], post)
        }
    }

    private fun publishLike(account: Account, post: Post) {
        xmppConnectionService.publishComment(
            account,
            post.commentsNode,
            "♥",
            object : XmppConnectionService.OnPostPublished {
                override fun onPostPublished() {
                    runOnUiThread { loadCommentsAndLikes(post) }
                }

                override fun onPostPublishFailed() {
                    runOnUiThread {
                        Toast.makeText(this@PostsActivity, R.string.error_liking_post, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            },
        )
    }

    private fun retractLike(account: Account, like: Comment, post: Post) {
        try {
            val uri = XmppUri(post.commentsNode ?: throw NullPointerException())
            val jid = uri.getJid()
            val node = uri.getParameter("node")
            xmppConnectionService.retractPost(
                account,
                jid,
                node,
                like.id,
                object : XmppConnectionService.OnPostRetracted {
                    override fun onPostRetracted(postId: String) {
                        runOnUiThread { loadCommentsAndLikes(post) }
                    }

                    override fun onPostRetractionFailed() {
                        runOnUiThread {
                            Toast.makeText(
                                    this@PostsActivity,
                                    R.string.error_removing_like,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "error retracting like", e)
            runOnUiThread {
                Toast.makeText(this, R.string.error_removing_like, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** A comment's retract button, with the adapter's confirmation. */
    private fun confirmRetractComment(post: Post, comment: Comment) {
        val authorJid = comment.author ?: return
        val postAuthorJid = post.author ?: return
        val postAuthorAccount = AccountRegistry.get().findAccountByJid(postAuthorJid)
        val commentAuthorAccount = AccountRegistry.get().findAccountByJid(authorJid)
        val iAmCommentAuthor = commentAuthorAccount != null && commentAuthorAccount.isOnlineAndConnected()
        val accountToSendFrom = if (iAmCommentAuthor) commentAuthorAccount else postAuthorAccount
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.retract_comment)
            .setMessage(R.string.retract_comment_confirm)
            .setPositiveButton(R.string.retract) { _, _ -> retractComment(accountToSendFrom, post, comment) }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .show()
    }

    private fun retractComment(account: Account?, post: Post, comment: Comment) {
        try {
            val uri = XmppUri(post.commentsNode ?: throw NullPointerException())
            val jid = uri.getJid()
            val node = uri.getParameter("node")
            xmppConnectionService.retractPost(
                account,
                jid,
                node,
                comment.id,
                object : XmppConnectionService.OnPostRetracted {
                    override fun onPostRetracted(postId: String) {
                        runOnUiThread {
                            comments[post]?.removeAll { it.id == postId }
                            render()
                        }
                    }

                    override fun onPostRetractionFailed() {
                        runOnUiThread {
                            Toast.makeText(
                                    this@PostsActivity,
                                    R.string.error_retracting_comment,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "error retracting comment", e)
            runOnUiThread {
                Toast.makeText(this, R.string.error_retracting_comment, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -- the post's own actions -------------------------------------------------------------------

    /** The comment button: answer the post, choosing an account when several could write it. */
    private fun replyToPost(post: Post) {
        if (post.author == null) {
            return
        }
        val postAccounts = accountsFor(post)
        if (postAccounts.size == 1) {
            replyToPost(postAccounts[0], post)
        } else {
            showAccountSelection(this, getString(R.string.choose_account_for_reply), postAccounts) {
                replyToPost(it, post)
            }
        }
    }

    private fun replyToPost(account: Account, post: Post) {
        val intent = Intent(this, CreatePostActivity::class.java)
        intent.putExtra("in_reply_to_id", post.id)
        intent.putExtra("in_reply_to_node", post.commentsNode)
        intent.putExtra("post_id", post.id)
        intent.putExtra("account", account.getUuid())
        intent.putExtra("post_title", post.title)
        intent.putExtra("post_content", post.content)
        postResultLauncher.launch(intent)
    }

    private fun editPost(post: Post) {
        val author = post.author ?: return
        val ownAccount = AccountRegistry.get().findAccountByJid(author.asBareJid()) ?: return
        val intent = Intent(this, CreatePostActivity::class.java)
        intent.putExtra("post_id", post.id)
        intent.putExtra("title", post.title)
        intent.putExtra("content", post.content)
        intent.putExtra("account", ownAccount.getUuid())
        val attachmentUrl = post.attachmentUrl
        if (attachmentUrl != null) {
            intent.putExtra("attachment_url", attachmentUrl)
            intent.putExtra("attachment_type", post.attachmentType)
        }
        val linkUrl = post.linkUrl
        if (linkUrl != null) {
            intent.putExtra("link_url", linkUrl)
        }
        postResultLauncher.launch(intent)
    }

    private fun share(post: Post) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.setType("text/plain")
        intent.putExtra(Intent.EXTRA_TEXT, post.title + "\n" + post.content)
        startActivity(
            Intent.createChooser(intent, getString(R.string.share_post_with)),
        )
    }

    /** The delete button: retract the post from its own account, after the adapter's confirmation. */
    private fun confirmRetractPost(post: Post) {
        val author = post.author ?: return
        val ownAccount = AccountRegistry.get().findAccountByJid(author.asBareJid()) ?: return
        val service = xmppConnectionService ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.retract_post)
            .setMessage(R.string.retract_post_confirm)
            .setPositiveButton(R.string.retract) { _, _ ->
                service.retractPost(
                    ownAccount,
                    Namespace.MICROBLOG,
                    post.id,
                    object : XmppConnectionService.OnPostRetracted {
                        override fun onPostRetracted(postId: String) {
                            runOnUiThread {
                                DatabaseBackend.get().deletePost(postId)
                                allPosts.removeAll { p -> p.id == postId }
                                filterAndDisplayPosts(mCurrentQuery)
                            }
                        }

                        override fun onPostRetractionFailed() {
                            runOnUiThread {
                                Toast.makeText(
                                        this@PostsActivity,
                                        R.string.error_retract_post,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    },
                )
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .show()
    }

    /** The download row: fetch the attachment, choosing an account when several could. */
    private fun download(post: Post) {
        val postAccounts = accountsFor(post)
        if (postAccounts.size == 1) {
            downloadAttachment(postAccounts[0], post)
        } else {
            showAccountSelection(this, getString(R.string.choose_account_for_download), postAccounts) {
                downloadAttachment(it, post)
            }
        }
    }

    private fun downloadAttachment(account: Account, post: Post) {
        val service = xmppConnectionService ?: return
        val message = Message(StubConversation(account, "", null, 0), null, Message.ENCRYPTION_NONE)
        message.setType(Message.TYPE_FILE)
        val params = Message.FileParams()
        params.url = post.attachmentUrl
        message.setFileParams(params)

        service.getHttpConnectionManager().createNewDownloadConnection(message, false) {
            service.copyAttachmentToDownloadsFolder(
                message,
                object : UiCallback<Int> {
                    override fun success(obj: Int) {
                        runOnUiThread {
                            Toast.makeText(
                                    this@PostsActivity,
                                    R.string.save_to_downloads_success,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }

                    override fun error(errorCode: Int, obj: Int?) {
                        runOnUiThread {
                            Toast.makeText(this@PostsActivity, errorCode, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun userInputRequired(pi: PendingIntent?, obj: Int) {}
                },
            )
        }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    // -- navigation -------------------------------------------------------------------------------

    private fun toggleSuggestionsVisibility() {
        mSuggestionsVisible = !mSuggestionsVisible
        getPreferences(Context.MODE_PRIVATE)
            .edit()
            .putBoolean("suggestions_visible", mSuggestionsVisible)
            .apply()
        render()
    }

    /** The follow button's one effect, the deleted adapter's own. */
    private fun follow(contact: Contact) {
        val service = xmppConnectionService ?: return
        val account = contact.getAccount()
        service.subscribeTo(account, contact.getJid(), Namespace.MICROBLOG) { packet ->
            runOnUiThread {
                if (packet.getType() == uk.xa0.tulkki.xmpp.models.stanza.Iq.Type.RESULT) {
                    contact.setFollowed(true)
                    service.updateContact(contact)
                    mFollowSuggestions.remove(contact)
                    loadPosts()
                } else {
                    Toast.makeText(this, R.string.error_subscribing_to_feed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** A post author's name or avatar: the owner's own account, or a contact's details. */
    private fun openAuthor(post: Post) {
        val author = post.author ?: return
        val ownAccount = AccountRegistry.get().findAccountByJid(author.asBareJid())
        var displayAccount = ownAccount
        if (displayAccount == null) {
            val postAccounts = accountsFor(post)
            if (postAccounts.size > 0) {
                displayAccount = postAccounts[0]
            }
        }
        if (displayAccount == null) {
            displayAccount = AccountUtils.getFirstEnabled(AccountRegistry.get().getAccounts()) as Account?
        }
        if (displayAccount == null) {
            return
        }
        if (author.asBareJid() == displayAccount.getJid().asBareJid()) {
            switchToAccount(displayAccount)
        } else {
            switchToContactDetails(displayAccount.getRoster().getContact(author))
        }
    }

    /** A comment author's name or avatar: the same two destinations. */
    private fun openCommentAuthor(post: Post, comment: Comment) {
        val authorJid = comment.author ?: return
        val postAuthorAccount =
            post.author?.let { AccountRegistry.get().findAccountByJid(it) }
        val contextAccount: Account? =
            postAuthorAccount ?: AccountUtils.getFirstEnabled(AccountRegistry.get().getAccounts()) as Account?
        if (contextAccount == null) {
            return
        }
        if (authorJid.asBareJid() == contextAccount.getJid().asBareJid()) {
            switchToAccount(contextAccount)
        } else {
            switchToContactDetails(contextAccount.getRoster().getContact(authorJid))
        }
    }

    private fun toggleSearch() {
        if (searchActive) {
            searchActive = false
            filterAndDisplayPosts("")
        } else {
            searchActive = true
            render()
        }
    }

    private fun openChats() {
        startActivity(Intent(applicationContext, ConversationListActivity::class.java))
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    private fun openStories() {
        val i = Intent(applicationContext, StoriesActivity::class.java)
        i.putExtra("show_nav_bar", true)
        startActivity(i)
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    private fun openCalls() {
        val i = Intent(applicationContext, CallsActivity::class.java)
        i.putExtra("show_nav_bar", true)
        startActivity(i)
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null) {
            val postUuid = intent.getStringExtra("post_uuid")
            if (postUuid != null) {
                // This is a rough search. A better way would be to scroll to the item
                filterAndDisplayPosts(postUuid)
            }
            val hashtag = intent.getStringExtra("hashtag")
            if (hashtag != null) {
                onSearchPerformed(hashtag)
            }
        }
    }

    /** The screen's gestures, each one the listener the deleted row attached. */
    private inner class Feed : PostsEvents {

        override fun onTogglePost(post: Post) {
            if (expandedPosts.contains(post)) {
                expandedPosts.remove(post)
                comments.remove(post)
                likes.remove(post)
            } else {
                expandedPosts.add(post)
                loadCommentsAndLikes(post)
            }
            render()
        }

        override fun onToggleSuggestions() {
            toggleSuggestionsVisibility()
        }

        override fun onFollowSuggestion(contact: Contact) {
            follow(contact)
        }

        override fun onContactClicked(contact: Contact) {
            switchToContactDetails(contact)
        }

        override fun onAuthorClicked(post: Post) {
            openAuthor(post)
        }

        override fun onCommentAuthorClicked(post: Post, comment: Comment) {
            openCommentAuthor(post, comment)
        }

        override fun onCommentRetract(post: Post, comment: Comment) {
            confirmRetractComment(post, comment)
        }

        override fun onLike(post: Post) {
            toggleLike(post)
        }

        override fun onLikesList(post: Post) {
            showLikes(this@PostsActivity, likes[post].orEmpty())
        }

        override fun onReply(post: Post) {
            replyToPost(post)
        }

        override fun onShare(post: Post) {
            share(post)
        }

        override fun onEdit(post: Post) {
            editPost(post)
        }

        override fun onDelete(post: Post) {
            confirmRetractPost(post)
        }

        override fun onDownload(post: Post) {
            download(post)
        }

        override fun onImagePreview(post: Post) {
            showImagePreview(this@PostsActivity, post.attachmentUrl)
        }

        override fun onVideoPreview(post: Post) {
            showVideoPreview(this@PostsActivity, post.attachmentUrl)
        }

        override fun onHashtag(hashtag: String) {
            onSearchPerformed(hashtag)
        }

        override fun onRefresh() {
            // The bar's `action_refresh`: drop the cached posts, read again, and refetch the
            // comments of whatever is still unfolded - which is what the deleted adapter's `bind`
            // did for an expanded row after a refresh.
            DatabaseBackend.get().clearPosts()
            postList.clear()
            render()
            loadPosts()
            for (post in expandedPosts.toList()) {
                loadCommentsAndLikes(post)
            }
        }

        override fun onNewPost() {
            val intent = Intent(this@PostsActivity, CreatePostActivity::class.java)
            postResultLauncher.launch(intent)
        }

        override fun onSearchToggle() {
            toggleSearch()
        }

        override fun onQueryChange(query: String) {
            filterAndDisplayPosts(query)
        }

        override fun onNavChats() {
            openChats()
        }

        override fun onNavStories() {
            openStories()
        }

        override fun onNavCalls() {
            openCalls()
        }
    }

    companion object {

        /** The order the deleted `CommentsAdapter` sorted its list into: oldest first, nulls first. */
        private val COMMENT_ORDER =
            Comparator<Comment> { c1, c2 ->
                val d1 = c1.published
                val d2 = c2.published
                when {
                    d1 == null && d2 == null -> 0
                    d1 == null -> -1
                    d2 == null -> 1
                    else -> d1.compareTo(d2)
                }
            }
    }
}
