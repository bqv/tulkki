package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.pep.Avatar
import java.util.Locale
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.MucOptions`.
 *
 * Declared in the island, implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). This is the first ref in this pair that is not a plain
 * member list, and the reason is in [HatRef]: an island value type that has to keep an
 * ordering cannot be a bare marker.
 *
 * `MucOptions.Error` is the enum family the coordinator ruled on (island-owned enum, ref returns
 * it, `:data` maps at the boundary) and it arrived with its call sites in part 11. `Affiliation` is
 * the same ruling's second case and arrived with part 12 - see [AffiliationRef]. `Role` arrived
 * with C5-E2 as [RoleRef], once a site wanted one: `XmppConnectionService.changeRoleInConference`
 * takes it and only stringifies it. The stop condition attached to that ruling is live: if a site ever
 * makes two enum identities meet, that is a report, not a commit.
 */
interface MucOptionsRef {

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.MucOptions.User`.
     *
     * Four members, which is what `AbstractParser.parseItem` writes on a parsed participant.
     */
    interface UserRef {

        fun getRealJid(): Jid?

        fun setRealJid(realJid: Jid?)

        fun setAffiliation(affiliation: String?)

        fun setRole(role: String?)

        // -- what `PresenceParser` reads and writes on a parsed participant (part 11) ---------------
        fun getFullJid(): Jid?

        /** Return-position drag: the model answers `Contact`, which implements `ContactRef`. */
        fun getContact(): ContactRef?

        fun setPgpKeyId(keyId: Long)

        /** `boolean`, not `void`: the class's own declaration, mirrored here. */
        fun setAvatar(avatar: Avatar?): Boolean

        fun setOccupantId(occupantId: String?)

        // -- what `MessageParser` reads and writes on a parsed participant (part 12) -----------------
        fun realJidMatchesAccount(): Boolean

        /** `boolean`, not `void`: the model's `setChatState` answers whether it changed. */
        fun setChatState(chatState: ChatState): Boolean

        /**
         * **Distinctly named on purpose**, and for exactly the reason [shownStatus]
         * is: the model's `getAffiliation()` answers the model enum, and a class may not have two
         * methods differing only in return type, so an island override is impossible. The name adapts
         * and `MucOptions.User` supplies the mapping. The island only ever asks whether the
         * affiliation ranks at least [MEMBER] - a value comparison on copied
         * ranks, never an identity one - so nothing about the model enum has to move.
         */
        fun affiliation(): AffiliationRef

        // -- C5-E2: what `XmppConnectionService` reads and writes on a room's participant ----------

        /** Read at `XCS:5022, 5447, 5471, 5783`; the model's own declaration. */
        fun getNick(): String?

        /**
         * Covariant: the model's `MucOptions.User.getConversation()` answers `Conversation`, which
         * implements [ConversationRef]. Read at `XCS:5146`'s neighbours.
         */
        fun getConversation(): ConversationRef

        fun setOnline(online: Boolean)

        /** Read at `XCS:777, 784, 789` (the mute trio). */
        fun getMuc(): Jid

        /**
         * Tulkki: C5-E2 - one member the brief's list did not name, and the additive build is what
         * found it: the brief says `DatabaseBackend`'s mute pair reads `getMuc()`/`getOccupantId()`
         * "both on the ref", but only `getMuc()` was. `XCS`'s own reaction paths read it off
         * `getSelf()` too, so the retype needs it either way. The model's own declaration.
         */
        fun getOccupantId(): String?


    }

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.MucOptions.Affiliation`.
     *
     * The enum ruling's third case, and the one the census could not see as a shape: it is
     * declared here with **the model's own ranks copied**, because `Affiliation` does not use
     * ordinals - `OUTCAST` ranks below `NONE` - and `ranks` is the only operation the island asks for.
     * `toString()` is copied too, so the one log line that printed the affiliation prints the same
     * text.
     *
     * The stop condition is live here as it is for `ErrorRef`: if a site ever made an island
     * `AffiliationRef` and a model `MucOptions.Affiliation` meet in one comparison, that is a report,
     * not a commit. No site does - the island only calls `ranks` against its own constant.
     */
    enum class AffiliationRef(private val rank: Int) {
        OWNER(4),
        ADMIN(3),
        MEMBER(2),
        OUTCAST(0),
        NONE(1);

        fun ranks(affiliation: AffiliationRef): Boolean = rank >= affiliation.rank

        override fun toString(): String = name.lowercase(Locale.US)
    }

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.MucOptions.Hat`.
     *
     * **A plain marker, and that is a forced change of shape.** `AbstractParser.parseItem`
     * collects hats into a `TreeSet` and a `TreeSet` needs an ordering on its element type, so the
     * first shape tried here was `interface HatRef extends Comparable<HatRef>` with
     * `MucOptions.Hat implements Comparable<Hat>, HatRef`. **That does not compile**, and not for a
     * style reason: javac rejects the class with "Comparable cannot be inherited with different
     * arguments", because `Comparable<T>` is invariant and a class may not inherit the same generic
     * interface with two type arguments. There is no spelling of a bound that fixes it.
     *
     * So the ref stays a marker and the **ordering is supplied by the port**:
     * `XmppConnectionService.DataStatics.hatOrder()` returns a `Comparator<HatRef>` built over the
     * model's own ordering, which is `toString().compareTo(...)` and therefore identical to
     * `Hat.compareTo`. The island builds `new TreeSet<>(dataStatics().hatOrder())`, the ordering is
     * the model's, and nothing about the sort changes.
     */
    interface HatRef {


    }

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.MucOptions.Error`.
     *
     * The enum ruling's second case, and the safe one: **the island only ever sets it** - 19 sites,
     * all in `PresenceParser` - and never reads or compares it, so unlike [PresenceRef.StatusRef]
     * there is no identity to preserve and no risk in the island owning an enum of its own. Same 16
     * constants, in the model's own order; `MucOptions.setError(ErrorRef)` maps name for name.
     *
     * The stop condition attached to the ruling is live here too: if a site ever made an island
     * `ErrorRef` and a model `MucOptions.Error` meet in one comparison, that is a report, not a commit.
     */
    enum class ErrorRef {
        NO_RESPONSE,
        SERVER_NOT_FOUND,
        REMOTE_SERVER_TIMEOUT,
        NONE,
        NICK_IN_USE,
        PASSWORD_REQUIRED,
        BANNED,
        MEMBERS_ONLY,
        RESOURCE_CONSTRAINT,
        KICKED,
        SHUTDOWN,
        DESTROYED,
        INVALID_NICK,
        TECHNICAL_PROBLEMS,
        UNKNOWN,
        NON_ANONYMOUS,
    }

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.MucOptions.OnRenameListener`.
     *
     * The model's own listener is an empty interface extending a private one that declares these
     * two methods, so `:data` satisfies this ref by adding it to the `extends` list - one body on the
     * model side, the model's existing one, and no adapter.
     */
    interface OnRenameListenerRef {

        fun onSuccess()

        fun onFailure()


    }

    /** Covariant in `:data`: the model returns `List<User>` and `User` implements [UserRef]. */
    fun getUsers(max: Int): List<UserRef>

    fun getUserCount(): Int

    fun online(): Boolean

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun setOnline(): Boolean

    fun getAvatar(): String?

    fun occupantId(): Boolean

    fun isPrivateAndNonAnonymous(): Boolean

    fun autoPushConfiguration(): Boolean

    /** Return-position drags: the model answers `Conversation`/`Account`, both of which implement. */
    fun getConversation(): ConversationRef

    fun getAccount(): AccountRef

    fun getSelf(): UserRef

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun setSelf(user: MucOptionsRef.UserRef): Boolean

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun updateUser(user: MucOptionsRef.UserRef): Boolean

    /** Covariant: the model's `deleteUser(Jid)` answers `User`, which implements [UserRef]. */
    fun deleteUser(jid: Jid): UserRef?

    /** The overload the island calls; `:data` maps the island enum by name on its first line. */
    fun setError(error: MucOptionsRef.ErrorRef)

    // -- the one member that was a *field* on the model ---------------------------------------------
    //
    // `MucOptions.onRenameListener` is a public mutable field the island reads, invokes and assigns
    // `null` to. An interface carries no mutable field, so the ref carries an accessor **and** a
    // nullable setter, and `setOnRenameListener(OnRenameListenerRef)` is an *overload* of the model's
    // own `setOnRenameListener(OnRenameListener)`: legal, because the two parameter types do not erase
    // alike. The field itself stays where it is, so every existing `:ui` site is untouched.
    fun onRenameListener(): OnRenameListenerRef?

    fun setOnRenameListener(listener: MucOptionsRef.OnRenameListenerRef?)

    // -- part 12: the room surface `MessageParser` reads --------------------------------------------
    fun getMembers(includeDomains: Boolean): List<@JvmSuppressWildcards Jid>

    /** Covariant: the model's `findUserByFullJid` answers `User`, which implements [UserRef]. */
    fun findUserByFullJid(jid: Jid?): UserRef?

    /** Covariant: the model's `findUserByOccupantId` answers `User`. */
    fun findUserByOccupantId(occupantId: String?, counterpart: Jid?): UserRef?

    fun getTrueCounterpart(jid: Jid): Jid?

    /**
     * Two overloads, and the file calls **both**: the `Jid` one with a parsed counterpart, the
     * `String` one with an occupant id out of a reactions payload. They are the model's own pair and
     * neither erases into the other.
     */
    fun isSelf(counterpart: Jid): Boolean

    fun isSelf(occupantId: String): Boolean

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun setSubject(subject: String?): Boolean

    fun setPassword(password: String?)

    /**
     * Tulkki: part 12. `MessageParser.extractStanzaId` asks the room whether it advertises
     * XEP-0359 before it trusts an id; the model's own predicate, unchanged.
     */
    fun hasFeature(feature: String): Boolean

    // -- part 17: what the generators read -----------------------------------------------------------

    /** The model's own predicate (`getFeatures().contains(MUC#stable_id)`), mirrored. */
    fun stableId(): Boolean

    fun getPassword(): String?

    /**
     * Tulkki: part 17 - `MessageArchiveService.Version` asks the room for its feature list when it
     * decides which MAM version to speak. Return-position drag: the model answers `List<String>`.
     */
    fun getFeatures(): List<String>

    // -- C5-E2: what `XmppConnectionService` reads and writes on a room -------------------------------
    //
    // Every member below is a declaration `MucOptions` already has, except `error()` (the model's
    // `getError()` returns the model enum, so the island name adapts - `ContactRef.shownStatus()`'s
    // precedent) and the `changeAffiliation(Jid, AffiliationRef)` overload, which maps back by name.
    // Nothing is added ahead of a consumer.

    /** `XCS:3824, 7456, 7529` - the "are we in this room at all" predicate. */
    fun participating(): Boolean

    /** `XCS:3913, 5029, 5053` - whether the room's MAM is usable. */
    fun mamSupport(): Boolean

    /** `XCS:3963`; the model already takes a [ContactRef], so nothing moves. */
    fun isContactInRoom(contact: ContactRef?): Boolean

    fun resetChatState()

    fun flagNoAutoPushConfiguration()

    /** `XCS:4997, 5020, 5447, 5471, 5542, 5782`. */
    fun nonanonymous(): Boolean

    fun membersOnly(): Boolean

    fun getActualNick(): String?

    fun getProposedNickPure(): String

    fun createJoinJid(nick: String): Jid?

    fun setOffline()

    fun getName(): String?

    /** Covariant: the model answers `User`, which implements [UserRef]. */
    fun findUserByRealJid(jid: Jid?): UserRef?

    /**
     * Tulkki: C5-E2 - `XCS:5758` hands this the result of `DataStatics.newServiceDiscoveryResult`, and
     * `MucOptions` already declares the ref-typed overload (`MucOptions.java:157`), so the model pays
     * no body. One member the brief's list did not name: without it the retyped local at `XCS:5750`
     * would have to stay model-typed and keep the import.
     */
    fun updateConfiguration(serviceDiscoveryResult: ServiceDiscoveryResultRef): Boolean

    /**
     * Tulkki: C5-E2 - **distinctly named**, the `getError()` the island cannot override. The four
     * casts `XCS:1619, 3394, 3418, 4300` carried exist only because the model's own name was
     * unreachable through the ref; with this member they are deleted rather than guarded.
     * `MucOptions.error()` maps name for name, the mirror of its existing `setError(ErrorRef)`.
     */
    fun error(): ErrorRef

    /**
     * Tulkki: C5-E2 - the write side of `changeAffiliation`, in island vocabulary. The model's own
     * `changeAffiliation(Jid, Affiliation)` stays (nothing outside the island calls it); this
     * overload maps the island enum back by name and delegates, so the user-list bookkeeping is not
     * duplicated. `Affiliation.valueOf` would silently answer `NONE` for an unrecognised name, which
     * is safe here because the island's enum has exactly the model's five constants - and
     * `MucOptionsEnumsRefTest` pins that.
     */
    fun changeAffiliation(jid: Jid, affiliation: MucOptionsRef.AffiliationRef)

    /**
     * Tulkki: C5-E2 - the island's view of `uk.xa0.tulkki.data.model.MucOptions.Role`.
     *
     * The model's four constants in its own order, and its own `toString()` copied
     * (`name().toLowerCase(Locale.US)`). The island only ever **stringifies** it - `XCS:5948`
     * passes it to `IqGenerator.changeRole`, which takes a `String` - so no identity is involved, no
     * mapping back is needed, and the `resId`/`rank` fields are deliberately not copied: nothing in
     * the island reads them.
     *
     * Pin it with a test anyway, and `MucOptionsEnumsRefTest` does: a copied `toString()` with no
     * test is exactly the silent drift the briefs warn about.
     */
    enum class RoleRef {
        MODERATOR,
        VISITOR,
        PARTICIPANT,
        NONE;

        override fun toString(): String = name.lowercase(Locale.US)
    }

    companion object {
        // -- what `PresenceParser` reads on the room's options (part 11) --------------------------------

        /**
         * Tulkki: the `MucOptions.STATUS_CODE_*` strings, copied.
         *
         * They are `public static final String`s - compile-time constants with no identity - so the
         * island can hold its own copies exactly as `ContactRef.OptionsRef` holds the option bits, and
         * `PresenceParser`'s nine `codes.contains(...)` tests read them from here rather than importing
         * the model type for nine literals. The values are the model's own.
         */
        @JvmField
        val STATUS_CODE_SELF_PRESENCE: String = "110"

        @JvmField
        val STATUS_CODE_ROOM_CREATED: String = "201"

        @JvmField
        val STATUS_CODE_BANNED: String = "301"

        @JvmField
        val STATUS_CODE_CHANGED_NICK: String = "303"

        @JvmField
        val STATUS_CODE_KICKED: String = "307"

        @JvmField
        val STATUS_CODE_AFFILIATION_CHANGE: String = "321"

        @JvmField
        val STATUS_CODE_LOST_MEMBERSHIP: String = "322"

        @JvmField
        val STATUS_CODE_SHUTDOWN: String = "332"

        @JvmField
        val STATUS_CODE_TECHNICAL_REASONS: String = "333"

    }
}
