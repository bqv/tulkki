package uk.xa0.tulkki.app

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.util.TreeSet
import java.util.regex.Pattern
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Bookmark
import uk.xa0.tulkki.data.model.Comment
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.JabberIdContact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.data.model.Reaction
import uk.xa0.tulkki.data.model.RtpSessionStatus
import uk.xa0.tulkki.data.model.ServiceDiscoveryResult
import uk.xa0.tulkki.data.model.Story
import uk.xa0.tulkki.data.model.TransferablePlaceholder
import uk.xa0.tulkki.data.updb.UnifiedPushDatabase
import uk.xa0.tulkki.data.utils.EmoticonText
import uk.xa0.tulkki.data.utils.GeoUris
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.data.utils.Patterns
import uk.xa0.tulkki.data.utils.QuickLoader
import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.AccountRegistryRef
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.libs.CommentRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PostRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.ReactionRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.libs.StoryRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The composition root's answer to [XmppConnectionService.DataStatics]: the `:data` entry points the
 * XMPP island reaches, in island vocabulary.
 *
 * <p>3.7 pair 9, cluster (b). It is a singleton with no service in it, because the callers that
 * forced the port are the ones with no service to ask - {@code TLSSocketFactory} and
 * {@code Resolver} are static and may run before any {@code XmppConnectionService} exists - so it is
 * installed from {@code TulkkiApplication.onCreate} the way pair 7's trust port is, and every body
 * here is one line, exactly as the 3.7 pair-9 commit 167beb22f8 §2.3 predicts.
 *
 * <p>It is also the reason the port is in `:app` rather than `:translation` or `:ui`: this is the
 * only module that may name both the island's port and `:data`'s classes.
 *
 * <p>It is a Kotlin `object`, which compiles to the same `public static final DataStaticsHost
 * INSTANCE` field the Java hand-wrote - the spelling `TulkkiApplication` still reads.
 *
 * <p><strong>Several parameters are nullable although the Java said nothing about it.</strong> A Java
 * parameter with no annotation is a platform type and accepted null; Kotlin's non-null parameter
 * inserts a check the Java never had, so each nullable here is a null the delegate already tolerates:
 * `guessMimeTypeFromUriAndMime`'s `type` (`mime == null`), `loadAttachments`' `account`/`jid`/`query`
 * (`account != null && jid != null`, `TextUtils.isEmpty(query)`), `parsePresence`'s three
 * (`caps == null` and a null `show`/`message` the model stores as-is), `newUser`'s four
 * (`fullJid`/`occupantId`/`nickname` each null out of `AbstractParser`'s try, and `MucOptions.User`
 * guards each; `hats` because `hats == null`), `newMessage`'s `body`/`remoteMsgId`, the reaction
 * ids, and `close`'s `Closeable`. Everywhere else the Java dereferenced the value too, so the check
 * stays.
 */
object DataStaticsHost : XmppConnectionService.DataStatics {

    override fun settings(context: Context): AppSettingsRef = AppSettings(context)

    /**
     * Tulkki: C5-E1 - the account registry. `AccountRegistry.get()` is the same instance `:ui`/`:app`
     * reach directly, so the island and the screens are two declared views of one list.
     */
    override fun accounts(): AccountRegistryRef = AccountRegistry.get()

    override fun secureDomains(): Set<Jid> = AppSettings.SECURE_DOMAINS

    override fun clearSessionPassword() {
        AppSettings.clearSessionPassword()
    }

    // -- cluster (b)'s second half ----------------------------------------------------------------

    // Both returns below are nullable for the reason `quickLoad`'s is: the Java static's contract
    // is "or null", and a non-null Kotlin return would throw instead of handing null back to a
    // caller that checks for it. `MimeUtils.guessMimeTypeFromUriAndMime` ends on
    // `mimeTypeFromPath`, which is null, and its caller
    // `XmppConnectionService.attachImageToConversation` reads `mimeType != null`.
    // `MimeUtils.guessExtensionFromMimeType`'s javadoc says "or null iff there is none" and returns
    // null; its caller `HttpDownloadConnection` reads `if (fileExtension != null)`.

    override fun guessMimeTypeFromUriAndMime(context: Context, uri: Uri, type: String?): String? =
            MimeUtils.guessMimeTypeFromUriAndMime(context, uri, type)

    override fun guessExtensionFromMimeType(mimeType: String): String? =
            MimeUtils.guessExtensionFromMimeType(mimeType)

    // Tulkki: `extractRelevantExtension` returns null when the path is empty or the name has no
    // dot (`MimeUtils`'s two `return null` paths), and its island caller passes the result straight
    // into `FileBackend.setupRelativeFilePath`, which takes it as it comes - there is no null check
    // to preserve, so the non-null Kotlin return was a check the Java never had.
    override fun extractRelevantExtension(path: String): String? =
            MimeUtils.extractRelevantExtension(path)

    override fun close(closeable: Closeable?) {
        FileBackend.close(closeable)
    }

    override fun fileSize(context: Context, uri: Uri): Long = FileBackend.getFileSize(context, uri)

    // Tulkki: C5-R1 - the island's file backend, built and installed in one body. The two identity
    // claims this slice rests on are both structural: `install` writes the object this method
    // returns, and `XmppConnectionService`'s field initializer is its only caller, so the island's
    // accessor and `FileBackends.get()` answer the same instance. No cast, and no second
    // `new FileBackend(...)` anywhere.
    //
    // Tulkki, 3.8-r: the preview badges `FileBackend` draws are `:ui`'s drawables, so the ids arrive
    // as a `PreviewIcons` value from the module that owns them - here, at the one place the object is
    // built, beside the service it already receives (the 3.8-r remaining hand-work commit
    // cebe3e9388, section 2). `:app` -> `:ui` is a declared edge, and `UIHelper.previewIcons()` is
    // the one place in the tree the seven names are written.
    override fun newFileBackend(service: XmppConnectionService): FileBackendRef {
        val fileBackend = FileBackend(service, UIHelper.previewIcons())
        FileBackends.install(fileBackend)
        return fileBackend
    }

    // Tulkki: C5-E5 - the three `DatabaseBackend` statics the island used to reach by class name.
    // They cannot live on `DatabaseBackendRef` (an interface has no body and may not name the model),
    // so they arrive here in the commit whose call sites read them, and the model keeps all three.
    // The first is the same `getInstance` the field was assigned from; the island holds what it
    // returns as the ref.

    override fun openDatabase(context: Context): DatabaseBackendRef =
            DatabaseBackend.getInstance(context)

    override fun closeDatabase() {
        DatabaseBackend.closeInstance()
    }

    override fun requiresMessageIndexRebuild(): Boolean =
            DatabaseBackend.requiresMessageIndexRebuild()

    // Tulkki: C5-R1 - `getAttachments`' body, which the island cannot write: its result is
    // `List<Attachment>`. Same thread and same call the island's own body had; C5-E5 retyped the
    // parameter to the island's ref, so the `getRelativeFilePaths` call runs on the object the
    // service is actually using rather than on a second lookup of the singleton. port-13 retired
    // `FilePathRef`, so the ref carries no `getRelativeFilePaths` member and the composition root
    // casts the ref to the model it really is - the object is unchanged.
    override fun loadAttachments(
            databaseBackend: DatabaseBackendRef,
            account: String?,
            jid: Jid?,
            query: String?,
            limit: Int,
            onMediaLoaded: uk.xa0.tulkki.xmpp.services.MediaLoadedHook) {
        Thread {
            onMediaLoaded.onMediaLoaded(
                    FileBackends.get()
                            .convertToAttachments(
                                    (databaseBackend as DatabaseBackend).getRelativeFilePaths(
                                            account, jid, query, limit)))
        }.start()
    }

    override fun warmUpUnifiedPushDatabase(context: Context) {
        UnifiedPushDatabase.getInstance(context)
    }

    /**
     * The island's list is `List<Conversation>` by its own declaration, so every element really is a
     * `Conversation` and this cast is a formality that only the wildcard makes necessary - the
     * parameter cannot be `List<Conversation>` because generics are invariant and the island must be
     * able to pass its own list. The other half of the same trade is the cast inside the island.
     *
     * <p>The `?` on the return is load-bearing: `QuickLoader.get` returns null when no conversation
     * is under the cursor yet (a fresh install, before any conversation is opened) and when nothing
     * in the list matches. The island already handles that - `XmppConnectionService`, the only
     * caller, reads `if (quickLoad != null)`. The non-null return this replaces threw inside the
     * Kotlin check instead, in `restoreFromDatabase`.
     */
    @Suppress("UNCHECKED_CAST")
    override fun quickLoad(haystack: List<out ConversationalRef>): ConversationalRef? =
            QuickLoader.get(haystack as List<Conversation>)

    // -- cluster (c)'s opening --------------------------------------------------------------------

    override fun prepareQuote(message: MessageRef, start: Int, end: Int): String =
            MessageUtils.prepareQuote(message as Message, start, end)

    override fun quote(body: String): String = QuoteHelper.quote(body)

    override fun autolinkWebUrl(): Pattern = Patterns.AUTOLINK_WEB_URL

    override fun geoUri(): Pattern = GeoUris.GEO_URI

    // -- cluster (c) turn 1's two constructions ----------------------------------------------------
    //
    // The island cannot `new` an interface, so it asks here. `hats` is converted element by element
    // rather than by casting the collection: the island's set is a `TreeSet<HatRef>` and the model's
    // constructor wants `Set<Hat>`; `null` is passed through as `null`, because `AbstractParser`
    // distinguishes "no hats element" from "an empty set of hats" and the model does too.

    // Tulkki: `fullJid`, `occupantId` and `nickname` are nullable because
    // `AbstractParser.parseItem` builds each one in a try (the JID parse assigns `fullJid = null`
    // on failure, `nickname` starts null, and the occupant id is null without an id attribute) and
    // passes them here; `MucOptions.User` guards each (`occupantId != null`, `nick != null`) and
    // only reads `fullJid` for `avatar.owner`, so it tolerates null. The non-null parameters threw
    // inside the Kotlin checks instead, on `Thread-9` while parsing a conference item, and they are
    // checked in declaration order, so all three have to go.
    override fun newUser(
            options: MucOptionsRef,
            fullJid: Jid?,
            occupantId: String?,
            nickname: String?,
            hats: Set<out MucOptionsRef.HatRef>?): MucOptionsRef.UserRef {
        val concrete: MutableSet<MucOptions.Hat>? =
                if (hats == null) {
                    null
                } else {
                    TreeSet<MucOptions.Hat>().apply {
                        for (hat in hats) {
                            add(hat as MucOptions.Hat)
                        }
                    }
                }
        return MucOptions.User(options as MucOptions, fullJid, occupantId, nickname, concrete)
    }

    override fun newHat(hat: Element): MucOptionsRef.HatRef = MucOptions.Hat(hat)

    /**
     * The model's own hat ordering, in ref vocabulary. `MucOptions.Hat.compareTo` is
     * `toString().compareTo(another.toString())` and `toString()` is the hat's title, so this is the
     * same ordering and a `TreeSet` of hats sorts as it always did.
     */
    override fun hatOrder(): Comparator<MucOptionsRef.HatRef> =
            Comparator { a, b -> a.toString().compareTo(b.toString()) }

    // -- the parsers' constructions ----------------------------------------------------------------

    override fun newComment(item: Element): CommentRef? = Comment.fromElement(item)

    override fun newPost(item: Element): PostRef? = Post.fromElement(item)

    override fun parseStories(pubsub: Element, contact: Jid): List<StoryRef> =
            Story.parseFromPubSub(pubsub, contact)

    // `Story.fromElement` returns null whenever the item carries no Atom entry or no enclosure
    // link, or names a different publisher, and both callers test for it - `IqParser` and
    // `MessageParser` each read `if (story != null)` before `onStoryReceived`. The return was
    // non-null here, so that null became a Kotlin check failure instead of reaching them.
    override fun newStory(item: Element, contact: Jid): StoryRef? = Story.fromElement(item, contact)

    // -- part 11: the conference-presence cluster's two constructions ------------------------------
    //
    // The parser cannot `new` an interface, so both land here. `newMessage`'s constructor takes a
    // `Conversational`, which `Conversation` and `StubConversation` both are - and the island's
    // parameter is the interface ref for exactly that reason, so the cast is to the interface and not
    // to `Conversation`.

    // Tulkki: `body` is nullable on all three `newMessage` overloads because the model says so -
    // `Message`'s constructor does `this.body = body == null ? "" : body` - and the parser uses it:
    // `MessageParser` builds a body-less message with `body == null ? null : body.content`. The
    // non-null parameter threw inside the Kotlin check instead, on the receive path.
    override fun newMessage(
            conversation: ConversationalRef,
            body: String?,
            encryption: Int,
            status: Int): MessageRef =
            Message(conversation as Conversational, body, encryption, status)

    override fun parsePresence(show: String?, caps: Element?, message: String?): PresenceRef =
            Presence.parse(show, caps, message)

    /**
     * Tulkki: part 14. The island's fallback presence for a contact that has no presence for the
     * resource it is injecting a discovery result for. Written here rather than in the island
     * precisely because `Presence.Status` is compared by identity inside `:data`, so the island must
     * not be the one to name a status value.
     */
    override fun newOfflinePresence(): PresenceRef =
            Presence(Presence.Status.OFFLINE, null, null, null, "")

    override fun newServiceDiscoveryResult(packet: Iq): ServiceDiscoveryResultRef =
            ServiceDiscoveryResult(packet)

    // Tulkki: C5-C - the placeholder is `:data`'s class and an island cannot `new` it, so the
    // construction is the port's; the object it hands back is the model's own, unchanged.

    override fun newTransferablePlaceholder(status: Int): Transferable =
            TransferablePlaceholder(status)

    // Tulkki: C5-E3 - the same shape one cluster later: `HttpDownloadConnection` builds its OMEMO
    // temporary file with `:data`'s constructor, which the island may not name.

    override fun newDownloadableFile(parent: File, name: String): DownloadableFileRef =
            DownloadableFile(parent, name)

    // -- part 14: the two `EmoticonText` statics ---------------------------------------------------

    override fun isEmoji(input: String): Boolean = EmoticonText.isEmoji(input)

    override fun existingVariant(original: String, existing: Set<String>): String =
            EmoticonText.existingVariant(original, existing)

    // -- part 12: the constructions `MessageParser` forces ----------------------------------------
    //
    // Ten more bodies for the third kind of coupling. Nine are one line; the two reaction ones carry
    // a cast because `Reaction`'s statics take and return `Collection<Reaction>` and the port spells
    // the wildcard - generics are invariant, and this is the composition root, the one module that
    // may name both ends.

    override fun newMessage(
            conversation: ConversationRef,
            status: Int,
            type: Int,
            remoteMsgId: String?): MessageRef =
            Message(conversation as Conversation, status, type, remoteMsgId)

    // -- part 16: the four statics `XmppConnectionService` asks for --------------------------------
    //
    // `newMessage`'s second constructor takes a `Conversational` (the interface `Conversation` and
    // `StubConversation` share), which is why the parameter is `ConversationalRef`; the three
    // private-message statics take the model type directly. Every cast here is identity-safe for the
    // reason the ref's javadoc gives: the objects really are `Message`s, built by these very
    // factories or read out of the database.

    override fun newMessage(conversation: ConversationalRef, body: String?, encryption: Int): MessageRef =
            Message(conversation as Conversational, body, encryption)

    // Tulkki: part 17 - the conversation service's own construction, the same third kind of coupling.
    override fun newConversation(
            name: String,
            account: AccountRef,
            contactJid: Jid,
            mode: Int): ConversationRef = Conversation(name, account as Account, contactJid, mode)

    override fun configurePrivateMessage(message: MessageRef) {
        Message.configurePrivateMessage(message as Message)
    }

    override fun configurePrivateMessage(message: MessageRef, counterpart: Jid) {
        Message.configurePrivateMessage(message as Message, counterpart)
    }

    override fun configurePrivateFileMessage(message: MessageRef): Boolean =
            Message.configurePrivateFileMessage(message as Message)

    override fun newFileParams(element: Element): MessageRef.FileParamsRef =
            Message.FileParams(element)

    override fun newRtpSessionStatus(successful: Boolean, duration: Long): String =
            RtpSessionStatus(successful, duration).toString()

    override fun parseBookmarksFromStorage(
            storage: Element,
            account: AccountRef): Map<Jid, BookmarkRef> =
            Bookmark.parseFromStorage(storage, account as Account)

    override fun parseBookmarkFromItem(item: Element, account: AccountRef): BookmarkRef? =
            Bookmark.parseFromItem(item, account as Account)

    /** Tulkki: C5-E2 - `Bookmark.parseFromPubSub`'s wildcard return accepts the model's map whole. */
    override fun parseBookmarksFromPubSub(
            pubSub: Element,
            account: AccountRef): Map<Jid, BookmarkRef> =
            Bookmark.parseFromPubSub(pubSub, account as Account)

    /** Tulkki: C5-E2 - `new Bookmark(Account, Jid)`, the constructor an interface cannot expose. */
    override fun newBookmark(account: AccountRef, jid: Jid): BookmarkRef =
            Bookmark(account as Account, jid)

    /**
     * Tulkki: C5-E2 - `JabberIdContact.load(Context)`. `:app` is the module that already owns the
     * host, so this is where the model static is reachable; the wildcard return accepts its
     * `Map<Jid, JabberIdContact>` whole.
     *
     * <p>port-13's `PhoneContactRef` deletion: the value is the family class `AbstractPhoneContact`
     * now - moved into the island, so this host no longer names a ref - because the entry's own JID
     * is the map's *key* rather than a member the island reads.
     */
    override fun loadJabberIdContacts(context: Context): Map<Jid, AbstractPhoneContact> =
            JabberIdContact.load(context)

    /**
     * Tulkki: C5-E2 - `MucOptions.defaultNick(Account)`, a static, forwarded with one cast.
     *
     * <p>Nullable because the static is: it returns `JidHelper.localPartOrFallback`, which ends on
     * `jid.getLocal()` and is null for a domain-only JID. Every island caller passes it to
     * `nick.equals(...)`, which a null answers false without complaint.
     */
    override fun defaultNick(account: AccountRef): String? = MucOptions.defaultNick(account as Account)

    // Tulkki: `from`, `trueJid`, `occupantId` and `envelopeId` are nullable because
    // `Reaction.withOccupantId` declares exactly that (`from: Jid?`, `trueJid: Jid?`,
    // `occupantId: String?`, `envelopeId: String?`), and the Java callers use the room:
    // `MessageParser.processReactions` passes a null `mucTrueCounterPart`, and
    // `XmppConnectionService.sendReactions` passes a literal null `envelopeId`. The non-null
    // parameters threw inside the Kotlin checks instead, on the parse thread and on the main thread.
    @Suppress("UNCHECKED_CAST")
    override fun reactionsWithOccupantId(
            existing: Collection<out ReactionRef>,
            reactions: Collection<String>,
            received: Boolean,
            from: Jid?,
            trueJid: Jid?,
            occupantId: String?,
            envelopeId: String?): Collection<ReactionRef> =
            Reaction.withOccupantId(
                    existing as Collection<Reaction>,
                    reactions,
                    received,
                    from,
                    trueJid,
                    occupantId,
                    envelopeId)

    // Tulkki: `from` and `envelopeId` are nullable for the same reason as the member above -
    // `Reaction.withFrom` declares `from: Jid?` and `envelopeId: String?` - and
    // `XmppConnectionService.sendReactions` (and the parse path) hands it a literal null
    // `envelopeId`. This one fired on the main thread from the reaction dialog.
    @Suppress("UNCHECKED_CAST")
    override fun reactionsWithFrom(
            existing: Collection<out ReactionRef>,
            reactions: Collection<String>,
            received: Boolean,
            from: Jid?,
            envelopeId: String?): Collection<ReactionRef> =
            Reaction.withFrom(
                    existing as Collection<Reaction>,
                    reactions,
                    received,
                    from,
                    envelopeId)
}
