package uk.xa0.tulkki.data.model

import android.content.Context
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.regex.Pattern
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.utils.StringUtils

/** Java's `trim()`, which removes every char `<= ' '`; Kotlin's `trim()` also strips NBSP. */
private fun String.javaTrim(): String = trim { it <= ' ' }

/**
 * One bookmarks-2 conference: the room JID, its name, nick, password and groups, the conversation it
 * is open as, and the `<extensions>` element the roster groups live in.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **It still `extends Element`, and it has no primary constructor**: Java's two constructors
 *    become two secondaries, each delegating to `super("conference")` - the public `(Account, Jid)`
 *    one, which sets the `jid` attribute, and the private `(Account)` one, which does not, exactly
 *    Java's split. A private primary `(Account, Jid?)` is impossible: it would share the JVM
 *    descriptor `(Account, Jid)V` with the public shape, which Kotlin reports as a platform
 *    declaration clash.
 * 2. **Eighteen members are `override`s** - the twelve `BookmarkRef` declares (`getAccount`,
 *    `getJid`, `getFullJid`, `getBookmarkName`, `setBookmarkName`, `getNick`, `setNick`, `autojoin`,
 *    `setAutojoin`, `getPassword`, `getExtensions`, `setConversation(ConversationRef)`) and
 *    `ListItem`'s (`getDisplayName`, `getJid`, `getAccount`, `getTags`, `match`, `compareTo`) plus
 *    `Avatarable`'s two. Kotlin's `override` is mandatory where Java's was advisory.
 * 3. **`getJid()` is non-null**: `ListItem`'s Kotlin member declares `Jid`, and Java's field is null
 *    only between the private constructor and `parse`/`parseFromItem`'s assignment - a shape that
 *    never escapes, since both return `null` when the JID is invalid. So the body is
 *    `jid ?: throw NullPointerException()` rather than a nullable return Java's callers tolerated.
 * 4. **`match`'s `if (needle == null) return true` is restored, because `ListItem.match` is
 *    nullable again.** This clause used to say the check *could not be written*, justified by "the
 *    nine Java callers - `BlocklistActivity:65`, `ChooseContactActivity:305`, `:313`, `:320`,
 *    `ShortcutActivity:68`, `StartConversationActivity:1365`, `:1376`, `:1381`, `:1423` - all pass a
 *    search string, never `null`". That was an assumption and it was wrong: `:1381` passes the search
 *    box's value, which is `null` until something is typed, and the non-null parameter threw at the
 *    parameter check on the owner's phone (`java.lang.NullPointerException: Parameter specified as
 *    non-null is null: method ... Bookmark.match, parameter needle`). `ListItem.match` and all three
 *    implementations now declare `needle: String?` and each restores its own pre-port answer; this is
 *    `Bookmark`'s (`Bookmark.java:264`). Recorded as a `FIXED:`, and the lesson with it: a "no caller
 *    does X" justification has to be measured, not assumed.
 * 5. **`needle.split("[,\\s]+")` becomes `Pattern.compile("[,\\s]+").split(needle, 0)`.** This row
 *    measured that Kotlin's single-`String` overload is a *literal* split and that `Regex.split`
 *    keeps trailing empty fields where Java's `String.split` drops them (`5a9dd424f1`, `067ce2c2a8`,
 *    `45591d1d7e`); `Pattern.split(input, 0)` is Java's call exactly.
 * 6. **`trim()` is Java's, through a file-private `String.javaTrim()`** - `trim { it <= ' ' }`, this
 *    row's helper shape - at the three sites where the text is arbitrary user input: `getDisplayName`,
 *    `printableValue`, `getFullJid` and nothing else. Kotlin's `trim()` also strips the
 *    non-breaking-space family, which Java's did not.
 * 7. **`compareToIgnoreCase` is unreachable through Kotlin's `String`** (this row measured it on
 *    `RawBlockable`): `compareTo(other, ignoreCase = true)` is the same call - it delegates to
 *    `java.lang.String.compareToIgnoreCase`.
 * 8. **`account`, `jid`, `conversation` and `extensions` stay private and keep Java's names.** A
 *    private Kotlin `val`/`var` generates the backing field and no accessor (measured with `javap`),
 *    and `extensions` - Java's `protected` field - has no reader outside this file (measured), so it
 *    can stay private beside the explicit `getExtensions()`. **Interop debt: five `@JvmStatic`s** -
 *    `parseFromStorage`, `parseFromItem`, `parseFromPubSub` (`DataStaticsHost:464`, `:469`, `:476`)
 *    and both `printableValue` overloads (`Conversation:7` and `ConferenceDetailsActivity:4` import
 *    it statically; `Conversation:1178` calls the two-arg form, `ConversationFragment:6267` the
 *    one-arg). `parse` has no Java caller - only `parseFromStorage` - so it carries no annotation.
 * 9. **`@Synchronized` on the four conversation accessors** is Java's `synchronized` method, monitor
 *    and all.
 *
 * Nothing in the tree extends `Bookmark`, so Kotlin's implicit `final` is Java's own shape.
 */
class Bookmark : Element, ListItem, BookmarkRef {

    private val account: Account

    private var jid: Jid?

    private var conversation: WeakReference<Conversation>? = null

    private var extensions: Element = Element("extensions", Namespace.BOOKMARKS2)

    constructor(account: Account, jid: Jid) : super("conference") {
        this.account = account
        this.jid = jid
        setAttribute("jid", jid)
    }

    private constructor(account: Account) : super("conference") {
        this.account = account
        this.jid = null
    }

    override fun getExtensions(): Element = extensions

    fun addGroup(group: String?) {
        addChild("group", "jabber:iq:roster").setContent(group)
        extensions.addChild("group", "jabber:iq:roster").setContent(group)
    }

    fun setGroups(groups: List<String>) {
        val children = ImmutableList.copyOf(getChildren())
        for (el in children) {
            if ("group" == el.getName()) {
                removeChild(el)
            }
        }

        val extChildren = ImmutableList.copyOf(extensions.getChildren())
        for (el in extChildren) {
            if ("group" == el.getName()) {
                extensions.removeChild(el)
            }
        }

        for (group in groups) {
            addGroup(group)
        }
    }

    override fun setAutojoin(autojoin: Boolean) {
        if (autojoin) {
            setAttribute("autojoin", "true")
        } else {
            setAttribute("autojoin", "false")
        }
    }

    override fun compareTo(another: ListItem): Int {
        if (getJid().isDomainJid() && !another.getJid().isDomainJid()) {
            return -1
        } else if (!getJid().isDomainJid() && another.getJid().isDomainJid()) {
            return 1
        }

        if (getDisplayName() == another.getDisplayName()) {
            return getJid().compareTo(another.getJid())
        }

        return getDisplayName().compareTo(another.getDisplayName(), ignoreCase = true)
    }

    override fun getDisplayName(): String {
        val c = getConversation()
        val name = getBookmarkName()
        return if (c != null) {
            c.getName().toString()
        } else if (name != null && printableValue(name, false)) {
            name.javaTrim()
        } else {
            val roomJid = getJid()
            roomJid.getLocal() ?: ""
        }
    }

    override fun getJid(): Jid = jid ?: throw NullPointerException()

    override fun getFullJid(): Jid? = getFullJid(getNick(), true)

    private fun getFullJid(nick: String?, tryFix: Boolean): Jid? {
        val roomJid = jid
        return try {
            if (roomJid == null || nick == null || nick.javaTrim().isEmpty()) {
                roomJid
            } else {
                roomJid.withResource(nick)
            }
        } catch (e: IllegalArgumentException) {
            try {
                if (tryFix) getFullJid(gnu.inet.encoding.Punycode.encode(nick), false) else null
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun getGroupTags(): List<ListItem.Tag> {
        val tags = ArrayList<ListItem.Tag>()

        for (element in getChildren()) {
            if ("group" == element.getName()) {
                val group = element.getContent()
                if (group != null) {
                    tags.add(ListItem.Tag(group))
                }
            }
        }

        return tags
    }

    override fun getTags(context: Context): List<ListItem.Tag> {
        val tags = ArrayList<ListItem.Tag>()
        tags.add(ListItem.Tag("Channel"))
        tags.addAll(getGroupTags())
        return tags
    }

    override fun getNick(): String? = Strings.emptyToNull(findChildContent("nick"))

    override fun setNick(nick: String?) {
        var element = findChild("nick")
        if (element == null) {
            element = addChild("nick")
        }
        element.setContent(nick)
    }

    override fun autojoin(): Boolean = getAttributeAsBoolean("autojoin")

    override fun getPassword(): String? = findChildContent("password")

    fun setPassword(password: String?) {
        val element = findChild("password")
        if (element != null) {
            element.setContent(password)
        }
    }

    override fun match(context: Context, needle: String?): Boolean {
        if (needle == null) {
            return true
        }
        val lowered = needle.lowercase(Locale.US)
        val parts = Pattern.compile("[,\\s]+").split(lowered, 0)
        if (parts.size > 1) {
            for (part in parts) {
                if (!match(context, part)) {
                    return false
                }
            }
            return true
        } else if (parts.size > 0) {
            return getJid().toString().contains(parts[0]) ||
                getDisplayName().lowercase(Locale.US).contains(parts[0]) ||
                matchInTag(context, parts[0])
        } else {
            return getJid().toString().contains(lowered) ||
                getDisplayName().lowercase(Locale.US).contains(lowered)
        }
    }

    private fun matchInTag(context: Context, needle: String): Boolean {
        val lowered = needle.lowercase(Locale.US)
        for (tag in getTags(context)) {
            if (tag.name.lowercase(Locale.US).contains(lowered)) {
                return true
            }
        }
        return false
    }

    override fun getAccount(): Account = account

    @Synchronized
    fun getConversation(): Conversation? = conversation?.get()

    @Synchronized
    fun setConversation(conversation: Conversation?) {
        this.conversation?.clear()
        this.conversation = if (conversation == null) null else WeakReference(conversation)
    }

    /**
     * Tulkki: 3.7 pair 9, part 17 - the island's overload. `XmppConnectionService` holds the
     * conversation as a `ConversationRef` and clears the bookmark's back-reference with it; a
     * parameter type is not covariant, so the ref gets its own entry point and the object is cast
     * once. Identity-safe for the ref's usual reason: the only conversations the service ever holds
     * are the model's own.
     *
     * <p>FIXED: the parameter is nullable, because two Java callers clear the back-reference with a
     * literal `null` **through this interface type** - `XmppConnectionService:4353`
     * (`archiveConversation`) and `:5622` (`leaveMuc`), both reached by archiving or leaving a
     * bookmarked room. The Java original's parameter was bare
     * (`public synchronized void setConversation(Conversation conversation)`) and its body
     * null-checked it, so a non-null Kotlin parameter threw at the parameter check before the body
     * ever ran. `ConferenceDetailsActivity:138` and `StartConversationActivity:675` are deliberately
     * untouched: there the static type is this class, both overloads are visible, and Java picks the
     * more specific `setConversation(Conversation?)`, so no check fires.
     */
    @Synchronized
    override fun setConversation(conversation: ConversationRef?) {
        setConversation(conversation as Conversation?)
    }

    override fun getBookmarkName(): String? = getAttribute("name")

    override fun setBookmarkName(name: String?): Boolean {
        val before = getBookmarkName()
        if (name != null) {
            setAttribute("name", name)
        } else {
            removeAttribute("name")
        }
        return StringUtils.changed(before, name)
    }

    override fun getAvatarBackgroundColor(): Int {
        val roomJid = jid
        return DisplayNames.getColorForName(
            if (roomJid != null) roomJid.asBareJid().toString() else getDisplayName(),
        )
    }

    override fun getAvatarName(): String = getDisplayName()

    companion object {

        @JvmStatic
        fun parseFromStorage(storage: Element?, account: Account): Map<Jid, Bookmark> {
            if (storage == null) {
                return Collections.emptyMap()
            }
            val bookmarks = HashMap<Jid, Bookmark>()
            for (item in storage.getChildren()) {
                if ("conference" == item.getName()) {
                    val bookmark = parse(item, account)
                    if (bookmark != null) {
                        val old = bookmarks.put(bookmark.getJid(), bookmark)
                        if (old != null &&
                            old.getBookmarkName() != null &&
                            bookmark.getBookmarkName() == null
                        ) {
                            bookmark.setBookmarkName(old.getBookmarkName())
                        }
                    }
                }
            }
            return bookmarks
        }

        @JvmStatic
        fun parseFromPubSub(pubSub: Element?, account: Account): Map<Jid, Bookmark> {
            if (pubSub == null) {
                return Collections.emptyMap()
            }
            val items = pubSub.findChild("items")
            if (items != null && Namespace.BOOKMARKS2 == items.getAttribute("node")) {
                val bookmarks: MutableMap<Jid, Bookmark> = HashMap()
                for (item in items.getChildren()) {
                    if ("item" == item.getName()) {
                        val bookmark = parseFromItem(item, account)
                        if (bookmark != null) {
                            bookmarks.put(bookmark.getJid(), bookmark)
                        }
                    }
                }
                return bookmarks
            }
            return Collections.emptyMap()
        }

        fun parse(element: Element, account: Account): Bookmark? {
            val bookmark = Bookmark(account)
            bookmark.setAttributes(element.getAttributes())
            bookmark.replaceChildren(element.getChildren())
            bookmark.jid = Jid.Invalid.getNullForInvalid(bookmark.getAttributeAsJid("jid"))
            if (bookmark.jid == null) {
                return null
            }
            return bookmark
        }

        @JvmStatic
        fun parseFromItem(item: Element, account: Account): Bookmark? {
            val conference = item.findChild("conference", Namespace.BOOKMARKS2)
            if (conference == null) {
                return null
            }
            val bookmark = Bookmark(account)
            bookmark.jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("id"))
            // TODO verify that we only use bare jids and ignore full jids
            if (bookmark.jid == null) {
                return null
            }
            bookmark.setBookmarkName(conference.getAttribute("name"))
            bookmark.setAutojoin(conference.getAttributeAsBoolean("autojoin"))
            bookmark.setNick(conference.findChildContent("nick"))
            bookmark.setPassword(conference.findChildContent("password"))
            val extensions = conference.findChild("extensions", Namespace.BOOKMARKS2)
            if (extensions != null) {
                for (ext in extensions.getChildren()) {
                    if ("group" == ext.getName() && "jabber:iq:roster" == ext.getNamespace()) {
                        bookmark.addGroup(ext.getContent())
                    }
                }
                bookmark.extensions = extensions
            }
            return bookmark
        }

        @JvmStatic
        fun printableValue(value: String?, permitNone: Boolean): Boolean =
            value != null && !value.javaTrim().isEmpty() && (permitNone || "None" != value)

        @JvmStatic
        fun printableValue(value: String?): Boolean = printableValue(value, true)
    }
}
