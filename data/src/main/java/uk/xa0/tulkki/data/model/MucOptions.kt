package uk.xa0.tulkki.data.model

import android.content.Context
import android.net.Uri
import com.google.common.base.Predicate
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import io.ipfs.cid.Cid
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.crypto.OmemoMucOptions
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.utils.JidHelper

/** Java's private `normalize(Jid, String)`: a nick the account's bare JID accepts as a resource. */
private fun normalize(account: Jid?, nick: String?): String? {
    if (account == null || Strings.isNullOrEmpty(nick)) {
        return null
    }

    try {
        return account.withResource(nick ?: throw NullPointerException()).getResource()
    } catch (e: IllegalArgumentException) {
        return null
    }
}

/**
 * One MUC's options: its users and self, the disco result that decides its features, the room-info
 * form, the join JID and the error state.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The nested `User`'s shared state is `internal`, not private.** In Java the outer class reads
 *    and writes `User`'s `role`, `affiliation`, `realJid`, `fullJid`, `nick`, `avatar`, `hats` and
 *    `chatState` directly; in Kotlin the outer class cannot reach a nested class's private members
 *    (measured in reverse when porting `Reaction.Aggregated`), and Kotlin has no package-private, so
 *    those eight are `internal var` and the rest stay private. No `@JvmField`: no Java field reader
 *    exists outside this file (measured - the island reads `affiliation()` and the getters). The
 *    explicit `getNick()`/`getRole()`/`getAffiliation()`/`getRealJid()` are unaffected, because a
 *    private or internal property emits no accessor beside them.
 * 2. **`account` is `internal val`** for the same reason, one level up: `User.realJidMatchesAccount()`
 *    reads `options.account` (Java's line 1277). `conversation` stays private.
 * 3. **`onRenameListener` is a private `var`.** Java's was a public mutable field, but no Java code
 *    outside this file reads it (measured: `PresenceParser:332` calls
 *    `MucOptionsRef.onRenameListener()`, which this class answers); a Kotlin `var` property would
 *    synthesise `getOnRenameListener()`/`setOnRenameListener(OnRenameListener?)`, the latter clashing
 *    with Java's own setter.
 * 4. **The private `OnEventListener` marker is dropped.** `OnRenameListener` extends only
 *    [`MucOptionsRef.OnRenameListenerRef`], which declares the same two methods the private marker
 *    declared, so every implementor's surface is unchanged and no public interface exposes a private
 *    supertype.
 * 5. **`createNameFromParticipants()` is public where Java's was package-private.** It has one caller,
 *    `Conversation:1181`, in the same package; `internal` would mangle the JVM name to
 *    `createNameFromParticipants$data` and break that javac call, and Kotlin cannot express
 *    package-private at all.
 * 6. **Two statics are `@JvmStatic`s, and the nine status codes are companion `const val`s**
 *    (static fields on the class, which is what the island's copies were made from).
 *    `defaultNick` has Java callers at `DataStaticsHost:498`, `StartConversationActivity:737` and
 *    `:1525`; `sub` at `ConferenceDetailsActivity:920`. `Affiliation.of`/`Role.of` have **no** Java
 *    caller, so they carry no annotation. **Interop debt: two `@JvmStatic`s.**
 * 7. **`getRoomInfoForm()` reads `ServiceDiscoveryResult.forms`**, which the commit before this one
 *    made `@JvmField internal` for exactly this file; `features` is read through the public
 *    `getFeatures()` override, so it needed no change.
 * 8. **`conversation.account` becomes `conversation.getAccount()`**: Java's `Conversation.account` is
 *    a `protected` field, unreadable from Kotlin, and the getter answers the same object.
 * 9. **`User`'s `options` stays nullable** and the five sites Java dereferences it are explicit
 *    `?: throw NullPointerException()` - Java's own NPE, at Java's own trigger. The constructor keeps
 *    Java's `options != null` tolerance.
 * 10. **The traps this row measured are closed here**: `name.split("\\s+")[0]` becomes
 *     `Pattern.compile("\\s+").split(name, 0)[0]`; `getOccupantId().charAt(0)` becomes `[0]`
 *     (Kotlin's `String` does not expose `charAt`); `compareToIgnoreCase` becomes
 *     `compareTo(other, ignoreCase = true)`; `Long.parseLong(hideTs)` becomes `hideTs.toLong()`;
 *     `String.valueOf(long)` becomes `toString()`. `toLowerCase(Locale.US)`/`toUpperCase(Locale.US)`
 *     keep their explicit locale, so no locale trap applies.
 * 11. **Java's oddities are kept, not tidied**: `getUsers(boolean)` ignores its parameter and calls
 *     `getUsers(true, false)`; `changed |= x` becomes `changed = changed or x` (Kotlin's `or` is a
 *     function call, so the right-hand side is still always evaluated, as Java's `|=` evaluated it);
 *     `getPgpKeyIds()` iterates the user set without the lock, as Java did.
 *
 * Nothing in the tree extends `MucOptions`, so Kotlin's implicit `final` is Java's own shape.
 */
class MucOptions(
    private val conversation: Conversation,
) : OmemoMucOptions, MucOptionsRef {

    private val users: MutableSet<User> = HashSet()

    internal val account: Account = conversation.getAccount()!!

    private var mAutoPushConfiguration = true

    private var serviceDiscoveryResult: ServiceDiscoveryResult? = null

    private var isOnline = false

    private var error: Error = Error.NONE

    private var password: String? = null

    private var onRenameListener: OnRenameListener? = null

    private var self: User

    init {
        val nick = getProposedNick(conversation.getAttribute("mucNick"))
        val selfUser = User(this, createJoinJid(nick), null, nick, HashSet())
        selfUser.affiliation = Affiliation.of(conversation.getAttribute("affiliation"))
        selfUser.role = Role.of(conversation.getAttribute("role"))
        this.self = selfUser
    }

    override fun getAccount(): Account = conversation.getAccount()!!

    fun setSelf(user: User): Boolean {
        this.self = user
        val roleChanged = conversation.setAttribute("role", user.role.toString())
        val affiliationChanged = conversation.setAttribute("affiliation", user.affiliation.toString())
        conversation.setAttribute("mucNick", user.getNick())
        return roleChanged || affiliationChanged
    }

    /**
     * Tulkki: the island's overload.
     *
     * <p>3.7 pair 9, part 11. Same fact as [updateUser]: a parameter type is not covariant, the
     * island's object is a `User` built by the port, and the cast is identity-safe.
     */
    override fun setSelf(user: MucOptionsRef.UserRef): Boolean = setSelf(user as User)

    fun changeAffiliation(jid: Jid, affiliation: Affiliation) {
        var user = findUserByRealJid(jid)
        synchronized(users) {
            if (user == null) {
                user = User(this, null, null, null, HashSet())
                user.setRealJid(jid)
                user.setOnline(false)
                users.add(user)
            }
            user.affiliation = affiliation
        }
    }

    /**
     * Tulkki: 3.7 C5-E2 - the island's overload of [changeAffiliation], the same shape as
     * [setError]. The mapping is name for name and delegates, so the "create the user if the room does
     * not know them yet" bookkeeping above stays in one place.
     */
    override fun changeAffiliation(jid: Jid, affiliation: MucOptionsRef.AffiliationRef) {
        changeAffiliation(jid, Affiliation.valueOf(affiliation.name))
    }

    override fun flagNoAutoPushConfiguration() {
        mAutoPushConfiguration = false
    }

    override fun autoPushConfiguration(): Boolean = mAutoPushConfiguration

    override fun isSelf(counterpart: Jid): Boolean = counterpart == self.getFullJid()

    override fun isSelf(occupantId: String): Boolean = occupantId == self.getOccupantId()

    override fun resetChatState() {
        synchronized(users) {
            for (user in users) {
                user.chatState = Config.DEFAULT_CHAT_STATE
            }
        }
    }

    override fun mamSupport(): Boolean = MessageArchiveService.Version.has(getFeatures())

    /**
     * Tulkki: 3.7 pair 9, part 14. The island builds a disco result through
     * `XmppConnectionService.DataStatics.newServiceDiscoveryResult` and holds the ref, so the one
     * cast lives here rather than in the island.
     */
    override fun updateConfiguration(serviceDiscoveryResult: ServiceDiscoveryResultRef): Boolean =
        updateConfiguration(serviceDiscoveryResult as ServiceDiscoveryResult)

    fun updateConfiguration(serviceDiscoveryResult: ServiceDiscoveryResult): Boolean {
        this.serviceDiscoveryResult = serviceDiscoveryResult
        val name: String?
        val roomConfigName = getRoomInfoForm().getFieldByName("muc#roomconfig_roomname")
        if (roomConfigName != null) {
            name = roomConfigName.getValue()
        } else {
            val identities = serviceDiscoveryResult.getIdentities()
            val identityName = if (identities.isNotEmpty()) identities[0].getName() else null
            val jid = conversation.getJid()!!
            name = if (identityName != null && identityName != jid.getLocal()) identityName else null
        }
        var changed = conversation.setAttribute("muc_name", name)
        changed =
            changed or
            conversation.setAttribute(
                Conversation.ATTRIBUTE_MEMBERS_ONLY,
                hasFeature("muc_membersonly"),
            )
        changed =
            changed or
            conversation.setAttribute(
                Conversation.ATTRIBUTE_MODERATED,
                hasFeature("muc_moderated"),
            )
        changed =
            changed or
            conversation.setAttribute(
                Conversation.ATTRIBUTE_NON_ANONYMOUS,
                hasFeature("muc_nonanonymous"),
            )
        return changed
    }

    private fun getRoomInfoForm(): Data {
        val forms: List<Data> =
            serviceDiscoveryResult?.forms ?: Collections.emptyList()
        return if (forms.isEmpty()) Data() else forms[0]
    }

    /**
     * The avatar filename the roster contact answers, or `null` when it has none: [Contact]'s own
     * getter is nullable and this return type used to say otherwise. `MucOptionsRef.getAvatar()`
     * declares a platform `String`, so the nullable override satisfies it unchanged, and the one
     * caller (`AvatarService:363`) passes it to still-Java `FileBackends.getAvatar`.
     */
    override fun getAvatar(): String? =
        account.getRoster().getContact(conversation.getJid()!!).getAvatarFilename()

    override fun hasFeature(feature: String): Boolean {
        val disco = serviceDiscoveryResult ?: return false
        return disco.getFeatures().contains(feature)
    }

    fun hasVCards(): Boolean = hasFeature("vcard-temp")

    fun canInvite(): Boolean {
        val hasPermission = !membersOnly() || self.getRole().ranks(Role.MODERATOR) || allowInvites()
        return hasPermission && online()
    }

    fun allowInvites(): Boolean {
        val field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowinvites")
        return field != null && "1" == field.getValue()
    }

    fun canChangeSubject(): Boolean =
        self.getRole().ranks(Role.MODERATOR) || participantsCanChangeSubject()

    fun participantsCanChangeSubject(): Boolean {
        val configField = getRoomInfoForm().getFieldByName("muc#roomconfig_changesubject")
        val infoField = getRoomInfoForm().getFieldByName("muc#roominfo_changesubject")
        val field = configField ?: infoField
        return field != null && "1" == field.getValue()
    }

    fun allowPm(): Boolean {
        val field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowpm")
        if (field == null) {
            return true // fall back if field does not exists
        }
        return if ("anyone" == field.getValue()) {
            true
        } else if ("participants" == field.getValue()) {
            self.getRole().ranks(Role.PARTICIPANT)
        } else if ("moderators" == field.getValue()) {
            self.getRole().ranks(Role.MODERATOR)
        } else {
            false
        }
    }

    fun allowPmRaw(): Boolean {
        val field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowpm")
        return field == null || listOf("anyone", "participants").contains(field.getValue())
    }

    override fun participating(): Boolean =
        self.getRole().ranks(Role.PARTICIPANT) || !moderated()

    override fun membersOnly(): Boolean =
        conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MEMBERS_ONLY, false)

    override fun getFeatures(): List<String> =
        serviceDiscoveryResult?.getFeatures() ?: Collections.emptyList()

    override fun nonanonymous(): Boolean =
        conversation.getBooleanAttribute(Conversation.ATTRIBUTE_NON_ANONYMOUS, false)

    override fun isPrivateAndNonAnonymous(): Boolean = membersOnly() && nonanonymous()

    fun moderated(): Boolean =
        conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MODERATED, false)

    override fun stableId(): Boolean =
        getFeatures().contains("http://jabber.org/protocol/muc#stable_id")

    override fun occupantId(): Boolean = getFeatures().contains(Namespace.OCCUPANT_ID)

    override fun deleteUser(jid: Jid): User? {
        val user = findUserByFullJid(jid)
        if (user != null) {
            synchronized(users) {
                users.remove(user)
                var realJidInMuc = false
                for (u in users) {
                    val userRealJid = user.realJid
                    if (userRealJid != null && userRealJid == u.realJid) {
                        realJidInMuc = true
                        break
                    }
                }
                val userRealJid = user.realJid
                val selfUser = userRealJid != null && userRealJid == account.getJid().asBareJid()
                if (membersOnly() &&
                    nonanonymous() &&
                    user.affiliation.ranks(Affiliation.MEMBER) &&
                    userRealJid != null &&
                    !realJidInMuc &&
                    !selfUser
                ) {
                    user.role = Role.NONE
                    user.avatar = null
                    user.fullJid = null
                    users.add(user)
                }
            }
        }
        return user
    }

    // returns true if real jid was new;
    fun updateUser(user: User): Boolean {
        var old: User? = null
        var realJidFound = false
        val userRealJid = user.realJid
        val userFullJid = user.fullJid
        if (userFullJid == null && userRealJid != null) {
            old = findUserByRealJid(userRealJid)
            realJidFound = old != null
            if (old != null) {
                if (old.fullJid != null) {
                    return false // don't add. user already exists
                } else {
                    synchronized(users) {
                        users.remove(old)
                    }
                }
            }
        } else if (userRealJid != null) {
            old = findUserByRealJid(userRealJid)
            realJidFound = old != null
            synchronized(users) {
                if (old != null && (old.fullJid == null || old.role == Role.NONE)) {
                    users.remove(old)
                }
            }
        }
        old = findUserByFullJid(user.getFullJid())

        synchronized(users) {
            if (old != null) {
                users.remove(old)
                if (old.nick != null &&
                    user.nick == null &&
                    (old.getName() ?: throw NullPointerException()) == user.getName()
                ) {
                    user.nick = old.nick
                }
                if (old.hats != null && user.hats == null) {
                    user.hats = old.hats
                }
                if (old.avatar != null && user.avatar == null) {
                    user.avatar = old.avatar
                }
            }
            val fullJidIsSelf =
                isOnline && user.getFullJid() != null && user.getFullJid() == self.getFullJid()
            if (!fullJidIsSelf) {
                users.add(user)
                return !realJidFound && user.realJid != null
            }
        }
        return false
    }

    /**
     * Tulkki: the island's overload.
     *
     * <p>3.7 pair 9, part 11. A parameter type is not covariant, so the model's own [updateUser]
     * cannot satisfy `MucOptionsRef.updateUser(UserRef)`; the object the island passes really is a
     * `User` (it is built by `DataStatics.newUser`), so the cast is identity-safe.
     */
    override fun updateUser(user: MucOptionsRef.UserRef): Boolean = updateUser(user as User)

    fun findUserByName(name: String?): User? {
        val requested = name ?: return null
        synchronized(users) {
            for (user in users) {
                if (requested == user.getName()) {
                    return user
                }
            }
        }
        return null
    }

    override fun findUserByFullJid(jid: Jid?): User? {
        val requested = jid ?: return null
        synchronized(users) {
            for (user in users) {
                if (requested == user.getFullJid()) {
                    return user
                }
            }
        }
        return null
    }

    override fun findUserByRealJid(jid: Jid?): User? {
        val requested = jid ?: return null
        val bare = requested.asBareJid()
        synchronized(users) {
            for (user in users) {
                if (bare == user.realJid) {
                    return user
                }
            }
        }
        return null
    }

    override fun findUserByOccupantId(occupantId: String?, counterpart: Jid?): User? =
        synchronized(users) {
            val found: User? =
                if (Strings.isNullOrEmpty(occupantId)) {
                    null
                } else {
                    Iterables.find(
                        users,
                        Predicate { u -> occupantId == u.getOccupantId() },
                        null,
                    )
                }
            if (Strings.isNullOrEmpty(occupantId) || found != null) {
                found
            } else {
                val user = User(this, counterpart, occupantId, null, HashSet())
                user.setOnline(false)
                user
            }
        }

    fun findOrCreateUserByRealJid(jid: Jid, fullJid: Jid?, occupantId: String?): User {
        val existing = findUserByRealJid(jid)
        if (existing != null) {
            return existing
        }
        val user = User(this, fullJid, occupantId, null, HashSet())
        user.setRealJid(jid)
        user.setOnline(false)
        return user
    }

    fun findUser(readByMarker: ReadByMarker): User? {
        val realJid = readByMarker.getRealJid()
        if (realJid != null) {
            return findOrCreateUserByRealJid(
                realJid.asBareJid(),
                readByMarker.getFullJid(),
                null,
            )
        } else if (readByMarker.getFullJid() != null) {
            return findUserByFullJid(readByMarker.getFullJid())
        } else {
            return null
        }
    }

    private fun findUser(reaction: Reaction): User? {
        val trueJid = reaction.trueJid
        if (trueJid != null) {
            return findOrCreateUserByRealJid(
                trueJid.asBareJid(),
                reaction.from,
                reaction.occupantId,
            )
        }
        val existing = findUserByOccupantId(reaction.occupantId, reaction.from)
        return if (existing != null) {
            existing
        } else if (reaction.from != null) {
            User(this, reaction.from, reaction.occupantId, null, HashSet())
        } else {
            null
        }
    }

    fun findUsers(reactions: Collection<Reaction>): List<User> {
        val builder = ImmutableList.builder<User>()
        for (reaction in reactions) {
            val user = findUser(reaction)
            if (user != null) {
                builder.add(user)
            }
        }
        return builder.build()
    }

    /**
     * Tulkki: 3.7 pair 9, part 15 - the parameter is the island's ref now, because the island's one
     * caller holds a `ContactRef`. No cast is needed: the only member read is `getJid()`.
     */
    override fun isContactInRoom(contact: ContactRef?): Boolean =
        contact != null && isUserInRoom(findUserByRealJid(contact.getJid().asBareJid()))

    fun isUserInRoom(jid: Jid?): Boolean = isUserInRoom(findUserByFullJid(jid))

    fun isUserInRoom(user: User?): Boolean = user != null && user.isOnline()

    override fun setOnline(): Boolean {
        val before = isOnline
        isOnline = true
        return !before
    }

    fun getUsers(): ArrayList<User> = getUsers(true)

    fun getUsers(includeOffline: Boolean): ArrayList<User> = getUsers(true, false)

    fun getUsers(includeOffline: Boolean, includeOutcast: Boolean): ArrayList<User> =
        synchronized(users) {
            val result = ArrayList<User>()
            for (user in users) {
                if (!user.isDomain() &&
                    (
                        if (includeOffline) {
                            includeOutcast || user.getAffiliation().ranks(Affiliation.NONE)
                        } else {
                            user.getRole().ranks(Role.PARTICIPANT)
                        }
                    )
                ) {
                    result.add(user)
                }
            }
            result
        }

    fun getUsersByRole(role: Role): ArrayList<User> =
        synchronized(users) {
            val list = ArrayList<User>()
            for (user in users) {
                if (user.getRole().ranks(role)) {
                    list.add(user)
                }
            }
            list
        }

    fun getUsersWithChatState(state: ChatState, max: Int): ArrayList<User> =
        synchronized(users) {
            val list = ArrayList<User>()
            for (user in users) {
                if (user.chatState == state) {
                    list.add(user)
                    if (list.size >= max) {
                        break
                    }
                }
            }
            list
        }

    override fun getUsers(max: Int): List<User> {
        val subset = ArrayList<User>()
        val addresses = HashSet<Jid>()
        addresses.add(account.getJid().asBareJid())
        synchronized(users) {
            for (user in users) {
                val realJid = user.getRealJid()
                if (realJid == null || (realJid.getLocal() != null && addresses.add(realJid))) {
                    subset.add(user)
                }
                if (subset.size >= max) {
                    break
                }
            }
        }
        return subset
    }

    override fun getUserCount(): Int = synchronized(users) { users.size }

    private fun getProposedNick(): String = getProposedNick(null)

    private fun getProposedNick(mucNick: String?): String {
        val bookmark = conversation.getBookmark()
        if (bookmark != null) {
            // if we already have a bookmark we consider this the source of truth
            return getProposedNickPure()
        }
        val storedJid = conversation.getJid()!!
        return if (mucNick != null) {
            mucNick
        } else if (storedJid.isBareJid()) {
            defaultNick(account)
        } else {
            storedJid.getResource() ?: throw NullPointerException()
        }
    }

    override fun getProposedNickPure(): String {
        val bookmark = conversation.getBookmark()
        val bookmarkedNick = normalize(account.getJid(), bookmark?.getNick())
        return bookmarkedNick ?: defaultNick(account)
    }

    override fun getActualNick(): String? {
        val nick = self.getNick()
        return nick ?: getProposedNick()
    }

    fun getActualName(): String? {
        val name = self.getName()
        return name ?: getProposedNick()
    }

    override fun online(): Boolean = isOnline

    fun getError(): Error = error

    fun setError(error: Error) {
        isOnline = isOnline && error == Error.NONE
        this.error = error
    }

    /**
     * Tulkki: the island's overload, and the enum ruling's safe case.
     *
     * <p>3.7 pair 9, part 11. The island only ever **sets** this and never reads or compares the
     * model enum, so an island `ErrorRef` carries no identity risk and the mapping is name for name.
     * `valueOf` is total over the sixteen shared constants and fails loudly if the two families ever
     * drift.
     */
    override fun setError(error: MucOptionsRef.ErrorRef) {
        setError(Error.valueOf(error.name))
    }

    /**
     * Tulkki: 3.7 C5-E2 - the read half, and the mirror of [setError] two methods up.
     */
    override fun error(): MucOptionsRef.ErrorRef = MucOptionsRef.ErrorRef.valueOf(error.name)

    fun setOnRenameListener(listener: OnRenameListener?) {
        onRenameListener = listener
    }

    /**
     * Tulkki: the island's overload of the model's own setter.
     *
     * <p>3.7 pair 9, part 11, and the one member of this ref that was a **field** rather than a
     * method. `OnRenameListener` extends the ref, so the cast is identity-safe.
     */
    override fun setOnRenameListener(listener: MucOptionsRef.OnRenameListenerRef?) {
        onRenameListener = listener as OnRenameListener?
    }

    /**
     * Tulkki: the field, as the island's reader sees it.
     *
     * <p>An interface cannot carry a mutable public field, so the island asks for the value; the
     * nullable answer is what its `!= null` test used to be.
     */
    override fun onRenameListener(): MucOptionsRef.OnRenameListenerRef? = onRenameListener

    override fun setOffline() {
        synchronized(users) {
            users.clear()
        }
        error = Error.NO_RESPONSE
        isOnline = false
    }

    override fun getSelf(): User = self

    override fun setSubject(subject: String?): Boolean =
        conversation.setAttribute("subject", subject)

    fun getSubject(): String? = conversation.getAttribute("subject")

    fun hideSubject() {
        val subjectTs = conversation.getAttribute("subjectTs")

        if (subjectTs == null) {
            conversation.setAttribute("subjectTs", (System.currentTimeMillis() - 1).toString())
        }

        conversation.setAttribute("subjectHideTs", System.currentTimeMillis().toString())
    }

    fun subjectHidden(): Boolean {
        val subjectTs = conversation.getAttribute("subjectTs")
        val hideTs = conversation.getAttribute("subjectHideTs")

        return if (subjectTs == null || hideTs == null) {
            false
        } else {
            hideTs.toLong() >= subjectTs.toLong()
        }
    }

    override fun getName(): String? = conversation.getAttribute("muc_name")

    private fun getFallbackUsersFromCryptoTargets(): List<User> {
        val users = ArrayList<User>()
        for (jid in conversation.getAcceptedCryptoTargets()) {
            val user = User(this, null, null, null, HashSet())
            user.setRealJid(jid)
            users.add(user)
        }
        return users
    }

    fun getUsersRelevantForNameAndAvatar(): List<User> =
        if (isOnline) getUsers(5) else getFallbackUsersFromCryptoTargets()

    fun createNameFromParticipants(): String? {
        val users = getUsersRelevantForNameAndAvatar()
        if (users.size >= 2) {
            val builder = StringBuilder()
            for (user in users) {
                if (builder.length != 0) {
                    builder.append(", ")
                }
                val name = DisplayNames.getDisplayName(user)
                if (name != null) {
                    builder.append(Pattern.compile("\\s+").split(name, 0)[0])
                }
            }
            return builder.toString()
        } else {
            return null
        }
    }

    override fun getPgpKeyIds(): LongArray {
        val ids = ArrayList<Long>()
        for (user in users) {
            if (user.getPgpKeyId() != 0L) {
                ids.add(user.getPgpKeyId())
            }
        }
        ids.add(account.getPgpId())
        val primitiveLongArray = LongArray(ids.size)
        for (i in ids.indices) {
            primitiveLongArray[i] = ids[i]
        }
        return primitiveLongArray
    }

    fun pgpKeysInUse(): Boolean {
        synchronized(users) {
            for (user in users) {
                if (user.getPgpKeyId() != 0L) {
                    return true
                }
            }
        }
        return false
    }

    fun everybodyHasKeys(): Boolean {
        synchronized(users) {
            for (user in users) {
                if (user.getPgpKeyId() == 0L) {
                    return false
                }
            }
        }
        return true
    }

    override fun createJoinJid(nick: String): Jid? = createJoinJid(nick, true)

    private fun createJoinJid(nick: String, tryFix: Boolean): Jid? =
        try {
            conversation.getJid()!!.withResource(nick)
        } catch (e: IllegalArgumentException) {
            try {
                if (tryFix) createJoinJid(gnu.inet.encoding.Punycode.encode(nick), false) else null
            } catch (e2: Exception) {
                null
            }
        }

    override fun getTrueCounterpart(jid: Jid): Jid? {
        if (jid == getSelf().getFullJid()) {
            return account.getJid().asBareJid()
        }
        val user = findUserByFullJid(jid)
        return user?.realJid
    }

    override fun getPassword(): String? {
        password = conversation.getAttribute(Conversation.ATTRIBUTE_MUC_PASSWORD)
        val bookmark = conversation.getBookmark()
        return if (password == null && bookmark != null && bookmark.getPassword() != null) {
            bookmark.getPassword()
        } else {
            password
        }
    }

    override fun setPassword(password: String?) {
        val bookmark = conversation.getBookmark()
        if (bookmark != null) {
            bookmark.setPassword(password)
        } else {
            this.password = password
        }
        conversation.setAttribute(Conversation.ATTRIBUTE_MUC_PASSWORD, password)
    }

    override fun getConversation(): Conversation = conversation

    override fun getMembers(includeDomains: Boolean): List<Jid> {
        val members = ArrayList<Jid>()
        synchronized(users) {
            for (user in users) {
                val realJid = user.realJid
                if (user.affiliation.ranks(Affiliation.MEMBER) &&
                    realJid != null &&
                    realJid.asBareJid() != conversation.getAccount()!!.getJid().asBareJid() &&
                    (!user.isDomain() || includeDomains)
                ) {
                    members.add(realJid)
                }
            }
        }
        return members
    }

    enum class Affiliation(
        private val rank: Int,
        private val resId: Int,
    ) {
        OWNER(4, R.string.owner),
        ADMIN(3, R.string.admin),
        MEMBER(2, R.string.member),
        OUTCAST(0, R.string.outcast),
        NONE(1, R.string.no_affiliation);

        fun getResId(): Int = resId

        override fun toString(): String = name.lowercase(Locale.US)

        fun outranks(affiliation: Affiliation): Boolean = rank > affiliation.rank

        fun ranks(affiliation: Affiliation): Boolean = rank >= affiliation.rank

        companion object {

            fun of(value: String?): Affiliation {
                if (value == null) {
                    return NONE
                }
                return try {
                    Affiliation.valueOf(value.uppercase(Locale.US))
                } catch (e: IllegalArgumentException) {
                    NONE
                }
            }
        }
    }

    enum class Role(
        private val resId: Int,
        private val rank: Int,
    ) {
        MODERATOR(R.string.moderator, 3),
        VISITOR(R.string.visitor, 1),
        PARTICIPANT(R.string.participant, 2),
        NONE(R.string.no_role, 0);

        fun getResId(): Int = resId

        override fun toString(): String = name.lowercase(Locale.US)

        fun ranks(role: Role): Boolean = rank >= role.rank

        companion object {

            fun of(value: String?): Role {
                if (value == null) {
                    return NONE
                }
                return try {
                    Role.valueOf(value.uppercase(Locale.US))
                } catch (e: IllegalArgumentException) {
                    NONE
                }
            }
        }
    }

    enum class Error {
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
     * Tulkki: the model's listener is also the island's.
     *
     * <p>3.7 pair 9, part 11. The ref declares the same two methods the (now dropped) private
     * `OnEventListener` declared, so satisfying it costs one name in the `extends` list and **no
     * adapter** - which is why [onRenameListener] can hand the value straight back.
     */
    interface OnRenameListener : MucOptionsRef.OnRenameListenerRef

    class Hat : Comparable<Hat>, MucOptionsRef.HatRef {

        private val uri: Uri?

        private val title: String?

        constructor(el: Element) {
            var parseUri: Uri? = null // null hat uri is invaild per spec
            try {
                parseUri = Uri.parse(el.getAttribute("uri"))
            } catch (e: Exception) {
            }
            uri = parseUri

            title = el.getAttribute("title")
        }

        constructor(uri: Uri?, title: String?) {
            this.uri = uri
            this.title = title
        }

        override fun toString(): String = title ?: ""

        fun getColor(): Int {
            val hatUri = uri
            return DisplayNames.getColorForName(if (hatUri == null) toString() else hatUri.toString())
        }

        override fun compareTo(other: Hat): Int = toString().compareTo(other.toString())
    }

    class User(
        private val options: MucOptions?,
        fullJidValue: Jid?,
        occupantIdValue: String?,
        nickValue: String?,
        hatsValue: Set<Hat>?,
    ) : Comparable<User>, Avatarable, MucOptionsRef.UserRef {

        internal var role: Role = Role.NONE

        internal var affiliation: Affiliation = Affiliation.NONE

        internal var realJid: Jid? = null

        internal var fullJid: Jid? = fullJidValue

        internal var nick: String? = nickValue

        private var pgpKeyId: Long = 0

        internal var avatar: Avatar? = null

        internal var chatState: ChatState = Config.DEFAULT_CHAT_STATE

        internal var hats: Set<Hat>? = hatsValue

        private var occupantId: String? = occupantIdValue

        private var online = true

        init {
            val mucOptions = options
            val currentOccupantId = occupantId
            if (currentOccupantId != null && mucOptions != null) {
                val conversation = mucOptions.getConversation()
                val sha1sum = conversation.getAttribute("occupantAvatar/" + currentOccupantId)
                if (sha1sum != null) {
                    val newAvatar = Avatar()
                    newAvatar.sha1sum = sha1sum
                    newAvatar.owner = fullJid
                    avatar = newAvatar
                }

                if (nick == null) {
                    this.nick = conversation.getAttribute("occupantNick/" + currentOccupantId)
                } else if (getNick() != getName()) {
                    conversation.setAttribute("occupantNick/" + currentOccupantId, nick)
                } else {
                    conversation.setAttribute("occupantNick/" + currentOccupantId, null as String?)
                }
            }
        }

        fun getName(): String? {
            val full = fullJid
            return if (full == null) null else full.getResource()
        }

        override fun getMuc(): Jid {
            val full = fullJid
            return if (full == null) {
                (options ?: throw NullPointerException()).getConversation().getJid()!!.asBareJid()
            } else {
                full.asBareJid()
            }
        }

        override fun getOccupantId(): String? = occupantId

        override fun getNick(): String? {
            val currentNick = nick
            return currentNick ?: getName()
        }

        override fun setOnline(o: Boolean) {
            online = o
        }

        fun isOnline(): Boolean = fullJid != null && online

        fun getRole(): Role = role

        override fun setRole(role: String?) {
            this.role = Role.of(role)
        }

        fun getAffiliation(): Affiliation = affiliation

        /**
         * Tulkki: `MucOptionsRef.UserRef.affiliation()`, and the name is different from
         * [getAffiliation] **on purpose** - a class may not have two methods differing only in return
         * type, and `MucOptionsRef.AffiliationRef` is not this enum. The mapping is `valueOf` over the
         * shared five constants, so it is total and loud if the two ever drift.
         */
        override fun affiliation(): MucOptionsRef.AffiliationRef =
            MucOptionsRef.AffiliationRef.valueOf(affiliation.name)

        override fun setAffiliation(affiliation: String?) {
            this.affiliation = Affiliation.of(affiliation)
        }

        fun getHats(): Set<Hat> {
            val currentHats = hats
            return currentHats ?: HashSet()
        }

        fun getPseudoHats(context: Context): List<Hat> {
            val hats = ArrayList<Hat>()
            if (getAffiliation() != Affiliation.NONE) {
                hats.add(Hat(null, context.getString(getAffiliation().getResId())))
            }
            if (getRole() != Role.PARTICIPANT) {
                hats.add(Hat(null, context.getString(getRole().getResId())))
            }
            return hats
        }

        fun getPgpKeyId(): Long {
            if (pgpKeyId != 0L) {
                return pgpKeyId
            }
            val real = realJid
            return if (real != null) {
                getAccount().getRoster().getContact(real).getPgpKeyId()
            } else {
                0
            }
        }

        override fun setPgpKeyId(id: Long) {
            pgpKeyId = id
        }

        override fun getContact(): Contact? {
            val real = realJid
            return if (fullJid != null) {
                getAccount().getRoster().getContactFromContactList(real)
            } else if (real != null) {
                getAccount().getRoster().getContact(real)
            } else {
                null
            }
        }

        override fun setAvatar(avatar: Avatar?): Boolean {
            val currentOccupantId = occupantId
            if (currentOccupantId != null) {
                (options ?: throw NullPointerException()).getConversation()
                    .setAttribute(
                        "occupantAvatar/" + currentOccupantId,
                        if (getContact() == null && avatar != null) avatar.sha1sum else null,
                    )
            }
            val currentAvatar = this.avatar
            if (currentAvatar != null && currentAvatar == avatar) {
                return false
            } else {
                this.avatar = avatar
                return true
            }
        }

        fun getAvatar(): String? {
            val currentAvatar = avatar
            if (currentAvatar != null) {
                return currentAvatar.getFilename()
            }
            val real = realJid
            val rosterAvatar =
                if (real != null) {
                    getAccount().getRoster().getContact(real).getAvatar()
                } else {
                    null
                }
            return rosterAvatar?.getFilename()
        }

        fun getAvatarCid(): Cid? {
            val currentAvatar = avatar
            if (currentAvatar != null) {
                return currentAvatar.cid()
            }
            val real = realJid
            val rosterAvatar =
                if (real != null) {
                    getAccount().getRoster().getContact(real).getAvatar()
                } else {
                    null
                }
            return rosterAvatar?.cid()
        }

        fun getAccount(): Account = (options ?: throw NullPointerException()).getAccount()

        override fun getConversation(): Conversation =
            (options ?: throw NullPointerException()).getConversation()

        override fun getFullJid(): Jid? = fullJid

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false

            val user = other as User

            if (role != user.role) return false
            if (affiliation != user.affiliation) return false
            val real = realJid
            if (real != null) {
                if (real != user.realJid) return false
            } else if (user.realJid != null) {
                return false
            }
            val full = fullJid
            return if (full != null) full == user.fullJid else user.fullJid == null
        }

        fun isDomain(): Boolean {
            val real = realJid
            return real != null && real.getLocal() == null && role == Role.NONE
        }

        override fun hashCode(): Int {
            var result = role.hashCode()
            result = 31 * result + affiliation.hashCode()
            result = 31 * result + (realJid?.hashCode() ?: 0)
            result = 31 * result + (fullJid?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "[fulljid:" +
                fullJid +
                ",realjid:" +
                realJid +
                ",affiliation" +
                affiliation.toString() +
                "]"

        override fun realJidMatchesAccount(): Boolean {
            val real = realJid
            return real != null &&
                real == (options ?: throw NullPointerException()).account.getJid().asBareJid()
        }

        override fun compareTo(other: User): Int {
            val otherOccupantId = other.getOccupantId()
            val occupantId = getOccupantId()
            val otherPseudoId = otherOccupantId != null && otherOccupantId[0] == '\u0000'
            val pseudoId = occupantId != null && occupantId[0] == '\u0000'
            if (otherPseudoId && !pseudoId) {
                return 1
            }
            if (pseudoId && !otherPseudoId) {
                return -1
            }
            if (other.getAffiliation().outranks(getAffiliation())) {
                return 1
            } else if (getAffiliation().outranks(other.getAffiliation())) {
                return -1
            } else {
                return getComparableName().compareTo(other.getComparableName(), ignoreCase = true)
            }
        }

        fun getComparableName(): String {
            val contact = getContact()
            return if (contact != null) {
                contact.getDisplayName()
            } else {
                getName() ?: ""
            }
        }

        override fun getRealJid(): Jid? = realJid

        override fun setRealJid(jid: Jid?) {
            realJid = if (jid != null) jid.asBareJid() else null
        }

        override fun setChatState(chatState: ChatState): Boolean {
            if (this.chatState == chatState) {
                return false
            }
            this.chatState = chatState
            return true
        }

        override fun getAvatarBackgroundColor(): Int {
            val real = realJid
            val seed = if (real != null) real.asBareJid().toString() else null
            return DisplayNames.getColorForName(seed ?: getName())
        }

        override fun getAvatarName(): String = getConversation().getName().toString()

        override fun setOccupantId(occupantId: String?) {
            this.occupantId = occupantId
        }
    }

    companion object {

        const val STATUS_CODE_SELF_PRESENCE = "110"
        const val STATUS_CODE_ROOM_CREATED = "201"
        const val STATUS_CODE_BANNED = "301"
        const val STATUS_CODE_CHANGED_NICK = "303"
        const val STATUS_CODE_KICKED = "307"
        const val STATUS_CODE_AFFILIATION_CHANGE = "321"
        const val STATUS_CODE_LOST_MEMBERSHIP = "322"
        const val STATUS_CODE_SHUTDOWN = "332"
        const val STATUS_CODE_TECHNICAL_REASONS = "333"

        @JvmStatic
        fun sub(users: List<User>, max: Int): List<User> {
            if (users.size < max) return users
            val subset = ArrayList<User>()
            val jids = HashSet<Jid>()
            for (user in users) {
                jids.add(user.getAccount().getJid().asBareJid())
                val realJid = user.getRealJid()
                if (realJid == null || (realJid.getLocal() != null && jids.add(realJid))) {
                    subset.add(user)
                }
                if (subset.size >= max) {
                    break
                }
            }
            return subset
        }

        @JvmStatic
        fun defaultNick(account: Account): String {
            val displayName = normalize(account.getJid(), account.getDisplayName())
            return displayName ?: JidHelper.localPartOrFallback(account.getJid())
        }
    }
}
