package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import android.util.Log
import java.text.ParseException
import java.util.Date
import java.util.Objects
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.PostRef

/**
 * One Atom `entry` of a PubSub feed: the `posts` row behind a post in the posts feed.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The eleven `public static final String`s become a companion of `const val`s.** Their readers
 *    are both languages - Kotlin (`schema/RawTables.kt:343-354`) and Java (`DatabaseBackend`, which
 *    spells the FQCN at `:2967-2991`) - and every one of them writes `Post.TABLENAME` and its
 *    siblings. A `const val` in a companion compiles to a static field **on `Post` itself**, so all
 *    of those reads are unchanged and need no `@JvmField`.
 * 2. **[fromElement] and [fromCursor] are companion `@JvmStatic`s.** Both are Java callers'
 *    (`DataStaticsHost:301`, `PostsActivity:297` and `DatabaseBackend:2978`), so without
 *    `@JvmStatic` they would exist only as `Companion.fromElement`/`Companion.fromCursor`.
 *    **Interop debt: two annotations.**
 * 3. **All nine properties are nullable, because Java's are.** `id`, `title` and `content` come
 *    straight from `Element.getAttribute`/`findChildContent`, which answer `null`; `author`,
 *    `published`, `commentsNode`, `attachmentUrl`, `attachmentType` and `linkUrl` are all assigned
 *    `null` on the paths that do not find them, and `fromCursor` puts a cursor's `null` into every
 *    one. A non-null Kotlin `String` would be a claim neither parser makes. They stay `val`s, so the
 *    nine getters `PostsActivity`, `PostsAdapter` and `CommentsAdapter` call are generated - no
 *    `@JvmField` has a creditor. `linkUrl` was a private, never-reassigned Java field, so `val` is
 *    the same surface.
 * 4. **`Objects.hash(id)` stays `Objects.hash(id)`.** Java's single-argument `Objects.hash` is
 *    `Arrays.hashCode(new Object[]{id})`, i.e. `31 + (id == null ? 0 : id.hashCode())` - **not**
 *    `id.hashCode()` - so it is called through `java.util.Objects` rather than replaced by a Kotlin
 *    property hash. `equals` keeps Java's `getClass() != o.getClass()` and its single `id`
 *    comparison; Kotlin's `==` on two nullable `String`s is `Intrinsics.areEqual`, which is
 *    `Objects.equals`.
 * 5. **The string work is Java's, call for call.** `Namespace.ATOM` is the Java constant of
 *    `uk.xa0.tulkki.xml.Namespace` (a class, so Kotlin reads it directly), `"link".equals(x)` is
 *    `"link" == x` (Kotlin's `==` on a `String?` is null-safe and content-comparing, exactly as
 *    Java's constant-receiver `equals` was), `AbstractParser.parseTimestamp` is called through its
 *    class because Kotlin has no static import, and `Log.e`'s concatenation becomes a template. No
 *    `trim()` and no case folding appears in this file, so no `javaTrim` helper is needed.
 * 6. **`values.put(PUBLISHED, ...)` keeps Java's boxed `Long`.** Java's
 *    `published != null ? published.getTime() : 0` widens the `int` to `long` and boxes it;
 *    `published?.time ?: 0L` is that same `Long`, so it selects the same `ContentValues` overload.
 * 7. **The `link` branch keeps Java's `if`/`else if` chain.** A `when` over `rel` would read the
 *    same, but the three arms assign three different locals and the chain is what the diff must show.

 * Nothing here is a field a Java caller reads as a field, and the statics are Java-visible by
 * construction, so the file's interop debt is the two `@JvmStatic`s of clause 2.
 */
class Post(
    val id: String?,
    val title: String?,
    val content: String?,
    val author: Jid?,
    val published: Date?,
    val commentsNode: String?,
    val attachmentUrl: String?,
    val attachmentType: String?,
    val linkUrl: String?,
) : PostRef {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val post = other as Post

        return id == post.id
    }

    override fun hashCode(): Int = Objects.hash(id)

    fun getContentValues(account: Account): ContentValues {
        val values = ContentValues()
        values.put(UUID, id)
        values.put(ACCOUNT_UUID, account.getUuid())
        values.put(AUTHOR_JID, author?.toString())
        values.put(TITLE, title)
        values.put(CONTENT, content)
        values.put(ATTACHMENT_URL, attachmentUrl)
        values.put(ATTACHMENT_TYPE, attachmentType)
        values.put(LINK_URL, linkUrl)
        values.put(PUBLISHED, published?.time ?: 0L)
        values.put(COMMENTS_NODE, commentsNode)
        return values
    }

    companion object {

        const val TABLENAME = "posts"
        const val UUID = "uuid"
        const val ACCOUNT_UUID = "account_uuid"
        const val AUTHOR_JID = "author_jid"
        const val TITLE = "title"
        const val CONTENT = "content"
        const val ATTACHMENT_URL = "attachment_url"
        const val ATTACHMENT_TYPE = "attachment_type"
        const val PUBLISHED = "published"
        const val COMMENTS_NODE = "comments_node"
        const val LINK_URL = "link_url"

        /** Parses one feed item, or `null` when it carries no Atom entry. */
        @JvmStatic
        fun fromElement(item: Element): Post? {
            val entry = item.findChild("entry", Namespace.ATOM) ?: return null
            val id = item.getAttribute("id")
            val title = entry.findChildContent("title")
            val content = entry.findChildContent("content")
            val authorElement = entry.findChild("author")
            var author: Jid? = null
            if (authorElement != null) {
                val uri = authorElement.findChildContent("uri")
                if (uri != null && uri.startsWith("xmpp:")) {
                    try {
                        author = Jid.of(uri.substring(5))
                    } catch (e: IllegalArgumentException) {
                        // ignore: an unparsable author is no author, exactly as in Java
                    }
                }
            }
            var published: Date? = null
            val publishedString = entry.findChildContent("published")
            if (publishedString != null) {
                try {
                    published = Date(AbstractParser.parseTimestamp(publishedString))
                } catch (e: ParseException) {
                    Log.e("Feeds", "Couldn't parse timestamp $publishedString")
                }
            }

            var commentsNode: String? = null
            var attachmentUrl: String? = null
            var attachmentType: String? = null
            var linkUrl: String? = null

            for (child in entry.getChildren()) {
                if ("link" == child.getName() && Namespace.ATOM == child.getNamespace()) {
                    val rel = child.getAttribute("rel")
                    if ("replies" == rel) {
                        commentsNode = child.getAttribute("href")
                    } else if ("enclosure" == rel) {
                        attachmentUrl = child.getAttribute("href")
                        attachmentType = child.getAttribute("type")
                    } else if ("related" == rel) {
                        linkUrl = child.getAttribute("href")
                    }
                }
            }

            return Post(
                id,
                title,
                content,
                author,
                published,
                commentsNode,
                attachmentUrl,
                attachmentType,
                linkUrl
            )
        }

        /** Reads one `posts` row, with the same tolerances Java's own body had. */
        @JvmStatic
        fun fromCursor(cursor: Cursor): Post {
            val uuid = cursor.getString(cursor.getColumnIndex(UUID))
            val authorJidStr = cursor.getString(cursor.getColumnIndex(AUTHOR_JID))
            var authorJid: Jid? = null
            try {
                if (authorJidStr != null) {
                    authorJid = Jid.of(authorJidStr)
                }
            } catch (e: IllegalArgumentException) {
                // ignore: an unparsable author column is no author, exactly as in Java
            }
            val title = cursor.getString(cursor.getColumnIndex(TITLE))
            val content = cursor.getString(cursor.getColumnIndex(CONTENT))
            val attachmentUrl = cursor.getString(cursor.getColumnIndex(ATTACHMENT_URL))
            val attachmentType = cursor.getString(cursor.getColumnIndex(ATTACHMENT_TYPE))
            val linkUrl = cursor.getString(cursor.getColumnIndex(LINK_URL))
            val published = cursor.getLong(cursor.getColumnIndex(PUBLISHED))
            val commentsNode = cursor.getString(cursor.getColumnIndex(COMMENTS_NODE))
            return Post(
                uuid,
                title,
                content,
                authorJid,
                Date(published),
                commentsNode,
                attachmentUrl,
                attachmentType,
                linkUrl
            )
        }
    }
}
