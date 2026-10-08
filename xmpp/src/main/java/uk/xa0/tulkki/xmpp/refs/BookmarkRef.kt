package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Bookmark`.
 *
 * Declared in the island, implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). Its only consumers for a long time were the two `Account`
 * operations that *take* a bookmark (`putBookmark`, `setBookmarks`), and a parameter the island only
 * passes through needs no member. `Bookmark.parseFromItem`/`parseFromStorage` are statics and go on
 * `DataStatics` when a cluster needs them, the way `pushTarget` did.
 *
 * **C5-B gave it the five reads `IqGenerator.publishBookmarkItem` makes.** That method builds the
 * `<conference>` item of a bookmarks-2 publish, so the island now reads a bookmark rather than only
 * passing one; all five are the model's own declarations and none is an enum, so nothing about
 * identity is involved.
 *
 * **C5-E2 grew it to the mutation and identity surface `XmppConnectionService`'s bookmark code
 * reads** - 30 sites, one import. Every member below is the model's own declaration, so `Bookmark`
 * pays no body for any of them; `getAccount()` and `setConversation(ConversationRef)` are covariant
 * returns / ref-typed overloads of declarations it already has.
 *
 * `Element asElement()` is deliberately **absent**. The only sites that would have wanted it were
 * the two push loops in `XmppConnectionService` (`pushBookmarksPrivateXml`, `pushBookmarksPep`,
 * `XCS:3521-3548`), and the round-429 ruling moved that serialisation into
 * [addBookmarksTo] instead, so no island caller holds a `BookmarkRef`
 * there. A wire-`Element` accessor on the abstraction whose job is to hide the model is exactly the
 * shape the ruling rejects (`Bookmark extends Element` is upstream's oddity, not ours to propagate).
 */
interface BookmarkRef {

    /**
     * Tulkki: C5-E2. Covariant - `Bookmark.getAccount()` answers the model `Account`, which
     * implements this interface.
     */
    fun getAccount(): AccountRef

    /**
     * Tulkki: C5-E2, and additive - `Bookmark` already declares `public Jid getJid()`
     * (`Bookmark.java:196-197`), so this costs `:data` no body. It is what lets
     * `processBookmarksInitial` and `getKnownConferenceHosts` drop their `(Bookmark)` casts.
     */
    fun getJid(): Jid

    fun getFullJid(): Jid?

    fun getBookmarkName(): String?

    /** `boolean`, not `void`: `XmppConnectionService` branches on whether the name changed. */
    fun setBookmarkName(name: String?): Boolean

    fun getNick(): String?

    fun setNick(nick: String?)

    fun autojoin(): Boolean

    fun setAutojoin(autojoin: Boolean)

    fun getPassword(): String?

    /** The stored extensions element, added to the item whole. */
    fun getExtensions(): Element

    /** The model's own ref-typed overload (`Bookmark.java:327`); a parameter is not covariant. */
    fun setConversation(conversation: ConversationRef?)
}
