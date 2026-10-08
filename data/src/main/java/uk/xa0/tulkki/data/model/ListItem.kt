package uk.xa0.tulkki.data.model

import android.content.Context
import java.io.Serializable
import java.util.Locale
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: 3.7 pair 9, part 14. [Avatarable] is what the island's `AvatarPort` names this type
 * through; `ListItemRef` was a narrower island name until port-13 retired it, and the port's arity-2
 * member took the parent marker then, so "a ListItem is an Avatarable" is the whole of the contract
 * the island sees.
 *
 * Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The five members stay functions.** [Avatarable] declares none of them, so none is an
 *    `override` -
 *    the opposite case to [Blockable] - and they are written as functions rather than properties
 *    because the three Java implementors (`Bookmark`, `Contact`, `RawBlockable`) spell those getters
 *    out and the interface should say what they write - the house style where the implementors are
 *    still Java, with [Blockable] and `UsageLedger.Store` as the precedents.
 * 2. **[Tag] stays a nested class, so its binary name stays `ListItem$Tag`** and the ~40 Java sites
 *    that name it `ListItem.Tag` - `Conversation.java:152`'s `import
 *    uk.xa0.tulkki.data.model.ListItem.Tag` included - are unchanged. A nested class inside a Kotlin
 *    interface is `static` in exactly Java's sense, which is what Java's nested-in-an-interface class
 *    already was; the deleted `TagEditorView`'s `new ListItem.Tag(completionText)` compiled the same
 *    way.
 * 3. **[Tag] implements `Comparable<Any?>` where Java implemented raw `Comparable`.** Java's erasure
 *    is `compareTo(Object)`, and Kotlin's `Comparable<Any?>` is that same method with that same
 *    parameter, so no bridge appears and no Java call site changes. `Any?` rather than `Any` is
 *    deliberate: Java's `if (!(o instanceof Tag)) return -1` answers `-1` for a null argument, which a
 *    non-null Kotlin parameter would have turned into a thrown NPE instead.
 * 4. **`name.toLowerCase(Locale.US)` is `name.lowercase(Locale.US)`.** Kotlin's `lowercase(Locale)`
 *    delegates to `java.lang.String.toLowerCase(Locale)` exactly, and the locale was explicit in Java,
 *    so the port's locale-sensitive `toLowerCase()` trap does not apply at this site - no helper and
 *    no change of semantics.
 * 5. **The tag holds a `val name` rather than Java's private field plus `getName()`.** Kotlin
 *    generates the same private field and the same public `getName()`, which is what the Java readers
 *    use (the deleted `TagEditorView:41-42` and `ListItemAdapter:103`; `ConversationListActivity:326`,
 *    `ConferenceDetailsActivity:565`'s `entry.getKey().getName()`).
 *
 * Nothing here is static and no Java caller reads a field as a field, so this file adds **zero**
 * interop debt.
 */
interface ListItem : Comparable<ListItem>, Avatarable {

    fun getDisplayName(): String

    fun getJid(): Jid

    fun getAccount(): Account

    fun getTags(context: Context): List<Tag>

    /**
     * Whether this row matches a search needle, or `true` for a null one: "no filter matches
     * everything", which is each implementation's own answer.
     *
     * <p>The parameter is nullable because the Java interface's was an unannotated platform type and
     * **every** implementation null-checked it - `Bookmark` returned `true`, `Contact` and
     * `RawBlockable` route null through `TextUtils.isEmpty`. A `:ui` search box hands over its value
     * before anything is typed, and that value is null (`StartConversationActivity.filterContacts`),
     * which is the crash that made this a `FIXED:` rather than a reading.
     */
    fun match(context: Context, needle: String?): Boolean

    /** One user tag: a name, compared case-insensitively in [Locale.US]. */
    class Tag(val name: String) : Serializable, Comparable<Any?> {

        override fun toString(): String = name

        override fun equals(other: Any?): Boolean {
            if (other !is Tag) return false
            return name.lowercase(Locale.US) == other.name.lowercase(Locale.US)
        }

        override fun compareTo(other: Any?): Int {
            if (other !is Tag) return -1
            return name.lowercase(Locale.US).compareTo(other.name.lowercase(Locale.US))
        }

        override fun hashCode(): Int = name.lowercase(Locale.US).hashCode()
    }
}
