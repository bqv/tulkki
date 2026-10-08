package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.crypto.sasl.HashedToken
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.libs.PresenceRef

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Account`.
 *
 * Declared in the island and implemented by the model class in `:data`, so an island file
 * can name the account without naming a non-island module (`docs/WORKSTREAMS.md` round 151:
 * a port declared in an island may not name a non-island module in its signatures, and `allow`
 * can never legalise that).
 *
 * The surface is grown cluster by cluster as the island's files are retyped, so it carries only
 * what the retyped cluster reads. Everything here is island vocabulary: primitives, `String`,
 * `Jid`, and this island's own SASL type.
 */
interface AccountRef {

    /**
     * Tulkki: 3.7 pair 9, part 16 - the send path's HTTP-upload question, asked of the conversation's
     * account through `MessageRef.getConversation().getAccount()`. Return type and parameter
     * are the model's own.
     */
    fun httpUploadAvailable(size: Long): Boolean

    /**
     * Tulkki: part 17 - the no-argument form, which `XmppConnectionService`'s ordered-list filter
     * asks of a conversation's account. Two plain `boolean` predicates with no enum identity, so both
     * are the model's own declarations and `Account` satisfies them with no body.
     */
    fun httpUploadAvailable(): Boolean

    fun isEnabled(): Boolean

    fun getUsername(): String

    fun getServer(): String

    fun getPassword(): String?

    fun getJid(): Jid

    fun getFastToken(): String?

    fun getPrivateKeyAlias(): String?

    fun getFastMechanism(): HashedToken?

    fun getDomain(): Jid

    fun getXmppConnection(): XmppConnection?

    // -- the parser cluster's surface -----------------------------------------------------------------
    //
    // Grown by ruling 3: each member is here because a call site in `AbstractParser` or in one of its
    // three subclasses reads it *through the inherited field*, which the turn-1 retype turns into this
    // ref even though the subclasses themselves are not retyped until the next turn. Nothing else is
    // added ahead of a consumer.
    fun getRoster(): RosterRef

    fun getOmemoSession(): OmemoSessionPort?

    /**
     * Tulkki: part 12's return-position drag. `MessageParser.parseOtrChat` fingerprints a finished OTR
     * session; the model answers the island's own `uk.xa0.tulkki.xmpp.services.OtrPeerPort`, so this
     * costs no `:data` edit at all.
     */
    fun getOtrPeer(): uk.xa0.tulkki.xmpp.services.OtrPeerPort?

    /**
     * Tulkki: a Java `Collection` return is `MutableCollection` in Kotlin, and the model answers the
     * **live** `CopyOnWriteArraySet` itself, not a copy. The island mutates through it
     * (`IqParser.addAll` / `.clear` / `.removeAll`, `ConversationHistory`'s `.add` / `.remove`), so
     * the read-only spelling was a conversion narrowing rather than a contract.
     */
    fun getBlocklist(): MutableCollection<@JvmSuppressWildcards Jid>

    fun clearBlocklist()

    fun isOnion(): Boolean

    /** Tulkki: part 16 - the second half of the pair `buildHttpClient` asks for. */
    fun isI2P(): Boolean

    /**
     * Tulkki: a Java `Set` return is `MutableSet` in Kotlin. `Account.getBookmarkedJids` answers a
     * fresh `HashSet` copy on purpose, and the island removes from that copy mid-pass
     * (`MdsBookmarks`, `MessageParser.deleteAllBookmarks`), so the read-only spelling was the
     * narrowing.
     */
    fun getBookmarkedJids(): MutableSet<@JvmSuppressWildcards Jid>

    // `setBookmarks` is deliberately **not** present, and the reason is a hard language limit rather
    // than a choice: `Account` already declares `setBookmarks(Map<Jid, Bookmark>)`, and *any*
    // `setBookmarks(Map<...>)` a ref could declare erases to the same `setBookmarks(Map)` — javac
    // rejects the pair as a name clash, wildcard or not. Part 12 retyped the one island caller
    // (`MessageParser.deleteAllBookmarks`), so the port carries the differently named member the
    // brief's §2.1 advice asks for and `:data` adapts it.

    /**
     * **Distinctly named** for the erasure clash above - the same language fact that gave
     * `MessageRef.replaceReactions` its name. `Account.replaceBookmarks` casts the map once and
     * delegates to its own `setBookmarks`, so the `bookmarksLoaded` flag and the lock are untouched.
     */
    fun replaceBookmarks(bookmarks: Map<Jid, out BookmarkRef>)

    /**
     * Tulkki: the PGP engine, in island vocabulary, so `MessageParser` can hand back the edit it just
     * made and let the engine re-decrypt it.
     *
     * The return type is `uk.xa0.tulkki.xmpp.services.PgpDecryptionPort` - the island's own port,
     * declared next to this interface - and not the class that implements it, because `:xmpp`
     * may not name `:crypto`'s `PgpDecryptionService` through a ref. `Account`'s own accessor returns
     * the class, which implements the port, so the covariant return costs `:data` nothing.
     */
    fun getPgpDecryptionService(): uk.xa0.tulkki.xmpp.services.PgpDecryptionPort?

    fun putBookmark(bookmark: BookmarkRef)

    // -- C5-E2: the bookmark surface, landed as a set per the round-429 ruling ----------------------
    //
    // The four members below plus `BookmarkRef` are one surface with one owner, because splitting
    // them across briefs is how two writers come to own one file. The registry slice (C5-E1)
    // consumes exactly three of them: `BookmarkRef.getJid()`, `getBookmarks()` and
    // `addBookmarksTo(Element)`. The ruling also struck `BookmarkRef.asElement()` and adopted this
    // push-loop device: `XmppConnectionService`'s `pushBookmarksPrivateXml`/`pushBookmarksPep` used
    // to iterate the bookmarks and `storage.addChild(bookmark)` each one; now the serialisation
    // lives here, in the model that owns the bookmarks, and the island calls one method.

    /**
     * Tulkki: C5-E2. Covariant - `Account.getBookmarks()` answers `Collection<Bookmark>`, which is a
     * subtype of this return type, so `Account` pays no body. Read by
     * `XmppConnectionService.getKnownConferenceHosts`, whose loop variable becomes a
     * `BookmarkRef` and whose only use of it is `getJid()`.
     */
    fun getBookmarks(): Collection<BookmarkRef>

    /** `Account.areBookmarksLoaded()` - the model's own declaration, so no body. */
    fun areBookmarksLoaded(): Boolean

    /**
     * Tulkki: C5-E2, and **not** the `Jid` overload. `Account.removeBookmark(Jid)` removes the key
     * **without** `asBareJid()` while `putBookmark` keys on `Bookmark.getJid()`, so swapping in
     * `bookmark.getJid()` is the same value only because a `Bookmark`'s jid is bare by construction -
     * an invariant nothing in either signature states. This overload keeps the call shape the island
     * already had (`account.removeBookmark(bookmark)`) and lets the model answer it.
     */
    fun removeBookmark(bookmark: BookmarkRef)

    /**
     * Tulkki: C5-E2, the round-429 ruling's mechanism for the two push loops at
     * `XCS:3521-3548`. `XmppConnectionService` builds the `<storage>` element (or the PEP payload
     * element) and hands it here; `Account` adds each of its own bookmarks to it.
     *
     * It **removes** two island loops rather than adapting them, which is why the objective
     * prefers it, and it keeps a wire-`Element` accessor off this interface's neighbour. It costs no
     * module edge: `:data`'s allow list names `:xmpp`, so `:data` may name `Element` freely, and
     * `AccountRef` is itself an `:xmpp` type, so the parameter adds nothing in either direction.
     */
    fun addBookmarksTo(storage: Element)

    /** `removeBookmark` has a `Jid` overload in the model, and that one needs no ref at all. */
    fun removeBookmark(jid: Jid)

    fun setDisplayName(displayName: String?)

    fun countPresences(): Int

    fun activateGracePeriod(duration: Long)

    /**
     * Tulkki: C5-E1. The write half of [activateGracePeriod], which the model already
     * declares as its own no-argument method, so `Account` pays no body. Read by the status listener
     * and by `switchToForeground`, both of which now hold the ref.
     */
    fun deactivateGracePeriod()

    fun setAvatar(avatar: String?): Boolean

    fun getAvatar(): String?

    // -- the account's four conference sets, as operations -----------------------------------------
    //
    // These were four public `Set<Conversation>` fields the island synchronized on and mutated by
    // name. An `AccountRef` cannot hand out a mutable set - `Set<? extends ConversationalRef>` cannot
    // express `add`/`clear` - and it must not hand out the lock either, so each operation takes the
    // collection's own lock inside the model and the island asks a question instead of reaching in.
    // That is a call *shape* change, not a type change, and it is the only one in the direction
    // (the 3.7 pair-9 commit 167beb22f8 §2.2).
    //
    // The `drain` pair is a single atomic take rather than "copy then clear", because the call sites
    // iterated the copied list *after* clearing the set and a join arriving between the two would
    // have been dropped.
    fun isConferenceJoinPending(conversation: ConversationalRef): Boolean

    fun isConferenceJoinInProgress(conversation: ConversationalRef): Boolean

    fun addConferenceJoinPending(conversation: ConversationalRef): Boolean

    fun removeConferenceJoinPending(conversation: ConversationalRef): Boolean

    fun addConferenceJoinInProgress(conversation: ConversationalRef): Boolean

    fun removeConferenceJoinInProgress(conversation: ConversationalRef): Boolean

    /** Drop every in-progress join; the account is being rebound. */
    fun clearConferenceJoinsInProgress()

    fun addConferenceLeavePending(conversation: ConversationalRef): Boolean

    fun removeConferenceLeavePending(conversation: ConversationalRef): Boolean

    /**
     * Tulkki: C5-E1 narrowed these two from `List<? extends ConversationalRef>` to
     * `List<? extends ConversationRef>`. The model answers `List<Conversation>`, so both declarations
     * compile; the narrower one is what the only caller needs - `XmppConnectionService`'s status
     * listener hands each element straight to `joinMuc`/`leaveMuc`, which take a
     * [ConversationRef]. Nothing else consumes them (`grep drainPendingConference` finds the
     * model, this interface and that one call site).
     */
    fun drainPendingConferenceJoins(): List<ConversationRef>

    fun drainPendingConferenceLeaves(): List<ConversationRef>

    fun addConferencePingInProgress(conversation: ConversationalRef): Boolean

    fun removeConferencePingInProgress(conversation: ConversationalRef): Boolean

    /** Drop every in-progress self-ping; the account is being rebound. */
    fun clearConferencePingsInProgress()

    // -- C5-B: the one member `PresenceGenerator` needed ---------------------------------------------
    //
    // `requestPresenceUpdatesFrom(ContactRef)` reads the requester's display name for the `<nick>`
    // child. It is the model's own declaration, so `Account` supplies it with no body. Everything
    // else C5-B wanted here turned out to be blocked behind `XmppConnection`'s own `Account` import
    // (see the commit): that file cannot be retyped yet, so nothing else is added ahead of a consumer.
    fun getDisplayName(): String?

    // -- C5-D: the state/option/session surface `XmppConnection` and `MessageArchiveService` read ----
    //
    // Two files' worth of `:xmpp -> :data` import, and the only part of C5 whose blocker is a
    // *design* rather than a retype: `XmppConnection.changeStatus` compares `Account.State` with
    // `!=`/`==` against a value it holds locally, and `AccountRef` had no status accessor at all.
    //
    // THE RULING, and why the name moves rather than the type. A same-named `getStatus()` returning
    // `StateRef` cannot override `Account.getStatus()` - same parameters, different return type is a
    // compile error - so the island accessor is **distinctly named** (`getStatusRef`) and returns a
    // type of its own. `Account` answers it by switching over its own enum, name for name, in one
    // place; identity therefore stays true inside the island (`StateRef.ONLINE == StateRef.ONLINE`),
    // and the model enum keeps serving `:ui`/`:app`/`:translation` unchanged. This is exactly the
    // `ContactRef.shownStatus()` -> `PresenceRef.StatusRef` precedent. **No site makes the two enums
    // meet** - that is the stop condition the brief names, and it did not fire.

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.Account.State`.
     *
     * Same constants, in the model's own order, but a type of its own - see the section comment.
     * The mapping both ways is exhaustive and lives in `Account.State` (`toRef()` /
     * `fromRef()`), so a constant added on either side and not on the other is a compile
     * error or a thrown `IllegalStateException`, never a silent `null`.
     */
    enum class StateRef(private val error: Boolean, private val reconnect: Boolean) {
        DISABLED(false, false),
        LOGGED_OUT(false, false),
        OFFLINE(false, true),
        CONNECTING(false, true),
        ONLINE(false, true),
        NO_INTERNET(false, true),
        CONNECTION_TIMEOUT(true, true),
        UNAUTHORIZED(true, true),
        TEMPORARY_AUTH_FAILURE(true, true),
        SERVER_NOT_FOUND(true, true),
        REGISTRATION_SUCCESSFUL(false, true),
        REGISTRATION_FAILED(true, false),
        REGISTRATION_WEB(true, false),
        REGISTRATION_CONFLICT(true, false),
        REGISTRATION_NOT_SUPPORTED(true, false),
        REGISTRATION_PLEASE_WAIT(true, false),
        REGISTRATION_INVALID_TOKEN(true, false),
        REGISTRATION_PASSWORD_TOO_WEAK(true, false),
        TLS_ERROR(true, true),
        TLS_ERROR_DOMAIN(true, true),
        CHANNEL_BINDING(true, true),
        INCOMPATIBLE_SERVER(true, true),
        INCOMPATIBLE_CLIENT(true, true),
        TOR_NOT_AVAILABLE(true, true),
        I2P_NOT_AVAILABLE(true, true),
        DOWNGRADE_ATTACK(true, true),
        SESSION_FAILURE(true, true),
        BIND_FAILURE(true, true),
        HOST_UNKNOWN(true, true),
        STREAM_ERROR(true, true),
        SEE_OTHER_HOST(true, true),
        STREAM_OPENING_ERROR(true, true),
        POLICY_VIOLATION(true, true),
        PAYMENT_REQUIRED(true, true),
        MISSING_INTERNET_PERMISSION(false, true),
        DANE_FAILED(true, true);

        fun isError(): Boolean = this.error

        fun isAttemptReconnect(): Boolean = this.reconnect
    }

    /**
     * Tulkki: the status the model would report, under the island's own name. `Account` maps its
     * effective `getStatus()` (which folds the disabled/soft-disabled options in), so the two agree
     * by construction rather than by a copy of that logic here.
     */
    fun getStatusRef(): StateRef

    /**
     * Tulkki: the write half. `XmppConnection.changeStatus` stores the status it just decided on,
     * and `Account.setStatus` also maintains `lastErrorStatus`; the mapping goes back to the model
     * enum and delegates, so that bookkeeping is not duplicated.
     */
    fun setStatusRef(status: AccountRef.StateRef)

    // -- C5-E2: the presence status a template carries ----------------------------------------------

    /**
     * Tulkki: 3.7 C5-E2 - the write half of [getPresenceStatusRef]. `XmppConnectionService
     * .changeStatus` used to read `template.getStatus()` (the model enum) and hand it to
     * `Account.setPresenceStatus`; with the template now a ref, the value arrives as
     * [PresenceRef.StatusRef] and the mapping back is `Presence.Status.fromRef`, the exhaustive
     * twin of `toRef` that C5-D already built. Delegates to the model's own setter, so any bookkeeping
     * stays in one place.
     */
    fun setPresenceStatusRef(status: PresenceRef.StatusRef)

    /** Tulkki: the error the model last recorded, for the one `getLastErrorStatus()` read. */
    fun getLastErrorStatusRef(): StateRef

    /** `Account.isOptionSet` - the model's own declaration, so `Account` satisfies it with no body. */
    fun isOptionSet(option: Int): Boolean

    /** `Account.setOption` - likewise its own declaration; the `boolean` is "did it change". */
    fun setOption(option: Int, value: Boolean): Boolean

    // -- the remainder `XmppConnection` reads ------------------------------------------------------------------

    /**
     * Tulkki: **non-null**, and this declaration is what makes it so. `AbstractEntity.uuid` is a
     * nullable field, but `Account` is this interface's only implementor: its constructor takes
     * `uuid: String`, assigns it once in `init` and nothing ever clears it, so the value is always
     * there. `Account` narrows its own `getUuid()` to match. The narrower spelling is also what lets
     * `findAccountByUuid` / `getPushTargets` / `isMucUserMuted` resolve against a `String` instead
     * of nine caller guards.
     */
    fun getUuid(): String

    /** `Account.isOnlineAndConnected` - added for `AccountUtils`, the other C5-D consumer. */
    fun isOnlineAndConnected(): Boolean

    fun getResource(): String?

    fun setResource(resource: String)

    /** Returns "did it change", which two call sites read. */
    fun setJid(jid: Jid?): Boolean

    fun getHostname(): String

    fun getPort(): Int

    fun getRosterVersion(): String

    /**
     * Tulkki: the pre-auth registration token, asked as a question rather than by key name.
     *
     * `XmppConnection` read it as `account.getKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN)` - and
     * that spelling is the import this interface exists to remove. Copying the key *string* onto this
     * interface was the obvious device and is the wrong one: `:ui`'s `MagicCreateActivity` writes the
     * same JSON key, and a copy that drifted would make the lookup answer `null` with no exception,
     * which is the silent kind. So the key string stays in `:data`, where it already lives, and the
     * island asks a named question instead. There is one caller, so nothing else needed `getKey`.
     */
    fun getPreAuthRegistrationToken(): String?

    /** The onion-mode question `XmppConnection` asks of the account while resolving. */
    fun isDirectToOnion(): Boolean

    fun getPinnedMechanismPriority(): Int

    fun setPinnedMechanism(mechanism: SaslMechanism)

    fun setFastToken(mechanism: HashedToken.Mechanism, token: String?)

    fun resetFastToken()

    /** The SASL fast path; `SaslMechanism` is this island's own type, so the port stays island vocabulary. */
    fun getQuickStartMechanism(): SaslMechanism?

    // -- C5-D: what `PresenceGenerator` reads of the account and its own presence status ----------

    /**
     * Tulkki: the island's view of `Account.getPresenceStatus()`, distinctly named for the same
     * reason as [getStatusRef]. The mapping lives in `Presence.Status.toRef()`.
     */
    fun getPresenceStatusRef(): PresenceRef.StatusRef

    /** Read by `PresenceGenerator`'s self-presence, which signs it into the packet. */
    fun getPgpSignature(): String?

    /** Read by `PresenceGenerator`'s self-presence, which puts it in the `<status>` child. */
    fun getPresenceStatusMessage(): String?

    // -- C5-E4: the Jingle cluster's surface ----------------------------------------------------------
    //
    // `Id.account` moving to this ref drags two `:app` helpers in with it: `NotificationService`'s
    // `setNotificationColor`/`modifyIncomingCall` take the account their caller holds, and that
    // caller is a jingle `Id`. Nothing is added ahead of a consumer.

    /**
     * Tulkki: 3.7 C5-E4. `NotificationService.setNotificationColor` reads the per-account avatar
     * colour once `modifyIncomingCall`'s parameter is the ref, and the account it is handed is now
     * `id.account`. `Account.getColor(boolean)` (`Account.java:397`) is the model's own declaration,
     * so `Account` satisfies it with no body.
     */
    fun getColor(dark: Boolean): Int

    /**
     * Tulkki: 3.7 C5-E4-2. `JingleConnectionManager.deliverMessage` asks how many of the account's
     * own devices could answer a call before it declines a proposal as busy.
     * `Account.activeDevicesWithRtpCapability()` (`Account.java:852`) is the model's own
     * declaration, so `Account` satisfies it with no body.
     */
    fun activeDevicesWithRtpCapability(): Int

    // -- C5-E1: the surface `XmppConnectionService`'s own bodies read --------------------------------
    //
    // Grown one member per retyped line, with the body that reads it. Every member below already has
    // a declaration on `Account` **except** `getTrueStatusRef`, so `:data` pays a body for exactly
    // that one (and for the three the model had under a different spelling). Nothing is added ahead
    // of a consumer: the registry slice (C5-E1 step 2) is what retires the field these bodies were
    // reading, and each of these is read from a member whose account now arrives as a ref.

    /** `Account.hasErrorStatus()` - the model's own declaration, so no body. */
    fun hasErrorStatus(): Boolean

    /** `Account.isConnectionEnabled()` - likewise. */
    fun isConnectionEnabled(): Boolean

    /** `Account.setShowErrorNotification(boolean)`; the `boolean` is "did it change". */
    fun setShowErrorNotification(show: Boolean): Boolean

    /**
     * `Account.setColor(Integer)` - the model's own parameter type, `Integer`, not `int`: the
     * model stores a nullable colour and `getColor(boolean)` falls back to a name-derived one when
     * it is unset, so a primitive here would quietly turn "no stored colour" into "colour 0".
     */
    fun setColor(color: Int?)

    /** `Account.getColorToSave()` - nullable on purpose, see [setColor]. */
    fun getColorToSave(): Int?

    /** `Account.setPassword(String)`; the retyped update-password path writes it. */
    fun setPassword(password: String?)

    /**
     * `Account.setPrivateKeyAlias(String)` and the three other plain setters the retyped
     * `updateKeyInAccount` / `changeStatus` / `pushMamPreferences` / `updateAccountOrder` bodies
     * write. Each is the model's own declaration.
     */
    fun setPrivateKeyAlias(alias: String?)

    fun setMamPrefs(prefs: Element?)

    fun setOrdering(ordering: Int)

    fun setPgpSignature(signature: String?): Boolean

    fun setPresenceStatusMessage(message: String?)

    /** `Account.initAccountServices(XmppConnectionService)`; the service is island vocabulary. */
    fun initAccountServices(service: XmppConnectionService)

    /** `Account.setXmppConnection(XmppConnection)`; the connection is island vocabulary. */
    fun setXmppConnection(connection: XmppConnection?)

    /**
     * `Account.getSelfContact()`, covariant: the model answers the `Contact` itself, which
     * implements [ContactRef]. Read by the retyped `getPresenceTemplates` and
     * `publishVCard4`.
     */
    fun getSelfContact(): ContactRef

    /**
     * `Account.getPgpDecryption()`: the model already answers the island's own
     * `uk.xa0.tulkki.xmpp.services.PgpDecryptionPort`, so no `:data` change.
     */
    fun getPgpDecryption(): uk.xa0.tulkki.xmpp.services.PgpDecryptionPort?

    /**
     * Tulkki: C5-E1 - the one member of this block that `Account` did **not** already declare.
     *
     * `Account.getTrueStatus()` answers the model enum and `getStatusRef()` answers
     * the *effective* status (with the disabled options folded in); `reconnectAccount` needs the raw
     * one to decide whether an error is worth a reconnect, so the mapping gets its own named
     * accessor rather than a second spelling of `getStatusRef`. `Account` supplies it with the same
     * exhaustive `State.toRef()` the existing pair uses.
     */
    fun getTrueStatusRef(): StateRef

    companion object {
        // -- the `OPTION_*` flags -------------------------------------------------------------------------
        //
        // `public static final int` bit flags with no identity: compile-time constants, copied here
        // exactly as `ContactRef.OptionsRef` and `MucOptionsRef.STATUS_CODE_*` were, because an island
        // cannot name `Account.OPTION_DISABLED` (that spelling is the import this interface exists to
        // remove) and a value mismatch would be a visible behaviour change rather than a silent one.
        // `Account`'s own fields hide these, so the model's values are still the ones that win.
        @JvmField
        val OPTION_DISABLED: Int = 1

        @JvmField
        val OPTION_REGISTER: Int = 2

        @JvmField
        val OPTION_MAGIC_CREATE: Int = 4

        @JvmField
        val OPTION_REQUIRES_ACCESS_MODE_CHANGE: Int = 5

        @JvmField
        val OPTION_LOGGED_IN_SUCCESSFULLY: Int = 6

        @JvmField
        val OPTION_HTTP_UPLOAD_AVAILABLE: Int = 7

        @JvmField
        val OPTION_UNVERIFIED: Int = 8

        @JvmField
        val OPTION_FIXED_USERNAME: Int = 9

        @JvmField
        val OPTION_QUICKSTART_AVAILABLE: Int = 10

        @JvmField
        val OPTION_SOFT_DISABLED: Int = 11

    }
}
