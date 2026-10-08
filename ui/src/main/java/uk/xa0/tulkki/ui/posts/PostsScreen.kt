package uk.xa0.tulkki.ui.posts

import android.graphics.drawable.Drawable
import android.util.Patterns
import android.util.TypedValue
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.util.regex.Pattern
import uk.xa0.tulkki.data.model.Comment
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.widget.AvatarView

/**
 * The feed screen: the posts the last read found, the follow suggestions above them, the search
 * field the bar's search item opens, and the bottom bar's four destinations.
 *
 * <p>**What it replaces.** `activity_posts.xml`, `item_post.xml` and `item_comment.xml` are gone,
 * and so are the three adapters that inflated them
 * ([uk.xa0.tulkki.ui.adapter.PostsAdapter], [uk.xa0.tulkki.ui.adapter.CommentsAdapter],
 * [uk.xa0.tulkki.ui.adapter.FollowSuggestionAdapter]). The bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]): the XML `SearchView` item is the search action in the
 * bar's own `actions` slot, and the `Refresh` item is its overflow, which is where the XML menu's
 * `showAsAction="never"` item already was.
 *
 * <p>**One LazyColumn, and the comments are items in it.** The old row held a nested `RecyclerView`
 * of comments, which a `LazyColumn` cannot nest; so the host hands this screen one flat
 * [PostsListItem] list - a post, then its comments while it is expanded - and the post card and each
 * comment are separate items. A comment is therefore as cheap to scroll past as a post is, and a
 * post with fifty comments is not one fifty-row item.
 *
 * <p>**What it does not draw.** Nothing is invented: the row keeps the card, the author line, the
 * three-line summary, the attachment hint, the 150 dp attachment, the download row, the link row,
 * the like/share/comment row and the timestamp, all from the deleted XML. The one visible liberty
 * is that the comments no longer sit *inside* the post's card - they follow it as their own rows,
 * because they are their own items now. The one invisible liberty is that the expanded body is not
 * selectable (`textIsSelectable` needed a view); its links are still links.
 *
 * <p>The screen names strings and drawables only through resources, and takes its title, its up
 * arrow, its search action and its refresh item from the Activity that hosts it - the split the
 * chrome already makes.
 *
 * @param state what the last read found
 * @param events what a gesture means; the host performs it and writes a new [state] back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(
    state: PostsState,
    events: PostsEvents,
    modifier: Modifier = Modifier,
) {
    val navBarHeight = dimensionResource(R.dimen.nav_bar_height)
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = if (state.navVisible) navBarHeight else 0.dp)) {
            if (state.searchActive) {
                SearchField(query = state.query, onQueryChange = { events.onQueryChange(it) })
            }
            if (state.suggestions.isNotEmpty()) {
                SuggestionsHeader(
                    visible = state.suggestionsVisible,
                    onToggle = { events.onToggleSuggestions() },
                )
                if (state.suggestionsVisible) {
                    SuggestionsRow(state.suggestions, events)
                }
            }
            PullToRefreshBox(
                false,
                { events.onRefresh() },
                Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.items, key = { it.key }) { item ->
                        when (item) {
                            is PostsListItem.PostItem -> PostCard(item.row, events)
                            is PostsListItem.CommentItem -> CommentEntry(item.post, item.row, events)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { events.onNewPost() },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp + if (state.navVisible) navBarHeight else 0.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_edit_24dp),
                contentDescription = stringResource(R.string.create_post),
            )
        }
        if (state.navVisible) {
            PostsNavBar(
                badges = state.badges,
                events = events,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Everything the feed screen draws, assembled by the host after a read. */
data class PostsState(
    /** The flat list: each post, then its comments while it is expanded. */
    val items: List<PostsListItem> = emptyList(),
    /** The roster contacts the owner could follow. */
    val suggestions: List<Contact> = emptyList(),
    /** Whether the suggestion strip is unfolded. */
    val suggestionsVisible: Boolean = true,
    /** Whether the search field is up: the bar's search action opened it. */
    val searchActive: Boolean = false,
    /** The text the search field filters on. */
    val query: String = "",
    /** Whether the bottom bar is shown at all (`show_nav_bar` and the intent's own extra). */
    val navVisible: Boolean = false,
    /** The four bottom-bar destinations' badges. */
    val badges: PostsBadges = PostsBadges(),
)

/** The bottom bar's badges, read off the service by the host. */
data class PostsBadges(
    /** The unread-message count drawn on the chats destination, `0` for none. */
    val unreadChats: Int = 0,
    /** Whether any story was published since the owner last read one. */
    val newStories: Boolean = false,
    /** Whether any post was published since the owner last opened the feed. */
    val newPosts: Boolean = false,
    /** Whether any call was missed since the owner last looked. */
    val missedCalls: Boolean = false,
)

/** One item of the feed's flat list. */
sealed interface PostsListItem {

    /** A stable key for `LazyColumn`, unique across posts and comments. */
    val key: String

    /** One post's card. */
    data class PostItem(val row: PostRow) : PostsListItem {
        override val key: String get() = "post:" + (row.post.id ?: row.post.hashCode().toString())
    }

    /** One comment, while its post is expanded. */
    data class CommentItem(val post: Post, val row: CommentRow) : PostsListItem {
        override val key: String
            get() = "comment:" + (row.comment.id ?: row.comment.hashCode().toString()) + ":" +
                (post.id ?: post.hashCode().toString())
    }
}

/** One post, with every fact the card draws already decided by the host. */
data class PostRow(
    val post: Post,
    /** Whether the card is unfolded: summary vs body, actions, comments. */
    val expanded: Boolean,
    val title: String?,
    /** The post's body, already through Markwon, or `null` when it has none. */
    val content: String?,
    val timestamp: String?,
    val authorName: String?,
    /** The thing whose avatar is drawn, or `null` for the person placeholder. */
    val authorAvatar: Avatarable?,
    /** Whether the post carries any attachment at all. */
    val hasAttachment: Boolean,
    val attachmentUrl: String?,
    val attachmentIsImage: Boolean,
    val attachmentIsVideo: Boolean,
    /** The link the post carries, drawn only when expanded. */
    val linkUrl: String?,
    /** Whether the service was there to act on the post at all. */
    val actionsVisible: Boolean,
    /** Whether this install wrote the post and may edit or retract it. */
    val editVisible: Boolean,
    /** Whether the post names an author, which is what the comment row needs. */
    val authorKnown: Boolean,
    /** The loaded likes, for the count and the long-press list. */
    val likeCount: String,
    val likeEnabled: Boolean,
    val likedByMe: Boolean,
)

/** One comment, with every fact its row draws already decided by the host. */
data class CommentRow(
    val comment: Comment,
    val authorName: String?,
    val authorAvatar: Avatarable?,
    val content: String?,
    val timestamp: String?,
    /** Whether this install may retract the comment. */
    val retractVisible: Boolean,
)

/**
 * What the feed screen's gestures mean. The host is the only implementor, and every method there
 * does what the deleted adapter's listener did - nothing new is reachable from the screen.
 */
interface PostsEvents {

    /** A tap on the card: fold or unfold it, fetching the comments when it opens. */
    fun onTogglePost(post: Post)

    /** The suggestions header's chevron. */
    fun onToggleSuggestions()

    /** The follow button on one suggestion. */
    fun onFollowSuggestion(contact: Contact)

    /** A suggestion's name or avatar: the contact's details. */
    fun onContactClicked(contact: Contact)

    /** The author's name or avatar: the owner's own account, or a contact's details. */
    fun onAuthorClicked(post: Post)

    /** A comment author's name or avatar. */
    fun onCommentAuthorClicked(post: Post, comment: Comment)

    /** A comment's retract button. */
    fun onCommentRetract(post: Post, comment: Comment)

    /** The like button: like the post, or take the owner's like back. */
    fun onLike(post: Post)

    /** A long press on the like button: who liked the post. */
    fun onLikesList(post: Post)

    /** The comment button: answer the post. */
    fun onReply(post: Post)

    /** The forward button: share the post's text out of the app. */
    fun onShare(post: Post)

    /** The edit button. */
    fun onEdit(post: Post)

    /** The delete button: retract the post, after confirmation. */
    fun onDelete(post: Post)

    /** The download row on an attachment that is neither image nor video. */
    fun onDownload(post: Post)

    /** A tap on an image or video attachment. */
    fun onImagePreview(post: Post)

    /** A tap on a video attachment. */
    fun onVideoPreview(post: Post)

    /** A tap on a hashtag in the expanded body: search for it. */
    fun onHashtag(hashtag: String)

    /** The pull-to-refresh gesture and the bar's refresh item. */
    fun onRefresh()

    /** The compose button. */
    fun onNewPost()

    /** The bar's search action: open the search field, or close it and clear the query. */
    fun onSearchToggle()

    /** The search field's text, on every keystroke. */
    fun onQueryChange(query: String)

    /** The bottom bar's chats destination. */
    fun onNavChats()

    /** The bottom bar's stories destination. */
    fun onNavStories()

    /** The bottom bar's calls destination. */
    fun onNavCalls()
}

/** The search field the bar's search action opens, with the field's own clear button. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear_24dp),
                            contentDescription = stringResource(uk.xa0.tulkki.data.R.string.cancel),
                        )
                    }
                }
            },
        )
    }
}

/** `follow_suggestions` and the chevron that folds the strip away. */
@Composable
private fun SuggestionsHeader(visible: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.follow_suggestions),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onToggle) {
            Icon(
                painter = painterResource(R.drawable.outline_keyboard_arrow_down_24),
                contentDescription = stringResource(R.string.toggle_suggestions),
                modifier = Modifier.rotate(if (visible) 180f else 0f),
            )
        }
    }
}

/** The suggestion strip: one contact each, with the follow button. */
@Composable
private fun SuggestionsRow(suggestions: List<Contact>, events: PostsEvents) {
    LazyRow(modifier = Modifier.fillMaxWidth()) {
        items(items = suggestions, key = { it.getJid().toString() }) { contact ->
            Column(
                modifier = Modifier.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PostsAvatar(
                    avatarable = contact,
                    loadSize = R.dimen.feed_suggestions_avatar_size,
                    size = dimensionResource(R.dimen.feed_suggestions_avatar_size),
                    onClick = { events.onContactClicked(contact) },
                )
                Text(
                    text = contact.getDisplayName(),
                    modifier = Modifier.padding(top = 4.dp).width(70.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { events.onFollowSuggestion(contact) }) {
                    Text(stringResource(R.string.follow))
                }
            }
        }
    }
}

/** One post's card. */
@Composable
private fun PostCard(row: PostRow, events: PostsEvents) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clickable { events.onTogglePost(row.post) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                PostsAvatar(
                    avatarable = row.authorAvatar,
                    loadSize = R.dimen.bubble_avatar_size,
                    size = 40.dp,
                    onClick = { events.onAuthorClicked(row.post) },
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.authorName.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    if (row.timestamp != null) {
                        Text(
                            text = row.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.editVisible) {
                    IconButton(onClick = { events.onEdit(row.post) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit_24dp),
                            contentDescription = stringResource(R.string.edit),
                        )
                    }
                    IconButton(onClick = { events.onDelete(row.post) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = stringResource(R.string.retract_post),
                        )
                    }
                }
            }
            if (row.title != null || (row.hasAttachment && !row.expanded)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.title.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (row.hasAttachment && !row.expanded) {
                        Icon(
                            painter = painterResource(R.drawable.ic_attach_file_24dp),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (row.content != null) {
                if (row.expanded) {
                    Text(
                        text = postsLinkedText(row.content, events::onHashtag),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    Text(
                        text = row.content,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.expanded && row.hasAttachment) {
                when {
                    row.attachmentIsImage || row.attachmentIsVideo ->
                        Attachment(
                            url = row.attachmentUrl,
                            video = row.attachmentIsVideo,
                            onOpen = {
                                if (row.attachmentIsVideo) {
                                    events.onVideoPreview(row.post)
                                } else {
                                    events.onImagePreview(row.post)
                                }
                            },
                        )
                    else ->
                        TextButton(onClick = { events.onDownload(row.post) }) {
                            Text(stringResource(R.string.download_file))
                        }
                }
            }
            if (row.expanded && row.linkUrl != null) {
                Text(
                    text = postsLinkedText(row.linkUrl, onHashtag = null),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (row.expanded && row.actionsVisible) {
                PostActions(row, events)
            }
        }
    }
}

/** The like count, the comment button and the forward button, on an unfolded card. */
@Composable
private fun PostActions(row: PostRow, events: PostsEvents) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .combinedClickable(
                        enabled = row.likeEnabled,
                        onClick = { events.onLike(row.post) },
                        onLongClick = { events.onLikesList(row.post) },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (row.likedByMe) R.drawable.favorite_filled_24 else R.drawable.favorite_border_24,
                    ),
                contentDescription =
                    stringResource(if (row.likedByMe) R.string.unlike else R.string.like),
            )
        }
        Text(
            text = row.likeCount,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { events.onReply(row.post) }) {
            Text(stringResource(R.string.comment))
        }
        TextButton(onClick = { events.onShare(row.post) }) {
            Text(stringResource(R.string.forward))
        }
    }
}

/** One comment, as its own list item under its post's card. */
@Composable
private fun CommentEntry(post: Post, row: CommentRow, events: PostsEvents) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        PostsAvatar(
            avatarable = row.authorAvatar,
            loadSize = R.dimen.posts_comments_avatar_size,
            size = 32.dp,
            onClick = { events.onCommentAuthorClicked(post, row.comment) },
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.authorName.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (row.retractVisible) {
                    IconButton(onClick = { events.onCommentRetract(post, row.comment) }) {
                        Icon(
                            painter = painterResource(R.drawable.delete_18dp),
                            contentDescription = stringResource(R.string.retract_comment),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (row.timestamp != null) {
                Text(
                    text = row.timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (row.content != null) {
                Text(
                    text = postsLinkedText(row.content, onHashtag = null),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * An image or a video attachment, at the deleted `item_post.xml`'s 150 dp and centred. The image
 * itself is still an [ImageView] the tree's own Glide loads, because this module has no Compose
 * image loader and inventing one is not this screen's job; the progress ring is the XML
 * `attachment_progress` and the play badge is `video_overlay_icon`.
 */
@Composable
private fun Attachment(url: String?, video: Boolean, onOpen: () -> Unit) {
    var loading by remember(url) { mutableStateOf(url != null) }
    Box(
        modifier = Modifier.fillMaxWidth().height(150.dp).clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        key(url) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        if (url != null) {
                            Glide.with(this)
                                .load(url)
                                .listener(
                                    object : RequestListener<Drawable> {
                                        override fun onLoadFailed(
                                            e: GlideException?,
                                            model: Any?,
                                            target: Target<Drawable>,
                                            isFirstResource: Boolean,
                                        ): Boolean {
                                            loading = false
                                            return false
                                        }

                                        override fun onResourceReady(
                                            resource: Drawable,
                                            model: Any,
                                            target: Target<Drawable>,
                                            dataSource: DataSource,
                                            isFirstResource: Boolean,
                                        ): Boolean {
                                            loading = false
                                            return false
                                        }
                                    },
                                )
                                .into(this)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (video) {
            Icon(
                painter = painterResource(R.drawable.ic_play_circle_24dp),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        }
        if (loading) {
            CircularProgressIndicator()
        }
    }
}

/** The avatar as the XML drew it: an [AvatarView] in an [AndroidView], loaded the way it always was. */
@Composable
private fun PostsAvatar(
    avatarable: Avatarable?,
    @DimenRes loadSize: Int,
    size: Dp,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (avatarable == null) {
            // The XML's own `ic_person_24dp`, drawn without a hosted view - which is also what keeps
            // a screenshot cell free of an `AndroidView`.
            Icon(
                painter = painterResource(R.drawable.ic_person_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AndroidView(
                factory = { context ->
                    AvatarView(context).also {
                        AvatarWorkerTask.loadAvatar(avatarable, it, loadSize)
                    }
                },
                update = { view -> AvatarWorkerTask.loadAvatar(avatarable, view, loadSize) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (onClick != null) {
            Box(modifier = Modifier.fillMaxSize().clickable(onClick = onClick))
        }
    }
}

/**
 * The bottom bar: the same four destinations, in the same order, as
 * `bottom_navigation_menu_feeds.xml`, with the same selected tab (feeds) and the same badges the
 * Activity put on the view. Its icons are the theme's own four attributes, resolved here so the
 * night theme keeps its own graphics.
 */
@Composable
private fun PostsNavBar(badges: PostsBadges, events: PostsEvents, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier, containerColor = Color.Transparent) {
        NavigationBarItem(
            selected = false,
            onClick = { events.onNavChats() },
            icon = {
                NavIcon(
                    R.string.chats,
                    R.attr.ic_chat_unselected,
                    R.drawable.outline_chat_black_24,
                    count = badges.unreadChats,
                )
            },
            label = { Text(stringResource(R.string.chats)) },
        )
        NavigationBarItem(
            selected = false,
            onClick = { events.onNavCalls() },
            icon = {
                NavIcon(
                    R.string.calls,
                    R.attr.ic_calls_unselected,
                    R.drawable.calls_unselected_black_24dp,
                    dot = badges.missedCalls,
                )
            },
            label = { Text(stringResource(R.string.calls)) },
        )
        NavigationBarItem(
            selected = false,
            onClick = { events.onNavStories() },
            icon = {
                NavIcon(
                    R.string.stories,
                    R.attr.ic_stories_unselected,
                    R.drawable.stories_unselected_black_24,
                    dot = badges.newStories,
                )
            },
            label = { Text(stringResource(R.string.stories)) },
        )
        NavigationBarItem(
            selected = true,
            onClick = {},
            icon = {
                NavIcon(
                    R.string.feeds,
                    R.attr.feed_selected,
                    R.drawable.feed_selected_black_24dp,
                    dot = badges.newPosts,
                )
            },
            label = { Text(stringResource(R.string.feeds)) },
        )
    }
}

/** One destination's icon: a number badge when it has a count, a dot when it has a flag. */
@Composable
private fun NavIcon(
    @StringRes label: Int,
    @AttrRes attribute: Int,
    @DrawableRes fallback: Int,
    count: Int = 0,
    dot: Boolean = false,
) {
    val icon = themeDrawableResource(attribute) ?: fallback
    when {
        count > 0 ->
            BadgedBox(badge = { Badge { Text(count.toString()) } }) {
                Icon(painterResource(icon), stringResource(label))
            }
        dot ->
            BadgedBox(badge = { Badge() }) {
                Icon(painterResource(icon), stringResource(label))
            }
        else -> Icon(painterResource(icon), stringResource(label))
    }
}

/** The drawable a theme attribute points at, or `null` when the theme does not set it. */
@Composable
private fun themeDrawableResource(@AttrRes attribute: Int): Int? {
    val context = LocalContext.current
    return remember(attribute, context) {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attribute, value, true)) {
            value.resourceId.takeIf { it != 0 }
        } else {
            null
        }
    }
}

/** The body with its hashtags and web addresses tappable, as the deleted row's spans made them. */
@Composable
private fun postsLinkedText(text: String, onHashtag: ((String) -> Unit)?): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val hashtagClick by rememberUpdatedState(onHashtag)
    return remember(text, linkColor, onHashtag == null) {
        linkedContent(text, linkColor, if (onHashtag == null) null else hashtagClick)
    }
}

/** `#[letters and digits]+`, the pattern the deleted adapter's `ClickableSpan`s used. */
private val HASHTAG: Pattern = Pattern.compile("#[\\p{L}\\p{N}]+")

/**
 * One string with its web addresses - and, when [onHashtag] is not `null`, its hashtags - as
 * [LinkAnnotation]s. It is the deleted `TextView`'s `autoLink="web"` plus the adapter's hashtag
 * spans, in one walk so the two can never overlap.
 */
private fun linkedContent(
    text: String,
    linkColor: Color,
    onHashtag: ((String) -> Unit)?,
): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
    val urls = Patterns.WEB_URL.matcher(text)
    val hashtags = HASHTAG.matcher(text)
    var nextUrl = if (urls.find()) urls.start() else -1
    var nextTag = if (hashtags.find()) hashtags.start() else -1
    return buildAnnotatedString {
        var last = 0
        while (nextUrl >= 0 || nextTag >= 0) {
            if (nextUrl >= 0 && nextUrl < last) {
                nextUrl = if (urls.find()) urls.start() else -1
                continue
            }
            if (nextTag >= 0 && nextTag < last) {
                nextTag = if (hashtags.find()) hashtags.start() else -1
                continue
            }
            if (nextUrl >= 0 && (nextTag < 0 || nextUrl <= nextTag)) {
                val url = urls.group().trimEnd(')', '.', ',', ';')
                append(text, last, nextUrl)
                withLink(LinkAnnotation.Url(url, styles)) { append(url) }
                last = nextUrl + url.length
                nextUrl = if (urls.find()) urls.start() else -1
            } else {
                val tag = hashtags.group()
                append(text, last, nextTag)
                if (onHashtag == null) {
                    append(tag)
                } else {
                    withLink(
                        LinkAnnotation.Clickable(tag = tag, styles = styles) { onHashtag(tag) },
                    ) {
                        append(tag)
                    }
                }
                last = nextTag + tag.length
                nextTag = if (hashtags.find()) hashtags.start() else -1
            }
        }
        append(text, last, text.length)
    }
}

