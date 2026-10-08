package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.ColorUtils
import com.google.common.base.Strings
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableList
import java.security.interfaces.DSAPublicKey
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import net.java.otr4j.crypto.OtrCryptoEngineImpl
import net.java.otr4j.crypto.OtrCryptoException
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.crypto.OtrService
import uk.xa0.tulkki.crypto.PgpDecryptionService
import uk.xa0.tulkki.crypto.PgpStore
import uk.xa0.tulkki.crypto.axolotl.AxolotlService
import uk.xa0.tulkki.crypto.axolotl.XmppAxolotlSession
import uk.xa0.tulkki.crypto.sasl.ChannelBinding
import uk.xa0.tulkki.crypto.sasl.ChannelBindingMechanism
import uk.xa0.tulkki.crypto.sasl.HashedToken
import uk.xa0.tulkki.crypto.sasl.HashedTokenSha256
import uk.xa0.tulkki.crypto.sasl.HashedTokenSha512
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import uk.xa0.tulkki.data.CryptoStore
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.xmpp.utils.XmppUri

/** Java's `trim()`, which removes every char `<= ' '`; Kotlin's `trim()` also strips NBSP. */
private fun String.javaTrim(): String = trim { it <= ' ' }

/**
 * One XMPP account: its JID and resource, its password and options, the four conference sets the
 * island mutates, its bookmarks and caps cache, its crypto services and the connection it is bound
 * to. It is the model half of `AccountRef` and one of the eight `Avatarable`s pair 2 moved down.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The inherited `AbstractEntity.UUID` is named through its class, and the companion carries a
 *    `UUID` alias for the Kotlin callers.** Java read the inherited static unqualified, which Kotlin
 *    cannot: a Kotlin class does not inherit a Java superclass's statics, so `cursor
 *    .getColumnIndexOrThrow(UUID)` is `AbstractEntity.UUID` here (measured with the 2.3.21 compiler
 *    before the port: `Sub.UUID` is an unresolved reference even when `Base.UUID` is a visible
 *    `static final`). The *other* direction was measured too - `BackupQueries.kt:41`,
 *    `RosterQueries.kt:147`, `RawTables.kt:355` and `Schema76.kt:583` (plus Java's
 *    `DatabaseBackend:1706`, `:1726`) write `Account.UUID`, and `Account.UUID` stops resolving once
 *    `Account` is Kotlin - so the companion declares `const val UUID = AbstractEntity.UUID`. Same
 *    value, same static field, and the readers do not move.
 * 2. **The 23 `static final String`s, the ten `OPTION_*` ints and `KEY_PRE_AUTH_REGISTRATION_TOKEN`
 *    become a companion of `const val`s** (`UUID` included: 34). A `const val` in a companion
 *    compiles to a static field **on `Account` itself**, so all 32 `Account.TABLENAME`,
 *    18 `Account.UUID`, 11 `Account.OPTION_DISABLED`, `Account.KEY_PRE_AUTH_REGISTRATION_TOKEN`
 *    (`MagicCreateActivity:176`) and the twenty-odd other reads - Java and Kotlin alike - are
 *    unchanged. `OPTION_DISABLED = 1` and `OPTION_REGISTER = 2` are bit *numbers*, so `setOption`'s
 *    `1 shl option` stays `1 shl option`. `KEY_PGP_SIGNATURE`, `KEY_PGP_ID` and
 *    `KEY_PINNED_MECHANISM` stay `private const val`: a companion's private member **is** reachable
 *    from the containing class (measured; the `Contact.Options` note that says otherwise is about a
 *    nested `object`, not a companion).
 * 3. **`parseKeys` and `fromCursor` are companion `@JvmStatic`s, and so is `State.fromRef`.** Their
 *    callers are Java: `ImportBackupWorker:327` and `DatabaseBackend:1683`; `AccountStateRefTest:25`
 *    pins `Account.State.fromRef` and `:42` pins `Account.State.values().length`. Without the
 *    annotation they would exist only as `Companion.parseKeys` / `Companion.fromCursor` /
 *    `State.Companion.fromRef`. **Interop debt: three `@JvmStatic`s.**
 * 4. **The nullable set is Java's own, read off its callers rather than inferred.** `getResource()`
 *    is `String?` because `XmppConnection.fixResource` (Java, on `AccountRef`) reads `null` as the
 *    "the server assigned the resource" answer - a non-null returning getter would have turned that
 *    branch into a throw. `getAvatar()`, `getDisplayName()`, `getPassword()`, `getKey`,
 *    `getPrivateKeyAlias()`, `getPgpSignature()`, `getOtrFingerprint()`, `getFastToken()`,
 *    `getFastMechanism()`, `getQuickStartMechanism()`, `getColorToSave()`, `getBookmark()`,
 *    `mamPrefs()` and the six service/connection accessors answer `null` exactly where Java's fields
 *    are null, and Java null-checks five of them (`Account.avatar` at `PresenceParser:372`,
 *    `XmppConnectionService:6546`, `:6725`, `PublishProfilePictureActivity:246`,
 *    `AvatarService:698`; `displayName` at `EditAccountActivity:2123`, `ConversationListActivity:465`;
 *    `privateKeyAlias` at `XmppConnection:665`, `SaslMechanism:115`, `AxolotlService:636`,
 *    `EditAccountActivity:887`; `pgpSignature` at `ConversationFragment:3978`, `:6693`; `xmppConnection`
 *    at thirteen sites). `getJid()`, `getUsername()`, `getServer()`, `getDomain()`, `getRoster()`,
 *    `getSelfContact()` and `getKeys()` stay non-null: no caller can make the field null
 *    (`setJid`'s five callers - `EditAccountActivity:378`, `XmppConnection:233`, `:241`, `:909`,
 *    `:2174` - all pass a value, and `AccountRegistry:83`, `EditAccountActivity:400`,
 *    `MagicCreateActivity:170` all construct with one), and fourteen Kotlin sites dereference
 *    `getJid()` through `Account`.
 * 5. **Two parameters keep Java's null *answers*, and two keep its null *tolerance*.** `getBookmark`
 *    takes `Jid?`: `Contact.kt:270` hands it `Contact.jid`, which is a `@JvmField var jid: Jid?`,
 *    and Java's `jid.asBareJid()` was the NPE - the body reproduces it with
 *    `jid ?: throw NullPointerException()`. `isBlocked(Jid?)` keeps Java's own `jid != null && ...`
 *    answer verbatim. `setPassword`, `setDisplayName`, `setHostname`, `setAvatar`, `setMamPrefs`,
 *    `setXmppConnection`, `setPrivateKeyAlias`, `setRosterVersion`, `setKey`'s value, `setColor` and
 *    `setFastToken`'s token are nullable because Java's field is (`setHostname(null)` at
 *    `ConnectionSettingsFragment:140`, `setAvatar(null)` at `XmppConnectionService:5297`,
 *    `setKey(…, this.preAuth)` at `MagicCreateActivity:176`). `getBookmark`'s parameter is the one
 *    that would have broken a Kotlin caller at compile time, which is why it was measured first.
 * 6. **The two `trim()` sites are Java's**, through this file's `String.javaTrim()`:
 *    `fromCursor`'s resource check (`resource.trim().isEmpty()`) and `isDirectToOnion`'s hostname.
 *    Kotlin's `trim()` also strips NBSP, and the hostname is configuration text.
 * 7. **The four conference sets keep Java's call shape, cast included - and `contains`/`remove` must
 *    not cast at all.** `ConversationalRef` is the parameter type precisely because a
 *    `StubConversation` is a placeholder that is not a `Conversation`; Java's
 *    `Set<Conversation>.contains(conversation)` answered `false` for one, so the two read-only
 *    helpers view the set as `MutableSet<Any?>` and call Java's own `contains(Object)`/`remove(Object)`.
 *    `add` keeps Java's `(Conversation) conversation`, which is the one operation that always cast.
 *    The private `drain`/`contains`/`add`/`remove` still take **the set's own monitor**, so the lock
 *    object and its granularity are unchanged, and `drain` is still one atomic take.
 * 8. **Two dead `protected` fields are deleted**: `resource` (`Account.java:224`) and `online`
 *    (`:228`) were declared and never read or written - `getResource()`/`setResource` work off `jid`,
 *    and `grep '\.online\b'` finds only `MucOptions.online()`. Neither has a subclass (this is a
 *    leaf: no `extends Account` anywhere) and no same-package reader, so removing them removes a
 *    surface nothing named.
 * 9. **`State` stays a nested enum, so its binary name stays `Account$State`**, and Java's three
 *    constructors collapse into one primary with two defaults: `OFFLINE(false)` means
 *    `(false, attemptReconnect = true)` and the no-argument constants mean `(true, true)`, exactly
 *    Java's three constructors. `isError` and `isAttemptReconnect` are `val`s, so the getters Java
 *    calls are `isError()`/`isAttemptReconnect()` - the second is deliberately *not* named
 *    `attemptReconnect`, which would have generated `getAttemptReconnect()`. `toRef()`/`fromRef()`
 *    keep Java's two exhaustive switches with their trailing throws.
 * 10. **`toLowerCase(Locale.US)` is `lowercase(Locale.US)`** (the locale was explicit, so the
 *    locale-sensitive `toLowerCase()` trap does not apply), `instanceof` is `is`, `new ArrayList<>(set)`
 *    is `ArrayList(set)`, and `getXmppConnection()`'s double read in `hasErrorStatus` becomes one
 *    local - the value has no side effect and the Java read it twice.
 *
 * <p>Interop debt: **three `@JvmStatic`s and 34 `const val`s**, no `@JvmField`, no `@JvmOverloads`,
 * no `@Throws`. The statics exist because Java readers name them by class; they go to zero when
 * `DatabaseBackend`, `ImportBackupWorker` and `AccountStateRefTest` are Kotlin.
 */
class Account private constructor(
    uuid: String,
    jid: Jid?,
    password: String?,
    options: Int,
    rosterVersion: String?,
    keysJson: String?,
    avatar: String?,
    displayName: String?,
    hostname: String?,
    port: Int,
    status: Presence.Status,
    statusMessage: String?,
    pinnedMechanism: String?,
    pinnedChannelBinding: String?,
    fastMechanism: String?,
    fastToken: String?,
    ordering: Int,
) : AbstractEntity(), Avatarable, OmemoAccount, AccountRef {

    private var jidValue: Jid? = null
    private var password: String? = null
    private var options: Int = 0
    private var status: State = State.OFFLINE
    private var lastErrorStatus: State = State.OFFLINE
    private var avatar: String? = null
    private var hostname: String? = null
    private var port: Int = 5222
    private var rosterVersion: String? = null
    private var displayName: String? = null
    private var axolotlService: AxolotlService? = null
    private var pgpStore: PgpStore? = null
    private var pgpDecryptionService: PgpDecryptionService? = null
    private var xmppConnection: XmppConnection? = null
    private var mEndGracePeriod: Long = 0L
    private val bookmarks: MutableMap<Jid, Bookmark> = HashMap()
    private var bookmarksLoaded = false
    private var presenceStatus: Presence.Status = Presence.Status.ONLINE
    private var presenceStatusMessage: String? = null
    private var pinnedMechanism: String? = null
    private var pinnedChannelBinding: String? = null
    private var fastMechanism: String? = null
    private var fastToken: String? = null
    private var color: Int? = null
    private val gateways: HashMultimap<String, Contact> = HashMultimap.create()
    private var otrFingerprint: String? = null
    private var mOtrService: OtrService? = null
    private var mamPrefs: Element? = null

    private val keys: JSONObject
    private val roster = Roster(this)
    private val blocklist: MutableCollection<Jid> = CopyOnWriteArraySet()

    // Tulkki: these four were public fields the island synchronized on, reached and mutated by name
    // (3.7 pair 9, the brief's §2.2 - the only drags in the direction that were a call shape rather
    // than a type). `AccountRef` carries the operations instead, each taking the collection's own
    // lock here, exactly as the call sites used to. The lock object is unchanged - it is still the
    // set - so the locking granularity is unchanged too; only who holds the monitor moved.
    private val pendingConferenceJoins: MutableSet<Conversation> = HashSet()
    private val pendingConferenceLeaves: MutableSet<Conversation> = HashSet()
    private val inProgressConferenceJoins: MutableSet<Conversation> = HashSet()
    private val inProgressConferencePings: MutableSet<Conversation> = HashSet()

    private var ordering = 0

    init {
        this.uuid = uuid
        this.jidValue = jid
        this.password = password
        this.options = options
        this.rosterVersion = rosterVersion
        this.keys = parseKeys(keysJson)
        this.avatar = avatar
        this.displayName = displayName
        this.hostname = hostname
        this.port = port
        this.presenceStatus = status
        this.presenceStatusMessage = statusMessage
        this.pinnedMechanism = pinnedMechanism
        this.pinnedChannelBinding = pinnedChannelBinding
        this.fastMechanism = fastMechanism
        this.fastToken = fastToken
        this.ordering = ordering
    }

    constructor(jid: Jid, password: String?) : this(
        java.util.UUID.randomUUID().toString(),
        jid,
        password,
        0,
        null,
        "",
        null,
        null,
        null,
        Resolver.XMPP_PORT_STARTTLS,
        Presence.Status.ONLINE,
        null,
        null,
        null,
        null,
        null,
        0,
    )

    // -- the four conference sets, as operations ----------------------------------------------------

    override fun isConferenceJoinPending(conversation: ConversationalRef): Boolean =
        contains(pendingConferenceJoins, conversation)

    override fun isConferenceJoinInProgress(conversation: ConversationalRef): Boolean =
        contains(inProgressConferenceJoins, conversation)

    override fun addConferenceJoinPending(conversation: ConversationalRef): Boolean =
        add(pendingConferenceJoins, conversation)

    override fun removeConferenceJoinPending(conversation: ConversationalRef): Boolean =
        remove(pendingConferenceJoins, conversation)

    override fun addConferenceJoinInProgress(conversation: ConversationalRef): Boolean =
        add(inProgressConferenceJoins, conversation)

    override fun removeConferenceJoinInProgress(conversation: ConversationalRef): Boolean =
        remove(inProgressConferenceJoins, conversation)

    override fun clearConferenceJoinsInProgress() {
        synchronized(inProgressConferenceJoins) {
            inProgressConferenceJoins.clear()
        }
    }

    override fun addConferenceLeavePending(conversation: ConversationalRef): Boolean =
        add(pendingConferenceLeaves, conversation)

    override fun removeConferenceLeavePending(conversation: ConversationalRef): Boolean =
        remove(pendingConferenceLeaves, conversation)

    override fun drainPendingConferenceJoins(): List<Conversation> = drain(pendingConferenceJoins)

    override fun drainPendingConferenceLeaves(): List<Conversation> = drain(pendingConferenceLeaves)

    override fun addConferencePingInProgress(conversation: ConversationalRef): Boolean =
        add(inProgressConferencePings, conversation)

    override fun removeConferencePingInProgress(conversation: ConversationalRef): Boolean =
        remove(inProgressConferencePings, conversation)

    override fun clearConferencePingsInProgress() {
        synchronized(inProgressConferencePings) {
            inProgressConferencePings.clear()
        }
    }

    // The drain is one atomic take: `new ArrayList<>(set)` followed by `clear()` under the set's own
    // lock is exactly what the call sites did, and leaving them separate would let a join slip in
    // between the copy and the clear and be lost. The caller is handed a list it owns.
    private fun drain(set: MutableSet<Conversation>): List<Conversation> = synchronized(set) {
        val drained = ArrayList(set)
        set.clear()
        drained
    }

    // Java's `Set<Conversation>.contains(Object)` and `.remove(Object)`: the argument is a
    // `ConversationalRef` and a `StubConversation` is not a `Conversation`, so this is the one place
    // the port must *not* cast - a cast would turn Java's `false` into a `ClassCastException`, which
    // `ConversationalRef`'s own comment records as the reason its parameter type is the
    // placeholder-inclusive one.
    @Suppress("UNCHECKED_CAST")
    private fun contains(set: MutableSet<Conversation>, conversation: ConversationalRef): Boolean =
        synchronized(set) { (set as MutableSet<Any?>).contains(conversation) }

    private fun add(set: MutableSet<Conversation>, conversation: ConversationalRef): Boolean =
        synchronized(set) { set.add(conversation as Conversation) }

    @Suppress("UNCHECKED_CAST")
    private fun remove(set: MutableSet<Conversation>, conversation: ConversationalRef): Boolean =
        synchronized(set) { (set as MutableSet<Any?>).remove(conversation) }

    // -- state, options and identity ----------------------------------------------------------------

    override fun setMamPrefs(prefs: Element?) {
        mamPrefs = prefs
    }

    fun mamPrefs(): Element? = mamPrefs

    override fun httpUploadAvailable(size: Long): Boolean {
        val connection = xmppConnection ?: return false
        return connection.getFeatures().httpUpload(size)
    }

    override fun httpUploadAvailable(): Boolean =
        isOptionSet(OPTION_HTTP_UPLOAD_AVAILABLE) || httpUploadAvailable(0L)

    override fun getDisplayName(): String? = displayName

    override fun setDisplayName(displayName: String?) {
        this.displayName = displayName
    }

    override fun getSelfContact(): Contact = getRoster().getContact(getJid())

    fun hasPendingPgpIntent(conversation: Conversation): Boolean {
        val service = pgpDecryptionService
        return service != null && service.hasPendingIntent(conversation)
    }

    fun isPgpDecryptionServiceConnected(): Boolean {
        val service = pgpDecryptionService
        return service != null && service.isConnected()
    }

    override fun setColor(color: Int?) {
        this.color = color
    }

    override fun getColor(dark: Boolean): Int {
        val saved = color
        if (saved != null) return saved

        return ColorUtils.setAlphaComponent(
            getAvatarBackgroundColor(),
            if (dark) 25 else 20,
        )
    }

    override fun getColorToSave(): Int? = color

    override fun setShowErrorNotification(newValue: Boolean): Boolean {
        val oldValue = showErrorNotification()
        setKey("show_error", newValue.toString())
        return newValue != oldValue
    }

    fun showErrorNotification(): Boolean {
        val key = getKey("show_error")
        return key == null || key.toBoolean()
    }

    override fun isEnabled(): Boolean = !isOptionSet(OPTION_DISABLED)

    override fun isConnectionEnabled(): Boolean =
        !isOptionSet(OPTION_DISABLED) && !isOptionSet(OPTION_SOFT_DISABLED)

    override fun isOptionSet(option: Int): Boolean = (options and (1 shl option)) != 0

    override fun setOption(option: Int, value: Boolean): Boolean {
        if (value && (option == OPTION_DISABLED || option == OPTION_SOFT_DISABLED)) {
            setStatus(State.OFFLINE)
        }
        val before = this.options
        if (value) {
            this.options = this.options or (1 shl option)
        } else {
            this.options = this.options and (1 shl option).inv()
        }
        return before != this.options
    }

    override fun getUsername(): String = getJid().getLocal() ?: throw NullPointerException()

    /**
     * The `next == null` branch is Java's own: the parameter is nullable, the field is assigned
     * either way, and a null JID is what "the server has not bound us yet" looks like to
     * `XmppConnection.fixResource`.
     */
    override fun setJid(next: Jid?): Boolean {
        val previousFull = jidValue
        val prev = jidValue?.asBareJid()
        val changed = prev == null || (next != null && prev != next.asBareJid())
        if (changed) {
            val oldAxolotlService = this.axolotlService
            if (oldAxolotlService != null) {
                oldAxolotlService.destroy()
                jidValue = next
                this.axolotlService = oldAxolotlService.makeNew()
            }
        }
        jidValue = next
        return next != null && next != previousFull
    }

    override fun getDomain(): Jid = getJid().getDomain()

    override fun getServer(): String = getJid().getDomain().toString()

    override fun getPassword(): String? = password

    override fun setPassword(password: String?) {
        this.password = password
    }

    override fun getHostname(): String = Strings.nullToEmpty(hostname)

    fun setHostname(hostname: String?) {
        this.hostname = hostname
    }

    override fun isOnion(): Boolean = getServer().endsWith(".onion")

    override fun isI2P(): Boolean = getServer().endsWith(".i2p")

    override fun isDirectToOnion(): Boolean {
        val hostname = Strings.nullToEmpty(this.hostname).javaTrim()
        return isOnion() && (hostname.isEmpty() || hostname.endsWith(".onion"))
    }

    override fun getPort(): Int = port

    fun setPort(port: Int) {
        this.port = port
    }

    fun getStatus(): State = if (isOptionSet(OPTION_DISABLED)) {
        State.DISABLED
    } else if (isOptionSet(OPTION_SOFT_DISABLED)) {
        State.LOGGED_OUT
    } else {
        status
    }

    fun unauthorized(): Boolean =
        status == State.UNAUTHORIZED || lastErrorStatus == State.UNAUTHORIZED

    fun getLastErrorStatus(): State = lastErrorStatus

    fun setStatus(status: State) {
        this.status = status
        if (status.isError || status == State.ONLINE) {
            lastErrorStatus = status
        }
    }

    /**
     * Tulkki: the one implementor of `AccountRef.getUuid`, which is non-null. `AbstractEntity.uuid`
     * is a nullable field and `getUuid()` is open, but this class's constructor takes
     * `uuid: String`, assigns it once in `init`, and no `Account` code ever clears it - so the
     * override narrows to the truth. The fallback keeps the NPE a caller dereferencing the field
     * would have got, and the descriptor stays `()Ljava/lang/String;`.
     */
    override fun getUuid(): String = uuid ?: throw NullPointerException("account has no uuid")

    /**
     * Tulkki: 3.7 C5-D - the island's view of {@link #getStatus()}, under a name of its own because a
     * same-named overload returning `AccountRef.StateRef` could not be an override. The map is
     * `State.toRef()` and it is exhaustive; the effective status still folds the disabled and
     * soft-disabled options in, because it is `getStatus()` that is mapped.
     */
    override fun getStatusRef(): AccountRef.StateRef = getStatus().toRef()

    /**
     * Tulkki: C5-E1 - the raw-status view, and the one member this slice added to the model.
     *
     * `getStatusRef()` folds the disabled and soft-disabled options in because it maps `getStatus()`;
     * `XmppConnectionService.reconnectAccount` asks whether an *error* is worth a reconnect and must
     * see the raw status, exactly as it read `getTrueStatus().isError()` before. The mapping is the
     * same exhaustive `State.toRef()` the other pair uses.
     */
    override fun getTrueStatusRef(): AccountRef.StateRef = getTrueStatus().toRef()

    /** Tulkki: the write half, mapped back and delegated so `lastErrorStatus` stays maintained. */
    override fun setStatusRef(status: AccountRef.StateRef) {
        setStatus(State.fromRef(status))
    }

    override fun getLastErrorStatusRef(): AccountRef.StateRef = getLastErrorStatus().toRef()

    /**
     * Tulkki: 3.7 C5-D - the island's view of `getPresenceStatus()`, under a name of its own for the
     * same language reason as `getStatusRef()`. The mapping is `Presence.Status.toRef()`, exhaustive
     * on the model's side.
     */
    override fun getPresenceStatusRef(): PresenceRef.StatusRef = getPresenceStatus().toRef()

    /**
     * Tulkki: 3.7 C5-D. `XmppConnection` used to name `KEY_PRE_AUTH_REGISTRATION_TOKEN` and call
     * `getKey(String)` with it; the key is written by `:ui`'s `MagicCreateActivity`, so the island
     * asks this question instead of holding a copy of the string that could drift.
     */
    override fun getPreAuthRegistrationToken(): String? = getKey(KEY_PRE_AUTH_REGISTRATION_TOKEN)

    override fun setPinnedMechanism(mechanism: SaslMechanism) {
        pinnedMechanism = mechanism.getMechanism()
        pinnedChannelBinding = if (mechanism is ChannelBindingMechanism) {
            mechanism.getChannelBinding().toString()
        } else {
            null
        }
    }

    override fun setFastToken(mechanism: HashedToken.Mechanism, token: String?) {
        fastMechanism = mechanism.name()
        fastToken = token
    }

    override fun resetFastToken() {
        fastMechanism = null
        fastToken = null
    }

    fun resetPinnedMechanism() {
        pinnedMechanism = null
        pinnedChannelBinding = null
        setKey(KEY_PINNED_MECHANISM, (-1).toString())
    }

    override fun getPinnedMechanismPriority(): Int {
        val fallback = getKeyAsInt(KEY_PINNED_MECHANISM, -1)
        if (Strings.isNullOrEmpty(pinnedMechanism)) {
            return fallback
        }
        val saslMechanism = getPinnedMechanism()
        return saslMechanism?.getPriority() ?: fallback
    }

    private fun getPinnedMechanism(): SaslMechanism? {
        val mechanism = Strings.nullToEmpty(pinnedMechanism)
        val channelBinding = ChannelBinding.get(pinnedChannelBinding)
        return SaslMechanism.Factory(this).of(mechanism, channelBinding)
    }

    override fun getFastMechanism(): HashedToken? {
        val mechanism = HashedToken.Mechanism.ofOrNull(fastMechanism)
        val token = fastToken
        if (mechanism == null || Strings.isNullOrEmpty(token)) {
            return null
        }
        if (mechanism.hashFunction.equals("SHA-256")) {
            return HashedTokenSha256(this, mechanism.channelBinding)
        } else if (mechanism.hashFunction.equals("SHA-512")) {
            return HashedTokenSha512(this, mechanism.channelBinding)
        } else {
            return null
        }
    }

    override fun getQuickStartMechanism(): SaslMechanism? =
        getFastMechanism() ?: getPinnedMechanism()

    override fun getFastToken(): String? = fastToken

    fun getTrueStatus(): State = status

    fun errorStatus(): Boolean = getStatus().isError

    override fun hasErrorStatus(): Boolean {
        val connection = xmppConnection
        return connection != null &&
            (getStatus().isError || getStatus() == State.CONNECTING) &&
            connection.getAttempt() >= 3
    }

    fun getPresenceStatus(): Presence.Status = presenceStatus

    fun setPresenceStatus(status: Presence.Status) {
        presenceStatus = status
    }

    /**
     * Tulkki: 3.7 C5-E2 - the write half of `getPresenceStatusRef()`, and distinctly named for the
     * same language reason: a `setPresenceStatus(PresenceRef.StatusRef)` overload *would* be legal
     * (different parameter type), but the pair this file already spells `getPresenceStatusRef` /
     * `getStatusRef` should not acquire a second spelling. The mapping is
     * `Presence.Status.fromRef`, and it delegates so any bookkeeping `setPresenceStatus` grows stays
     * in one place.
     */
    override fun setPresenceStatusRef(status: PresenceRef.StatusRef) {
        setPresenceStatus(Presence.Status.fromRef(status))
    }

    override fun getPresenceStatusMessage(): String? = presenceStatusMessage

    override fun setPresenceStatusMessage(message: String?) {
        presenceStatusMessage = message
    }

    override fun getResource(): String? = getJid().getResource()

    override fun setResource(resource: String) {
        jidValue = getJid().withResource(resource)
    }

    override fun getJid(): Jid = jidValue ?: throw NullPointerException()

    override fun getKeys(): JSONObject = keys

    override fun getKey(name: String): String? = synchronized(keys) { keys.optString(name, null) }

    fun getKeyAsInt(name: String, defaultValue: Int): Int {
        val key = getKey(name)
        return try {
            if (key == null) defaultValue else key.toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    override fun setKey(keyName: String, keyValue: String?): Boolean {
        synchronized(keys) {
            return try {
                keys.put(keyName, keyValue)
                true
            } catch (e: JSONException) {
                false
            }
        }
    }

    override fun setPrivateKeyAlias(alias: String?) {
        setKey("private_key_alias", alias)
    }

    override fun getPrivateKeyAlias(): String? = getKey("private_key_alias")

    override fun getContentValues(): ContentValues {
        val values = ContentValues()
        values.put(AbstractEntity.UUID, uuid)
        values.put(USERNAME, getJid().getLocal())
        values.put(SERVER, getJid().getDomain().toString())
        values.put(PASSWORD, password)
        values.put(OPTIONS, options)
        synchronized(keys) {
            values.put(KEYS, keys.toString())
        }
        values.put(ROSTERVERSION, rosterVersion)
        values.put(AVATAR, avatar)
        values.put(DISPLAY_NAME, displayName)
        values.put(HOSTNAME, hostname)
        values.put(PORT, port)
        values.put(STATUS, presenceStatus.toShowString())
        values.put(STATUS_MESSAGE, presenceStatusMessage)
        values.put(RESOURCE, getJid().getResource())
        values.put(PINNED_MECHANISM, pinnedMechanism)
        values.put(PINNED_CHANNEL_BINDING, pinnedChannelBinding)
        values.put(FAST_MECHANISM, fastMechanism)
        values.put(FAST_TOKEN, fastToken)
        values.put(ORDERING, ordering)
        return values
    }

    override fun getAxolotlService(): AxolotlService? = axolotlService

    /**
     * Pair 7: the same engine seen through the port the XMPP island declares. The additive accessor
     * is deliberate - `getAxolotlService()`'s `AxolotlService` return type is named by eleven
     * `uk.xa0` files, six of them in `:ui`, and retyping it would move the island reach rather than
     * remove it. Only the island's six sites move to this one.
     */
    override fun getOmemoSession(): OmemoSessionPort? = axolotlService

    override fun initAccountServices(context: XmppConnectionService) {
        this.pgpStore = CryptoStore(context, this)
        this.mOtrService = OtrService(context, this)
        this.axolotlService = AxolotlService(this, context)
        this.pgpDecryptionService = PgpDecryptionService(this.pgpStore, context)
        val connection = xmppConnection
        if (connection != null) {
            // The value is Java's own `new AxolotlService(...)` two lines up: non-null at this
            // site, while the field stays nullable because Java declared it `= null` and
            // `getFingerprints` null-checks it. The callee's non-null parameter is its own Java
            // contract - `advancedStreamFeaturesLoadedListeners` is read by
            // `enableAdvancedStreamFeatures` as `listener.onAdvancedStreamFeaturesAvailable(...)`
            // unguarded, so a null listener was never usable and Java could only have thrown.
            connection.addOnAdvancedStreamFeaturesAvailableListener(
                axolotlService ?: throw NullPointerException("axolotlService"),
            )
        }
    }

    override fun getPgpStore(): PgpStore? = pgpStore

    fun getOtrService(): OtrService? = mOtrService

    /** Pair 7: the OTR engine through the port the XMPP island declares. */
    override fun getOtrPeer(): uk.xa0.tulkki.xmpp.services.OtrPeerPort? = mOtrService

    override fun getPgpDecryptionService(): PgpDecryptionService? = pgpDecryptionService

    /** Pair 7: the OpenPGP decryption queue through the port the XMPP island declares. */
    override fun getPgpDecryption(): uk.xa0.tulkki.xmpp.services.PgpDecryptionPort? = pgpDecryptionService

    override fun getXmppConnection(): XmppConnection? = xmppConnection

    override fun setXmppConnection(connection: XmppConnection?) {
        xmppConnection = connection
    }

    override fun getRosterVersion(): String = rosterVersion ?: ""

    fun getOtrFingerprint(): String? {
        if (otrFingerprint == null) {
            try {
                val service = mOtrService ?: return null
                val publicKey = service.getPublicKey() ?: return null
                if (publicKey !is DSAPublicKey) {
                    return null
                }
                otrFingerprint = OtrCryptoEngineImpl().getFingerprint(publicKey).lowercase(Locale.US)
                return otrFingerprint
            } catch (e: OtrCryptoException) {
                return null
            }
        } else {
            return otrFingerprint
        }
    }

    fun setRosterVersion(version: String?) {
        rosterVersion = version
    }

    override fun countPresences(): Int = getSelfContact().getPresences().size()

    override fun activeDevicesWithRtpCapability(): Int {
        var i = 0
        for (presence in getSelfContact().getPresences().getPresences()) {
            if (RtpCapability.check(presence) != RtpCapability.Capability.NONE) {
                i++
            }
        }
        return i
    }

    override fun getPgpSignature(): String? = getKey(KEY_PGP_SIGNATURE)

    override fun setPgpSignature(signature: String?): Boolean =
        setKey(KEY_PGP_SIGNATURE, signature)

    fun unsetPgpSignature(): Boolean =
        synchronized(keys) { keys.remove(KEY_PGP_SIGNATURE) != null }

    override fun getPgpId(): Long = synchronized(keys) {
        if (keys.has(KEY_PGP_ID)) {
            try {
                keys.getLong(KEY_PGP_ID)
            } catch (e: JSONException) {
                0L
            }
        } else {
            0L
        }
    }

    fun setPgpSignId(pgpID: Long): Boolean {
        synchronized(keys) {
            try {
                if (pgpID == 0L) {
                    keys.remove(KEY_PGP_ID)
                } else {
                    keys.put(KEY_PGP_ID, pgpID)
                }
            } catch (e: JSONException) {
                return false
            }
            return true
        }
    }

    override fun getRoster(): Roster = roster

    fun refreshCapsFor(contact: Contact) {
        synchronized(gateways) {
            for (k in HashSet(gateways.keySet())) {
                gateways.remove(k, contact)
            }
            for (p in contact.getPresences().getPresences()) {
                val disco = p.getServiceDiscoveryResult() ?: continue
                for (identity in disco.getIdentities()) {
                    if ("gateway" == identity.getCategory()) {
                        val type = identity.getType() ?: throw NullPointerException()
                        gateways.put(type, contact)
                    }
                }
            }
        }
    }

    fun getGateways(type: String): Collection<Contact> = synchronized(gateways) {
        ImmutableList.copyOf(gateways.get(type))
    }

    override fun getBookmarks(): Collection<Bookmark> = synchronized(bookmarks) {
        ImmutableList.copyOf(bookmarks.values)
    }

    override fun areBookmarksLoaded(): Boolean {
        val connection = xmppConnection ?: throw NullPointerException()
        if (connection.getFeatures().bookmarksConversion()) return true

        return bookmarksLoaded
    }

    /**
     * Tulkki: the island's overloads for the two bookmark operations whose parameter is a `:data`
     * type.
     *
     * 3.7 pair 9, turn 1 of cluster (c). A ref cannot be a `Bookmark`, so the port carries the
     * ref-typed signature and the model adapts here - the shape the brief names for every
     * parameter-position drag (§2.2), and the only kind of member this pair adds to `:data`.
     * `setBookmarks` is **not** among them: its parameter is a `Map`, and a ref-typed `Map` overload
     * would erase to the same `setBookmarks(Map)` as the model's own and javac refuses the pair.
     */
    override fun putBookmark(bookmark: BookmarkRef) {
        putBookmark(bookmark as Bookmark)
    }

    /**
     * Tulkki: C5-E2. `AccountRef.removeBookmark(BookmarkRef)` - the overload that keeps the island's
     * old call shape (`account.removeBookmark(bookmark)`) instead of routing it through the bare-jid
     * `Jid` overload, whose key the model does *not* `asBareJid()`. Identity-safe like the rest of
     * this pair: only `:data` ever builds a `Bookmark`.
     */
    override fun removeBookmark(bookmark: BookmarkRef) {
        removeBookmark(bookmark as Bookmark)
    }

    /**
     * Tulkki: C5-E2, the round-429 ruling - the serialisation `XmppConnectionService`'s
     * `pushBookmarksPrivateXml`/`pushBookmarksPep` used to do in the island, done in the model that
     * owns the bookmarks instead. The two callers differ only in which element they hand over
     * (`<storage>` for private XML, the PEP payload element), and both loops were
     * `storage.addChild(bookmark)` over `getBookmarks()`, so this is those lines and nothing more.
     * `Bookmark extends Element`, so the child added is the same object it always was and the stanza
     * is byte-identical.
     */
    override fun addBookmarksTo(storage: Element) {
        for (bookmark in getBookmarks()) {
            storage.addChild(bookmark)
        }
    }

    /**
     * Tulkki: `AccountRef.replaceBookmarks`, the **differently named** member the erasure clash
     * forces - see the class comment above, and the 3.7 pair-9-shadows commit 94e1fed6c1 §2.1.
     *
     * The island's one caller passes `Collections.emptyMap()` to clear the account's bookmarks before
     * announcing the deletion, so the ref's parameter is the wildcard map the island can spell. The
     * cast is identity-safe for the same reason every cast in this pair is: the object really is a
     * `Map<Jid, Bookmark>` - only `:data` ever builds one - and the body is the model's own
     * `setBookmarks`, which keeps the lock and the `bookmarksLoaded` flag exactly as they were.
     */
    @Suppress("UNCHECKED_CAST")
    override fun replaceBookmarks(bookmarks: Map<Jid, out BookmarkRef>) {
        setBookmarks(bookmarks as Map<Jid, Bookmark>)
    }

    fun setBookmarks(bookmarks: Map<Jid, Bookmark>) {
        synchronized(this.bookmarks) {
            this.bookmarks.clear()
            this.bookmarks.putAll(bookmarks)
            this.bookmarksLoaded = true
        }
    }

    fun putBookmark(bookmark: Bookmark) {
        synchronized(bookmarks) {
            bookmarks[bookmark.getJid()] = bookmark
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        synchronized(bookmarks) {
            bookmarks.remove(bookmark.getJid())
        }
    }

    override fun removeBookmark(jid: Jid) {
        synchronized(bookmarks) {
            bookmarks.remove(jid)
        }
    }

    override fun getBookmarkedJids(): MutableSet<Jid> = synchronized(bookmarks) {
        HashSet(bookmarks.keys)
    }

    /**
     * The parameter is nullable because Java's was: the body dereferences it inside the lock, which
     * is the NPE `Contact.kt:270` used to get from Java when it handed over its own nullable `jid`.
     */
    fun getBookmark(jid: Jid?): Bookmark? = synchronized(bookmarks) {
        bookmarks[(jid ?: throw NullPointerException()).asBareJid()]
    }

    override fun setAvatar(filename: String?): Boolean {
        if (avatar != null && avatar == filename) {
            return false
        } else {
            avatar = filename
            return true
        }
    }

    override fun getAvatar(): String? = avatar

    override fun activateGracePeriod(duration: Long) {
        if (duration > 0) {
            mEndGracePeriod = SystemClock.elapsedRealtime() + duration
        }
    }

    override fun deactivateGracePeriod() {
        mEndGracePeriod = 0L
    }

    fun inGracePeriod(): Boolean = SystemClock.elapsedRealtime() < mEndGracePeriod

    fun getShareableUri(): String {
        val fingerprints = getFingerprints()
        val uri = "xmpp:" + Uri.encode(getJid().asBareJid().toString(), "@/+")
        return if (fingerprints.isEmpty()) {
            uri
        } else {
            XmppUri.getFingerprintUri(uri, fingerprints, ';')
        }
    }

    // `getShareableLink` used to build an invite URL on a third party's web host for the account.
    // It is deleted rather than re-pointed: that host is not ours, and Tulkki has no invite page of
    // its own, so the `xmpp:` URI above is the one address the app hands out.
    private fun getFingerprints(): List<XmppUri.Fingerprint> {
        val fingerprints = ArrayList<XmppUri.Fingerprint>()
        val otr = getOtrFingerprint()
        if (otr != null) {
            fingerprints.add(XmppUri.Fingerprint(XmppUri.FingerprintType.OTR, otr))
        }
        val service = axolotlService ?: return fingerprints
        fingerprints.add(
            XmppUri.Fingerprint(
                XmppUri.FingerprintType.OMEMO,
                service.getOwnFingerprint().substring(2),
                service.getOwnDeviceId(),
            ),
        )
        for (session: XmppAxolotlSession in service.findOwnSessions()) {
            if (session.getTrust().isVerified() && session.getTrust().isActive()) {
                val fingerprint = session.getFingerprint() ?: continue
                fingerprints.add(
                    XmppUri.Fingerprint(
                        XmppUri.FingerprintType.OMEMO,
                        fingerprint.substring(2).replace("\\s".toRegex(), ""),
                        session.getRemoteAddress().getDeviceId(),
                    ),
                )
            }
        }
        return fingerprints
    }

    fun isBlocked(contact: ListItem): Boolean {
        val jid = contact.getJid()
        return blocklist.contains(jid.asBareJid()) || blocklist.contains(jid.getDomain())
    }

    fun isBlocked(jid: Jid?): Boolean =
        jid != null && blocklist.contains(jid.asBareJid())

    override fun getBlocklist(): MutableCollection<Jid> = blocklist

    override fun clearBlocklist() {
        blocklist.clear()
    }

    override fun isOnlineAndConnected(): Boolean =
        getStatus() == State.ONLINE && getXmppConnection() != null

    override fun getAvatarBackgroundColor(): Int =
        DisplayNames.getColorForName(getJid().asBareJid().toString())

    override fun getAvatarName(): String {
        throw IllegalStateException("This method should not be called")
    }

    fun getOrdering(): Int = ordering

    override fun setOrdering(ordering: Int) {
        this.ordering = ordering
    }

    enum class State(val isError: Boolean = true, val isAttemptReconnect: Boolean = true) {
        DISABLED(false, false),
        LOGGED_OUT(false, false),
        OFFLINE(false),
        CONNECTING(false),
        ONLINE(false),
        NO_INTERNET(false),
        CONNECTION_TIMEOUT,
        UNAUTHORIZED,
        TEMPORARY_AUTH_FAILURE,
        SERVER_NOT_FOUND,
        REGISTRATION_SUCCESSFUL(false),
        REGISTRATION_FAILED(true, false),
        REGISTRATION_WEB(true, false),
        REGISTRATION_CONFLICT(true, false),
        REGISTRATION_NOT_SUPPORTED(true, false),
        REGISTRATION_PLEASE_WAIT(true, false),
        REGISTRATION_INVALID_TOKEN(true, false),
        REGISTRATION_PASSWORD_TOO_WEAK(true, false),
        TLS_ERROR,
        TLS_ERROR_DOMAIN,
        CHANNEL_BINDING,
        INCOMPATIBLE_SERVER,
        INCOMPATIBLE_CLIENT,
        TOR_NOT_AVAILABLE,
        I2P_NOT_AVAILABLE,
        DOWNGRADE_ATTACK,
        SESSION_FAILURE,
        BIND_FAILURE,
        HOST_UNKNOWN,
        STREAM_ERROR,
        SEE_OTHER_HOST,
        STREAM_OPENING_ERROR,
        POLICY_VIOLATION,
        PAYMENT_REQUIRED,
        MISSING_INTERNET_PERMISSION(false),
        DANE_FAILED(true);

        fun getReadableId(): Int = when (this) {
            DISABLED -> R.string.account_status_disabled
            LOGGED_OUT -> R.string.account_state_logged_out
            ONLINE -> R.string.rtp_state_connected
            CONNECTING -> R.string.account_status_connecting
            OFFLINE -> R.string.account_status_offline
            UNAUTHORIZED -> R.string.account_status_unauthorized
            SERVER_NOT_FOUND -> R.string.account_status_not_found
            NO_INTERNET -> R.string.account_status_no_internet
            CONNECTION_TIMEOUT -> R.string.account_status_connection_timeout
            REGISTRATION_FAILED -> R.string.account_status_regis_fail
            REGISTRATION_WEB -> R.string.account_status_regis_web
            REGISTRATION_CONFLICT -> R.string.account_status_regis_conflict
            REGISTRATION_SUCCESSFUL -> R.string.account_status_regis_success
            REGISTRATION_NOT_SUPPORTED -> R.string.account_status_regis_not_sup
            REGISTRATION_INVALID_TOKEN -> R.string.account_status_regis_invalid_token
            TLS_ERROR -> R.string.account_status_tls_error
            TLS_ERROR_DOMAIN -> R.string.account_status_tls_error_domain
            INCOMPATIBLE_SERVER -> R.string.account_status_incompatible_server
            INCOMPATIBLE_CLIENT -> R.string.account_status_incompatible_client
            CHANNEL_BINDING -> R.string.account_status_channel_binding
            TOR_NOT_AVAILABLE -> R.string.account_status_tor_unavailable
            I2P_NOT_AVAILABLE -> R.string.account_status_i2p_unavailable
            BIND_FAILURE -> R.string.account_status_bind_failure
            SESSION_FAILURE -> R.string.session_failure
            DOWNGRADE_ATTACK -> R.string.sasl_downgrade
            HOST_UNKNOWN -> R.string.account_status_host_unknown
            POLICY_VIOLATION -> R.string.account_status_policy_violation
            REGISTRATION_PLEASE_WAIT -> R.string.registration_please_wait
            REGISTRATION_PASSWORD_TOO_WEAK -> R.string.registration_password_too_weak
            STREAM_ERROR -> R.string.account_status_stream_error
            STREAM_OPENING_ERROR -> R.string.account_status_stream_opening_error
            PAYMENT_REQUIRED -> R.string.payment_required
            SEE_OTHER_HOST -> R.string.reconnect_on_other_host
            MISSING_INTERNET_PERMISSION -> R.string.missing_internet_permission
            TEMPORARY_AUTH_FAILURE -> R.string.account_status_temporary_auth_failure
            DANE_FAILED -> R.string.dane_failed
            else -> R.string.account_status_unknown
        }

        /**
         * Tulkki: 3.7 C5-D. The island cannot compare this enum - `AccountRef.getStatusRef()` is a
         * type of its own - so the mapping travels here, in `:data`, once, and it is the only place
         * the two enums meet.
         *
         * Exhaustive by construction in the direction that matters: every constant of this enum has
         * a case, and the trailing throw is unreachable unless one is added here without a matching
         * `AccountRef.StateRef` constant - at which point the island's own switch back
         * (`fromRef`) throws too, so the two can never silently disagree.
         */
        fun toRef(): AccountRef.StateRef = when (this) {
            DISABLED -> AccountRef.StateRef.DISABLED
            LOGGED_OUT -> AccountRef.StateRef.LOGGED_OUT
            OFFLINE -> AccountRef.StateRef.OFFLINE
            CONNECTING -> AccountRef.StateRef.CONNECTING
            ONLINE -> AccountRef.StateRef.ONLINE
            NO_INTERNET -> AccountRef.StateRef.NO_INTERNET
            CONNECTION_TIMEOUT -> AccountRef.StateRef.CONNECTION_TIMEOUT
            UNAUTHORIZED -> AccountRef.StateRef.UNAUTHORIZED
            TEMPORARY_AUTH_FAILURE -> AccountRef.StateRef.TEMPORARY_AUTH_FAILURE
            SERVER_NOT_FOUND -> AccountRef.StateRef.SERVER_NOT_FOUND
            REGISTRATION_SUCCESSFUL -> AccountRef.StateRef.REGISTRATION_SUCCESSFUL
            REGISTRATION_FAILED -> AccountRef.StateRef.REGISTRATION_FAILED
            REGISTRATION_WEB -> AccountRef.StateRef.REGISTRATION_WEB
            REGISTRATION_CONFLICT -> AccountRef.StateRef.REGISTRATION_CONFLICT
            REGISTRATION_NOT_SUPPORTED -> AccountRef.StateRef.REGISTRATION_NOT_SUPPORTED
            REGISTRATION_PLEASE_WAIT -> AccountRef.StateRef.REGISTRATION_PLEASE_WAIT
            REGISTRATION_INVALID_TOKEN -> AccountRef.StateRef.REGISTRATION_INVALID_TOKEN
            REGISTRATION_PASSWORD_TOO_WEAK -> AccountRef.StateRef.REGISTRATION_PASSWORD_TOO_WEAK
            TLS_ERROR -> AccountRef.StateRef.TLS_ERROR
            TLS_ERROR_DOMAIN -> AccountRef.StateRef.TLS_ERROR_DOMAIN
            CHANNEL_BINDING -> AccountRef.StateRef.CHANNEL_BINDING
            INCOMPATIBLE_SERVER -> AccountRef.StateRef.INCOMPATIBLE_SERVER
            INCOMPATIBLE_CLIENT -> AccountRef.StateRef.INCOMPATIBLE_CLIENT
            TOR_NOT_AVAILABLE -> AccountRef.StateRef.TOR_NOT_AVAILABLE
            I2P_NOT_AVAILABLE -> AccountRef.StateRef.I2P_NOT_AVAILABLE
            DOWNGRADE_ATTACK -> AccountRef.StateRef.DOWNGRADE_ATTACK
            SESSION_FAILURE -> AccountRef.StateRef.SESSION_FAILURE
            BIND_FAILURE -> AccountRef.StateRef.BIND_FAILURE
            HOST_UNKNOWN -> AccountRef.StateRef.HOST_UNKNOWN
            STREAM_ERROR -> AccountRef.StateRef.STREAM_ERROR
            SEE_OTHER_HOST -> AccountRef.StateRef.SEE_OTHER_HOST
            STREAM_OPENING_ERROR -> AccountRef.StateRef.STREAM_OPENING_ERROR
            POLICY_VIOLATION -> AccountRef.StateRef.POLICY_VIOLATION
            PAYMENT_REQUIRED -> AccountRef.StateRef.PAYMENT_REQUIRED
            MISSING_INTERNET_PERMISSION -> AccountRef.StateRef.MISSING_INTERNET_PERMISSION
            DANE_FAILED -> AccountRef.StateRef.DANE_FAILED
            else -> throw IllegalStateException("unmapped Account.State: $this")
        }

        companion object {

            /** Tulkki: the other half of `toRef()`, exhaustive in the same way. */
            @JvmStatic
            fun fromRef(ref: AccountRef.StateRef): State = when (ref) {
                AccountRef.StateRef.DISABLED -> DISABLED
                AccountRef.StateRef.LOGGED_OUT -> LOGGED_OUT
                AccountRef.StateRef.OFFLINE -> OFFLINE
                AccountRef.StateRef.CONNECTING -> CONNECTING
                AccountRef.StateRef.ONLINE -> ONLINE
                AccountRef.StateRef.NO_INTERNET -> NO_INTERNET
                AccountRef.StateRef.CONNECTION_TIMEOUT -> CONNECTION_TIMEOUT
                AccountRef.StateRef.UNAUTHORIZED -> UNAUTHORIZED
                AccountRef.StateRef.TEMPORARY_AUTH_FAILURE -> TEMPORARY_AUTH_FAILURE
                AccountRef.StateRef.SERVER_NOT_FOUND -> SERVER_NOT_FOUND
                AccountRef.StateRef.REGISTRATION_SUCCESSFUL -> REGISTRATION_SUCCESSFUL
                AccountRef.StateRef.REGISTRATION_FAILED -> REGISTRATION_FAILED
                AccountRef.StateRef.REGISTRATION_WEB -> REGISTRATION_WEB
                AccountRef.StateRef.REGISTRATION_CONFLICT -> REGISTRATION_CONFLICT
                AccountRef.StateRef.REGISTRATION_NOT_SUPPORTED -> REGISTRATION_NOT_SUPPORTED
                AccountRef.StateRef.REGISTRATION_PLEASE_WAIT -> REGISTRATION_PLEASE_WAIT
                AccountRef.StateRef.REGISTRATION_INVALID_TOKEN -> REGISTRATION_INVALID_TOKEN
                AccountRef.StateRef.REGISTRATION_PASSWORD_TOO_WEAK -> REGISTRATION_PASSWORD_TOO_WEAK
                AccountRef.StateRef.TLS_ERROR -> TLS_ERROR
                AccountRef.StateRef.TLS_ERROR_DOMAIN -> TLS_ERROR_DOMAIN
                AccountRef.StateRef.CHANNEL_BINDING -> CHANNEL_BINDING
                AccountRef.StateRef.INCOMPATIBLE_SERVER -> INCOMPATIBLE_SERVER
                AccountRef.StateRef.INCOMPATIBLE_CLIENT -> INCOMPATIBLE_CLIENT
                AccountRef.StateRef.TOR_NOT_AVAILABLE -> TOR_NOT_AVAILABLE
                AccountRef.StateRef.I2P_NOT_AVAILABLE -> I2P_NOT_AVAILABLE
                AccountRef.StateRef.DOWNGRADE_ATTACK -> DOWNGRADE_ATTACK
                AccountRef.StateRef.SESSION_FAILURE -> SESSION_FAILURE
                AccountRef.StateRef.BIND_FAILURE -> BIND_FAILURE
                AccountRef.StateRef.HOST_UNKNOWN -> HOST_UNKNOWN
                AccountRef.StateRef.STREAM_ERROR -> STREAM_ERROR
                AccountRef.StateRef.SEE_OTHER_HOST -> SEE_OTHER_HOST
                AccountRef.StateRef.STREAM_OPENING_ERROR -> STREAM_OPENING_ERROR
                AccountRef.StateRef.POLICY_VIOLATION -> POLICY_VIOLATION
                AccountRef.StateRef.PAYMENT_REQUIRED -> PAYMENT_REQUIRED
                AccountRef.StateRef.MISSING_INTERNET_PERMISSION -> MISSING_INTERNET_PERMISSION
                AccountRef.StateRef.DANE_FAILED -> DANE_FAILED
                else -> throw IllegalStateException("unmapped AccountRef.StateRef: $ref")
            }
        }
    }

    companion object {

        const val TABLENAME = "accounts"

        const val USERNAME = "username"
        const val SERVER = "server"
        const val PASSWORD = "password"
        const val OPTIONS = "options"
        const val ROSTERVERSION = "rosterversion"
        const val KEYS = "keys"
        const val AVATAR = "avatar"
        const val DISPLAY_NAME = "display_name"
        const val HOSTNAME = "hostname"
        const val PORT = "port"
        const val STATUS = "status"
        const val STATUS_MESSAGE = "status_message"
        const val RESOURCE = "resource"
        const val PINNED_MECHANISM = "pinned_mechanism"
        const val PINNED_CHANNEL_BINDING = "pinned_channel_binding"
        const val FAST_MECHANISM = "fast_mechanism"
        const val FAST_TOKEN = "fast_token"
        const val ORDERING = "ordering"

        const val OPTION_DISABLED = 1
        const val OPTION_REGISTER = 2
        const val OPTION_MAGIC_CREATE = 4
        const val OPTION_REQUIRES_ACCESS_MODE_CHANGE = 5
        const val OPTION_LOGGED_IN_SUCCESSFULLY = 6
        const val OPTION_HTTP_UPLOAD_AVAILABLE = 7
        const val OPTION_UNVERIFIED = 8
        const val OPTION_FIXED_USERNAME = 9
        const val OPTION_QUICKSTART_AVAILABLE = 10
        const val OPTION_SOFT_DISABLED = 11

        private const val KEY_PGP_SIGNATURE = "pgp_signature"
        private const val KEY_PGP_ID = "pgp_id"
        private const val KEY_PINNED_MECHANISM = "pinned_mechanism"
        const val KEY_PRE_AUTH_REGISTRATION_TOKEN = "pre_auth_registration"

        /**
         * The column `AbstractEntity` declares, kept reachable **by this class's name**: Kotlin does
         * not resolve an inherited Java static through a Kotlin subclass, and `Account.UUID` is what
         * five Kotlin files and two Java ones already write. Each reader was listed before the port,
         * not after.
         */
        const val UUID = AbstractEntity.UUID

        @JvmStatic
        fun parseKeys(keys: String?): JSONObject {
            if (Strings.isNullOrEmpty(keys)) {
                return JSONObject()
            }
            return try {
                JSONObject(keys)
            } catch (e: JSONException) {
                JSONObject()
            }
        }

        @JvmStatic
        fun fromCursor(cursor: Cursor): Account {
            val jid: Jid = try {
                val resource = cursor.getString(cursor.getColumnIndexOrThrow(RESOURCE))
                Jid.of(
                    cursor.getString(cursor.getColumnIndexOrThrow(USERNAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(SERVER)),
                    if (resource == null || resource.javaTrim().isEmpty()) null else resource,
                )
            } catch (e: IllegalArgumentException) {
                Log.d(
                    Config.LOGTAG,
                    cursor.getString(cursor.getColumnIndexOrThrow(USERNAME)) +
                        "@" +
                        cursor.getString(cursor.getColumnIndexOrThrow(SERVER)),
                )
                throw AssertionError(e)
            }
            return Account(
                cursor.getString(cursor.getColumnIndexOrThrow(AbstractEntity.UUID)),
                jid,
                cursor.getString(cursor.getColumnIndexOrThrow(PASSWORD)),
                cursor.getInt(cursor.getColumnIndexOrThrow(OPTIONS)),
                cursor.getString(cursor.getColumnIndexOrThrow(ROSTERVERSION)),
                cursor.getString(cursor.getColumnIndexOrThrow(KEYS)),
                cursor.getString(cursor.getColumnIndexOrThrow(AVATAR)),
                cursor.getString(cursor.getColumnIndexOrThrow(DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(HOSTNAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(PORT)),
                Presence.Status.fromShowString(
                    cursor.getString(cursor.getColumnIndexOrThrow(STATUS)),
                ),
                cursor.getString(cursor.getColumnIndexOrThrow(STATUS_MESSAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(PINNED_MECHANISM)),
                cursor.getString(cursor.getColumnIndexOrThrow(PINNED_CHANNEL_BINDING)),
                cursor.getString(cursor.getColumnIndexOrThrow(FAST_MECHANISM)),
                cursor.getString(cursor.getColumnIndexOrThrow(FAST_TOKEN)),
                cursor.getInt(cursor.getColumnIndexOrThrow(ORDERING)),
            )
        }
    }
}
