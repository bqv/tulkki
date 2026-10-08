package uk.xa0.tulkki.data.model

import android.util.Log
import java.text.ParseException
import java.util.Date
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.CommentRef

/**
 * One Atom `entry` of a PubSub feed: a comment on a post, or a like on it (the title is the marker).
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **[fromElement] returns `Comment?` and is a companion `@JvmStatic`.** Java's `fromElement`
 *    returned `null` when the `entry` child was missing - `:ui`'s `PostsAdapter:435` checks exactly
 *    that - so the Kotlin return type is nullable rather than a narrowing; and its two callers are
 *    Java (`:app`'s `DataStaticsHost.newComment:296` and `PostsAdapter`), so without `@JvmStatic` the
 *    method would exist only as `Companion.fromElement`. **Interop debt: one annotation.**
 * 2. **The four properties are nullable, because Java's are.** `title`, `author` and `published` are
 *    assigned `null` on the paths that do not find them, and `id` comes straight from
 *    `Element.getAttribute("id")` unchecked; a non-null Kotlin `String` would be a claim the parser
 *    does not make. They stay `val`s, which generates `getId()`/`getTitle()`/`getAuthor()`/
 *    `getPublished()` - the four getters `PostsAdapter` and `CommentsAdapter` call - so no
 *    `@JvmField` has a creditor. **Zero annotations of that kind.**
 * 3. **`AbstractParser.parseTimestamp` is called through its class.** Java used a static import;
 *    Kotlin has none for Java statics, so the call is `AbstractParser.parseTimestamp(...)` and the
 *    import is the class. The `ParseException` is still caught - Kotlin does not check it, but Java's
 *    `catch` was real behaviour (a bad timestamp logs and leaves `published` null), not a formality.
 * 4. **`Element.findChild(...) ?: return null` is Java's `if (entry == null) return null`**, and the
 *    `Log.e("Feeds", ...)` message keeps Java's text and its concrete tag; only the concatenation
 *    becomes a template.
 *
 * It stays a plain `class`, not a `data class`, and final: Java had identity equality, and `grep`
 * finds no `extends Comment`. [CommentRef] is empty (measured in its own KDoc: the island only
 * *builds* one of these and never reads a member), so there is no `override` in this file.
 */
class Comment(
    val id: String?,
    val title: String?,
    val author: Jid?,
    val published: Date?,
) : CommentRef {

    companion object {

        /** Parses one feed item, or `null` when it carries no Atom entry. */
        @JvmStatic
        fun fromElement(item: Element): Comment? {
            val entry = item.findChild("entry", "http://www.w3.org/2005/Atom") ?: return null
            val id = item.getAttribute("id")
            val title = entry.findChildContent("title")
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
            return Comment(id, title, author, published)
        }
    }
}
