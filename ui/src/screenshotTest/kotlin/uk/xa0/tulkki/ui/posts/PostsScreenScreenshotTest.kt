package uk.xa0.tulkki.ui.posts

import android.content.res.Configuration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.util.Date
import uk.xa0.tulkki.data.model.Comment
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The posts family's four cells: the feed and the composer, each in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the other
 * converted screens use).
 *
 * <p>**What the feed's two cells pin.** A card whose author line, title, three-line summary and
 * like/comment/forward row are the deleted `item_post.xml`'s; the comment rows under it, which are
 * their own `LazyColumn` items now; the second post folded; the four bottom-bar destinations with
 * their badges; and the compose button. The avatars are the placeholder the XML drew when no account
 * could be resolved - a cell cannot construct a live [uk.xa0.tulkki.data.model.Account], and the
 * suggestion strip and a real avatar are the parts of the screen a preview cannot build.
 *
 * <p>**What the composer's two cells pin.** The reply preview's absence, the three fields with their
 * labels, the attachment buttons and the publish button - the form an edit shows. The account picker
 * is not in them for the same reason the avatars are not: its items are [uk.xa0.tulkki.data.model.Account]s.
 *
 * <p>The fixtures are [PostsState]/[CreatePostState] values, which is what the screens are handed, so
 * a reference here is a picture of the state and not of a hand-written row that could drift.
 */
@PreviewTest
@Preview(name = "posts-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun PostsDarkScreenshot() = Feed(darkTheme = true)

@PreviewTest
@Preview(name = "posts-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun PostsLightScreenshot() = Feed(darkTheme = false)

@PreviewTest
@Preview(name = "create-post-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun CreatePostDarkScreenshot() = Composer(darkTheme = true)

@PreviewTest
@Preview(name = "create-post-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun CreatePostLightScreenshot() = Composer(darkTheme = false)

/**
 * The feed where production composes it: inside the chrome, because the screen draws no background
 * of its own and the `Scaffold` paints it.
 */
@Composable
private fun Feed(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.feeds),
            onUp = {},
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24dp),
                        contentDescription = stringResource(R.string.search),
                    )
                }
            },
            menu = listOf(ChromeMenuItem(stringResource(R.string.refresh)) {}),
        ) {
            PostsScreen(state = feedState(), events = QuietEvents)
        }
    }
}

/** The composer inside the same chrome: an edit, with its fields and its bottom row. */
@Composable
private fun Composer(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.create_post),
            onUp = {},
        ) {
            CreatePostScreen(
                state =
                    CreatePostState(
                        replyMode = false,
                        accountVisible = false,
                        initialTitle = "A post about Finnish",
                        initialContent = "What I learned today: the #puhekieli in this one is explained in its sentence.",
                        initialLink = "https://example.org/notes",
                    ),
                events = QuietForm,
            )
        }
    }
}

private fun feedState(): PostsState {
    val first =
        Post(
            "post-1",
            "A post about Finnish",
            "What I learned today: the #puhekieli in this one is explained in its sentence. See https://example.org/notes for the rest.",
            Jid.of("alice@example.org"),
            Date(1_700_000_000_000L),
            null,
            null,
            null,
            null,
        )
    val second =
        Post(
            "post-2",
            "Second post",
            "A short one.",
            Jid.of("bob@example.org"),
            Date(1_700_000_100_000L),
            null,
            null,
            null,
            null,
        )
    val firstComment =
        Comment(
            "comment-1",
            "That is a good example.",
            Jid.of("bob@example.org"),
            Date(1_700_000_050_000L),
        )
    val secondComment =
        Comment(
            "comment-2",
            "I did not know that.",
            Jid.of("carol@example.org"),
            Date(1_700_000_060_000L),
        )
    return PostsState(
        items =
            listOf(
                PostsListItem.PostItem(
                    PostRow(
                        post = first,
                        expanded = true,
                        title = first.title,
                        content = first.content,
                        timestamp = "14.11.2023 22:13",
                        authorName = "Alice",
                        authorAvatar = null,
                        hasAttachment = false,
                        attachmentUrl = null,
                        attachmentIsImage = false,
                        attachmentIsVideo = false,
                        linkUrl = null,
                        actionsVisible = true,
                        editVisible = false,
                        authorKnown = true,
                        likeCount = "2",
                        likeEnabled = true,
                        likedByMe = true,
                    ),
                ),
                PostsListItem.CommentItem(first, commentRow(firstComment, "Bob", "14.11.2023 22:14")),
                PostsListItem.CommentItem(first, commentRow(secondComment, "Carol", "14.11.2023 22:14")),
                PostsListItem.PostItem(
                    PostRow(
                        post = second,
                        expanded = false,
                        title = second.title,
                        content = second.content,
                        timestamp = "14.11.2023 22:15",
                        authorName = "Bob",
                        authorAvatar = null,
                        hasAttachment = false,
                        attachmentUrl = null,
                        attachmentIsImage = false,
                        attachmentIsVideo = false,
                        linkUrl = null,
                        actionsVisible = true,
                        editVisible = false,
                        authorKnown = true,
                        likeCount = "",
                        likeEnabled = false,
                        likedByMe = false,
                    ),
                ),
            ),
        suggestions = emptyList(),
        suggestionsVisible = true,
        searchActive = false,
        query = "",
        navVisible = true,
        badges = PostsBadges(unreadChats = 3, newStories = true, newPosts = true, missedCalls = false),
    )
}

private fun commentRow(comment: Comment, name: String, timestamp: String): CommentRow =
    CommentRow(
        comment = comment,
        authorName = name,
        authorAvatar = null,
        content = comment.title,
        timestamp = timestamp,
        retractVisible = false,
    )

/** A feed that does nothing: a reference is a picture of the rows, not of an effect being performed. */
private val QuietEvents =
    object : PostsEvents {
        override fun onTogglePost(post: Post) = Unit

        override fun onToggleSuggestions() = Unit

        override fun onFollowSuggestion(contact: Contact) = Unit

        override fun onContactClicked(contact: Contact) = Unit

        override fun onAuthorClicked(post: Post) = Unit

        override fun onCommentAuthorClicked(post: Post, comment: Comment) = Unit

        override fun onCommentRetract(post: Post, comment: Comment) = Unit

        override fun onLike(post: Post) = Unit

        override fun onLikesList(post: Post) = Unit

        override fun onReply(post: Post) = Unit

        override fun onShare(post: Post) = Unit

        override fun onEdit(post: Post) = Unit

        override fun onDelete(post: Post) = Unit

        override fun onDownload(post: Post) = Unit

        override fun onImagePreview(post: Post) = Unit

        override fun onVideoPreview(post: Post) = Unit

        override fun onHashtag(hashtag: String) = Unit

        override fun onRefresh() = Unit

        override fun onNewPost() = Unit

        override fun onSearchToggle() = Unit

        override fun onQueryChange(query: String) = Unit

        override fun onNavChats() = Unit

        override fun onNavStories() = Unit

        override fun onNavCalls() = Unit
    }

/** A composer that does nothing, for the same reason. */
private val QuietForm =
    object : CreatePostEvents {
        override fun onAccountSelected(position: Int) = Unit

        override fun onAttachFile() = Unit

        override fun onAttachImage() = Unit

        override fun onAttachVideo() = Unit

        override fun onPublish(title: String, content: String, linkUrl: String) = Unit
    }
