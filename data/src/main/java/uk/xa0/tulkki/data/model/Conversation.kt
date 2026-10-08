package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.database.Cursor
import android.database.DataSetObserver
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.Pair
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.core.util.Consumer
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.media3.common.util.Log
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.caverock.androidsvg.SVG
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.google.common.base.Optional
import com.google.common.base.Strings
import com.google.common.collect.ComparisonChain
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.collect.Multimap
import io.ipfs.cid.Cid
import io.michaelrocks.libphonenumber.android.NumberParseException
import java.lang.ref.WeakReference
import java.security.interfaces.DSAPublicKey
import java.text.DecimalFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.Objects
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import java.util.stream.Collectors
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import net.java.otr4j.OtrException
import net.java.otr4j.crypto.OtrCryptoException
import net.java.otr4j.session.SessionID
import net.java.otr4j.session.SessionImpl
import net.java.otr4j.session.SessionStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.crypto.OmemoAccount
import uk.xa0.tulkki.crypto.OmemoContact
import uk.xa0.tulkki.crypto.OmemoMucOptions
import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.crypto.OtrPeer
import uk.xa0.tulkki.crypto.PgpDecryptionService
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.model.Bookmark.Companion.printableValue
import uk.xa0.tulkki.data.model.ListItem.Tag
import uk.xa0.tulkki.data.utils.BobCid
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.data.utils.EmoticonText
import uk.xa0.tulkki.data.utils.GetThumbnailForCid
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.data.utils.PhoneNumberNormalizer
import uk.xa0.tulkki.data.utils.Util
import uk.xa0.tulkki.data.view.ViewPorts
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.forms.Option
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.libs.FilePathInfoRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.JidHelper
import java.net.URI

/**
 * One row of the `conversations` table: who this chat is with, every message in it, the draft, the
 * thread, the reactions and the OTR/OMEMO state.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The fields Java declared `public`/`protected` that other Java or Kotlin files read directly
 *    stay real JVM fields through `@JvmField`** - `messages` (read by [Message.next]/[Message.prev]
 *    in this same package), `messagesLoaded` and `historyPartLoadedForward` (read as *fields* by
 *    `ConversationFragment` and `MessageAdapter`, while the island's `ConversationRef` calls them as
 *    methods, so both spellings exist), `account` (a `protected` field Java code reads across the
 *    package, which Kotlin's subclass-only `protected` would take away), `xmppConnectionService`,
 *    `pagerAdapter`, `mCurrentTab`, `thread`, `replyTo`, `caption`, `threads`, `reactions`,
 *    `anyMatchSpam`.
 * 2. **`getAccount()` is nullable and so is `account`.** Java's body is `return this.account;` and
 *    eight Java call sites *receive* that null and test it (`XmppActivity:935`, `:1056`,
 *    `MessageAdapter:3030`, `UIHelper:211`, `ConversationListFragment:634`, `ShareWithActivity:421`,
 *    `XmppTulkkiHost:1086`), so a non-null Kotlin signature would turn a handled state into a
 *    `NullPointerException`. The places Java dereferenced it without a test keep `account!!`, which
 *    reproduces Java's own NPE at Java's own trigger.
 * 3. **`getContact()` and `getMucOptions()` stay non-null** (`Roster.getContact` always answers an
 *    object, and `getMucOptions` is lazily built), while **`getJid()` is nullable**: the field is
 *    Java's own `contactJid`, and two Java callers pass `null` for it on purpose - the engine's
 *    `HeldConversation`/`BareConversation` doubles, which may not name an island type to build a
 *    `Jid`. Every internal site that dereferenced it keeps Java's NPE with `!!`.
 * 4. **`Thread` is spelled `Conversation.Thread`** wherever it is a type or a constructor: the
 *    nested class shadows `java.lang.Thread` for Java, and `java.lang.Thread` also has a
 *    `Thread(String)` constructor, so an unqualified name would compile to the wrong class silently.
 * 5. **Every nullable parameter is measured, not assumed**: `setEphemeralBy(null)` (:ui, six sites),
 *    `setCorrectingMessage(null)`, `setDraftMessage(null)`, `setNextCounterpart(null)`,
 *    `setCaption(... ?: null)`, `setLanguageOverride(... ?: null)`, `setFirstMamReference(null)`,
 *    `setThread(conversation.getThread())`, `isRead(null)`, `storeSecurely(null)`.
 * 6. **Java's statics become companion members**, and the two with Java callers carry `@JvmStatic`:
 *    `fromCursor` (called by `DatabaseBackend`) and `doubtHoldColumnValue` (called by
 *    `ConversationDoubtHoldTest`). The constants stay `const val`, which the compiler emits as
 *    static fields on `Conversation` - what `DatabaseBackend` and the four query objects read.
 */
open class Conversation(
    uuid: String?,
    name: String?,
    contactUuid: String?,
    accountUuid: String?,
    contactJid: Jid?,
    created: Long,
    status: Int,
    mode: Int,
    attributes: String?,
) : AbstractEntity(), Blockable, Comparable<Conversation>, Conversational, Avatarable, ConversationRef,
    OtrPeer {

    private val name: String? = name
    private val contactUuid: String? = contactUuid
    private val accountUuid: String? = accountUuid
    private val created: Long = created
    private val attributes: JSONObject = parseAttributes(attributes)

    init {
        this.uuid = uuid
    }

    // Tulkki: `public` where upstream had `protected`. Kotlin cannot read a Java `protected` field of
    // another class, and the Kotlin port of `Message` reads this list in its `next()`/`prev()` - the
    // same measurement `ServiceDiscoveryResult.forms` records. Every Java reader is in this package
    // and unaffected, and `messagesLoaded` beside it was already public.
    @JvmField
    val messages: ArrayList<Message> = ArrayList()

    @JvmField
    var historyPartMessages: ArrayList<Message> = ArrayList()

    @JvmField
    var messagesLoaded = AtomicBoolean(true)

    @JvmField
    var historyPartLoadedForward = AtomicBoolean(true)

    @JvmField
    var account: Account? = null

    private var draftMessage: String? = null

    private var contactJid: Jid? = contactJid

    private var status: Int = status

    private var mode: Int = mode

    private var nextCounterpart: Jid? = null

    // Tulkki: filled from the cursor, not from the constructor, so every existing call site of the
    // constructor stays untouched.
    private var detectedLanguage: String? = null
    private var languageOverride: String? = null

    // Tulkki: null while this conversation never chose, then the tri-state itself.
    private var doubtHold: Boolean? = null

    @Transient
    private var mucOptions: MucOptions? = null

    private var messagesLeftOnServer = true
    private var mOutgoingChatState: ChatState = Config.DEFAULT_CHAT_STATE
    private var mIncomingChatState: ChatState = Config.DEFAULT_CHAT_STATE
    private var mFirstMamReference: String? = null

    @JvmField
    var mCurrentTab = -1

    @JvmField
    var pagerAdapter: ConversationPagerAdapter = ConversationPagerAdapter()

    @JvmField
    var thread: Element? = null

    @JvmField
    var lockThread = false

    @JvmField
    var userSelectedThread = false

    @JvmField
    var replyTo: Message? = null

    @JvmField
    var caption: String? = null

    @JvmField
    var threads: HashMap<String, Conversation.Thread> = HashMap()

    @JvmField
    var reactions: Multimap<String, Reaction> = HashMultimap.create()

    private var displayState: String? = null

    @JvmField
    var xmppConnectionService: XmppConnectionService? = null

    private var hasPermanentCounterpart = false

    @Transient
    private var otrSession: SessionImpl? = null

    @Transient
    private var otrFingerprint: String? = null

    private var mSmp: Smp = Smp()

    private var symmetricKey: ByteArray? = null

    private var mLastReceivedOtrMessageId: String? = null

    @JvmField
    var anyMatchSpam = false

    private var lastKnownStatusText: String? = null // Store the previous status text

    constructor(name: String?, account: Account, contactJid: Jid?, mode: Int) : this(
        java.util.UUID.randomUUID().toString(),
        name,
        null,
        account.getUuid(),
        contactJid,
        System.currentTimeMillis(),
        STATUS_AVAILABLE,
        mode,
        "",
    ) {
        this.account = account
    }

    /**
     * Tulkki: the owner's per-conversation answer for the doubt-hold, or {@code null} when this
     * conversation never gave one.
     *
     * <p>This is the tri-state and not a decision: {@code null} means "follow the shipped default",
     * which is **on**, and the rule that consults this value is the one that resolves that default
     * (`:translation`); this class never answers it. {@code TRUE} holds a doubtful translation for
     * the owner's tap, {@code FALSE} sends the answer as it stands.
     */
    fun getDoubtHold(): Boolean? {
        return this.doubtHold
    }

    fun setDoubtHold(doubtHold: Boolean?) {
        this.doubtHold = doubtHold
    }

    fun getDetectedLanguage(): String? {
        return this.detectedLanguage
    }

    fun setDetectedLanguage(language: String?) {
        this.detectedLanguage = language
    }

    fun getLanguageOverride(): String? {
        return this.languageOverride
    }

    fun setLanguageOverride(language: String?) {
        this.languageOverride = language
    }

    fun getDraftMessage(): String? {
        return draftMessage
    }

    fun setDraftMessage(draftMessage: String?) {
        this.draftMessage = draftMessage
    }

    /** short for is Private and Non-anonymous */
    override fun isSingleOrPrivateAndNonAnonymous(): Boolean {
        return mode == MODE_SINGLE || isPrivateAndNonAnonymous()
    }

    override fun isPrivateAndNonAnonymous(): Boolean {
        return getMucOptions().isPrivateAndNonAnonymous()
    }

    @Synchronized
    override fun getMucOptions(): MucOptions {
        if (this.mucOptions == null) {
            this.mucOptions = MucOptions(this)
        }
        return this.mucOptions!!
    }

    override fun resetMucOptions() {
        this.mucOptions = null
    }

    override fun setContactJid(jid: Jid) {
        this.contactJid = jid
    }

    override fun getNextCounterpart(): Jid? {
        return this.nextCounterpart
    }

    fun setNextCounterpart(jid: Jid?) {
        this.nextCounterpart = jid
    }

    override fun getNextEncryption(): Int {
        if (!Config.supportOmemo() && !Config.supportOpenPgp() && !Config.supportOtr()) {
            return Message.ENCRYPTION_NONE
        }
        if (OmemoSetting.isAlways()) {
            return if (suitableForOmemoByDefault(this)) Message.ENCRYPTION_AXOLOTL else Message.ENCRYPTION_NONE
        }
        val defaultEncryption: Int = if (suitableForOmemoByDefault(this)) {
            OmemoSetting.getEncryption()
        } else {
            Message.ENCRYPTION_NONE
        }
        val encryption = this.getIntAttribute(ATTRIBUTE_NEXT_ENCRYPTION, defaultEncryption)
        return if (encryption < 0) {
            defaultEncryption
        } else {
            encryption
        }
    }

    fun setNextEncryption(encryption: Int): Boolean {
        return this.setAttribute(ATTRIBUTE_NEXT_ENCRYPTION, encryption)
    }

    fun getNextMessage(): String {
        val nextMessage = getAttribute(ATTRIBUTE_NEXT_MESSAGE)
        return nextMessage ?: ""
    }

    fun smpRequested(): Boolean {
        return smp().status == Smp.STATUS_CONTACT_REQUESTED
    }

    @Nullable
    fun getDraft(): Draft? {
        val timestamp = getLongAttribute(ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP, 0)
        val messageTime: Long
        synchronized(this.messages) {
            if (this.messages.size == 0) {
                messageTime = Math.max(getCreated(), getLastClearHistory().getTimestamp())
            } else {
                messageTime = this.messages[this.messages.size - 1].getTimeSent()
            }
        }
        if (timestamp > messageTime) {
            val message = getAttribute(ATTRIBUTE_NEXT_MESSAGE)
            if (!TextUtils.isEmpty(message) && timestamp != 0L) {
                return Draft(message!!, timestamp)
            }
        }
        return null
    }

    override fun setNextMessage(input: String?): Boolean {
        val message = if (input == null || input.trim().isEmpty()) null else input
        val changed = getNextMessage() != message
        this.setAttribute(ATTRIBUTE_NEXT_MESSAGE, message)
        if (changed) {
            this.setAttribute(
                ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP,
                if (message == null) 0L else System.currentTimeMillis(),
            )
        }
        return changed
    }

    override fun setSymmetricKey(key: ByteArray?) {
        this.symmetricKey = key
    }

    fun getSymmetricKey(): ByteArray? {
        return this.symmetricKey
    }

    override fun getBookmark(): Bookmark? {
        return account!!.getBookmark(this.contactJid!!)
    }

    fun findDuplicateMessage(message: Message): Message? {
        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                if (this.messages[i].similar(message)) {
                    return this.messages[i]
                }
            }
        }
        return null
    }

    fun hasDuplicateMessage(message: Message): Boolean {
        return findDuplicateMessage(message) != null
    }

    // -------------------------------------------------------------------------------------------
    // Tulkki: 3.7 pair 9, part 12. Three more `MessageRef` overloads, for the same reason part 11's
    // `add(MessageRef)` exists: `MessageParser` now holds refs, a parameter type is not covariant, and
    // every one of these objects really is a `Message`. The parameter types do not erase alike, so the
    // model's own methods keep serving `:data` and `:ui` unchanged.

    override fun findDuplicateMessage(message: MessageRef): MessageRef? {
        return findDuplicateMessage(message as Message)
    }

    override fun hasDuplicateMessage(message: MessageRef): Boolean {
        return hasDuplicateMessage(message as Message)
    }

    override fun prepend(offset: Int, message: MessageRef) {
        prepend(offset, message as Message)
    }

    override fun findSentMessageWithBody(body: String): Message? {
        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = this.messages[i]
                if (message.getStatus() == Message.STATUS_UNSEND || message.getStatus() == Message.STATUS_SEND) {
                    val otherBody: String?
                    if (message.hasFileOnRemoteHost()) {
                        otherBody = message.getFileParams().url
                    } else {
                        otherBody = message.getRawBody()
                    }
                    if (otherBody != null && otherBody == body) {
                        return message
                    }
                }
            }
            return null
        }
    }

    override fun findRtpSession(sessionId: String, s: Int): Message? {
        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = this.messages[i]
                if (message.getStatus() == s &&
                    message.getType() == Message.TYPE_RTP_SESSION &&
                    sessionId == message.getRemoteMsgId()
                ) {
                    return message
                }
            }
        }
        return null
    }

    override fun possibleDuplicate(serverMsgId: String?, remoteMsgId: String?): Boolean {
        if (serverMsgId == null || remoteMsgId == null) {
            return false
        }
        synchronized(this.messages) {
            for (message in this.messages) {
                if (serverMsgId == message.getServerMsgId() || remoteMsgId == message.getRemoteMsgId()) {
                    return true
                }
            }
        }
        return false
    }

    override fun getLastMessageTransmitted(): MamReference {
        val lastClear = getLastClearHistory()
        var lastReceived = MamReference(0)
        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = this.messages[i]
                if (message.isPrivateMessage()) {
                    continue // it's unsafe to use private messages as anchor. They could be coming
                    // from user archive
                }
                if (message.getStatus() == Message.STATUS_RECEIVED ||
                    message.isCarbon() ||
                    message.getServerMsgId() != null
                ) {
                    lastReceived = MamReference(message.getTimeSent(), message.getServerMsgId())
                    break
                }
            }
        }
        return MamReference.max(lastClear, lastReceived) ?: lastReceived
    }

    override fun setMutedTill(value: Long) {
        this.setAttribute(ATTRIBUTE_MUTED_TILL, value.toString())
    }

    fun isMuted(): Boolean {
        return System.currentTimeMillis() < this.getLongAttribute(ATTRIBUTE_MUTED_TILL, 0)
    }

    fun alwaysNotify(): Boolean {
        return mode == MODE_SINGLE ||
            getBooleanAttribute(
                ATTRIBUTE_ALWAYS_NOTIFY,
                Config.ALWAYS_NOTIFY_BY_DEFAULT || isPrivateAndNonAnonymous(),
            )
    }

    fun notifyReplies(): Boolean {
        return alwaysNotify() || getBooleanAttribute(ATTRIBUTE_NOTIFY_REPLIES, false)
    }

    fun setStoreSecurely(cache: Boolean) {
        setAttribute("storeMedia", if (cache) "explicit_on" else "explicit_off")
    }

    fun storeSecurely(xmppConnectionService: XmppConnectionService?): Boolean {
        val preference = getAttribute("storeMedia")

        if ("explicit_on" == preference) {
            return true
        }
        if ("explicit_off" == preference || "shared" == preference) {
            return false
        }

        if (xmppConnectionService != null) {
            return xmppConnectionService.getBooleanPreference(
                AppSettings.USE_INTERNAL_SECURE_STORAGE,
                R.bool.default_store_media_securely,
            )
        }

        return true
    }

    override fun setAttribute(key: String, value: Boolean): Boolean {
        return setAttribute(key, value.toString())
    }

    private fun setAttribute(key: String, value: Long): Boolean {
        return setAttribute(key, value.toString())
    }

    private fun setAttribute(key: String, value: Int): Boolean {
        return setAttribute(key, value.toString())
    }

    override fun setAttribute(key: String, value: String?): Boolean {
        synchronized(this.attributes) {
            try {
                if (value == null) {
                    if (this.attributes.has(key)) {
                        this.attributes.remove(key)
                        return true
                    } else {
                        return false
                    }
                } else {
                    val prev = this.attributes.optString(key, null)
                    this.attributes.put(key, value)
                    return value != prev
                }
            } catch (e: JSONException) {
                throw AssertionError(e)
            }
        }
    }

    fun setAttribute(key: String, jids: List<Jid>): Boolean {
        val array = JSONArray()
        for (jid in jids) {
            array.put(jid.asBareJid().toString())
        }
        synchronized(this.attributes) {
            try {
                this.attributes.put(key, array)
                return true
            } catch (e: JSONException) {
                return false
            }
        }
    }

    fun getAttribute(key: String): String? {
        synchronized(this.attributes) {
            return this.attributes.optString(key, null)
        }
    }

    private fun getJidListAttribute(key: String): List<Jid> {
        val list = ArrayList<Jid>()
        synchronized(this.attributes) {
            try {
                val array = this.attributes.getJSONArray(key)
                for (i in 0 until array.length()) {
                    try {
                        list.add(Jid.of(array.getString(i)))
                    } catch (e: IllegalArgumentException) {
                        // ignored
                    }
                }
            } catch (e: JSONException) {
                // ignored
            }
        }
        return list
    }

    private fun getIntAttribute(key: String, defaultValue: Int): Int {
        val value = this.getAttribute(key)
        return if (value == null) {
            defaultValue
        } else {
            try {
                value.toInt()
            } catch (e: NumberFormatException) {
                defaultValue
            }
        }
    }

    fun getLongAttribute(key: String, defaultValue: Long): Long {
        val value = this.getAttribute(key)
        return if (value == null) {
            defaultValue
        } else {
            try {
                value.toLong()
            } catch (e: NumberFormatException) {
                defaultValue
            }
        }
    }

    override fun getBooleanAttribute(key: String, defaultValue: Boolean): Boolean {
        val value = this.getAttribute(key)
        return if (value == null) {
            defaultValue
        } else {
            value.toBoolean()
        }
    }

    fun remove(message: Message) {
        synchronized(this.messages) {
            this.messages.remove(message)
        }
    }

    /** Tulkki: part 16 - the island's own `deleteMessage` holds a ref for the row it removes. */
    override fun remove(message: MessageRef) {
        remove(message as Message)
    }

    fun checkSpam(vararg messages: Message) {
        if (anyMatchSpam) return

        val locale = java.util.Locale.getDefault()
        val script = locale.script
        for (m in messages) {
            if (getMode() != MODE_MULTI) {
                val resource = m.getCounterpart()?.getResource()
                if (resource != null && resource.length < 10) {
                    anyMatchSpam = true
                    return
                }
            }
            val body = m.getRawBody()!!
            try {
                if ("Cyrl" != script && body.matches(Regex(".*\\p{IsCyrillic}.*"))) {
                    anyMatchSpam = true
                    return
                }
            } catch (e: java.util.regex.PatternSyntaxException) {
                // Not supported on old android
            }
            if (body.length > 500 || m.getLinks().isNotEmpty() ||
                body.matches(Regex(".*(?:\\n.*\\n.*\\n|[Aa]\\s*d\\s*v\\s*v\\s*e\\s*r\\s*t|[Pp]romotion|[Dd][Dd][Oo][Ss]|[Ee]scrow|payout|seller|\\?OTR|write me when will be|v seti|[Pp]rii?vee?t|there\\?|online\\?|exploit).*"))
            ) {
                anyMatchSpam = true
                return
            }
        }
    }

    fun add(message: Message) {
        checkSpam(message)
        if (message.getRetractId() != null) {
            return // Don't add it
        }
        synchronized(this.messages) {
            this.messages.add(message)
        }
    }

    /**
     * Tulkki: the island's overload.
     *
     * <p>3.7 pair 9, part 11. A parameter type is not covariant in Java, so `add(Message)` cannot
     * satisfy `ConversationRef.add(MessageRef)`; the island's object really is a `Message` (it builds
     * it through `DataStatics.newMessage`), so the cast is identity-safe and the body is the one above.
     */
    override fun add(message: MessageRef) {
        add(message as Message)
    }

    fun prepend(offset: Int, message: Message) {
        checkSpam(message)
        if (message.getRetractId() != null) {
            return // Don't add it
        }
        val properListToAdd: ArrayList<Message>

        if (historyPartMessages.isNotEmpty()) {
            properListToAdd = historyPartMessages
        } else {
            properListToAdd = this.messages
        }

        synchronized(this.messages) {
            properListToAdd.add(Math.min(offset, properListToAdd.size), message)
        }

        if (historyPartMessages.isNotEmpty() && hasDuplicateMessage(historyPartMessages[historyPartMessages.size - 1])) {
            messages.addAll(0, historyPartMessages)
            jumpToLatest()
        }
    }

    /**
     * Tulkki: 3.7 pair 9, part 16 - the parameter is the island's ref, wildcarded, because its only
     * caller ({@code XmppConnectionService.loadMoreMessages}) holds a {@code List<MessageRef>} now and
     * a parameter type is not covariant. Generics are invariant, so {@code List<? extends MessageRef>}
     * is what accepts that list; the body casts once at the top and then works in the model type it
     * always did (`checkSpam` takes a {@code Message[]}).
     */
    @Suppress("UNCHECKED_CAST")
    override fun addAll(index: Int, messageRefs: List<MessageRef>, fromPagination: Boolean) {
        val messages = messageRefs as List<Message>
        checkSpam(*messages.toTypedArray())
        val filteredMessages = ArrayList<Message>()
        for (message in messages) {
            if (message.getRetractId() != null) {
                // Optionally, ensure it's removed from the main list if it could exist there
                synchronized(this.messages) {
                    this.messages.remove(message)
                }
            } else {
                filteredMessages.add(message)
            }
        }

        if (filteredMessages.isEmpty()) {
            return // Nothing to add
        }
        synchronized(this.messages) {
            val properListToAdd: ArrayList<Message>

            if (fromPagination && historyPartMessages.isNotEmpty()) {
                properListToAdd = historyPartMessages
            } else {
                properListToAdd = this.messages
            }

            if (index == -1) {
                properListToAdd.addAll(messages)
            } else {
                properListToAdd.addAll(index, messages)
            }
        }
        account?.getPgpDecryptionService()?.decrypt(messages)
    }

    override fun expireOldMessages(timestamp: Long) {
        synchronized(this.messages) {
            val now = System.currentTimeMillis()
            val iterator = this.messages.listIterator()
            while (iterator.hasNext()) {
                val message = iterator.next()
                if (message.getTimeSent() < timestamp) {
                    iterator.remove()
                } else if (message.getExpireAt() > 0 && message.getExpireAt() < now) {
                    message.setBody(null as String?)
                    message.setSubject(null)
                    message.setDeleted(true)
                    message.setRelativeFilePath(null)
                    message.setFileParams(null)
                    message.setEncryption(Message.ENCRYPTION_NONE)
                    message.setReactions(Collections.emptyList<Reaction>())
                }
            }
            untieMessages()
        }
    }

    override fun sort() {
        synchronized(this.messages) {
            this.messages.sortWith(Comparator { left, right ->
                if (left.getTimeSent() < right.getTimeSent()) {
                    -1
                } else if (left.getTimeSent() > right.getTimeSent()) {
                    1
                } else {
                    0
                }
            })
            untieMessages()
        }
    }

    /** Tulkki: part 16 - the same wildcard as {@link #addAll(int, List, boolean)}, for the jump path. */
    @Suppress("UNCHECKED_CAST")
    override fun jumpToHistoryPart(messages: List<MessageRef>) {
        historyPartMessages.clear()
        historyPartMessages.addAll(messages as List<Message>)
    }

    fun jumpToLatest() {
        historyPartMessages.clear()
    }

    fun isInHistoryPart(): Boolean {
        return historyPartMessages.isNotEmpty()
    }

    private fun untieMessages() {
        for (message in this.messages) {
            message.untie()
        }
    }

    override fun unreadCount(xmppConnectionService: XmppConnectionService?): Int {
        synchronized(this.messages) {
            var count = 0
            for (message in Lists.reverse(this.messages)) {
                val raw = message.getRawBody()
                if (message.getSubject() != null && !message.isOOb() && (raw == null || raw.length == 0)) continue
                if (asReaction(message) != null) continue
                if (message.getRetractId() != null) continue
                if ((raw == null || "" == raw || " " == raw) && message.getReply() != null && message.edited() && message.getHtml() != null) continue
                val muted = xmppConnectionService != null && message.getStatus() == Message.STATUS_RECEIVED && getMode() == MODE_MULTI && xmppConnectionService.isMucUserMuted(getAccountUuid(), "" + getJid(), message.getOccupantId() ?: throw NullPointerException("message has no occupant id"))
                if (muted) continue
                if (message.isRead()) {
                    if (message.getType() == Message.TYPE_RTP_SESSION) {
                        continue
                    }
                    return count
                }
                ++count
            }
            return count
        }
    }

    fun receivedMessagesCount(): Int {
        var count = 0
        synchronized(this.messages) {
            for (message in messages) {
                val raw = message.getRawBody()
                if (message.getSubject() != null && !message.isOOb() && (raw == null || raw.length == 0)) continue
                if (asReaction(message) != null) continue
                if (message.getRetractId() != null) continue
                if ((raw == null || "" == raw || " " == raw) && message.getReply() != null && message.edited() && message.getHtml() != null) continue
                if (message.getStatus() == Message.STATUS_RECEIVED) {
                    ++count
                }
            }
        }
        return count
    }

    override fun sentMessagesCount(): Int {
        var count = 0
        synchronized(this.messages) {
            for (message in messages) {
                if (message.getStatus() != Message.STATUS_RECEIVED) {
                    ++count
                }
            }
        }
        return count
    }

    override fun canInferPresence(): Boolean {
        val contact = getContact()
        if (contact.canInferPresence()) return true
        return sentMessagesCount() > 0
    }

    fun isChatRequest(pref: String?): Boolean {
        if ("disable" == pref) return false
        if ("strangers" == pref) return isWithStranger()
        if (!isWithStranger() && !strangerInvited()) return false
        return anyMatchSpam
    }

    override fun isWithStranger(): Boolean {
        val contact = getContact()
        return mode == MODE_SINGLE &&
            !contact.isOwnServer() &&
            !contact.showInContactList() &&
            !contact.isSelf() &&
            !(contact.getJid().isDomainJid() && JidHelper.isQuicksyDomain(contact.getJid())) &&
            sentMessagesCount() == 0
    }

    fun strangerInvited(): Boolean {
        val inviterS = getAttribute("inviter")
        if (inviterS == null) return false
        val inviter = account!!.getRoster().getContact(Jid.of(inviterS))
        return getBookmark() == null && !inviter.showInContactList() && !inviter.isSelf() && sentMessagesCount() == 0
    }

    fun getReceivedMessagesCountSinceUuid(uuid: String?): Int {
        if (uuid == null) {
            return 0
        }
        var count = 0
        synchronized(this.messages) {
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                if (message.getRetractId() != null) continue
                if (uuid == message.getUuid()) {
                    return count
                }
                if (message.getStatus() <= Message.STATUS_RECEIVED) {
                    ++count
                }
            }
        }
        return 0
    }

    override fun getAvatarBackgroundColor(): Int {
        return DisplayNames.getColorForName(getName().toString())
    }

    override fun getAvatarName(): String {
        return getName().toString()
    }

    fun setCurrentTab(tab: Int) {
        mCurrentTab = tab
    }

    fun getCurrentTab(): Int {
        if (xmppConnectionService != null && xmppConnectionService!!.getBooleanPreference("jump_to_commands_tab", R.bool.jump_to_commands_tab)) {
            if (mCurrentTab >= 0) return mCurrentTab

            if (!isRead(null) || getContact().resourceWhichSupport(Namespace.COMMANDS) == null) {
                return 0
            }

            return 1
        } else return 0
    }

    fun refreshSessions() {
        pagerAdapter.refreshSessions()
    }

    fun startCommand(command: Element, xmppConnectionService: XmppConnectionService) {
        pagerAdapter.startCommand(command, xmppConnectionService)
    }

    fun startMucConfig(xmppConnectionService: XmppConnectionService) {
        pagerAdapter.startMucConfig(xmppConnectionService)
    }

    fun switchToSession(node: String?): Boolean {
        return pagerAdapter.switchToSession(node)
    }

    // Tulkki, 3.8-r: `commandsViewId` is the id of the `ListView` the pager's second page must
    // carry - `:ui`'s `fragment_conversation.xml` declares it with `@+id/`, and an `@+id` cannot be
    // cut out of its carrier, so the module that owns the layout hands the id in. There is no
    // sentinel: the `pager == null` reset path returns before the lookup ever runs (the 3.8-r
    // remaining hand-work commit cebe3e9388, section 3.2).
    fun setupViewPager(pager: ViewPager?, tabs: TabLayout?, onboarding: Boolean, oldConversation: Conversation?, @IdRes commandsViewId: Int) {
        pagerAdapter.setupViewPager(pager, tabs, onboarding, oldConversation, commandsViewId)
    }

    fun showViewPager() {
        pagerAdapter.show()
    }

    fun hideViewPager() {
        pagerAdapter.hide()
    }

    override fun setDisplayState(stanzaId: String?) {
        this.displayState = stanzaId
    }

    override fun getEphemeralTimer(): Int {
        return getIntAttribute(ATTRIBUTE_EPHEMERAL_TIMER, 0)
    }

    override fun setEphemeralTimer(timer: Int): Boolean {
        if (getEphemeralTimer() != timer && timer > 0) {
            setEphemeralHintHidden(false)
        }
        return setAttribute(ATTRIBUTE_EPHEMERAL_TIMER, timer)
    }

    fun ephemeralHintHidden(): Boolean {
        return getBooleanAttribute(ATTRIBUTE_EPHEMERAL_HINT_HIDDEN, false)
    }

    fun setEphemeralHintHidden(hidden: Boolean) {
        setAttribute(ATTRIBUTE_EPHEMERAL_HINT_HIDDEN, hidden)
    }

    fun getEphemeralBy(): String? {
        return getAttribute(ATTRIBUTE_EPHEMERAL_BY)
    }

    override fun setEphemeralBy(by: String?) {
        setAttribute(ATTRIBUTE_EPHEMERAL_BY, by)
    }

    override fun getDisplayState(): String? {
        return this.displayState
    }

    // Tulkki: part 17 - `OnMessageFound` moved to `ConversationRef`, where the island that is its
    // only implementor can name it. Nothing else changes: this class inherits its superinterface's
    // member types, so the four finder signatures below still resolve the simple name, and
    // `Conversation.OnMessageFound` still resolves through the class.

    /**
     * Tulkki: part 17 - the ref's view of the public {@code messagesLoaded} field, which an
     * interface cannot carry. `XmppConnectionService` and `ConversationFragment`'s
     * `OnMoreMessagesLoaded` body are the call sites.
     */
    override fun messagesLoaded(): AtomicBoolean {
        return messagesLoaded
    }

    /** Tulkki: part 17 - the ref's view of the public {@code historyPartLoadedForward} field. */
    override fun historyPartLoadedForward(): AtomicBoolean {
        return historyPartLoadedForward
    }

    /**
     * Tulkki: part 17 - the ref's natural-order member. Parameters are not covariant, so this is an
     * overload beside {@code Comparable<Conversation>}'s, not a replacement for it.
     */
    override fun compareTo(another: ConversationRef): Int {
        return compareTo(another as Conversation)
    }

    /** Tulkki: part 17 - the ref takes the island type; a parameter is not covariant in Java. */
    override fun setAccount(account: AccountRef?) {
        setAccount(account as Account?)
    }

    fun getThread(id: String): Conversation.Thread? {
        return threads[id]
    }

    fun recentThreads(): List<Conversation.Thread> {
        val recent = ArrayList<Conversation.Thread>()
        recent.addAll(threads.values)
        recent.sortWith(Comparator { a, b ->
            if (b.getLastTime() == a.getLastTime()) 0 else if (b.getLastTime() > a.getLastTime()) 1 else -1
        })
        return if (recent.size < 5) recent else recent.subList(0, 5)
    }

    override fun isBlocked(): Boolean {
        return getContact().isBlocked()
    }

    override fun isDomainBlocked(): Boolean {
        return getContact().isDomainBlocked()
    }

    override fun getBlockedJid(): Jid {
        return getContact().getBlockedJid()
    }

    override fun getLastReceivedOtrMessageId(): String? {
        return this.mLastReceivedOtrMessageId
    }

    override fun setLastReceivedOtrMessageId(id: String?) {
        this.mLastReceivedOtrMessageId = id
    }

    override fun countMessages(): Int {
        synchronized(this.messages) {
            return this.messages.size
        }
    }

    override fun getFirstMamReference(): String? {
        return this.mFirstMamReference
    }

    override fun setFirstMamReference(reference: String?) {
        this.mFirstMamReference = reference
    }

    override fun setLastClearHistory(time: Long, reference: String?) {
        if (reference != null) {
            setAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY, "$time:$reference")
        } else {
            setAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY, time)
        }
    }

    override fun getLastClearHistory(): MamReference {
        return MamReference.fromAttribute(getAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY))
    }

    override fun getAcceptedCryptoTargets(): List<Jid> {
        return if (mode == MODE_SINGLE) {
            Collections.singletonList(getJid()!!.asBareJid())
        } else {
            getJidListAttribute(ATTRIBUTE_CRYPTO_TARGETS)
        }
    }

    override fun setAcceptedCryptoTargets(acceptedTargets: List<Jid>) {
        setAttribute(ATTRIBUTE_CRYPTO_TARGETS, acceptedTargets)
    }

    fun setCorrectingMessage(correctingMessage: Message?): Boolean {
        setAttribute(
            ATTRIBUTE_CORRECTING_MESSAGE,
            correctingMessage?.getUuid(),
        )
        return correctingMessage == null && draftMessage != null
    }

    fun getCorrectingMessage(): Message? {
        val uuid = getAttribute(ATTRIBUTE_CORRECTING_MESSAGE)
        return if (uuid == null) null else findSentMessageWithUuid(uuid)
    }

    fun withSelf(): Boolean {
        return getContact().isSelf()
    }

    override fun compareTo(another: Conversation): Int {
        return ComparisonChain.start()
            .compareFalseFirst(
                another.getBooleanAttribute(ATTRIBUTE_PINNED_ON_TOP, false) && another.withSelf(),
                getBooleanAttribute(ATTRIBUTE_PINNED_ON_TOP, false) && withSelf(),
            )
            .compareFalseFirst(
                another.getBooleanAttribute(ATTRIBUTE_PINNED_ON_TOP, false),
                getBooleanAttribute(ATTRIBUTE_PINNED_ON_TOP, false),
            )
            .compare(another.getSortableTime(), getSortableTime())
            .result()
    }

    fun getSortableTime(): Long {
        val draft = getDraft()
        val messageTime: Long
        synchronized(this.messages) {
            if (this.messages.size == 0) {
                messageTime = Math.max(getCreated(), getLastClearHistory().getTimestamp())
            } else {
                messageTime = this.messages[this.messages.size - 1].getTimeReceived()
            }
        }

        return if (draft == null) {
            messageTime
        } else {
            Math.max(messageTime, draft.getTimestamp())
        }
    }

    override fun getThread(): Element? {
        return this.thread
    }

    fun setThread(thread: Element?) {
        this.thread = thread
    }

    fun setLockThread(flag: Boolean) {
        this.lockThread = flag
        if (flag) setUserSelectedThread(true)
    }

    fun getLockThread(): Boolean {
        return this.lockThread
    }

    fun setUserSelectedThread(flag: Boolean) {
        this.userSelectedThread = flag
    }

    fun getUserSelectedThread(): Boolean {
        return this.userSelectedThread
    }

    fun setReplyTo(m: Message?) {
        this.replyTo = m
    }

    override fun getReplyTo(): Message? {
        return this.replyTo
    }

    fun setCaption(caption: String?) {
        this.caption = caption
    }

    override fun getCaption(): String? {
        return this.caption
    }

    fun isRead(xmppConnectionService: XmppConnectionService?): Boolean {
        return unreadCount(xmppConnectionService) < 1
    }

    /**
     * Tulkki: 3.7 pair 9, part 16 - the return type is the island's ref now. Its only caller is
     * {@code XmppConnectionService.markRead}, which holds {@code List<MessageRef>} and passes the list
     * straight on to {@code MessageRef}-typed work; a return type cannot be overloaded, so the
     * declaration moves rather than gaining a sibling. Every element really is a {@code Message}.
     */
    override fun markRead(upToUuid: String?): List<MessageRef> {
        val unread = ImmutableList.builder<MessageRef>()
        synchronized(this.messages) {
            for (message in this.messages) {
                if (!message.isRead()) {
                    message.markRead()
                    unread.add(message)
                }
                if (message.getUuid() == upToUuid) {
                    return unread.build()
                }
            }
        }
        return unread.build()
    }

    override fun getLatestMessage(): Message {
        synchronized(this.messages) {
            val now = System.currentTimeMillis()
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                // **NEW CHECK: Skip retracted and expired messages**
                if (message.getRetractId() != null || message.isDeleted() || (message.getExpireAt() > 0 && message.getExpireAt() < now)) {
                    message.markRead()
                    continue
                }
                val raw = message.getRawBody()
                if (message.getSubject() != null && !message.isOOb() && (raw == null || raw.length == 0)) continue
                if ((raw == null || "" == raw || " " == raw) && message.getReply() != null && message.edited() && message.getHtml() != null) continue
                if (asReaction(message) != null) continue
                return message
            }
        }

        val message = Message(this, "", Message.ENCRYPTION_NONE)
        message.setType(Message.TYPE_STATUS)
        message.setTime(Math.max(getCreated(), getLastClearHistory().getTimestamp()))
        message.setTimeReceived(Math.max(getCreated(), getLastClearHistory().getTimestamp()))
        return message
    }

    override fun getName(): CharSequence {
        if (getMode() == MODE_MULTI) {
            val roomName = getMucOptions().getName()
            val subject = getMucOptions().getSubject()
            val bookmark = getBookmark()
            val bookmarkName = bookmark?.getBookmarkName()
            if (printableValue(roomName)) {
                return roomName!!
            } else if (printableValue(subject)) {
                return subject!!
            } else if (printableValue(bookmarkName, false)) {
                return bookmarkName!!
            } else {
                val generatedName = getMucOptions().createNameFromParticipants()
                if (printableValue(generatedName)) {
                    return generatedName!!
                } else {
                    return contactJid!!.getLocal() ?: contactJid!!
                }
            }
        } else if (!Config.QUICKSY_DOMAIN.equals(contactJid!!.getDomain()) && isWithStranger()) {
            return contactJid!!
        } else {
            return this.getContact().getDisplayName()
        }
    }

    fun getTags(ctx: Context): List<Tag> {
        return if (getMode() == MODE_MULTI) {
            if (getBookmark() == null) ArrayList<Tag>() else getBookmark()!!.getTags(ctx)
        } else {
            getContact().getTags(ctx)
        }
    }

    /**
     * Tulkki: Java's declared type is `Account` and its own answer is still `null` - the field stays
     * `null` until `setAccount`, and eight Java call sites *receive* that null and test it
     * (`XmppActivity:935`, `:1056`, `MessageAdapter:3030`, `UIHelper:211`,
     * `ConversationListFragment:634`, `ShareWithActivity:421`, `XmppTulkkiHost:1086`). The Kotlin
     * signature is therefore nullable, exactly as wide as Java's platform type - never narrower.
     *
     * <p>`:app`'s `NotificationService.kt:1559` was ported against the platform type and dereferences
     * this without a `?`; that one line is the only creditor the lane's build cannot repair from
     * `:data` (the lane may not write `:app`). It is named in the commit body and the report.
     */
    override fun getAccount(): Account? {
        return this.account
    }

    /**
     * Tulkki: the column is `accountUuid TEXT` (nullable, `schema-75.sql`), so Java's answer can be
     * `null`. Nullable, as Java's platform type was; `ExportBackupWorker.kt:594` is the `:app` line
     * that assumes otherwise.
     */
    override fun getAccountUuid(): String? {
        return this.accountUuid
    }

    fun setAccount(account: Account?) {
        this.account = account
    }

    override fun getContact(): Contact {
        return account!!.getRoster().getContact(this.contactJid!!)
    }

    /**
     * Tulkki: Java's field is `private Jid contactJid` and its own answer is `null` for a
     * conversation built without an address - `HeldSendAlreadyHeldTest.HeldConversation` and
     * `ReviewKeyTest.BareConversation` both pass `null` here on purpose (the engine's tests may not
     * name an island type to build one), so the parameter and this return are nullable and
     * `Message`'s constructor already safe-calls it. Every production caller passes a real Jid; the
     * internal sites that dereferenced it keep Java's own NPE through `!!`.
     */
    override fun getJid(): Jid? {
        return this.contactJid
    }

    override fun getStatus(): Int {
        return this.status
    }

    override fun setStatus(status: Int) {
        this.status = status
    }

    fun getCreated(): Long {
        return this.created
    }

    override fun getContentValues(): ContentValues {
        val values = ContentValues()
        values.put(UUID, uuid)
        values.put(NAME, name)
        values.put(CONTACT, contactUuid)
        values.put(ACCOUNT, accountUuid)
        values.put(CONTACTJID, contactJid!!.toString())
        values.put(CREATED, created)
        values.put(STATUS, status)
        values.put(MODE, mode)
        // Tulkki: the conversation's language travels with every write, so opening a conversation
        // does not silently drop it.
        values.put(DETECTED_LANGUAGE, detectedLanguage)
        values.put(LANGUAGE_OVERRIDE, languageOverride)
        values.put(DOUBT_HOLD, doubtHoldColumnValue(doubtHold))
        synchronized(this.attributes) {
            values.put(ATTRIBUTES, attributes.toString())
        }
        return values
    }

    override fun getMode(): Int {
        return this.mode
    }

    override fun setMode(mode: Int) {
        this.mode = mode
    }

    override fun startOtrSession(presence: String, sendStart: Boolean): SessionImpl? {
        if (this.otrSession != null) {
            return this.otrSession
        } else {
            val sessionId = SessionID(this.getJid()!!.asBareJid().toString(), presence, "xmpp")
            this.otrSession = SessionImpl(sessionId, getAccount()!!.getOtrService())
            try {
                if (sendStart) {
                    this.otrSession!!.startSession()
                    return this.otrSession
                }
                return this.otrSession
            } catch (e: OtrException) {
                return null
            }
        }
    }

    override fun getOtrSession(): SessionImpl? {
        return this.otrSession
    }

    override fun resetOtrSession() {
        this.otrFingerprint = null
        this.otrSession = null
        this.mSmp.hint = null
        this.mSmp.secret = null
        this.mSmp.status = Smp.STATUS_NONE
    }

    fun smp(): Smp {
        return mSmp
    }

    override fun setSmpStatus(status: Int) {
        this.mSmp.status = status
    }

    override fun setSmpHint(hint: String?) {
        this.mSmp.hint = hint
    }

    override fun getSmpStatus(): Int {
        return this.mSmp.status
    }

    override fun getSmpHint(): String? {
        return this.mSmp.hint
    }

    override fun startOtrIfNeeded(): Boolean {
        if (this.otrSession != null && this.otrSession!!.getSessionStatus() != SessionStatus.ENCRYPTED) {
            try {
                this.otrSession!!.startSession()
                return true
            } catch (e: OtrException) {
                this.resetOtrSession()
                return false
            }
        } else {
            return true
        }
    }

    override fun endOtrIfNeeded(): Boolean {
        if (this.otrSession != null) {
            if (this.otrSession!!.getSessionStatus() == SessionStatus.ENCRYPTED) {
                try {
                    this.otrSession!!.endSession()
                    this.resetOtrSession()
                    return true
                } catch (e: OtrException) {
                    this.resetOtrSession()
                    return false
                }
            } else {
                this.resetOtrSession()
                return false
            }
        } else {
            return false
        }
    }

    override fun hasValidOtrSession(): Boolean {
        return this.otrSession != null
    }

    @Synchronized
    fun getOtrFingerprint(): String? {
        if (this.otrFingerprint == null) {
            try {
                if (getOtrSession() == null || getOtrSession()!!.getSessionStatus() != SessionStatus.ENCRYPTED) {
                    return null
                }
                val remotePubKey = getOtrSession()!!.getRemotePublicKey() as DSAPublicKey
                this.otrFingerprint = getAccount()!!.getOtrService()!!.getFingerprint(remotePubKey).lowercase(Locale.US)
            } catch (ignored: OtrCryptoException) {
                return null
            } catch (ignored: UnsupportedOperationException) {
                return null
            }
        }
        return this.otrFingerprint
    }

    fun verifyOtrFingerprint(): Boolean {
        val fingerprint = getOtrFingerprint()
        return if (fingerprint != null) {
            getContact().addOtrFingerprint(fingerprint)
            true
        } else {
            false
        }
    }

    fun isOtrFingerprintVerified(): Boolean {
        return getContact().getOtrFingerprints().contains(getOtrFingerprint())
    }

    class Smp {
        @JvmField
        var secret: String? = null

        @JvmField
        var hint: String? = null

        @JvmField
        var status = 0

        companion object {
            const val STATUS_NONE = 0
            const val STATUS_CONTACT_REQUESTED = 1
            const val STATUS_WE_REQUESTED = 2
            const val STATUS_FAILED = 3
            const val STATUS_VERIFIED = 4
        }
    }

    companion object {

        const val TABLENAME = "conversations"

        /**
         * [AbstractEntity.UUID], restated on this class. Java read `Conversation.UUID` through
         * inheritance and `getContentValues` still writes it from here - Kotlin does not inherit a
         * Java static into a subclass's scope.
         */
        const val UUID = AbstractEntity.UUID

        // The two mode values are inherited from Conversational as well as declared by the crypto
        // island's port, so the class names its own to keep unqualified uses unambiguous.
        const val MODE_MULTI = Conversational.MODE_MULTI
        const val MODE_SINGLE = Conversational.MODE_SINGLE

        const val STATUS_AVAILABLE = 0
        const val STATUS_ARCHIVED = 1

        const val NAME = "name"
        const val ACCOUNT = "accountUuid"
        const val CONTACT = "contactUuid"
        const val CONTACTJID = "contactJid"
        const val STATUS = "status"
        const val CREATED = "created"
        const val MODE = "mode"
        const val ATTRIBUTES = "attributes"

        // Tulkki: the language this conversation is written in, read from what the others write before
        // translation, plus the owner's override, which wins when it is set. Both schema 72.
        const val DETECTED_LANGUAGE = "detected_language"
        const val LANGUAGE_OVERRIDE = "language_override"

        // Tulkki: the per-conversation doubt-hold switch, schema 79. INTEGER and nullable, so `NULL` is
        // "this conversation never chose" and the shipped default - which is on - is not written into
        // rows that never decided. The rule that consults the value resolves the default; nothing here
        // or in the column does.
        const val DOUBT_HOLD = "doubt_hold"

        const val ATTRIBUTE_MUTED_TILL = "muted_till"
        const val ATTRIBUTE_ALWAYS_NOTIFY = "always_notify"
        const val ATTRIBUTE_NOTIFY_REPLIES = "notify_replies"
        const val ATTRIBUTE_LAST_CLEAR_HISTORY = "last_clear_history"
        const val ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS = "formerly_private_non_anonymous"
        const val ATTRIBUTE_PINNED_ON_TOP = "pinned_on_top"
        const val ATTRIBUTE_MUC_PASSWORD = "muc_password"
        const val ATTRIBUTE_MEMBERS_ONLY = "members_only"
        const val ATTRIBUTE_MODERATED = "moderated"
        const val ATTRIBUTE_NON_ANONYMOUS = "non_anonymous"
        private const val ATTRIBUTE_NEXT_MESSAGE = "next_message"
        private const val ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP = "next_message_timestamp"
        private const val ATTRIBUTE_CRYPTO_TARGETS = "crypto_targets"
        private const val ATTRIBUTE_NEXT_ENCRYPTION = "next_encryption"
        private const val ATTRIBUTE_CORRECTING_MESSAGE = "correcting_message"
        const val ATTRIBUTE_EPHEMERAL_TIMER = "ephemeral_timer"
        const val ATTRIBUTE_EPHEMERAL_HINT_HIDDEN = "ephemeral_hint_hidden"
        const val ATTRIBUTE_EPHEMERAL_BY = "ephemeral_by"

        private fun parseAttributes(attributes: String?): JSONObject {
            return if (Strings.isNullOrEmpty(attributes)) {
                JSONObject()
            } else {
                try {
                    JSONObject(attributes!!)
                } catch (e: JSONException) {
                    JSONObject()
                }
            }
        }

        @JvmStatic
        fun fromCursor(cursor: Cursor): Conversation {
            val conversation = Conversation(
                cursor.getString(cursor.getColumnIndexOrThrow(UUID)),
                cursor.getString(cursor.getColumnIndexOrThrow(NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(CONTACT)),
                cursor.getString(cursor.getColumnIndexOrThrow(ACCOUNT)),
                Jid.ofOrInvalid(cursor.getString(cursor.getColumnIndexOrThrow(CONTACTJID))),
                cursor.getLong(cursor.getColumnIndexOrThrow(CREATED)),
                cursor.getInt(cursor.getColumnIndexOrThrow(STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(MODE)),
                cursor.getString(cursor.getColumnIndexOrThrow(ATTRIBUTES)),
            )
            // Tulkki, the same way Message.fromCursor reads its translation columns.
            conversation.setDetectedLanguage(
                cursor.getString(cursor.getColumnIndexOrThrow(DETECTED_LANGUAGE)),
            )
            conversation.setLanguageOverride(
                cursor.getString(cursor.getColumnIndexOrThrow(LANGUAGE_OVERRIDE)),
            )
            conversation.setDoubtHold(readDoubtHold(cursor))
            return conversation
        }

        @JvmStatic
        fun getLatestMarkableMessage(messages: List<Message>, isPrivateAndNonAnonymousMuc: Boolean): Message? {
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                if (message.getStatus() <= Message.STATUS_RECEIVED &&
                    (message.markable || isPrivateAndNonAnonymousMuc) &&
                    !message.isPrivateMessage()
                ) {
                    return message
                }
            }
            return null
        }

        private fun suitableForOmemoByDefault(conversation: Conversation): Boolean {
            if (conversation.getJid()!!.asBareJid() == Config.BUG_REPORTS) {
                return false
            }
            if (conversation.getContact().isOwnServer()) {
                return false
            }
            val contact = conversation.getJid()!!.getDomain().toString()
            val account = conversation.getAccount()!!.getServer()
            if (Config.OMEMO_EXCEPTIONS.matchesContactDomain(XmppConnectionService.trustPort(), contact) ||
                Config.OMEMO_EXCEPTIONS.ACCOUNT_DOMAINS.contains(account)
            ) {
                return false
            }
            return conversation.isSingleOrPrivateAndNonAnonymous() ||
                conversation.getBooleanAttribute(ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, false)
        }

        /**
         * The column's tri-state as the row holds it: absent stays absent, and {@code 0} is not "unset".
         */
        private fun readDoubtHold(cursor: Cursor): Boolean? {
            val column = cursor.getColumnIndexOrThrow(DOUBT_HOLD)
            return if (cursor.isNull(column)) null else cursor.getInt(column) != 0
        }

        /**
         * The column's own spelling of the tri-state, for {@link #getContentValues()}: {@code null} for
         * "never chose", {@code 1} for on and {@code 0} for off.
         *
         * <p>Package-private so the round-trip test writes and reads the spelling a save writes. It is a
         * spelling, not a rule: what a {@code null} *means* is resolved by the rule that consults it.
         */
        @JvmStatic
        fun doubtHoldColumnValue(doubtHold: Boolean?): Int? {
            if (doubtHold == null) {
                return null
            }
            return if (doubtHold) 1 else 0
        }
    }
    override fun hasMessagesLeftOnServer(): Boolean {
        return messagesLeftOnServer
    }

    override fun setHasMessagesLeftOnServer(value: Boolean) {
        this.messagesLeftOnServer = value
    }

    fun getFirstUnreadMessage(): Message? {
        var first: Message? = null
        synchronized(this.messages) {
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                val raw = message.getRawBody()
                if (message.getSubject() != null && !message.isOOb() && (raw == null || raw.length == 0)) continue
                if (message.getRetractId() != null) continue
                if ((raw == null || "" == raw || " " == raw) && message.getReply() != null && message.edited() && message.getHtml() != null) continue
                if (asReaction(message) != null) continue
                if (message.isRead()) {
                    return first
                } else {
                    first = message
                }
            }
        }
        return first
    }

    override fun findMostRecentRemoteDisplayableId(): String? {
        val multi = mode == MODE_MULTI
        synchronized(this.messages) {
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                val raw = message.getRawBody()
                if (message.getSubject() != null && !message.isOOb() && (raw == null || raw.length == 0)) continue
                if (message.getRetractId() != null) continue
                if ((raw == null || "" == raw || " " == raw) && message.getReply() != null && message.edited() && message.getHtml() != null) continue
                if (asReaction(message) != null) continue
                if (message.getStatus() == Message.STATUS_RECEIVED) {
                    val serverMsgId = message.getServerMsgId()
                    if (serverMsgId != null && multi) {
                        return serverMsgId
                    }
                    return message.getRemoteMsgId()
                }
            }
        }
        return null
    }

    fun countFailedDeliveries(): Int {
        var count = 0
        synchronized(this.messages) {
            for (message in this.messages) {
                if (message.getStatus() == Message.STATUS_SEND_FAILED) {
                    ++count
                }
            }
        }
        return count
    }

    fun getLastEditableMessage(): Message? {
        synchronized(this.messages) {
            for (i in messages.size - 1 downTo 0) {
                val message = messages[i]
                if (message.isEditable()) {
                    if (message.isGeoUri() || message.getType() != Message.TYPE_TEXT) {
                        return null
                    }
                    return message
                }
            }
        }
        return null
    }

    override fun findUnsentMessageWithUuid(uuid: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                val s = message.getStatus()
                if ((s == Message.STATUS_UNSEND || s == Message.STATUS_WAITING) &&
                    message.getUuid() == uuid
                ) {
                    return message
                }
            }
        }
        return null
    }

    override fun findWaitingMessages(onMessageFound: ConversationRef.OnMessageFound) {
        val results = ArrayList<Message>()
        synchronized(this.messages) {
            for (message in this.messages) {
                if (message.getStatus() == Message.STATUS_WAITING) {
                    results.add(message)
                }
            }
        }
        for (result in results) {
            onMessageFound.onMessageFound(result)
        }
    }

    override fun findMessagesAndCallsToNotify(onMessageFound: ConversationRef.OnMessageFound) {
        val results = ArrayList<Message>()
        synchronized(this.messages) {
            for (message in this.messages) {
                if (message.isRead() || message.notificationWasDismissed()) {
                    continue
                }
                results.add(message)
            }
        }
        for (result in results) {
            onMessageFound.onMessageFound(result)
        }
    }

    fun findMessageWithFileAndUuid(uuid: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                // Tulkki: `getTransferable()` answers `uk.xa0.tulkki.libs.Transferable` (2026-10-08,
                // one type for both sides); every member read below is on it.
                val transferable = message.getTransferable()
                val unInitiatedButKnownSize =
                    MessageUtils.unInitiatedButKnownSize(message)
                if (message.getUuid() == uuid &&
                    message.getEncryption() != Message.ENCRYPTION_PGP &&
                    (message.isFileOrImage() ||
                        message.treatAsDownloadable() ||
                        unInitiatedButKnownSize ||
                        (transferable != null &&
                            transferable.getStatus() !=
                            Transferable.STATUS_UPLOADING))
                ) {
                    return message
                }
            }
        }
        return null
    }

    override fun findMessageWithUuid(uuid: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (message.getUuid() == uuid) {
                    return message
                }
            }
        }
        return null
    }

    override fun markAsDeleted(uuids: List<String>): Boolean {
        var deleted = false
        val pgpDecryptionService = account!!.getPgpDecryptionService()
        synchronized(this.messages) {
            for (message in this.messages) {
                if (uuids.contains(message.getUuid())) {
                    message.setDeleted(true)
                    deleted = true
                    if (message.getEncryption() == Message.ENCRYPTION_PGP &&
                        pgpDecryptionService != null
                    ) {
                        pgpDecryptionService.discard(message)
                    }
                }
            }
        }
        return deleted
    }

    override fun markAsChanged(files: List<FilePathInfoRef>): Boolean {
        var changed = false
        val pgpDecryptionService = account!!.getPgpDecryptionService()
        synchronized(this.messages) {
            for (message in this.messages) {
                for (file in files) {
                    if (file.getUuid() == message.getUuid()) {
                        message.setDeleted(file.deleted())
                        changed = true
                        if (file.deleted() &&
                            message.getEncryption() == Message.ENCRYPTION_PGP &&
                            pgpDecryptionService != null
                        ) {
                            pgpDecryptionService.discard(message)
                        }
                    }
                }
            }
        }
        return changed
    }

    override fun clearMessages() {
        synchronized(this.messages) {
            this.messages.clear()
        }
    }

    override fun setIncomingChatState(state: ChatState): Boolean {
        if (this.mIncomingChatState == state) {
            return false
        }
        this.mIncomingChatState = state
        return true
    }

    fun getIncomingChatState(): ChatState {
        return this.mIncomingChatState
    }

    override fun setOutgoingChatState(state: ChatState): Boolean {
        if (mode == MODE_SINGLE && !getContact().isSelf() ||
            (isPrivateAndNonAnonymous() && getNextCounterpart() == null)
        ) {
            if (this.mOutgoingChatState != state) {
                this.mOutgoingChatState = state
                return true
            }
        }
        return false
    }

    override fun getOutgoingChatState(): ChatState {
        return this.mOutgoingChatState
    }

    fun trim() {
        synchronized(this.messages) {
            val size = messages.size
            val maxsize = Config.PAGE_SIZE * Config.MAX_NUM_PAGES
            if (size > maxsize) {
                val discards = this.messages.subList(0, size - maxsize)
                val pgpDecryptionService = account!!.getPgpDecryptionService()
                if (pgpDecryptionService != null) {
                    pgpDecryptionService.discard(discards)
                }
                discards.clear()
                untieMessages()
            }
        }
    }

    override fun findUnsentMessagesWithEncryption(encryptionType: Int, onMessageFound: ConversationRef.OnMessageFound) {
        synchronized(this.messages) {
            for (message in this.messages) {
                if ((message.getStatus() == Message.STATUS_UNSEND || message.getStatus() == Message.STATUS_WAITING) &&
                    (message.getEncryption() == encryptionType)
                ) {
                    onMessageFound.onMessageFound(message)
                }
            }
        }
    }

    override fun findUnsentTextMessages(onMessageFound: ConversationRef.OnMessageFound) {
        val results = ArrayList<Message>()
        synchronized(this.messages) {
            for (message in this.messages) {
                if ((message.getType() == Message.TYPE_TEXT || message.hasFileOnRemoteHost()) &&
                    message.getStatus() == Message.STATUS_UNSEND
                ) {
                    results.add(message)
                }
            }
        }
        for (result in results) {
            onMessageFound.onMessageFound(result)
        }
    }

    override fun findSentMessageWithUuidOrRemoteId(id: String): Message? {
        return findSentMessageWithUuidOrRemoteId(id, false, false)
    }

    override fun findSentMessageWithUuidOrRemoteId(id: String, ignorestatus: Boolean, withedits: Boolean): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (id == message.getUuid() ||
                    ((message.getStatus() >= Message.STATUS_SEND || ignorestatus) &&
                        (id == message.getRemoteMsgId() || (getMode() == MODE_MULTI && id == message.getServerMsgId())))
                ) {
                    return message
                }

                if (withedits) {
                    for (itm in message.edits) {
                        if (id == itm.getEditedId()) {
                            return message
                        }
                    }
                }
            }
        }
        return null
    }

    override fun findMessageWithUuidOrRemoteId(id: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (id == message.getUuid() || id == message.getRemoteMsgId()) {
                    return message
                }
            }
        }
        return null
    }

    fun findMessageWithRemoteIdAndCounterpart(id: String, counterpart: Jid?): Message? {
        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = messages[i]
                val mcp = message.getCounterpart()
                if (mcp == null && counterpart != null) {
                    continue
                }
                if (counterpart == null || mcp == counterpart || mcp!!.asBareJid() == counterpart) {
                    val idMatch = id == message.getUuid() || id == message.getRemoteMsgId() || (getMode() == MODE_MULTI && id == message.getServerMsgId())
                    if (idMatch) return message
                }
            }
        }
        return null
    }

    override fun findSentMessageWithUuid(id: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (id == message.getUuid()) {
                    return message
                }
            }
        }
        return null
    }

    override fun findMessageWithRemoteId(id: String, counterpart: Jid): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (counterpart == message.getCounterpart() &&
                    (id == message.getRemoteMsgId() || id == message.getUuid())
                ) {
                    return message
                }
            }
        }
        return null
    }

    override fun findReceivedWithRemoteId(id: String): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (message.getStatus() == Message.STATUS_RECEIVED &&
                    id == message.getRemoteMsgId()
                ) {
                    return message
                }
            }
        }
        return null
    }

    override fun findMessageWithServerMsgId(id: String?): Message? {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (id != null && id == message.getServerMsgId()) {
                    return message
                }
            }
        }
        return null
    }

    override fun hasMessageWithCounterpart(counterpart: Jid): Boolean {
        synchronized(this.messages) {
            for (message in this.messages) {
                if (counterpart == message.getCounterpart()) {
                    return true
                }
            }
        }
        return false
    }

    fun findMessageReactingTo(id: String?, reactor: Jid?): Message? {
        if (id == null) return null

        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = messages[i]
                if (reactor == null && message.getStatus() < Message.STATUS_SEND) continue
                if (reactor != null && message.getCounterpart() == null) continue
                if (reactor != null && !(message.getCounterpart() == reactor || message.getCounterpart()!!.asBareJid() == reactor)) continue

                val r = message.getReactionsEl()
                if (r != null && r.getAttribute("id") != null && id == r.getAttribute("id")) {
                    return message
                }
            }
        }
        return null
    }

    fun findMessagesBy(user: MucOptions.User): List<Message> {
        val result = ArrayList<Message>()
        synchronized(this.messages) {
            for (m in this.messages) {
                // occupant id?
                val trueCp = m.getTrueCounterpart()
                if (m.getCounterpart() == user.getFullJid() || (trueCp != null && trueCp == user.getRealJid())) {
                    result.add(m)
                }
            }
        }
        return result
    }

    fun findReactionsTo(id: String?, reactor: Jid?): Set<String> {
        val reactionEmoji = HashSet<String>()
        val reactM = findMessageReactingTo(id, reactor)
        val reactions = reactM?.getReactionsEl()
        if (reactions != null) {
            for (el in reactions.getChildren()) {
                if (el.getName() == "reaction" && el.getNamespace() == "urn:xmpp:reactions:0") {
                    reactionEmoji.add(el.getContent())
                }
            }
        }
        return reactionEmoji
    }

    fun findReplies(id: String?): Set<Message> {
        val replies = HashSet<Message>()
        if (id == null) return replies

        synchronized(this.messages) {
            for (i in this.messages.size - 1 downTo 0) {
                val message = messages[i]
                if (id == message.getServerMsgId()) break
                if (id == message.getUuid()) break
                val r = message.getReply()
                if (r != null && r.getAttribute("id") != null && id == r.getAttribute("id")) {
                    replies.add(message)
                }
            }
        }
        return replies
    }

    fun loadMoreTimestamp(): Long {
        if (messages.size < 1) return 0
        if (getLockThread() && messages.size > 5000) return 0

        return if (messages[0].getType() == Message.TYPE_STATUS && messages.size >= 2) {
            messages[1].getTimeSent()
        } else {
            messages[0].getTimeSent()
        }
    }

    fun populateWithMessages(messages: MutableList<Message>, xmppConnectionService: XmppConnectionService) {
        if (historyPartMessages.size > 0) {
            messages.clear()
            messages.addAll(this.historyPartMessages)
            threads.clear()
            reactions.clear()
        } else {
            synchronized(this.messages) {
                messages.clear()
                messages.addAll(this.messages)
                threads.clear()
                reactions.clear()
            }
        }
        val extraIds = HashSet<String>()
        val now = System.currentTimeMillis()
        val iterator = messages.listIterator(messages.size)
        while (iterator.hasPrevious()) {
            val m = iterator.previous()

            // **New Check: retracted or expired ephemeral messages**
            if (m.getRetractId() != null || m.isDeleted() || (m.getExpireAt() > 0 && m.getExpireAt() < now)) {
                iterator.remove()
                continue // Move to the next message
            }

            val mthread = m.getThread()
            if (mthread != null) {
                var thread = threads[mthread.getContent()]
                if (thread == null) {
                    thread = Conversation.Thread(mthread.getContent())
                    threads[mthread.getContent()] = thread
                }
                if (thread.subject == null && (m.getSubject() != null && !m.isOOb() && (m.getRawBody() == null || m.getRawBody()!!.length == 0))) {
                    thread.subject = m
                } else {
                    if (thread.last == null) thread.last = m
                    thread.first = m
                }
            }

            val raw = m.getRawBody()
            if ((raw == null || "" == raw || " " == raw) && m.getReply() != null && m.edited() && m.getHtml() != null) {
                iterator.remove()
                continue
            }

            val reactionPair = asReaction(m)
            if (reactionPair != null) {
                reactions.put(reactionPair.first, reactionPair.second)
                iterator.remove()
            } else if (m.wasMergedIntoPrevious(xmppConnectionService) || (m.getSubject() != null && !m.isOOb() && (raw == null || raw.length == 0)) || (getLockThread() && !extraIds.contains(m.replyId()) && (mthread == null || mthread.getContent() != (getThread()?.getContent() ?: "")))) {
                iterator.remove()
            } else if (getLockThread() && mthread != null) {
                val reply = m.getReply()
                val replyId = reply?.getAttribute("id")
                if (replyId != null) extraIds.add(replyId)
                val reactions = m.getReactionsEl()
                val reactionId = reactions?.getAttribute("id")
                if (reactionId != null) extraIds.add(reactionId)
            }
        }
    }

    protected fun asReaction(m: Message): Pair<String, Reaction>? {
        val reply = m.getReply()
        if (reply != null && reply.getAttribute("id") != null) {
            val envelopeId: String?
            if (m.isCarbon() || m.getStatus() == Message.STATUS_RECEIVED) {
                envelopeId = m.getRemoteMsgId()
            } else {
                envelopeId = m.getUuid()
            }

            val body = m.getBody(true).toString().replace(Regex("\\s"), "")
            if (EmoticonText.isEmoji(body)) {
                return Pair(reply.getAttribute("id"), Reaction(body, null, m.getStatus() <= Message.STATUS_RECEIVED, m.getCounterpart(), m.getTrueCounterpart(), m.getOccupantId(), envelopeId))
            } else {
                val html = m.getHtml()
                if (html == null) return null

                val spannable = m.getSpannableBody(null, null, false)
                val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
                for (span in imageSpans) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    spannable.delete(start, end)
                }
                if (imageSpans.size == 1 && spannable.toString().replace(Regex("\\s"), "").length < 1) {
                    // Only one inline image, so it's a custom emoji by itself as a reply/reaction
                    val source = imageSpans[0].source
                    var shortcode = ""
                    val img = html.findChild("img")
                    if (img != null) {
                        shortcode = (img.getAttribute("alt") ?: throw NullPointerException()).replace(Regex("(^:)|(:$)"), "")
                    }
                    if (source != null && source.length > 0 && source.substring(0, 4) == "cid:") {
                        val cid = BobCid.cid(Uri.parse(source))
                        return Pair(reply.getAttribute("id"), Reaction(shortcode, cid, m.getStatus() <= Message.STATUS_RECEIVED, m.getCounterpart(), m.getTrueCounterpart(), m.getOccupantId(), envelopeId))
                    }
                }
            }
        }
        return null
    }

    fun aggregatedReactionsFor(m: Message, thumbnailer: Function<Reaction, GetThumbnailForCid>): Reaction.Aggregated {
        val result = HashSet<Reaction>()
        if (getMode() == MODE_MULTI && !m.isPrivateMessage()) {
            result.addAll(reactions.get(m.getServerMsgId()!!))
        } else if (m.getStatus() > Message.STATUS_RECEIVED) {
            // port-5: `AbstractEntity.getUuid()` is nullable; a message with no uuid has no key to
            // aggregate reactions under, and Guava's `Multimap` refuses a null key, so none are.
            val uuid = m.getUuid()
            if (uuid != null) {
                result.addAll(reactions.get(uuid))
            }
        } else {
            result.addAll(reactions.get(m.getRemoteMsgId()!!))
        }
        result.addAll(m.getReactions())
        return Reaction.aggregated(result, thumbnailer)
    }

    class Draft internal constructor(
        private val message: String,
        private val timestamp: Long,
    ) {
        fun getTimestamp(): Long {
            return timestamp
        }

        fun getMessage(): String {
            return message
        }
    }

    inner class ConversationPagerAdapter : PagerAdapter() {
        @JvmField
        var mPager: WeakReference<ViewPager?> = WeakReference<ViewPager?>(null)
        @JvmField
        var mTabs: WeakReference<TabLayout?> = WeakReference<TabLayout?>(null)
        @JvmField
        var sessions: ArrayList<CommandSession>? = null
        @JvmField
        var page1: WeakReference<View?> = WeakReference<View?>(null)
        @JvmField
        var page2: WeakReference<View?> = WeakReference<View?>(null)
        @JvmField
        var mOnboarding: Boolean = false
        @JvmField
        var commandsViewId: Int = 0

        fun setupViewPager(pager: ViewPager?, tabs: TabLayout?, onboarding: Boolean, oldConversation: Conversation?, commandsViewId: Int) {
            mPager = WeakReference<ViewPager?>(pager)
            mTabs = WeakReference<TabLayout?>(tabs)
            mOnboarding = onboarding
            this.commandsViewId = commandsViewId

            if (oldConversation != null) {
                oldConversation.pagerAdapter.mPager.clear()
                oldConversation.pagerAdapter.mTabs.clear()
            }

            if (pager == null) {
                page1.clear()
                page2.clear()
                return
            }
            if (sessions != null) show()

            if (pager.getChildAt(0) != null) page1 = WeakReference<View?>(pager.getChildAt(0))
            if (pager.getChildAt(1) != null) page2 = WeakReference<View?>(pager.getChildAt(1))
            if (page2.get() != null && page2.get()!!.findViewById<View>(commandsViewId) == null) {
                page1.clear()
                page2.clear()
            }
            if (oldConversation != null) {
                if (page1.get() == null) page1 = oldConversation.pagerAdapter.page1
                if (page2.get() == null) page2 = oldConversation.pagerAdapter.page2
            }
            if (page1.get() == null || page2.get() == null) {
                throw IllegalStateException("page1 or page2 were not present as child or in model?")
            }
            pager.removeView(page1.get())
            pager.removeView(page2.get())
            pager.setAdapter(this)
            tabs!!.setupWithViewPager(pager)
            pager.post { pager.setCurrentItem(getCurrentTab()) }

            pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) { }
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) { }

                override fun onPageSelected(position: Int) {
                    setCurrentTab(position)
                }
            })
        }

        fun show() {
            if (sessions == null) {
                sessions = ArrayList()
                notifyDataSetChanged()
            }
            if (!mOnboarding && mTabs.get() != null) mTabs.get()!!.setVisibility(View.VISIBLE)
        }

        fun hide() {
            if (sessions != null && !sessions!!.isEmpty()) return // Do not hide during active session
            if (mPager.get() != null) mPager.get()!!.setCurrentItem(0)
            if (mTabs.get() != null) mTabs.get()!!.setVisibility(View.GONE)
            sessions = null
            notifyDataSetChanged()
        }

        fun refreshSessions() {
            if (sessions == null) return

            for (session in sessions!!) {
                session.refresh()
            }
        }

        fun startCommand(command: Element, xmppConnectionService: XmppConnectionService) {
            show()
            val session = CommandSession(command.getAttribute("name"), command.getAttribute("node"), xmppConnectionService)

            val packet = Iq(Iq.Type.SET)
            packet.setTo(command.getAttributeAsJid("jid"))
            val c = packet.addChild("command", Namespace.COMMANDS)
            c.setAttribute("node", command.getAttribute("node"))
            c.setAttribute("action", "execute")

            val task: TimerTask = object : TimerTask() {
                override fun run() {
                    if (getAccount()!!.getStatus() != Account.State.ONLINE) {
                        val self: TimerTask = this
                        Timer().schedule(object : TimerTask() {
                            override fun run() {
                                self.run()
                            }
                        }, 1000)
                    } else {
                        xmppConnectionService.sendIqPacket(getAccount() ?: throw NullPointerException("conversation has no account"), packet, { iq ->
                            session.updateWithResponse(iq)
                        }, 120L)
                    }
                }
            }

            // Tulkki: this was a Cheogram Play licence-report branch.  Its checker always called its
            // callback back with a hard-wired (null, null) - "skipping license checks in free build"
            // - so the branch that would have added <license>/<licenseSignature> could never run and
            // both arms of the if were exactly this call.  The checker class is deleted
            // (docs/MIGRATION.md "The flavour collapse" F2, which names it) and the branch with it,
            // leaving the one statement it always executed.
            task.run()

            sessions!!.add(session)
            notifyDataSetChanged()
            if (mPager.get() != null) mPager.get()!!.setCurrentItem(getCount() - 1)
        }

        fun startMucConfig(xmppConnectionService: XmppConnectionService) {
            val session = MucConfigSession(xmppConnectionService)
            val packet = Iq(Iq.Type.GET)
            packet.setTo(getJid()!!.asBareJid())
            packet.addChild("query", "http://jabber.org/protocol/muc#owner")

            val task: TimerTask = object : TimerTask() {
                override fun run() {
                    if (getAccount()!!.getStatus() != Account.State.ONLINE) {
                        val self: TimerTask = this
                        Timer().schedule(object : TimerTask() {
                            override fun run() {
                                self.run()
                            }
                        }, 1000)
                    } else {
                        xmppConnectionService.sendIqPacket(getAccount() ?: throw NullPointerException("conversation has no account"), packet, { iq ->
                            session.updateWithResponse(iq)
                        }, 120L)
                    }
                }
            }
            task.run()

            sessions!!.add(session)
            notifyDataSetChanged()
            if (mPager.get() != null) mPager.get()!!.setCurrentItem(getCount() - 1)
        }

        fun removeSession(session: CommandSession) {
            sessions!!.remove(session)
            notifyDataSetChanged()
        }

        fun switchToSession(node: String?): Boolean {
            if (sessions == null || node == null) return false

            var i = 0
            for (session in sessions!!) {
                if (session.getNode() == node) {
                    if (mPager.get() != null) mPager.get()!!.setCurrentItem(i + 2)
                    return true
                }
                i++
            }

            return false
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            if (position == 0) {
                val pg1 = page1.get()
                if (pg1 != null && pg1.getParent() != null) {
                    (pg1.getParent() as ViewGroup).removeView(pg1)
                }
                container.addView(pg1)
                return pg1!!
            }
            if (position == 1) {
                val pg2 = page2.get()
                if (pg2 != null && pg2.getParent() != null) {
                    (pg2.getParent() as ViewGroup).removeView(pg2)
                }
                container.addView(pg2)
                return pg2!!
            }

            if (position - 2 >= sessions!!.size) return null as Any
            val session = sessions!![position - 2]
            val v = session.inflateUi(container.getContext()) { s -> removeSession(s) }
            if (v != null && v.getParent() != null) {
                (v.getParent() as ViewGroup).removeView(v)
            }
            container.addView(v)
            return session
        }

        override fun destroyItem(container: ViewGroup, position: Int, o: Any) {
            if (position < 2) {
                container.removeView(o as View)
                return
            }

            (o as CommandSession).getView()?.let { container.removeView(it) }
        }

        override fun getItemPosition(o: Any): Int {
            if (mPager.get() != null) {
                if (o === page1.get()) return PagerAdapter.POSITION_UNCHANGED
                if (o === page2.get()) return PagerAdapter.POSITION_UNCHANGED
            }

            val pos = if (sessions == null) -1 else sessions!!.indexOf(o)
            if (pos < 0) return PagerAdapter.POSITION_NONE
            return pos + 2
        }

        override fun getCount(): Int {
            if (sessions == null) return 1

            val count = 2 + sessions!!.size
            if (mTabs.get() == null) return count

            if (count > 2) {
                mTabs.get()!!.setTabMode(TabLayout.MODE_SCROLLABLE)
            } else {
                mTabs.get()!!.setTabMode(TabLayout.MODE_FIXED)
            }
            return count
        }

        override fun isViewFromObject(view: View, o: Any): Boolean {
            if (view === o) return true

            if (o is CommandSession) {
                return o.getView() === view
            }

            return false
        }

        override fun getPageTitle(position: Int): CharSequence? {
            when (position) {
                0 -> return mTabs.get()!!.getContext().getString(R.string.conversation)
                1 -> return mTabs.get()!!.getContext().getString(R.string.commands)
                else -> {
                    val session: CommandSession? = sessions!![position - 2]
                    if (session == null) return super.getPageTitle(position)
                    return session.getTitle()
                }
            }
        }

        /**
         * One open ad-hoc command page (XEP-0050 over XEP-0004).
         *
         * It owns the session's state and its decisions - the field-type dispatch, validation,
         * the submit, the wait - and now only *describes* the page: [form] assembles a
         * [CommandForm] of plain data and callbacks, and a [CommandFormRenderer] in `:ui` draws
         * it. The `RecyclerView.Adapter` of Android views that used to live here is gone with
         * its fourteen layouts; what remains is the same model over the same `Element`s.
         */
        open inner class CommandSession : CommandFormSession {

            @JvmField
            var executing = false
            @JvmField
            var loading = false
            @JvmField
            var loadingHasBeenLong = false
            @JvmField
            var loadingTimer: Timer = Timer()
            @JvmField
            var mTitle: String? = null
            @JvmField
            var mNode: String? = null
            @JvmField
            var response: Iq? = null
            @JvmField
            var responseElement: Element? = null
            @JvmField
            var expectingRemoval = false
            @JvmField
            var reported: MutableList<Field>? = null
            @JvmField
            var items: SparseArray<Item> = SparseArray()
            @JvmField
            var xmppConnectionService: XmppConnectionService? = null
            @JvmField
            var actionsAdapter: ActionsAdapter? = null
            @JvmField
            var actionToWebview: WebView? = null
            @JvmField
            var fillableFieldCount = 0
            @JvmField
            var pendingResponsePacket: Iq? = null
            @JvmField
            var waitingForRefresh = false

            private val formListeners = LinkedHashSet<CommandFormListener>()
            private var page: View? = null
            private var remover: Consumer<CommandSession>? = null

            constructor(title: String?, node: String?, xmppConnectionService: XmppConnectionService?) {
                actionsAdapter = ActionsAdapter()
                loading()
                mTitle = title
                mNode = node
                this.xmppConnectionService = xmppConnectionService
            }

            fun getTitle(): String? {
                return mTitle
            }

            fun getNode(): String? {
                return mNode
            }

            override fun title(): String? = mTitle

            override fun addFormListener(listener: CommandFormListener) {
                formListeners.add(listener)
            }

            override fun removeFormListener(listener: CommandFormListener) {
                formListeners.remove(listener)
            }

            private fun context(): Context? = mPager.get()?.getContext() ?: page?.getContext()

            /** The one observable change: every `notifyDataSetChanged` call becomes this. */
            fun notifyDataSetChanged() {
                val snapshot = ArrayList(formListeners)
                for (listener in snapshot) listener.onFormChanged()
            }

            override fun form(): CommandForm {
                val rows = ArrayList<CommandFormItem>()
                val count = getItemCount()
                for (i in 0 until count) {
                    val item = getItem(i) ?: continue
                    val row: CommandFormItem? = when (item.viewType) {
                        TYPE_ERROR -> noteItem(item, true)
                        TYPE_NOTE -> noteItem(item, false)
                        TYPE_WEB -> webItem(item)
                        TYPE_RESULT_FIELD -> resultFieldItem(item as Field)
                        TYPE_RESULT_CELL -> resultCellItem(item as Cell)
                        TYPE_ITEM_CARD -> itemCardItem(item)
                        TYPE_CHECKBOX_FIELD -> checkboxItem(item as Field)
                        TYPE_SEARCH_LIST_FIELD -> searchListItem(item as Field)
                        TYPE_RADIO_EDIT_FIELD -> radioEditItem(item as Field)
                        TYPE_SPINNER_FIELD -> spinnerItem(item as Field)
                        TYPE_BUTTON_GRID_FIELD -> buttonGridItem(item as Field)
                        TYPE_TEXT_FIELD -> textFieldItem(item as Field)
                        TYPE_SLIDER_FIELD -> sliderItem(item as Field)
                        TYPE_PROGRESSBAR -> ProgressItem(loadingHasBeenLong)
                        else -> null
                    }
                    if (row != null) rows.add(row)
                }
                return CommandForm(rows, actionItems())
            }

            override fun executeAction(name: String): Boolean {
                if (execute(name)) {
                    remover?.accept(this)
                    return true
                }
                return false
            }

            override fun executeWebAction(action: String): Boolean {
                actionToWebview = null
                return executeAction(action)
            }

            override fun preventDefault(view: View) {
                actionToWebview = view as? WebView
            }

            private fun noteItem(item: Item, errorView: Boolean): CommandFormItem {
                val el = item.el
                if (!errorView) {
                    val content = el?.getContent() ?: ""
                    return NoteItem(content, el?.getAttribute("type") == "error")
                }
                val error = el?.findChild("error")
                if (error == null) {
                    return NoteItem("Unexpected response", true)
                }
                var text = error.findChildContent("text", "urn:ietf:params:xml:ns:xmpp-stanzas")
                if (text.isNullOrEmpty()) {
                    text = error.getChildren().firstOrNull()?.getName()
                }
                return NoteItem(text ?: "", true)
            }

            private fun webItem(item: Item): CommandFormItem {
                val el = item.el
                return WebItem(
                    el?.findChildContent("desc", "jabber:x:oob"),
                    el?.findChildContent("url", "jabber:x:oob") ?: "",
                )
            }

            private fun resultFieldItem(field: Field): ResultFieldItem =
                resultFieldItem(field.el, field.getLabel().orNull(), field.getDesc().orNull())

            private fun resultFieldItem(el: Element?, label: String?, desc: String?): ResultFieldItem {
                if (el == null) return ResultFieldItem(label, desc, null, emptyList())
                val validate = el.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val datatype = validate?.getAttribute("datatype")
                val type = el.getAttribute("type")
                val values = ArrayList<ResultValue>()
                for (child in el.getChildren()) {
                    if (child.getName() == "value" && child.getNamespace() == "jabber:x:data") {
                        values.add(resultValue(child, datatype, type))
                    }
                }
                val media = el.findChild("media", "urn:xmpp:media-element")
                var mediaUrl: String? = null
                if (media != null) {
                    for (uriEl in media.getChildren()) {
                        if (uriEl.getName() != "uri" || uriEl.getNamespace() != "urn:xmpp:media-element") continue
                        val mime = uriEl.getAttribute("type") ?: continue
                        val uri = uriEl.getContent() ?: continue
                        if (mime.startsWith("image/") && Uri.parse(uri).getScheme() == "https") {
                            mediaUrl = uri
                            break
                        }
                    }
                }
                return ResultFieldItem(label, desc, mediaUrl, values)
            }

            private fun resultValue(el: Element, datatype: String?, type: String?): ResultValue {
                val raw = el.getContent()
                return ResultValue(
                    text = formatValue(datatype, raw, false) ?: raw,
                    linkUrl = linkFor(datatype, type, raw),
                    onOpenLink = { url -> openLink(url) },
                    onCopy = { value -> copyText(value) },
                )
            }

            private fun resultCellItem(cell: Cell): ResultCellItem {
                val reportedField = cell.reported
                val el = cell.el
                if (el == null) {
                    return ResultCellItem(reportedField.getLabel().or(""), true, null, {}, {})
                }
                val validate = reportedField.el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val datatype = validate?.getAttribute("datatype")
                val raw = el.findChildContent("value", "jabber:x:data")
                val text = formatValue(datatype, raw, true) ?: ""
                return ResultCellItem(
                    text = text,
                    header = false,
                    linkUrl = linkFor(datatype, reportedField.getType().orNull(), text),
                    onOpenLink = { url -> openLink(url) },
                    onCopy = { value -> copyText(value) },
                )
            }

            private fun linkFor(datatype: String?, type: String?, value: String): String? {
                if (type == "jid-single" || type == "jid-multi") {
                    return try {
                        "xmpp:" + Uri.encode(Jid.of(value).toString(), "@/+")
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                if ("xs:anyURI" == datatype) return value
                if ("html:tel" == datatype) {
                    val ctx = context() ?: return null
                    return try {
                        "tel:" + PhoneNumberNormalizer.normalizePhoneNumber(ctx, value)
                    } catch (e: IllegalArgumentException) {
                        null
                    } catch (e: NumberParseException) {
                        null
                    }
                }
                return null
            }

            private fun openLink(url: String) {
                val anchor = getView() ?: return
                ViewPorts.linkedText().openLink(url, account, anchor)
            }

            private fun copyText(value: String) {
                val ctx = context() ?: return
                if (ViewPorts.clipboard().copyText(ctx, value, R.string.message)) {
                    Toast.makeText(ctx, R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }

            private fun itemCardItem(item: Item): CommandFormItem {
                val fields = ArrayList<ResultFieldItem>()
                val reportedFields = reported
                val el = item.el
                if (reportedFields != null && el != null) {
                    for (reportedField in reportedFields) {
                        for (fieldEl in el.getChildren()) {
                            if (fieldEl.getName() != "field" || fieldEl.getNamespace() != "jabber:x:data") continue
                            if (fieldEl.getAttribute("var") == null || fieldEl.getAttribute("var") != reportedField.getVar()) continue
                            for (label in reportedField.getLabel().asSet()) fieldEl.setAttribute("label", label)
                            for (desc in reportedField.getDesc().asSet()) fieldEl.setAttribute("desc", desc)
                            for (type in reportedField.getType().asSet()) fieldEl.setAttribute("type", type)
                            val validate = reportedField.el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                            if (validate != null) fieldEl.addChild(validate)
                            fields.add(
                                resultFieldItem(
                                    fieldEl,
                                    uk.xa0.tulkki.xmpp.forms.Field.parse(fieldEl).getLabel(),
                                    fieldEl.findChildContent("desc", "jabber:x:data"),
                                )
                            )
                        }
                    }
                }
                return ItemCardItem(fields)
            }

            private fun checkboxItem(field: Field): CommandFormItem {
                val value = field.getValue()
                val checked = value.getContent() == "true" || value.getContent() == "1"
                value.setContent(if (checked) "true" else "false")
                return CheckboxItem(
                    label = field.getLabel().or(""),
                    desc = field.getDesc().orNull(),
                    checked = checked,
                    onChecked = { isChecked -> field.getValue().setContent(if (isChecked) "true" else "false") },
                )
            }

            private fun optionModel(option: Option): CommandFormOption {
                val value = option.getValue() ?: ""
                return CommandFormOption(value, option.toString(), null)
            }

            private fun filterOptions(options: List<Option>, query: String): List<Option> {
                val q = query.replace(Regex("\\W"), "").lowercase()
                if (q.isEmpty()) return options
                return options.filter { it.toString().replace(Regex("\\W"), "").lowercase().contains(q) }
            }

            private fun searchListItem(field: Field): CommandFormItem {
                val el = field.el
                val validate = el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val open = validate != null && validate.findChild("open", "http://jabber.org/protocol/xdata-validate") != null
                val multi = field.getType().orNull() == "list-multi"
                val all = field.getOptions()
                val query = field.searchQuery
                val filtered = filterOptions(all, query)
                return SearchListItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    error = field.error,
                    query = query,
                    options = filtered.map { optionModel(it) },
                    selected = field.getValues().toSet(),
                    multi = multi,
                    canType = open,
                    onQuery = { q ->
                        field.searchQuery = q
                        if (!multi && open) field.setValues(listOf(q))
                        notifyDataSetChanged()
                    },
                    onSelect = { option, checked ->
                        val values = HashSet<String>()
                        if (multi) {
                            val optionValues = all.map { it.getValue() }.toSet()
                            values.addAll(field.getValues())
                            for (value in field.getValues()) {
                                if (filtered.any { it.getValue() == value } || (!open && !optionValues.contains(value))) {
                                    values.remove(value)
                                }
                            }
                            if (checked) values.add(option.value) else values.remove(option.value)
                        } else {
                            if (checked) values.add(option.value)
                        }
                        field.setValues(values)
                        if (!multi && open) field.searchQuery = field.getValues().joinToString("\n")
                        notifyDataSetChanged()
                    },
                )
            }

            private fun radioEditItem(field: Field): CommandFormItem {
                val value = field.getValue()
                val selected = value.getContent()
                val validate = field.el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val open = validate != null && validate.findChild("open", "http://jabber.org/protocol/xdata-validate") != null
                return RadioEditItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    error = field.error,
                    options = field.getOptions().map { optionModel(it) },
                    selected = selected,
                    open = open,
                    onSelect = { option ->
                        field.getValue().setContent(option.value)
                        notifyDataSetChanged()
                    },
                    onOpen = { text ->
                        field.getValue().setContent(text)
                        notifyDataSetChanged()
                    },
                )
            }

            private fun spinnerItem(field: Field): CommandFormItem {
                val selected = field.getValue().getContent()
                return SpinnerItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    options = field.getOptions().map { optionModel(it) },
                    selected = selected,
                    onSelect = { option ->
                        field.getValue().setContent(option.value)
                        notifyDataSetChanged()
                    },
                )
            }

            private fun buttonGridItem(field: Field): CommandFormItem {
                val ctx = context()
                val value = field.getValue()
                val current = value.getContent() ?: ""
                val theOptions: MutableList<Option> = if (field.getType().orNull() == "boolean") {
                    ArrayList(
                        listOf(
                            Option("false", ctx?.getString(R.string.no) ?: "No"),
                            Option("true", ctx?.getString(R.string.yes) ?: "Yes"),
                        )
                    )
                } else {
                    ArrayList(field.getOptions())
                }
                var defaultOption = theOptions.firstOrNull { it.getValue() == current }
                if (defaultOption == null && current.isNotEmpty()) {
                    defaultOption = Option(current, current)
                }
                defaultOption?.let { theOptions.remove(it) }
                val validate = field.el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val open = validate != null && validate.findChild("open", "http://jabber.org/protocol/xdata-validate") != null
                return ButtonGridItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    error = field.error,
                    options = theOptions.map { optionModel(it) },
                    defaultOption = defaultOption?.let { optionModel(it) },
                    open = open,
                    customText = current,
                    onChoose = { option ->
                        value.setContent(option.value)
                        runAndLoad()
                    },
                    onDefault = {
                        defaultOption?.let { value.setContent(it.getValue()) }
                        runAndLoad()
                    },
                    onCustom = { text ->
                        value.setContent(text)
                        runAndLoad()
                    },
                )
            }

            private fun runAndLoad() {
                execute()
                loading = true
                notifyDataSetChanged()
            }

            private fun textFieldItem(field: Field): CommandFormItem {
                val el = field.el
                return TextFieldItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    error = field.error,
                    prefix = el?.findChildContent("x", "https://ns.cheogram.com/prefix-label"),
                    suffix = el?.findChildContent("x", "https://ns.cheogram.com/suffix-label"),
                    value = field.getValues().joinToString("\n"),
                    input = inputFor(el),
                    onChange = { text -> field.setValues(text.split("\n")) },
                )
            }

            private fun inputFor(el: Element?): CommandFormInput {
                if (el == null) return CommandFormInput.TEXT
                val type = el.getAttribute("type")
                var multi = false
                if (type != null) {
                    if (type == "text-multi" || type == "jid-multi") multi = true
                    if (type == "jid-single" || type == "jid-multi") return CommandFormInput.EMAIL
                    if (type == "text-private") return CommandFormInput.PASSWORD
                }
                val validate = el.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val datatype = validate?.getAttribute("datatype")
                if (datatype != null) {
                    when (datatype) {
                        "xs:integer", "xs:int", "xs:long", "xs:short", "xs:byte" -> return CommandFormInput.NUMBER
                        "xs:decimal", "xs:double" -> return CommandFormInput.DECIMAL
                        "xs:date" -> return CommandFormInput.DATE
                        "xs:dateTime" -> return CommandFormInput.DATETIME
                        "xs:time" -> return CommandFormInput.TIME
                        "xs:anyURI" -> return CommandFormInput.URI
                        "html:tel" -> return CommandFormInput.PHONE
                        "html:email" -> return CommandFormInput.EMAIL
                    }
                }
                return if (multi) CommandFormInput.MULTILINE else CommandFormInput.TEXT
            }

            private fun sliderItem(field: Field): CommandFormItem {
                val validate = field.el?.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                val datatype = validate?.getAttribute("datatype")
                val range = validate?.findChild("range", "http://jabber.org/protocol/xdata-validate")
                var min: Float? = null
                try {
                    min = range?.getAttribute("min")?.toFloat()
                } catch (e: NumberFormatException) {
                }
                var max: Float? = null
                try {
                    max = range?.getAttribute("max")?.toFloat()
                } catch (e: NumberFormatException) {
                }
                val options = field.getOptions().map { it.getValue()?.toFloatOrNull() }.filterNotNull().sorted()
                if (options.isNotEmpty()) {
                    if (min == null) min = options.first()
                    if (max == null) max = options.last()
                }
                val lo = min ?: 0f
                val hi = if (max != null && max!! > lo) max!! else lo + 1f
                val raw = if (field.getValues().isNotEmpty()) field.getValue().getContent()?.toFloatOrNull() else null
                val value = if (raw != null && raw in lo..hi) raw else lo
                var step = 0f
                if (datatype == "xs:integer" || datatype == "xs:int" || datatype == "xs:long" ||
                    datatype == "xs:short" || datatype == "xs:byte"
                ) {
                    step = 1f
                }
                if (options.size > 1) {
                    var candidate = -1f
                    var prev: Float? = null
                    var uniform = true
                    for (option in options) {
                        val previous = prev
                        if (previous != null) {
                            val next = option - previous
                            if (candidate > 0 && candidate != next) {
                                uniform = false
                                break
                            }
                            candidate = next
                        }
                        prev = option
                    }
                    if (uniform && candidate > 0) step = candidate
                }
                return SliderItem(
                    label = field.getLabel().orNull(),
                    desc = field.getDesc().orNull(),
                    value = value,
                    min = lo,
                    max = hi,
                    step = step,
                    onChange = { v -> field.setValues(listOf(DecimalFormat().format(v))) },
                )
            }

            private fun actionItems(): List<CommandFormAction> {
                val ctx = context() ?: return emptyList()
                val adapter = actionsAdapter ?: return emptyList()
                val out = ArrayList<CommandFormAction>()
                for (i in 0 until adapter.count) {
                    val entry = adapter.getItem(i)
                    val name = entry.first ?: ""
                    var label: String = entry.second ?: name
                    val resId = ctx.getResources().getIdentifier("action_" + name, "string", ctx.getPackageName())
                    if (resId != 0 && label == name) label = ctx.getResources().getString(resId)
                    val colors = MaterialColors.getColorRoles(ctx, DisplayNames.getColorForName(name))
                    out.add(
                        CommandFormAction(
                            name,
                            label,
                            colors.getOnAccent(),
                            MaterialColors.harmonizeWithPrimary(ctx, colors.getAccent()),
                        )
                    )
                }
                return out
            }

            protected fun formatValue(datatype: String?, value: String?, compact: Boolean): String? {
                if ("xs:dateTime" == datatype) {
                    var zonedDateTime: ZonedDateTime? = null
                    try {
                        zonedDateTime = ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                    } catch (e: DateTimeParseException) {
                        try {
                            val almostIso = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss] X")
                            zonedDateTime = ZonedDateTime.parse(value, almostIso)
                        } catch (e2: DateTimeParseException) {
                        }
                    }
                    if (zonedDateTime == null) return value
                    val localZonedDateTime = zonedDateTime.withZoneSameInstant(ZoneId.systemDefault())
                    val outputFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    return localZonedDateTime.toLocalDateTime().format(outputFormat)
                }

                if ("html:tel" == datatype && !compact) {
                    return PhoneNumberUtils.formatNumber(value, value, null)
                }

                return value
            }

            fun updateWithResponse(iq: Iq) {
                val view = getView()
                if (view != null && view.isAttachedToWindow()) {
                    view.post { updateWithResponseUiThread(iq) }
                } else {
                    pendingResponsePacket = iq
                }
            }

            protected open fun updateWithResponseUiThread(iq: Iq) {
                val oldTimer = this.loadingTimer
                this.loadingTimer = Timer()
                oldTimer.cancel()
                this.executing = false
                this.loading = false
                this.loadingHasBeenLong = false
                this.responseElement = null
                this.fillableFieldCount = 0
                this.reported = null
                this.response = iq
                this.items.clear()
                this.actionsAdapter!!.clear()

                var actionsCleared = false
                val command = iq.findChild("command", "http://jabber.org/protocol/commands")
                if (iq.getType() == Iq.Type.RESULT && command != null) {
                    if (mNode!!.equals("jabber:iq:register") && command.getAttribute("status") != null && command.getAttribute("status").equals("completed")) {
                        xmppConnectionService!!.createContact(getAccount()!!.getRoster().getContact(iq.getFrom() ?: throw NullPointerException("from")), true)
                    }

                    if (xmppConnectionService!!.isOnboarding() && mNode!!.equals("jabber:iq:register") && !"canceled".equals(command.getAttribute("status")) && xmppConnectionService!!.getPreferences().contains("onboarding_action")) {
                        xmppConnectionService!!.getPreferences().edit().putBoolean("onboarding_continued", true).commit()
                    }

                    val actions = command.findChild("actions", "http://jabber.org/protocol/commands")
                    if (actions != null) {
                        for (action in actions.getChildren()) {
                            if (!"http://jabber.org/protocol/commands".equals(action.getNamespace())) continue
                            if ("execute".equals(action.getName())) continue

                            actionsAdapter!!.add(Pair.create(action.getName(), action.getName()))
                        }
                    }

                    for (el in command.getChildren()) {
                        if ("x".equals(el.getName()) && "jabber:x:data".equals(el.getNamespace())) {
                            val form = Data.parse(el) ?: throw NullPointerException()
                            val title = form.getTitle()
                            if (title != null) {
                                mTitle = title
                                this@ConversationPagerAdapter.notifyDataSetChanged()
                            }

                            if ("result".equals(el.getAttribute("type")) || "form".equals(el.getAttribute("type"))) {
                                this.responseElement = el
                                setupReported(el.findChild("reported", "jabber:x:data"))
                            }

                            val actionList = form.getFieldByName("http://jabber.org/protocol/commands#actions")
                            if (actionList != null) {
                                actionsAdapter!!.clear()

                                for (action in actionList.getOptions()) {
                                    actionsAdapter!!.add(Pair.create(action.getValue(), action.toString()))
                                }
                            }

                            var fillableField: uk.xa0.tulkki.xmpp.forms.Field? = null
                            for (field in form.getFields()) {
                                if ((field.getType() == null || (!field.getType().equals("hidden") && !field.getType().equals("fixed"))) && field.getFieldName() != null && !field.getFieldName().equals("http://jabber.org/protocol/commands#actions")) {
                                    val validate = field.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                                    val range = if (validate == null) null else validate.findChild("range", "http://jabber.org/protocol/xdata-validate")
                                    fillableField = if (range == null) field else null
                                    fillableFieldCount++
                                }
                            }

                            if (fillableFieldCount == 1 && fillableField != null && actionsAdapter!!.countProceed() < 2 && (("list-single".equals(fillableField.getType()) && Option.forField(fillableField).size < 50) || ("boolean".equals(fillableField.getType()) && fillableField.getValue() == null))) {
                                actionsCleared = true
                                actionsAdapter!!.clearProceed()
                            }
                            break
                        }
                        if (el.getName().equals("x") && el.getNamespace().equals("jabber:x:oob")) {
                            val url = el.findChildContent("url", "jabber:x:oob")
                            if (url != null) {
                                val scheme = Uri.parse(url).getScheme()
                                if (scheme == null) {
                                    break
                                }
                                if (scheme.equals("http") || scheme.equals("https")) {
                                    this.responseElement = el
                                    break
                                }
                                if (scheme.equals("xmpp")) {
                                    expectingRemoval = true
                                    ViewPorts.screenLaunch().launchUriHandler(getView()!!.getContext(), url)
                                    break
                                }
                            }
                        }
                        if (el.getName().equals("note") && el.getNamespace().equals("http://jabber.org/protocol/commands")) {
                            this.responseElement = el
                            break
                        }
                    }

                    if (responseElement == null && command.getAttribute("status") != null && (command.getAttribute("status").equals("completed") || command.getAttribute("status").equals("canceled"))) {
                        if ("jabber:iq:register".equals(mNode) && "canceled".equals(command.getAttribute("status"))) {
                            if (xmppConnectionService!!.isOnboarding()) {
                                if (xmppConnectionService!!.getPreferences().contains("onboarding_action")) {
                                    xmppConnectionService!!.deleteAccount(getAccount()!!)
                                } else {
                                    if (xmppConnectionService!!.getPreferences().getBoolean("onboarding_continued", false)) {
                                        removeSession(this)
                                        return
                                    } else {
                                        xmppConnectionService!!.getPreferences().edit().putString("onboarding_action", "cancel").commit()
                                        xmppConnectionService!!.deleteAccount(getAccount()!!)
                                    }
                                }
                            }
                            xmppConnectionService!!.archiveConversation(this@Conversation)
                        }

                        expectingRemoval = true
                        removeSession(this)
                        return
                    }

                    if ("executing".equals(command.getAttribute("status")) && actionsAdapter!!.countExceptCancel() < 1 && !actionsCleared) {
                        actionsAdapter!!.add(Pair.create("execute", "execute"))
                    }

                    if (!actionsAdapter!!.isEmpty || fillableFieldCount > 0) {
                        if ("completed".equals(command.getAttribute("status")) || "canceled".equals(command.getAttribute("status"))) {
                            actionsAdapter!!.add(Pair.create("close", "close"))
                        } else if (actionsAdapter!!.getPosition("cancel") < 0 && !xmppConnectionService!!.isOnboarding()) {
                            actionsAdapter!!.insert(Pair.create("cancel", "cancel"), 0)
                        }
                    }
                }

                if (actionsAdapter!!.isEmpty) {
                    actionsAdapter!!.add(Pair.create("close", "close"))
                }

                actionsAdapter!!.sort { x, y ->
                    if (x.first.equals("cancel")) return@sort -1
                    if (y.first.equals("cancel")) return@sort 1
                    if (x.first.equals("prev") && xmppConnectionService!!.isOnboarding()) return@sort -1
                    if (y.first.equals("prev") && xmppConnectionService!!.isOnboarding()) return@sort 1
                    0
                }

                var dataForm: Data? = null
                if (responseElement != null && responseElement!!.getName().equals("x") && responseElement!!.getNamespace().equals("jabber:x:data")) dataForm = Data.parse(responseElement!!)
                if (mNode!!.equals("jabber:iq:register") &&
                    xmppConnectionService!!.getPreferences().contains("onboarding_action") &&
                    dataForm != null && dataForm.getFieldByName("gateway-jid") != null
                ) {
                    dataForm.put("gateway-jid", xmppConnectionService!!.getPreferences().getString("onboarding_action", ""))
                    execute()
                }
                xmppConnectionService!!.getPreferences().edit().remove("onboarding_action").commit()
                notifyDataSetChanged()
            }

            protected fun setupReported(el: Element?) {
                if (el == null) {
                    reported = null
                    return
                }

                reported = ArrayList()
                for (fieldEl in el.getChildren()) {
                    if (!fieldEl.getName().equals("field") || !fieldEl.getNamespace().equals("jabber:x:data")) continue
                    reported!!.add(mkField(fieldEl)!!)
                }
            }

            fun getItemCount(): Int {
                if (loading) return 1
                if (response == null) return 0
                if (response!!.getType() == Iq.Type.RESULT && responseElement != null && responseElement!!.getNamespace().equals("jabber:x:data")) {
                    var i = 0
                    for (el in responseElement!!.getChildren()) {
                        if (!el.getNamespace().equals("jabber:x:data")) continue
                        if (el.getName().equals("title")) continue
                        if (el.getName().equals("field")) {
                            val type = el.getAttribute("type")
                            if (type != null && type.equals("hidden")) continue
                            if (el.getAttribute("var") != null && el.getAttribute("var").equals("http://jabber.org/protocol/commands#actions")) continue
                        }

                        if (el.getName().equals("reported") || el.getName().equals("item")) {
                            if (reported == null) continue
                            if (1 < reported!!.size) {
                                if (el.getName().equals("reported")) continue
                                i += 1
                            } else {
                                i += reported!!.size
                            }
                            continue
                        }

                        i++
                    }
                    return i
                }
                return 1
            }

            fun getItem(position: Int): Item? {
                if (loading) return Item(null, TYPE_PROGRESSBAR)
                if (items.get(position) != null) return items.get(position)
                if (response == null) return null

                if (response!!.getType() == Iq.Type.RESULT && responseElement != null) {
                    if (responseElement!!.getNamespace().equals("jabber:x:data")) {
                        var i = 0
                        for (el in responseElement!!.getChildren()) {
                            if (!el.getNamespace().equals("jabber:x:data")) continue
                            if (el.getName().equals("title")) continue
                            if (el.getName().equals("field")) {
                                val type = el.getAttribute("type")
                                if (type != null && type.equals("hidden")) continue
                                if (el.getAttribute("var") != null && el.getAttribute("var").equals("http://jabber.org/protocol/commands#actions")) continue
                            }

                            if (el.getName().equals("reported") || el.getName().equals("item")) {
                                var cell: Cell? = null

                                if (reported != null) {
                                    if (1 < reported!!.size) {
                                        if (el.getName().equals("reported")) continue
                                        if (i == position) {
                                            items.put(position, Item(el, TYPE_ITEM_CARD))
                                            return items.get(position)
                                        }
                                    } else {
                                        if (reported!!.size > position - i) {
                                            val reportedField = reported!!.get(position - i)
                                            var itemField: Element? = null
                                            if (el.getName().equals("item")) {
                                                for (subel in el.getChildren()) {
                                                    if (subel.getAttribute("var").equals(reportedField.getVar())) {
                                                        itemField = subel
                                                        break
                                                    }
                                                }
                                            }
                                            cell = Cell(reportedField, itemField)
                                        } else {
                                            i += reported!!.size
                                            continue
                                        }
                                    }
                                }

                                if (cell != null) {
                                    items.put(position, cell)
                                    return cell
                                }
                            }

                            if (i < position) {
                                i++
                                continue
                            }

                            return mkItem(el, position)
                        }
                    }
                }

                return mkItem(responseElement ?: response!!, position)
            }

            fun getItemViewType(position: Int): Int {
                return getItem(position)!!.viewType
            }

            open inner class Item(el: Element?, viewType: Int) {
                var el: Element? = el
                var viewType: Int = viewType
                var error: String? = null

                open fun validate(): Boolean {
                    error = null
                    return true
                }
            }

            inner class Field(el: uk.xa0.tulkki.xmpp.forms.Field, viewType: Int) : Item(el, viewType) {

                @JvmField
                var searchQuery: String = ""

                override fun validate(): Boolean {
                    if (!super.validate()) return false
                    if (el!!.findChild("required", "jabber:x:data") == null) return true
                    if (getValue().getContent() != null && !getValue().getContent().equals("")) return true

                    error = "this value is required"
                    return false
                }

                fun getVar(): String? {
                    return el!!.getAttribute("var")
                }

                fun getType(): Optional<String> {
                    return Optional.fromNullable(el!!.getAttribute("type"))
                }

                fun getLabel(): Optional<String> {
                    var label: String? = el!!.getAttribute("label")
                    if (label == null) label = getVar()
                    return Optional.fromNullable(label)
                }

                fun getDesc(): Optional<String> {
                    return Optional.fromNullable(el!!.findChildContent("desc", "jabber:x:data"))
                }

                fun getValue(): Element {
                    var value = el!!.findChild("value", "jabber:x:data")
                    if (value == null) {
                        value = el!!.addChild("value", "jabber:x:data")
                    }
                    return value
                }

                fun setValues(values: Collection<String>) {
                    for (child in el!!.getChildren()) {
                        if ("value".equals(child.getName()) && "jabber:x:data".equals(child.getNamespace())) {
                            el!!.removeChild(child)
                        }
                    }

                    for (value in values) {
                        el!!.addChild("value", "jabber:x:data").setContent(value)
                    }
                }

                fun getValues(): List<String> {
                    val values = ArrayList<String>()
                    for (child in el!!.getChildren()) {
                        if ("value".equals(child.getName()) && "jabber:x:data".equals(child.getNamespace())) {
                            values.add(child.getContent())
                        }
                    }
                    return values
                }

                fun getOptions(): List<Option> {
                    return Option.forField(el!!)
                }
            }

            inner class Cell(reported: Field, item: Element?) : Item(item, TYPE_RESULT_CELL) {
                var reported: Field = reported
            }

            protected fun mkField(el: Element): Field? {
                var viewType = -1

                val formType = responseElement!!.getAttribute("type")
                if (formType != null) {
                    var fieldType = el.getAttribute("type")
                    if (fieldType == null) fieldType = "text-single"

                    if (formType.equals("result") || fieldType.equals("fixed")) {
                        viewType = TYPE_RESULT_FIELD
                    } else if (formType.equals("form")) {
                        val validate = el.findChild("validate", "http://jabber.org/protocol/xdata-validate")
                        val datatype = if (validate == null) null else validate.getAttribute("datatype")
                        val range = if (validate == null) null else validate.findChild("range", "http://jabber.org/protocol/xdata-validate")
                        if (fieldType.equals("boolean")) {
                            if (fillableFieldCount == 1 && actionsAdapter!!.countProceed() < 1) {
                                viewType = TYPE_BUTTON_GRID_FIELD
                            } else {
                                viewType = TYPE_CHECKBOX_FIELD
                            }
                        } else if (range != null && range.getAttribute("min") != null && range.getAttribute("max") != null && (
                                "xs:integer".equals(datatype) || "xs:int".equals(datatype) || "xs:long".equals(datatype) || "xs:short".equals(datatype) || "xs:byte".equals(datatype) ||
                                    "xs:decimal".equals(datatype) || "xs:double".equals(datatype)
                                )
                        ) {
                            viewType = TYPE_SLIDER_FIELD
                        } else if (fieldType.equals("list-single")) {
                            if (fillableFieldCount == 1 && actionsAdapter!!.countProceed() < 1 && Option.forField(el).size < 50) {
                                viewType = TYPE_BUTTON_GRID_FIELD
                            } else if (Option.forField(el).size > 9) {
                                viewType = TYPE_SEARCH_LIST_FIELD
                            } else if (el.findChild("value", "jabber:x:data") == null || (validate != null && validate.findChild("open", "http://jabber.org/protocol/xdata-validate") != null)) {
                                viewType = TYPE_RADIO_EDIT_FIELD
                            } else {
                                viewType = TYPE_SPINNER_FIELD
                            }
                        } else if (fieldType.equals("list-multi")) {
                            viewType = TYPE_SEARCH_LIST_FIELD
                        } else {
                            viewType = TYPE_TEXT_FIELD
                        }
                    }

                    return Field(uk.xa0.tulkki.xmpp.forms.Field.parse(el), viewType)
                }

                return null
            }

            protected fun mkItem(el: Element, pos: Int): Item {
                var viewType = TYPE_ERROR

                if (response != null && response!!.getType() == Iq.Type.RESULT) {
                    if (el.getName().equals("note")) {
                        viewType = TYPE_NOTE
                    } else if (el.getNamespace().equals("jabber:x:oob")) {
                        viewType = TYPE_WEB
                    } else if (el.getName().equals("instructions") && el.getNamespace().equals("jabber:x:data")) {
                        viewType = TYPE_NOTE
                    } else if (el.getName().equals("field") && el.getNamespace().equals("jabber:x:data")) {
                        val field = mkField(el)
                        if (field != null) {
                            items.put(pos, field)
                            return field
                        }
                    }
                }

                val item = Item(el, viewType)
                items.put(pos, item)
                return item
            }

            /**
             * The view-free replacement for the old `ActionsAdapter`: a name/label list with the
             * questions the response builder asks of it. No `ArrayAdapter`, no `getView`, and no
             * `simple_list_item` row behind either.
             */
            inner class ActionsAdapter {
                private val entries = ArrayList<Pair<String, String>>()

                val count: Int get() = entries.size
                val isEmpty: Boolean get() = entries.isEmpty()

                fun clear() {
                    entries.clear()
                }

                fun add(entry: Pair<String, String>) {
                    entries.add(entry)
                }

                fun insert(entry: Pair<String, String>, index: Int) {
                    entries.add(index, entry)
                }

                fun getItem(i: Int): Pair<String, String> = entries[i]

                fun getPosition(name: String): Int = entries.indexOfFirst { it.first == name }

                fun countProceed(): Int = entries.count { it.first != "cancel" && it.first != "prev" }

                fun countExceptCancel(): Int = entries.count { it.first != "cancel" }

                fun clearProceed() {
                    val cancel = entries.firstOrNull { it.first == "cancel" }
                    val prev = entries.firstOrNull { it.first == "prev" }
                    entries.clear()
                    if (cancel != null) entries.add(cancel)
                    if (prev != null) entries.add(prev)
                }

                fun sort(comparator: Comparator<Pair<String, String>>) {
                    entries.sortWith(comparator)
                }
            }

            @JvmField
            val TYPE_ERROR = 1
            @JvmField
            val TYPE_NOTE = 2
            @JvmField
            val TYPE_WEB = 3
            @JvmField
            val TYPE_RESULT_FIELD = 4
            @JvmField
            val TYPE_TEXT_FIELD = 5
            @JvmField
            val TYPE_CHECKBOX_FIELD = 6
            @JvmField
            val TYPE_SPINNER_FIELD = 7
            @JvmField
            val TYPE_RADIO_EDIT_FIELD = 8
            @JvmField
            val TYPE_RESULT_CELL = 9
            @JvmField
            val TYPE_PROGRESSBAR = 10
            @JvmField
            val TYPE_SEARCH_LIST_FIELD = 11
            @JvmField
            val TYPE_ITEM_CARD = 12
            @JvmField
            val TYPE_BUTTON_GRID_FIELD = 13
            @JvmField
            val TYPE_SLIDER_FIELD = 14

            fun getView(): View? = page

            fun validate(): Boolean {
                val count = getItemCount()
                var isValid = true
                for (i in 0 until count) {
                    val oneIsValid = getItem(i)!!.validate()
                    isValid = isValid && oneIsValid
                }
                notifyDataSetChanged()
                return isValid
            }

            fun execute(): Boolean {
                return execute("execute")
            }

            fun execute(actionPosition: Int): Boolean {
                return execute(actionsAdapter!!.getItem(actionPosition).first ?: "")
            }

            @Synchronized
            open fun execute(action: String): Boolean {
                if (!"cancel".equals(action) && executing) {
                    loadingHasBeenLong = true
                    notifyDataSetChanged()
                    return false
                }
                if (!action.equals("cancel") && !action.equals("prev") && !validate()) return false

                if (response == null) return true
                val command = response!!.findChild("command", "http://jabber.org/protocol/commands")
                if (command == null) return true
                val status = command.getAttribute("status")
                if (status == null || (!status.equals("executing") && !action.equals("prev"))) return true

                if (actionToWebview != null && !action.equals("cancel") && Build.VERSION.SDK_INT >= 23) {
                    actionToWebview!!.postWebMessage(WebMessage("xmpp_xep0050/" + action), Uri.parse("*"))
                    return false
                }

                val packet = Iq(Iq.Type.SET)
                packet.setTo(response!!.getFrom())
                val c = packet.addChild("command", Namespace.COMMANDS)
                c.setAttribute("node", mNode)
                c.setAttribute("sessionid", command.getAttribute("sessionid"))

                val formType = if (responseElement == null) null else responseElement!!.getAttribute("type")
                if (!action.equals("cancel") &&
                    !action.equals("prev") &&
                    responseElement != null &&
                    responseElement!!.getName().equals("x") &&
                    responseElement!!.getNamespace().equals("jabber:x:data") &&
                    formType != null && formType.equals("form")
                ) {
                    val form = Data.parse(responseElement!!) ?: throw NullPointerException()
                    val actionList = form.getFieldByName("http://jabber.org/protocol/commands#actions")
                    if (actionList != null) {
                        actionList.setValue(action)
                        c.setAttribute("action", "execute")
                    }

                    if (mNode!!.equals("jabber:iq:register") && xmppConnectionService!!.isOnboarding() && form.getFieldByName("gateway-jid") != null) {
                        if (form.getValue("gateway-jid") == null) {
                            xmppConnectionService!!.getPreferences().edit().remove("onboarding_action").commit()
                        } else {
                            xmppConnectionService!!.getPreferences().edit().putString("onboarding_action", form.getValue("gateway-jid")).commit()
                        }
                    }

                    responseElement!!.setAttribute("type", "submit")
                    val rsm = responseElement!!.findChild("set", "http://jabber.org/protocol/rsm")
                    if (rsm != null) {
                        val max = Element("max", "http://jabber.org/protocol/rsm")
                        max.setContent("1000")
                        rsm.addChild(max)
                    }

                    c.addChild(responseElement!!)
                }

                if (c.getAttribute("action") == null) c.setAttribute("action", action)

                executing = true
                xmppConnectionService!!.sendIqPacket(getAccount()!!, packet, { iq -> updateWithResponse(iq) }, 120L)

                loading()
                return false
            }

            fun refresh() {
                synchronized(this) {
                    if (waitingForRefresh) notifyDataSetChanged()
                }
            }

            protected fun loading() {
                val view = getView()
                try {
                    loadingTimer.schedule(object : TimerTask() {
                        override fun run() {
                            val current = getView()
                            loading = true

                            try {
                                loadingTimer.schedule(object : TimerTask() {
                                    override fun run() {
                                        loadingHasBeenLong = true
                                        val target = view ?: current
                                        if (target != null) target.post { notifyDataSetChanged() }
                                    }
                                }, 3000)
                            } catch (e: IllegalStateException) {
                            }

                            val target = view ?: current
                            if (target != null) target.post { notifyDataSetChanged() }
                        }
                    }, 500)
                } catch (e: IllegalStateException) {
                }
            }

            fun inflateUi(context: Context, remover: Consumer<CommandSession>): View {
                this.remover = remover
                val renderer = CommandFormRenderers.renderer
                if (renderer == null) return View(context)
                val view = renderer.createView(context, this, null)
                page = view
                return view
            }
        }

        inner class MucConfigSession(xmppConnectionService: XmppConnectionService?) : CommandSession("Configure Channel", null, xmppConnectionService) {

            override fun updateWithResponseUiThread(iq: Iq) {
                val oldTimer = this.loadingTimer
                this.loadingTimer = Timer()
                oldTimer.cancel()
                this.executing = false
                this.loading = false
                this.loadingHasBeenLong = false
                this.responseElement = null
                this.fillableFieldCount = 0
                this.reported = null
                this.response = iq
                this.items.clear()
                this.actionsAdapter!!.clear()

                val query = iq.findChild("query", "http://jabber.org/protocol/muc#owner")
                if (iq.getType() == Iq.Type.RESULT && query != null) {
                    val form = Data.parse(query.findChild("x", "jabber:x:data"))
                        ?: throw NullPointerException()
                    val title = form.getTitle()
                    if (title != null) {
                        mTitle = title
                        this@ConversationPagerAdapter.notifyDataSetChanged()
                    }

                    this.responseElement = form
                    setupReported(form.findChild("reported", "jabber:x:data"))

                    if (actionsAdapter!!.countExceptCancel() < 1) {
                        actionsAdapter!!.add(Pair.create("save", "Save"))
                    }

                    if (actionsAdapter!!.getPosition("cancel") < 0) {
                        actionsAdapter!!.insert(Pair.create("cancel", "cancel"), 0)
                    }
                } else if (iq.getType() == Iq.Type.RESULT) {
                    expectingRemoval = true
                    removeSession(this)
                    return
                } else {
                    actionsAdapter!!.add(Pair.create("close", "close"))
                }

                notifyDataSetChanged()
            }

            @Synchronized
            override fun execute(action: String): Boolean {
                if ("cancel".equals(action)) {
                    val packet = Iq(Iq.Type.SET)
                    packet.setTo(response!!.getFrom())
                    val form = packet
                        .addChild("query", "http://jabber.org/protocol/muc#owner")
                        .addChild("x", "jabber:x:data")
                    form.setAttribute("type", "cancel")
                    xmppConnectionService!!.sendIqPacket(getAccount()!!, packet, null)
                    return true
                }

                if (!"save".equals(action)) return true

                val packet = Iq(Iq.Type.SET)
                packet.setTo(response!!.getFrom())

                val formType = if (responseElement == null) null else responseElement!!.getAttribute("type")
                if (responseElement != null &&
                    responseElement!!.getName().equals("x") &&
                    responseElement!!.getNamespace().equals("jabber:x:data") &&
                    formType != null && formType.equals("form")
                ) {
                    responseElement!!.setAttribute("type", "submit")
                    packet
                        .addChild("query", "http://jabber.org/protocol/muc#owner")
                        .addChild(responseElement!!)
                }

                executing = true
                xmppConnectionService!!.sendIqPacket(getAccount()!!, packet, { iq -> updateWithResponse(iq) }, 120L)

                loading()

                return false
            }
        }
    }

    class Thread internal constructor(private val threadId: String) {
        @JvmField
        var subject: Message? = null

        @JvmField
        var first: Message? = null

        @JvmField
        var last: Message? = null

        fun getThreadId(): String {
            return threadId
        }

        fun getSubject(): String? {
            if (subject == null) return null

            return subject!!.getSubject()
        }

        fun getDisplay(): String? {
            val s = getSubject()
            if (s != null) return s

            if (first != null) {
                return first!!.getBody()
            }

            return ""
        }

        fun getLastTime(): Long {
            if (last == null) return 0

            return last!!.getTimeSent()
        }
    }

    // Set the status message hidden timestamp
    fun hideStatusMessage() {
        val statusTs = this.getAttribute("statusTs")

        if (statusTs == null) {
            this.setAttribute("statusTs", (System.currentTimeMillis() - 1).toString())
        }

        this.setAttribute("statusHideTs", System.currentTimeMillis().toString())
    }

    fun statusMessageHidden(): Boolean {
        val statusTs = this.getAttribute("statusTs")
        val statusHideTs = this.getAttribute("statusHideTs")

        if (statusTs == null) {
            return false
        }
        if (statusHideTs == null) {
            return false
        }
        return try {
            statusHideTs.toLong() >= statusTs.toLong()
        } catch (e: NumberFormatException) {
            Log.w(Config.LOGTAG, "NumberFormatException parsing status timestamps for " + getJid() + ": statusTs=" + statusTs + ", statusHideTs=" + statusHideTs)
            false // Default to not hidden if timestamps are corrupt
        }
    }

    // Get the status message
    fun getSingleStatusMessage(contact: Contact): String? {
        val statusMessages = contact.getPresences().getStatusMessages()
        if (statusMessages.isEmpty()) {
            return null
        } else if (statusMessages.size == 1) {
            val message = statusMessages[0]
            val span: Spannable = SpannableString(message)
            if (EmoticonText.isOnlyEmoji(message)) {
                span.setSpan(
                    RelativeSizeSpan(2.0f),
                    0,
                    message.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            return span.toString()
        } else {
            val builder = StringBuilder()
            val s = statusMessages.size
            for (i in 0 until s) {
                builder.append(statusMessages[i])
                if (i < s - 1) {
                    builder.append("\n")
                }
            }
            return builder.toString()
        }
    }

    /**
     * Retrieves a consolidated single status message from the contact's presences.
     */
    private fun getSingleStatusMessageTrimmed(contact: Contact?): String? {
        if (contact == null || contact.getPresences() == null) {
            return null
        }
        val statusMessages = contact.getPresences().getStatusMessages()
        if (statusMessages == null || statusMessages.isEmpty()) {
            return null
        }
        for (msg in statusMessages) {
            if (!TextUtils.isEmpty(msg)) {
                return msg.trim() // Return the first non-empty, trimmed message
            }
        }
        return null // No non-empty status message found
    }

    /**
     * Call this method when the associated Contact object has been updated
     * with new presence/status information.
     *
     * @param updatedContact The Contact object, assumed to have the latest status messages.
     * @return true if the single status message text changed since the last call, false otherwise.
     */
    fun onContactUpdatedAndCheckStatusChange(updatedContact: Contact): Boolean {
        val currentStatusText = getSingleStatusMessage(updatedContact)

        // More concise check for change using Objects.equals (null-safe)
        if (!Objects.equals(lastKnownStatusText, currentStatusText)) {
            this.lastKnownStatusText = currentStatusText // Update for next comparison
            onContactStatusMessageChanged(currentStatusText, System.currentTimeMillis())
            return true // Text has changed
        }

        // Text has not changed (or both are null and thus equal)
        return false
    }

    /**
     * Gets the last known status text that was processed by onContactUpdatedAndCheckStatusChange.
     * Useful if you want the UI to display this value after a change is detected.
     */
    fun getLastProcessedStatusText(): String? {
        return lastKnownStatusText
    }

    /**
     * Call this method when a contact's XMPP status message
     * has been updated. This will update the status timestamp and ensure
     * that any previously hidden status message for this conversation is made visible again.
     *
     * @param newStatusMessageText The text of the new status message (can be used if you store it).
     * @param newStatusTimestamp The timestamp (in milliseconds) of when the new status was set/received.
     */
    fun onContactStatusMessageChanged(newStatusMessageText: String?, newStatusTimestamp: Long) {
        this.setAttribute("statusTs", newStatusTimestamp.toString())

        // To make statusMessageHidden() return false, we ensure statusHideTs is less than statusTs.
        // Set to an older value:
        // this.setAttribute("statusHideTs", "0");
        // or
        this.setAttribute("statusHideTs", (newStatusTimestamp - 1).toString())

        // If you also store the status message text directly in Conversation attributes:
        // if (newStatusMessageText != null) {
        //     this.setAttribute(Conversation.ATTRIBUTE_LAST_STATUS_MESSAGE, newStatusMessageText);
        // } else {
        //     this.removeAttribute(Conversation.ATTRIBUTE_LAST_STATUS_MESSAGE);
        // }

        Log.d(Config.LOGTAG, "Updated status timestamps for conversation " + getJid() +
            ". statusTs=" + newStatusTimestamp + ", statusHideTs is now removed/older.")
    }
}
