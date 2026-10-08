package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import android.util.Log
import java.text.ParseException
import java.util.ArrayList
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.StoryRef

/**
 * One published story: the Atom entry's id, publisher, enclosure URL/type/title and its timestamp.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **`url`, `type` and `title` are nullable.** `Cursor.getString` and the Atom link's own
 *    attributes can answer `null`, and `StoryRef` (Java) declares `String` - a platform type - so the
 *    nullable override is the same width Java carried. `getUuid()` is inherited from `AbstractEntity`
 *    and satisfies `StoryRef.getUuid()` exactly as it did before the port.
 * 2. **`contact` stays non-null `Jid`.** Java declared it so, and every construction site has a real
 *    publisher JID (`parseFromPubSub` from the pubsub item, `fromElement` from the parser).
 * 3. **A `UUID` companion constant is added.** `RawTables.kt:359` writes `${Story.UUID}`, which
 *    resolved through Java's static inheritance while `Story` was Java; Kotlin does not inherit a
 *    Java static into a subclass's scope, so the constant restates `AbstractEntity.UUID`'s own value
 *    - the same shape `Message.kt:2103` and `Conversation.kt:1628` already carry.
 * 4. **`fromCursor` is `@JvmStatic`** (`DatabaseBackend.java:3126` names it). `fromElement` and
 *    `parseFromPubSub` are named only by `:app`'s Kotlin `DataStaticsHost`, but keep the Java surface
 *    the file had. **Interop debt: three `@JvmStatic`s.**
 * 5. **Both `fromElement` overloads return `Story?`**, as Java's did: a missing Atom entry, a missing
 *    enclosure link, a mismatched publisher or an invalid author JID all answer `null`, and
 *    `DataStaticsHost.newStory` tests for it.
 * 6. **`parseTimestamp` is called through its class, not imported as a static.** The static import
 *    resolved while `AbstractParser` was Java; `port-50` turns it into a Kotlin companion, whose
 *    members Kotlin imports as `...Companion.parseTimestamp` and offers no class-name static import
 *    for, so the ordering is `AbstractParser.parseTimestamp(...)` - the spelling `Comment.kt` and
 *    `Post.kt` already use. The `Element`/`Long` overload answers `Long?` there (its Java callers
 *    pass `null` and read it back), and `parseFromPubSub` passes a non-null `0L`, so the elvis
 *    restates the Java's own `return d` default. The empty `catch (ParseException)` on `updated`
 *    stays empty, and the `published` one keeps its `Log.e`.
 */
class Story(
    uuid: String?,
    private val contact: Jid,
    private val url: String?,
    private val type: String?,
    private val title: String?,
    private val published: Long,
) : AbstractEntity(), StoryRef {

    init {
        this.uuid = uuid
    }

    override fun getContentValues(): ContentValues {
        val values = ContentValues()
        values.put(AbstractEntity.UUID, uuid)
        values.put(CONTACT, contact.toString())
        values.put(URL, url)
        values.put(TYPE, type)
        values.put(TITLE, title)
        values.put(PUBLISHED, published)
        return values
    }

    override fun getContact(): Jid = contact

    override fun getUrl(): String? = url

    override fun getType(): String? = type

    override fun getTitle(): String? = title

    override fun getPublished(): Long = published

    companion object {

        const val TABLENAME = "stories"
        const val CONTACT = "contact"
        const val URL = "url"
        const val TYPE = "type"
        const val TITLE = "title"
        const val PUBLISHED = "published"

        /** [AbstractEntity.UUID], restated so Kotlin's `Story.UUID` resolves (`RawTables.kt:359`). */
        const val UUID = AbstractEntity.UUID

        private const val ATOM = "http://www.w3.org/2005/Atom"

        @JvmStatic
        fun fromCursor(cursor: Cursor): Story = Story(
            cursor.getString(cursor.getColumnIndex(AbstractEntity.UUID)),
            Jid.of(cursor.getString(cursor.getColumnIndex(CONTACT))),
            cursor.getString(cursor.getColumnIndex(URL)),
            cursor.getString(cursor.getColumnIndex(TYPE)),
            cursor.getString(cursor.getColumnIndex(TITLE)),
            cursor.getLong(cursor.getColumnIndex(PUBLISHED)),
        )

        @JvmStatic
        fun fromElement(item: Element, contact: Jid): Story? = fromElement(item, contact, 0)

        @JvmStatic
        fun fromElement(item: Element, contact: Jid, fallbackTimestamp: Long): Story? {
            val entry: Element? = item.findChild("entry", ATOM)
            if (entry == null) {
                return null
            }

            val author: Element? = entry.findChild("author", ATOM)
            if (author != null) {
                val uri: Element? = author.findChild("uri", ATOM)
                val authorUri = if (uri != null) uri.getContent() else null
                if (authorUri != null && authorUri.startsWith("xmpp:")) {
                    try {
                        val authorJid = Jid.of(authorUri.substring(5))
                        if (authorJid.asBareJid() != contact.asBareJid()) {
                            Log.w(Config.LOGTAG, "Story author JID ($authorJid) does not match publisher JID ($contact). Ignoring story.")
                            return null
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.w(Config.LOGTAG, "Invalid JID in story author URI: $authorUri")
                        return null
                    }
                }
            }

            var link: Element? = null
            val children: List<Element>? = entry.getChildren()
            if (children != null) {
                for (child in children) {
                    if ("link" == child.getName() && "enclosure" == child.getAttribute("rel")) {
                        link = child
                        break
                    }
                }
            }

            if (link == null) {
                return null
            }

            var timestamp = 0L

            if (fallbackTimestamp > 0) {
                timestamp = fallbackTimestamp
            } else {
                val published: Element? = entry.findChild("published", ATOM)
                val publishedContent = if (published == null) null else published.getContent()
                if (publishedContent != null) {
                    try {
                        timestamp = AbstractParser.parseTimestamp(publishedContent)
                    } catch (e: ParseException) {
                        Log.e("Story", "Couldn't parse timestamp $publishedContent")
                    }
                }
            }

            if (timestamp == 0L) {
                val updated: Element? = entry.findChild("updated", ATOM)
                val updatedContent = if (updated == null) null else updated.getContent()
                if (updatedContent != null) {
                    try {
                        timestamp = AbstractParser.parseTimestamp(updatedContent)
                    } catch (e: ParseException) {
                        // Java's empty catch, kept empty.
                    }
                }
            }

            if (timestamp == 0L) {
                timestamp = System.currentTimeMillis()
            }

            return Story(
                item.getAttribute("id"),
                contact,
                link.getAttribute("href"),
                link.getAttribute("type"),
                entry.findChildContent("title", ATOM),
                timestamp,
            )
        }

        @JvmStatic
        fun parseFromPubSub(pubsub: Element?, contact: Jid): List<Story> {
            val stories = ArrayList<Story>()
            if (pubsub == null) {
                return stories
            }
            val items: Element? = pubsub.findChild("items")
            if (items != null) {
                val children: List<Element>? = items.getChildren()
                if (children != null) {
                    for (item in children) {
                        if (item.getName() == "item") {
                            val timestamp = AbstractParser.parseTimestamp(item, 0L) ?: 0L
                            val story = fromElement(item, contact, timestamp)
                            if (story != null) {
                                stories.add(story)
                            }
                        }
                    }
                }
            }
            return stories
        }
    }
}
