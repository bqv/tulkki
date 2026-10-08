package uk.xa0.tulkki.data.model

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.TextUtils
import android.util.Log
import com.google.common.base.Strings
import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.crypto.OmemoContact
import uk.xa0.tulkki.data.BuildConfig
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.data.view.AvatarReader
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.pep.UserTune
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.JidHelper

/** Java's `trim()`, which removes every char `<= ' '`; Kotlin's `trim()` also strips NBSP. */
private fun String.javaTrim(): String = trim { it <= ' ' }

/**
 * One entry in the roster: its account, its names, its presence set, its PGP keys, the address-book
 * entry it was merged from, and the phone account it registers with the OS.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **`jid`, `account` and `avatar` are `@JvmField` fields *and* keep their own getters.** Java
 *    declared all three `protected`; Kotlin's `:ui` reads two of them as **properties**
 *    (`PresenceIndicator.kt:55` writes `contact.account`, which used to be the property Kotlin
 *    synthesised from Java's `getAccount()`), and a Kotlin property cannot override a Kotlin
 *    interface's `fun getAccount(): Account` - measured: `'x' overrides nothing`. A field named
 *    `account` and a method named `getAccount()` do not collide, so both shapes exist at once: Kotlin
 *    callers keep property syntax, Java callers keep `getAccount()`, and the non-null getters
 *    ([getJid], [getAccount]) answer `?: throw NullPointerException()` where Java dereferenced the same
 *    nullable field. **Interop debt: three `@JvmField`s, with Kotlin creditors.**
 * 2. **`getDisplayName()`'s four `TextUtils.isEmpty` name branches are written as null checks.**
 *    `TextUtils.isEmpty(x)` *is* `x == null || x.length() == 0`, and `ListItem.getDisplayName()` is
 *    non-null to Kotlin, which cannot smart-cast a getter call or a member `var`; the rewritten
 *    conditions admit exactly the strings Java returned, and [getPublicDisplayName] does the same.
 * 3. **`match` closes both of this row's measured traps**: `needle.toLowerCase(Locale.US).trim()` is
 *    `javaTrim()` (the needle is user text and Kotlin's `trim()` also strips NBSP), and
 *    `split("[,\\s]+")` is `Pattern.compile("[,\\s]+").split(lowered, 0)`, because Kotlin's
 *    single-`String` overload is a *literal* split and `Regex.split` keeps trailing empties Java's
 *    `String.split` drops. The needle is **nullable**: Java's `TextUtils.isEmpty(needle)` answered
 *    `true` for `null` - "no filter matches everything" - and `:ui`'s
 *    `StartConversationActivity.filterContacts` passes a search box value that is `null` until
 *    something is typed, which crashed on the non-null parameter this port's first draft declared
 *    (`FIXED:` on `Bookmark.match`). The guard is `TextUtils.isEmpty`'s own answer in clause 2's
 *    spelling, which is also what lets the compiler see the non-null path to `lowercase`.
 * 4. **`isOwnServer()` and `unsetPhoneContact(Class)` are public**, as Java's package-private and
 *    public shapes already were: `Conversation:371`, `:2044` and `ContactListSyncService:118`, `:171`
 *    are Java callers across the package boundary, and `internal` would mangle the JVM name to
 *    `isOwnServer$data` and break javac.
 * 5. **Debt instruments: one `@JvmStatic`, four `@JvmField`s, twenty-eight `const val`s, four
 *    `@Synchronized`, no `@JvmOverloads`, no `@Throws`.** `fromCursor` has Java callers
 *    (`DatabaseBackend:1873`); the fifteen column names and `CONNECTION_SERVICE` are compile-time
 *    constants; **`APPLICATION_ID` cannot be a `const val`** - it is `BuildConfig.APPLICATION_ID`, a
 *    Java `static final`, so it is `@JvmField val` and keeps its static field for the OS-persisted
 *    `PhoneAccountHandle` (`ApplicationIdTest` pins the value). `getOption(Class)` has **no** Java
 *    caller - only `Roster.kt` - so it carries no annotation. `@Synchronized` is Java's
 *    `synchronized` method on the four phone-contact/avatar paths.
 * 6. **`Options` becomes a nested `object` of `const val`s.** Java's was a `final class` of ints with
 *    no callers of `new Contact.Options()` (measured) and no static import; `SYNCED_VIA_ADDRESS_BOOK`
 *    was `private static final` but the outer class reads it, and a nested object's private member is
 *    not reachable from the outer class, so it is `internal const val`.
 * 7. **[setAvatar] keeps Java's dereference of the *new* avatar**: the PEP/VCARD guard only runs when
 *    the incoming avatar is non-null, and Java threw `NullPointerException` if it was not, so the
 *    branch is `(newAvatar ?: throw NullPointerException()).origin`.
 * 8. **`getClass()` is `javaClass`.** Kotlin's `Any` has no `getClass()`; `phoneContact.javaClass`
 *    answers the same `Class<? extends AbstractPhoneContact>` that `Contact.getOption(Class)` keys on.
 * 9. **The explicit nullables are Java's own values**: [getAvatarFilename], [getProfilePhoto],
 *    [getSystemAccount], [getRtpCapability] (`null` when calls are disabled), [getUserTune],
 *    [getLastResource], [getServerName] and [getProfilePhoto]. **Master's Kotlin callers were read
 *    before this commit** (`git grep … master`): `AvatarService.kt:134-145`, `:213-215`, `:504`,
 *    `:582-588` compare them against `null` or pass them to still-Java `FileBackends`/`FileBackend`/
 *    `Uri.parse` parameters, and `ContactListSyncService.kt:99`, `NotificationService.kt:604`, `:1532`,
 *    `:1609`, `:1643` take them into locals first - so **nothing breaks at merge**. The one fragility
 *    worth knowing is that `AvatarService.kt:135`, `:145`, `:584`, `:588` call the getter twice around
 *    a null check (`getAvatar(getAvatarFilename(), …)`), which Kotlin accepts only while
 *    `FileBackends`/`FileBackend` are still Java; when those are ported, each site needs a local.
 * 10. **`getShownStatus()` becomes the property `shownStatus`**, beside the ref's `shownStatus()`
 *     function - `PresenceIndicator.kt:53` writes `contact?.shownStatus`, the synthesised property
 *     Java's getter used to give Kotlin, and a property and a same-named function may coexist
 *     (measured when porting `Reaction`). Java's `contact.getShownStatus()` is the property's getter,
 *     so no caller moves.
 * 11. **Java's dead local is dropped**: `getTags` declared `Presence.Status status = getShownStatus()`
 *     and never used it; `getShownStatus()` is a pure read of the presences map, so removing it
 *     changes nothing observable. It is the only deletion.
 * 11. **`Objects.equals(previous, rtpCapability)` stays `java.util.Objects`**, not Kotlin's `==`, to
 *     keep the call Java wrote; and `isOwnServer()` compares two `Jid`s (Java's `Jid.getDomain()`
 *     answers a `Jid`, not a `String`), which Kotlin's `==` can express directly.
 *
 * Nothing in the tree extends `Contact`, so Kotlin's implicit `final` is Java's own shape.
 */
class Contact : ListItem, Blockable, OmemoContact, ContactRef {

    private var accountUuid: String? = null

    private var systemName: String? = null

    private var serverName: String? = null

    private var presenceName: String? = null

    private var commonName: String? = null

    @JvmField var jid: Jid? = null

    private var subscription = 0

    private var systemAccount: Uri? = null

    private var photoUri: String? = null

    private val keys: JSONObject

    private var groups: JSONArray = JSONArray()

    private var systemTags: JSONArray = JSONArray()

    private val presences = Presences()

    @JvmField var account: Account? = null

    @JvmField var avatar: Avatar? = null

    private var mActive = false

    private var mLastseen = 0L

    private var mLastPresence: String? = null

    private var mUserTune: UserTune? = null

    private var rtpCapability: RtpCapability.Capability? = null

    private var callsDisabled = false

    constructor(other: Contact) :
        this(
            null,
            other.systemName,
            other.serverName,
            other.presenceName,
            other.jid,
            other.subscription,
            other.photoUri,
            other.systemAccount,
            other.keys.toString(),
            other.getAvatar()?.sha1sum,
            other.mLastseen,
            other.mLastPresence,
            other.groups.toString(),
            other.rtpCapability,
            other.callsDisabled,
        ) {
        setAccount(other.getAccount())
    }

    constructor(
        account: String?,
        systemName: String?,
        serverName: String?,
        presenceName: String?,
        jid: Jid?,
        subscription: Int,
        photoUri: String?,
        systemAccount: Uri?,
        keys: String?,
        avatar: String?,
        lastseen: Long,
        presence: String?,
        groups: String?,
        rtpCapability: RtpCapability.Capability?,
        callsDisabled: Boolean,
    ) {
        this.accountUuid = account
        this.systemName = systemName
        this.serverName = serverName
        this.presenceName = presenceName
        this.jid = jid
        this.subscription = subscription
        this.photoUri = photoUri
        this.systemAccount = systemAccount
        var tmpJsonObject: JSONObject
        try {
            tmpJsonObject = if (keys == null) JSONObject("") else JSONObject(keys)
        } catch (e: JSONException) {
            tmpJsonObject = JSONObject()
        }
        this.keys = tmpJsonObject
        if (avatar != null) {
            val newAvatar = Avatar()
            newAvatar.sha1sum = avatar
            newAvatar.origin = Avatar.Origin.VCARD // always assume worst
            this.avatar = newAvatar
        }
        try {
            this.groups = if (groups == null) JSONArray() else JSONArray(groups)
        } catch (e: JSONException) {
            this.groups = JSONArray()
        }
        this.mLastseen = lastseen
        this.mLastPresence = presence
        this.rtpCapability = rtpCapability
        this.callsDisabled = callsDisabled
    }

    constructor(jid: Jid) :
        this(
            null,
            null,
            null,
            null,
            jid,
            0,
            null,
            null,
            null,
            null,
            0L,
            null,
            null,
            null,
            false,
        )

    override fun getDisplayName(): String {
        val sysName = systemName
        if (isSelf() && TextUtils.isEmpty(sysName)) {
            val displayName = getAccount().getDisplayName()
            if (displayName != null && displayName.isNotEmpty()) {
                return displayName
            }
        }
        val cn = commonName
        val srvName = serverName
        val presName = presenceName
        if (Config.X509_VERIFICATION && cn != null && cn.isNotEmpty()) {
            return cn
        } else if (sysName != null && sysName.isNotEmpty()) {
            return sysName
        } else if (srvName != null && srvName.isNotEmpty()) {
            return srvName
        }

        val bookmark: ListItem? = getAccount().getBookmark(jid)
        if (bookmark != null) {
            return bookmark.getDisplayName()
        } else if (presName != null && presName.isNotEmpty() && mutualPresenceSubscription()) {
            return presName
        } else if (presName != null && presName.isNotEmpty()) {
            return presName + (if (mutualPresenceSubscription()) "" else " (" + getJid() + ")")
        } else if (getJid().getLocal() != null) {
            return JidHelper.localPartOrFallback(getJid())
        } else {
            return getJid().getDomain().toString()
        }
    }

    fun getPublicDisplayName(): String {
        val presName = presenceName
        return if (presName != null && presName.isNotEmpty()) {
            presName
        } else if (getJid().getLocal() != null) {
            JidHelper.localPartOrFallback(getJid())
        } else {
            getJid().getDomain().toString()
        }
    }

    fun getProfilePhoto(): String? = photoUri

    override fun getJid(): Jid = jid ?: throw NullPointerException()

    fun getGroupTags(): List<ListItem.Tag> {
        val tags = ArrayList<ListItem.Tag>()
        for (group in getGroups(true)) {
            tags.add(ListItem.Tag(group))
        }
        return tags
    }

    override fun getTags(context: Context): List<ListItem.Tag> {
        val tags = HashSet<ListItem.Tag>()
        tags.addAll(getGroupTags())
        for (tag in getSystemTags(true)) {
            tags.add(ListItem.Tag(tag))
        }
        if (isBlocked()) {
            tags.add(ListItem.Tag(context.getString(R.string.blocked)))
        }
        if (!showInRoster() && getSystemAccount() != null) {
            tags.add(ListItem.Tag("Android"))
        }
        return ArrayList(tags)
    }

    override fun match(context: Context, needle: String?): Boolean {
        if (needle == null || needle.isEmpty()) {
            return true
        }
        val lowered = needle.lowercase(Locale.US).javaTrim()
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
                getDisplayName().lowercase(Locale.US).contains(lowered) ||
                matchInTag(context, lowered)
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

    fun getContentValues(): ContentValues =
        synchronized(keys) {
            val values = ContentValues()
            values.put(ACCOUNT, accountUuid)
            values.put(SYSTEMNAME, systemName)
            values.put(SERVERNAME, serverName)
            values.put(PRESENCE_NAME, presenceName)
            values.put(JID, getJid().toString())
            values.put(OPTIONS, subscription)
            values.put(SYSTEMACCOUNT, systemAccount?.toString())
            values.put(PHOTOURI, photoUri)
            values.put(KEYS, keys.toString())
            values.put(AVATAR, avatar?.getFilename())
            values.put(LAST_PRESENCE, mLastPresence)
            values.put(LAST_TIME, mLastseen)
            values.put(GROUPS, groups.toString())
            values.put(RTP_CAPABILITY, rtpCapability?.toString())
            values.put(CALLS_DISABLED, if (callsDisabled) 1 else 0)
            values
        }

    override fun getAccount(): Account = account ?: throw NullPointerException()

    fun setAccount(account: Account) {
        this.account = account
        this.accountUuid = account.getUuid()
    }

    override fun getPresences(): Presences = presences

    fun updatePresence(resource: String, presence: Presence) {
        presences.updatePresence(resource, presence)
    }

    override fun removePresence(resource: String) {
        presences.removePresence(resource)
        refreshCaps()
    }

    override fun clearPresences() {
        presences.clearPresences()
        resetOption(Options.PENDING_SUBSCRIPTION_REQUEST)
        refreshCaps()
    }

    /**
     * Java's `getShownStatus()` as the property Kotlin's `:ui` already reads: `PresenceIndicator.kt:53`
     * writes `contact.shownStatus` (the synthesised property the Java getter gave it), so the getter
     * stays a property with a custom getter and [shownStatus]'s function sits beside it - a property and
     * a same-named function may coexist. Java's `contact.getShownStatus()` is this property's getter.
     */
    val shownStatus: Presence.Status
        get() = presences.getShownStatus()

    /**
     * Tulkki: the island's view of the same fact, under a name of its own.
     *
     * <p>3.7 pair 9, part 11. `PresenceParser` compares this with `==` inside the island, and it
     * cannot be an override of [getShownStatus]: a class may not have two methods that differ only in
     * return type, and the model enum must keep serving `:ui`/`:app`/`:translation` unchanged. The
     * mapping is name for name over the six constants.
     */
    override fun shownStatus(): PresenceRef.StatusRef =
        when (presences.getShownStatus()) {
            Presence.Status.CHAT -> PresenceRef.StatusRef.CHAT
            Presence.Status.ONLINE -> PresenceRef.StatusRef.ONLINE
            Presence.Status.AWAY -> PresenceRef.StatusRef.AWAY
            Presence.Status.XA -> PresenceRef.StatusRef.XA
            Presence.Status.DND -> PresenceRef.StatusRef.DND
            Presence.Status.OFFLINE -> PresenceRef.StatusRef.OFFLINE
        }

    /**
     * Tulkki: the island's overload. The parameter types do not erase alike
     * (`Presence` vs `PresenceRef`), so the pair is legal, and the island's object really is a
     * `Presence` - `Presence.parse` is what built it.
     */
    override fun updatePresence(resource: String, presence: PresenceRef) {
        updatePresence(resource, presence as Presence)
    }

    fun resourceWhichSupport(namespace: String): Jid? {
        val resource = getPresences().firstWhichSupport(namespace) ?: return null

        return if (resource.isEmpty()) getJid() else getJid().withResource(resource)
    }

    fun setPhotoUri(uri: String?): Boolean =
        if (uri != null && uri != photoUri) {
            photoUri = uri
            true
        } else if (photoUri != null && uri == null) {
            photoUri = null
            true
        } else {
            false
        }

    override fun setServerName(serverName: String?) {
        this.serverName = serverName
    }

    fun setSystemName(systemName: String?): Boolean {
        val old = getDisplayName()
        this.systemName = systemName
        return old != getDisplayName()
    }

    fun setSystemTags(systemTags: Collection<String>): Boolean {
        val old = this.systemTags
        this.systemTags = JSONArray()
        for (tag in systemTags) {
            this.systemTags.put(tag)
        }
        return old != this.systemTags
    }

    override fun setPresenceName(presenceName: String?): Boolean {
        val old = getDisplayName()
        this.presenceName = presenceName
        return old != getDisplayName()
    }

    fun getSystemAccount(): Uri? = systemAccount

    fun setSystemAccount(lookupUri: Uri?) {
        systemAccount = lookupUri
    }

    fun setGroups(groups: List<String>) {
        this.groups = JSONArray(groups)
    }

    private fun getGroups(unique: Boolean): Collection<String> {
        val groups: MutableCollection<String> = if (unique) HashSet() else ArrayList()
        for (i in 0 until this.groups.length()) {
            try {
                groups.add(this.groups.getString(i))
            } catch (e: JSONException) {
            }
        }
        return groups
    }

    fun copySystemTagsToGroups() {
        for (tag in getSystemTags(true)) {
            groups.put(tag)
        }
    }

    private fun getSystemTags(unique: Boolean): Collection<String> {
        val tags: MutableCollection<String> = if (unique) HashSet() else ArrayList()
        for (i in 0 until systemTags.length()) {
            try {
                tags.add(systemTags.getString(i))
            } catch (e: JSONException) {
            }
        }
        return tags
    }

    fun getOtrFingerprints(): ArrayList<String> =
        synchronized(keys) {
            val fingerprints = ArrayList<String>()
            try {
                if (keys.has("otr_fingerprints")) {
                    val prints = keys.getJSONArray("otr_fingerprints")
                    for (i in 0 until prints.length()) {
                        val print = if (prints.isNull(i)) null else prints.getString(i)
                        if (print != null && print.isNotEmpty()) {
                            fingerprints.add(print.lowercase(Locale.US))
                        }
                    }
                }
            } catch (e: JSONException) {
            }
            fingerprints
        }

    override fun addOtrFingerprint(print: String): Boolean =
        synchronized(keys) {
            if (getOtrFingerprints().contains(print)) {
                false
            } else {
                try {
                    val fingerprints: JSONArray =
                        if (!keys.has("otr_fingerprints")) {
                            JSONArray()
                        } else {
                            keys.getJSONArray("otr_fingerprints")
                        }
                    fingerprints.put(print)
                    keys.put("otr_fingerprints", fingerprints)
                    true
                } catch (e: JSONException) {
                    false
                }
            }
        }

    override fun getPgpKeyId(): Long =
        synchronized(keys) {
            if (keys.has("pgp_keyid")) {
                try {
                    keys.getLong("pgp_keyid")
                } catch (e: JSONException) {
                    0L
                }
            } else {
                0L
            }
        }

    override fun setPgpKeyId(keyId: Long): Boolean {
        val previousKeyId = getPgpKeyId()
        synchronized(keys) {
            try {
                keys.put("pgp_keyid", keyId)
                return previousKeyId != keyId
            } catch (e: JSONException) {
            }
        }
        return false
    }

    override fun setOption(option: Int) {
        subscription = subscription or (1 shl option)
    }

    override fun resetOption(option: Int) {
        subscription = subscription and (1 shl option).inv()
    }

    override fun getOption(option: Int): Boolean = (subscription and (1 shl option)) != 0

    override fun canInferPresence(): Boolean = showInContactList() || isSelf()

    override fun showInRoster(): Boolean =
        (getOption(Options.IN_ROSTER) && !getOption(Options.DIRTY_DELETE)) ||
            getOption(Options.DIRTY_PUSH)

    override fun showInContactList(): Boolean =
        showInRoster() || getOption(Options.SYNCED_VIA_OTHER) || systemAccount != null

    override fun parseSubscriptionFromElement(item: Element) {
        val ask = item.getAttribute("ask")
        val subscription = item.getAttribute("subscription")

        if (subscription == null) {
            resetOption(Options.FROM)
            resetOption(Options.TO)
        } else {
            when (subscription) {
                "to" -> {
                    resetOption(Options.FROM)
                    setOption(Options.TO)
                }
                "from" -> {
                    resetOption(Options.TO)
                    setOption(Options.FROM)
                    resetOption(Options.PREEMPTIVE_GRANT)
                    resetOption(Options.PENDING_SUBSCRIPTION_REQUEST)
                }
                "both" -> {
                    setOption(Options.TO)
                    setOption(Options.FROM)
                    resetOption(Options.PREEMPTIVE_GRANT)
                    resetOption(Options.PENDING_SUBSCRIPTION_REQUEST)
                }
                "none" -> {
                    resetOption(Options.FROM)
                    resetOption(Options.TO)
                }
            }
        }

        // do NOT override asking if pending push request
        if (!getOption(Options.DIRTY_PUSH)) {
            if (ask != null && ask == "subscribe") {
                setOption(Options.ASKING)
            } else {
                resetOption(Options.ASKING)
            }
        }
    }

    override fun parseGroupsFromElement(item: Element) {
        groups = JSONArray()
        for (element in item.getChildren()) {
            if ("group" == element.getName()) {
                val content = element.getContent()
                if (content != null) {
                    groups.put(content)
                }
            }
        }
    }

    override fun asElement(): Element {
        val item = Element("item")
        item.setAttribute("jid", jid)
        if (serverName != null) {
            item.setAttribute("name", serverName)
        } else {
            item.setAttribute("name", getDisplayName())
        }
        for (group in getGroups(false)) {
            item.addChild("group").setContent(group)
        }
        return item
    }

    override fun compareTo(other: ListItem): Int {
        if (getJid().isDomainJid() && !other.getJid().isDomainJid()) {
            return -1
        } else if (!getJid().isDomainJid() && other.getJid().isDomainJid()) {
            return 1
        }

        if (getDisplayName() == other.getDisplayName()) {
            return getJid().compareTo(other.getJid())
        }

        return getDisplayName().compareTo(other.getDisplayName(), ignoreCase = true)
    }

    override fun getServer(): String = getJid().getDomain().toString()

    override fun setAvatar(avatar: Avatar?): Boolean = setAvatar(avatar, false)

    override fun setAvatar(avatar: Avatar?, previouslyOmittedPepFetch: Boolean): Boolean {
        val currentAvatar = this.avatar
        if (currentAvatar != null && currentAvatar == avatar) {
            return false
        }
        val newAvatar = avatar
        if (!previouslyOmittedPepFetch &&
            currentAvatar != null &&
            currentAvatar.origin == Avatar.Origin.PEP &&
            (newAvatar ?: throw NullPointerException()).origin == Avatar.Origin.VCARD
        ) {
            return false
        }
        this.avatar = avatar
        return true
    }

    fun getAvatarFilename(): String? = avatar?.getFilename()

    fun getAvatar(): Avatar? = avatar

    override fun mutualPresenceSubscription(): Boolean =
        getOption(Options.FROM) && getOption(Options.TO)

    override fun isBlocked(): Boolean = getAccount().isBlocked(this)

    override fun isDomainBlocked(): Boolean = getAccount().isBlocked(getJid().getDomain())

    override fun getBlockedJid(): Jid = if (isDomainBlocked()) getJid().getDomain() else getJid()

    override fun isSelf(): Boolean =
        getAccount().getJid().asBareJid() == getJid().asBareJid()

    fun isOwnServer(): Boolean = getAccount().getJid().getDomain() == getJid().asBareJid()

    override fun setCommonName(name: String?) {
        commonName = name
    }

    override fun flagActive() {
        mActive = true
    }

    override fun flagInactive() {
        mActive = false
    }

    fun isActive(): Boolean = mActive && getAccount().isOnlineAndConnected()

    override fun setLastseen(timestamp: Long): Boolean =
        if (timestamp > mLastseen) {
            mLastseen = timestamp
            true
        } else {
            false
        }

    fun getLastseen(): Long = mLastseen

    override fun setLastResource(resource: String?) {
        mLastPresence = resource
    }

    fun getLastResource(): String? = mLastPresence

    override fun setUserTune(tune: UserTune?) {
        mUserTune = tune
    }

    override fun getUserTune(): UserTune? = mUserTune

    fun getServerName(): String? = serverName

    @Synchronized
    override fun setPhoneContact(phoneContact: AbstractPhoneContact): Boolean {
        setOption(getOption(phoneContact.javaClass))
        setSystemAccount(phoneContact.getLookupUri())
        var changed = setSystemName(phoneContact.getDisplayName())
        changed = changed or setPhotoUri(phoneContact.getPhotoUri())
        return changed
    }

    @Synchronized
    fun unsetPhoneContact(clazz: Class<out AbstractPhoneContact>): Boolean {
        resetOption(getOption(clazz))
        var changed = false
        if (!getOption(Options.SYNCED_VIA_ADDRESS_BOOK) && !getOption(Options.SYNCED_VIA_OTHER)) {
            setSystemAccount(null)
            changed = changed or setPhotoUri(null)
            changed = changed or setSystemName(null)
        }
        return changed
    }

    // Tulkki: 3.7 C5-E2 - the island's entry point to the merge, and port-13's `PhoneContactRef`
    // deletion held the model's own `setPhoneContact(AbstractPhoneContact)` as that entry point:
    // with the class an island type the old ref overload and its forward cast are gone, and this
    // `override` is the only member. The class literal stays here.

    @Synchronized
    override fun unsetJabberIdPhoneContact(): Boolean = unsetPhoneContact(JabberIdContact::class.java)

    protected fun phoneAccountLabel(): String =
        getAccount().getJid().asBareJid().toString() + "/" + getJid().asBareJid().toString()

    override fun phoneAccountHandle(): PhoneAccountHandle {
        val componentName = ComponentName(APPLICATION_ID, CONNECTION_SERVICE)
        return PhoneAccountHandle(componentName, phoneAccountLabel())
    }

    // This Contact is a gateway to use for voice calls, register it with OS
    override fun registerAsPhoneAccount(ctx: XmppConnectionService) {
        if (Build.VERSION.SDK_INT < 23) return
        if (Build.VERSION.SDK_INT >= 33) {
            if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELECOM) &&
                !ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
            ) {
                return
            }
        } else {
            if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) {
                return
            }
        }

        val telecomManager = ctx.getSystemService(TelecomManager::class.java)

        val phoneAccount =
            PhoneAccount.builder(
                phoneAccountHandle(),
                getAccount().getJid().asBareJid().toString(),
            )
                .setAddress(
                    Uri.fromParts("xmpp", getAccount().getJid().asBareJid().toString(), null),
                )
                .setIcon(
                    Icon.createWithBitmap(
                        FileBackend.drawDrawable(
                            AvatarReader.get(
                                this,
                                AvatarReader.systemUiAvatarSize(ctx) / 2,
                                false,
                            ),
                        ),
                    ),
                )
                .setHighlightColor(0x7401CF)
                .setShortDescription(getJid().asBareJid().toString())
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .build()

        try {
            telecomManager.registerPhoneAccount(phoneAccount)
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "Could not registerPhoneAccount: " + e)
        }
    }

    // Unregister any associated PSTN gateway integration
    override fun unregisterAsPhoneAccount(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        if (Build.VERSION.SDK_INT >= 33) {
            if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELECOM) &&
                !ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
            ) {
                return
            }
        } else {
            if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) {
                return
            }
        }

        val telecomManager = ctx.getSystemService(TelecomManager::class.java)

        try {
            telecomManager.unregisterPhoneAccount(phoneAccountHandle())
        } catch (e: SecurityException) {
            Log.w(Config.LOGTAG, "Could not unregister " + getJid() + " as phone account: " + e)
        }
    }

    override fun getAvatarBackgroundColor(): Int {
        val contactJid = jid
        return DisplayNames.getColorForName(
            if (contactJid != null) contactJid.asBareJid().toString() else getDisplayName(),
        )
    }

    override fun getAvatarName(): String = getDisplayName()

    fun hasAvatarOrPresenceName(): Boolean {
        val currentAvatar = avatar
        return (currentAvatar != null && currentAvatar.getFilename() != null) || presenceName != null
    }

    override fun refreshRtpCapability(): Boolean {
        val previous = rtpCapability
        rtpCapability = RtpCapability.check(this, false)
        return !Objects.equals(previous, rtpCapability)
    }

    override fun refreshCaps() {
        getAccount().refreshCapsFor(this)
    }

    override fun getRtpCapability(): RtpCapability.Capability? {
        if (callsDisabled) {
            return null
        }
        return rtpCapability ?: RtpCapability.Capability.NONE
    }

    override fun setCallsDisabled(callsDisabled: Boolean) {
        this.callsDisabled = callsDisabled
    }

    override fun areCallsDisabled(): Boolean = callsDisabled

    object Options {

        const val TO = 0
        const val FROM = 1
        const val ASKING = 2
        const val PREEMPTIVE_GRANT = 3
        const val IN_ROSTER = 4
        const val PENDING_SUBSCRIPTION_REQUEST = 5
        const val DIRTY_PUSH = 6
        const val DIRTY_DELETE = 7
        internal const val SYNCED_VIA_ADDRESS_BOOK = 8
        const val SYNCED_VIA_OTHER = 9
        const val FOLLOWED = 10
    }

    // Method to update this Contact's presences/status messages
    fun updatePresences(newStatusMessages: List<String>?) {
        presences.updateStatusMessages(newStatusMessages)
    }

    fun isFollowed(): Boolean = getOption(Options.FOLLOWED)

    fun setFollowed(followed: Boolean) {
        if (followed) {
            setOption(Options.FOLLOWED)
        } else {
            resetOption(Options.FOLLOWED)
        }
    }

    companion object {

        const val TABLENAME = "contacts"

        const val SYSTEMNAME = "systemname"
        const val SERVERNAME = "servername"
        const val PRESENCE_NAME = "presence_name"
        const val JID = "jid"
        const val OPTIONS = "options"
        const val SYSTEMACCOUNT = "systemaccount"
        const val PHOTOURI = "photouri"
        const val KEYS = "pgpkey"
        const val ACCOUNT = "accountUuid"
        const val AVATAR = "avatar"
        const val LAST_PRESENCE = "last_presence"
        const val LAST_TIME = "last_time"
        const val GROUPS = "groups"
        const val RTP_CAPABILITY = "rtpCapability"
        const val CALLS_DISABLED = "callsDisabled"

        /**
         * The applicationId and the Telecom service class it names, as the OS stores them.
         *
         * <p>Both values end up inside a [PhoneAccountHandle] that Telecom persists on the device, so
         * they are *data* rather than code: a wrong applicationId compiles and runs, it only orphans
         * every call account the owner has already registered. `BuildConfig.APPLICATION_ID` is a Java
         * `static final`, not a Kotlin compile-time constant, so this is the one `@JvmField` in the
         * file; `ApplicationIdTest` is what notices a different value.
         */
        @JvmField
        val APPLICATION_ID: String = BuildConfig.APPLICATION_ID

        const val CONNECTION_SERVICE = "uk.xa0.tulkki.app.extras.ConnectionService"

        @JvmStatic
        fun fromCursor(cursor: Cursor): Contact? {
            val jid: Jid =
                try {
                    Jid.of(cursor.getString(cursor.getColumnIndex(JID)))
                } catch (e: IllegalArgumentException) {
                    // TODO: Borked DB... handle this somehow?
                    return null
                }
            val systemAccount: Uri? =
                try {
                    Uri.parse(cursor.getString(cursor.getColumnIndex(SYSTEMACCOUNT)))
                } catch (e: Exception) {
                    null
                }
            return Contact(
                cursor.getString(cursor.getColumnIndex(ACCOUNT)),
                cursor.getString(cursor.getColumnIndex(SYSTEMNAME)),
                cursor.getString(cursor.getColumnIndex(SERVERNAME)),
                cursor.getString(cursor.getColumnIndex(PRESENCE_NAME)),
                jid,
                cursor.getInt(cursor.getColumnIndex(OPTIONS)),
                cursor.getString(cursor.getColumnIndex(PHOTOURI)),
                systemAccount,
                cursor.getString(cursor.getColumnIndex(KEYS)),
                cursor.getString(cursor.getColumnIndex(AVATAR)),
                cursor.getLong(cursor.getColumnIndex(LAST_TIME)),
                cursor.getString(cursor.getColumnIndex(LAST_PRESENCE)),
                cursor.getString(cursor.getColumnIndex(GROUPS)),
                RtpCapability.Capability.of(cursor.getString(cursor.getColumnIndex(RTP_CAPABILITY))),
                cursor.getInt(cursor.getColumnIndex(CALLS_DISABLED)) > 0,
            )
        }

        fun getOption(clazz: Class<out AbstractPhoneContact>?): Int =
            if (clazz == JabberIdContact::class.java) {
                Options.SYNCED_VIA_ADDRESS_BOOK
            } else {
                Options.SYNCED_VIA_OTHER
            }
    }
}
