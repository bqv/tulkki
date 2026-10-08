package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.Log
import android.view.View
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import com.google.common.io.ByteSource
import com.google.common.primitives.Longs
import io.ipfs.cid.Cid
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URISyntaxException
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.regex.Pattern
import org.json.JSONException
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.app.http.URL
import uk.xa0.tulkki.crypto.OmemoConversation
import uk.xa0.tulkki.crypto.OmemoMessage
import uk.xa0.tulkki.crypto.axolotl.AxolotlService
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.data.text.XhtmlBody
import uk.xa0.tulkki.data.utils.BobCid
import uk.xa0.tulkki.data.utils.Counterparts
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.data.utils.EmoticonText
import uk.xa0.tulkki.data.utils.GeoUris
import uk.xa0.tulkki.data.utils.GetThumbnailForCid
import uk.xa0.tulkki.data.utils.InlineImageSpan
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.data.utils.Patterns
import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.data.view.ViewPorts
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.ReactionRef
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.StringUtils
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xml.XmlReader

/**
 * One row of `messages`: what was said, who said it, how it was encrypted, and everything the UI
 * hangs off it.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The twenty-four fields Java declared `protected` or `public` stay `@JvmField` fields**, with
 *    the explicit getters and setters written beside them. `Conversation` (Java, same package)
 *    reads `message.markable` and iterates `message.edits`, and `IndividualMessage` is a Java
 *    *subclass* that assigns `separator.body` - a Kotlin `var` would have generated
 *    `getBody()`/`setBody()` and taken the field away from all three. `markable` and `edits` have a
 *    measured Java creditor each; the rest keep the shape Java gave them rather than a new one.
 *    **Interop debt: twenty-four `@JvmField`s here, one more for `PLAIN_TEXT_SPAN` and six in
 *    [FileParams].**
 * 2. **The private cache fields are renamed with an `m` prefix where the Java name would generate an
 *    accessor that collides with a method.** `oob` would generate `getOob()` beside
 *    `getOob(): URI?`; `reactions`, `counterparts`, `fileParams`, `errorMessage`, `bodyLanguage`,
 *    `translatedBody`, `translationLang`, `translationState`, `retractId`, `ephemeralTimer`,
 *    `expireAt`, `ephemeralIWantOut`, `storyReference`, `expanded`, `isGeoUri`, `isEmojisOnly`,
 *    `treatAsDownloadable` and `readByMarkers` all collide the same way. Every one of them was
 *    **private** in Java, so no caller can see the rename; `mNextMessage`/`mPreviousMessage` already
 *    carried it. `isGeoUri` is the sharpest: a Kotlin `Boolean` property spelled `isGeoUri` emits
 *    `isGeoUri()`, the exact JVM name of the method.
 * 3. **Every reference parameter and return that Java left unannotated is nullable, and each one was
 *    measured against its callers** rather than assumed. The parameters are `setBody(String?)`
 *    (`Conversation:1975` passes `(String) null`, `ConversationFragment:1492` a ternary that can be
 *    `null`), `setSubject(null)` (`Conversation:1976`), `setRelativeFilePath(null)`
 *    (`FileBackend:1071`, `:1242`), `setFileParams(null)` (`Conversation:1979`,
 *    `ConversationFragment:3088`, `HttpDownloadConnection:353`, `MessageParser:1324`),
 *    `setTransferable(null)` (`BobTransfer:176`, `:188`, five more), `setTranslatedBody(null)` and
 *    `setTranslationLang(null)` (`OutgoingTranslation:882-883`, `MessageParser:1372-1373`),
 *    `setErrorMessage(null)` (`JingleFileTransferConnection:108`), `setFingerprint(null)`
 *    (`JingleFileTransferConnection:372`), `setTrueCounterpart(user.getRealJid())` and
 *    `setCounterpart(user.getFullJid())` (`ConversationFragment:6573`, `:6597-6598` - both Kotlin
 *    accessors answering `Jid?`), `setMucUser(null)` (`MessageParser:1224` feeds it
 *    `findUserByFullJid`, which answers `User?`), `setInReplyTo(null)`
 *    (`DatabaseBackend:967`, `:1065` hand it a `Pair`'s value), and `setOccupantId(String?)`
    *    (`NotificationService:475`). The returns are every accessor whose field Java left `null`:
 *    `getCounterpart`, `getTrueCounterpart`, `getRawBody`, `getSubject`, `getQuoteableBody`,
 *    `getEncryptedBody`, `getRelativeFilePath`, `getRemoteMsgId`, `getServerMsgId`,
 *    `getErrorMessage`, `getBodyLanguage`, `getTranslatedBody`, `getTranslationLang`, `getRetractId`,
 *    `getFingerprint`, `getFileUrl`, `getInReplyTo`, `getTransferable`, `getOob`, `wholeIsKnownURI`,
 *    `getCounterparts`, `getCommands`, `getHtml`, `getModerated`, `getThread`, `getReply`,
 *    `getReactionsEl`, `getContact`, `getStoryReference`, `getMimeType`, `getConversationUuid`,
 *    `getOccupantId`, `getUuid` (inherited), `getOmemoConversation` and `replyId`. **`getConversation` is nullable for a
 *    measured reason**: `IndividualMessage.fromSnapshot(snapshot, null)` stores the `null` it is
 *    handed - the constructor Java wrote never checks it - and `SnapshotTest` pins "a null one is
 *    passed straight through" with `assertNull`. The master-caller audit is in the commit body.
 * 4. **`getAvatarName()` is the one member that cannot answer Java's own value**, exactly as
 *    [Room]'s port recorded: Java returned `DisplayNames.getMessageDisplayName`, which answers
 *    `null` on the sent-to-a-room path where the sender has no nick, and [Avatarable]'s Kotlin
 *    member is non-null. The null becomes `Strings.nullToEmpty`'s empty string; the only reader is
 *    an avatar content description.
 * 5. **The sites where Java dereferenced a nullable value keep Java's dereference.** `similar`'s
 *    file-URL comparison is `bodyString!! == otherBody`, and every `List.remove(null)` (a no-op on a
 *    list that never holds null) becomes a `?.let` guard, because Kotlin's
 *    `MutableList<Element>.remove` will not take an `Element?`.
 * 6. **`similar(Message)` is `public` where Java's was package-private.** `Conversation:1587` is a
 *    Java caller in the same package, and Kotlin's `internal` would mangle the JVM name to
 *    `similar$data` and break javac - the same measurement [MucOptions] recorded for
 *    `createNameFromParticipants`.
 * 7. **The traps this row closes**: `input.split(regex)` becomes Java's own
 *    `Pattern.compile(...).split(...)` at all four sites (Kotlin's single-`String` overload is a
 *    *literal* split and `Regex.split` keeps trailing empties Java drops); `this.body += append`
 *    keeps Java's string concatenation, which renders a null body as the four letters `null`;
 *    `Message.parsePrivateInt` is a file-private function rather than a companion member, so the
 *    nested [FileParams] reaches it without a synthetic accessor; `readByMarkerSet` is a
 *    `MutableSet` behind a `Set` parameter, because `ReadByMarker.fromJsonString` answers Kotlin's
 *    read-only `Set` and Kotlin's `Set` is not a `MutableSet`.
 * 8. **Debt instruments: thirty-one `@JvmField`s (twenty-four on the class, one on the companion,
 *    six in [FileParams]); six `@JvmStatic`s; seventy `const val`s; two `@Throws`; twenty-nine
 *    `@Synchronized`s; no `@JvmOverloads`.**
 *    The statics are the six Java callers measure - `fromCursor` (`DatabaseBackend`,
 *    `MessageLookupStore`), `createStatusMessage` and `createLoadMoreMessage`
 *    (`ConversationFragment`), `configurePrivateMessage(Message)` (`ConversationFragment`,
 *    `MessageAdapter`, `XmppActivity`'s host), `configurePrivateMessage(Message, Jid)`
 *    (`MucDetailsContextMenuHelper`) and `configurePrivateFileMessage` (`DataStaticsHost`).
 *    `@Throws` is on `fromCursor` (`DatabaseBackend:1374`, `:1394` catch `IOException` around it)
 *    and on [FileParams.setCids] (`FileBackend:2236` catches it in a multi-catch).
 *
 * Nothing in the tree extends `Message` except `IndividualMessage`, whose three overrides
 * (`next`, `prev`, `isValidInSession`) are the only `open` members here.
 */
open class Message protected constructor(
    private val conversation: Conversational?,
) : AbstractEntity(), Avatarable, OmemoMessage, MessageRef {

    @JvmField
    var markable = false

    @JvmField
    protected var conversationUuid: String? = null

    @JvmField
    protected var counterpart: Jid? = null

    @JvmField
    protected var trueCounterpart: Jid? = null

    @JvmField
    protected var occupantId: String? = null

    @JvmField
    protected var body: String? = null

    @JvmField
    protected var subject: String? = null

    @JvmField
    protected var encryptedBody: String? = null

    @JvmField
    protected var timeSent: Long = 0

    @JvmField
    protected var timeReceived: Long = 0

    @JvmField
    protected var encryption: Int = 0

    @JvmField
    protected var status: Int = 0

    @JvmField
    protected var type: Int = 0

    @JvmField
    protected var deleted = false

    @JvmField
    protected var carbon = false

    private var mOob = false

    @JvmField
    protected var payloads: MutableList<Element> = ArrayList()

    // Tulkki: `public` where Java had `protected`. Kotlin cannot read a Java `protected` field of
    // another class, and the Kotlin port of `Conversation` reads this list in
    // `findSentMessageWithUuidOrRemoteId` - the same measurement `Conversation.messages` records.
    @JvmField
    var edits: MutableList<Edit> = ArrayList()

    @JvmField
    protected var relativeFilePath: String? = null

    @JvmField
    protected var read = true

    @JvmField
    protected var notificationDismissed = false

    @JvmField
    protected var remoteMsgId: String? = null

    private var mBodyLanguage: String? = null

    // Tulkki: the translated rendering of this message. The original always stays in body.
    private var mTranslatedBody: String? = null

    private var mTranslationLang: String? = null

    private var mTranslationState = TRANSLATION_NONE

    @JvmField
    protected var serverMsgId: String? = null

    // Tulkki: the field is the shared type itself (`uk.xa0.tulkki.libs.Transferable`, 2026-10-08). An
    // island implementor that carries only that type has to be storable here, and it is, so no cast
    // stands on the path.
    @JvmField
    protected var transferable: Transferable? = null

    private var mNextMessage: Message? = null

    private var mPreviousMessage: Message? = null

    private var axolotlFingerprint: String? = null

    private var mErrorMessage: String? = null

    private var readByMarkerSet: MutableSet<ReadByMarker> = CopyOnWriteArraySet()

    @JvmField
    protected var mInReplyTo: Message? = null

    private var reactionList: Collection<Reaction> = emptyList()

    private var mIsGeoUri: Boolean? = null

    private var wholeIsKnownURI: Uri? = null

    private var mIsEmojisOnly: Boolean? = null

    private var mTreatAsDownloadable: Boolean? = null

    private var mFileParams: FileParams? = null

    private var mCounterparts: MutableList<MucOptions.User>? = null

    private var mucUserRef: WeakReference<MucOptions.User>? = null

    private var mRetractId: String? = null

    private var mEphemeralTimer = 0

    private var mExpireAt = 0L

    private var mEphemeralIWantOut = false

    private var mStoryReference: androidx.core.util.Pair<Jid, String>? = null

    private var mExpanded = false

    public constructor(conversation: Conversational, body: String?, encryption: Int) : this(
        conversation,
        body,
        encryption,
        STATUS_UNSEND,
    )

    public constructor(
        conversation: Conversational,
        body: String?,
        encryption: Int,
        status: Int,
    ) : this(
        conversation,
        java.util.UUID.randomUUID().toString(),
        conversation.getUuid(),
        conversation.getJid()?.asBareJid(),
        null,
        body,
        System.currentTimeMillis(),
        encryption,
        status,
        TYPE_TEXT,
        false,
        null,
        null,
        null,
        null,
        true,
        null,
        false,
        null,
        null,
        false,
        false,
        null,
        null,
        emptyList(),
        System.currentTimeMillis(),
        null,
        null,
        null,
        null,
        conversation.getEphemeralTimer(),
        0L,
    )

    public constructor(conversation: Conversation, status: Int, type: Int, remoteMsgId: String?) : this(
        conversation,
        java.util.UUID.randomUUID().toString(),
        conversation.getUuid(),
        conversation.getJid()?.asBareJid(),
        null,
        null,
        System.currentTimeMillis(),
        Message.ENCRYPTION_NONE,
        status,
        type,
        false,
        remoteMsgId,
        null,
        null,
        null,
        true,
        null,
        false,
        null,
        null,
        false,
        false,
        null,
        null,
        emptyList(),
        System.currentTimeMillis(),
        null,
        null,
        null,
        null,
        conversation.getEphemeralTimer(),
        0L,
    )

    protected constructor(
        conversation: Conversational?,
        uuid: String?,
        conversationUUid: String?,
        counterpart: Jid?,
        trueCounterpart: Jid?,
        body: String?,
        timeSent: Long,
        encryption: Int,
        status: Int,
        type: Int,
        carbon: Boolean,
        remoteMsgId: String?,
        relativeFilePath: String?,
        serverMsgId: String?,
        fingerprint: String?,
        read: Boolean,
        edited: String?,
        oob: Boolean,
        errorMessage: String?,
        readByMarkers: Set<ReadByMarker>?,
        markable: Boolean,
        deleted: Boolean,
        bodyLanguage: String?,
        occupantId: String?,
        reactions: Collection<Reaction>,
        timeReceived: Long,
        subject: String?,
        fileParams: String?,
        payloads: MutableList<Element>?,
        retractId: String?,
        ephemeralTimer: Int,
        expireAt: Long,
    ) : this(conversation) {
        this.uuid = uuid
        this.conversationUuid = conversationUUid
        this.counterpart = counterpart
        this.trueCounterpart = trueCounterpart
        this.body = body ?: ""
        this.timeSent = timeSent
        this.encryption = encryption
        this.status = status
        this.type = type
        this.carbon = carbon
        this.remoteMsgId = remoteMsgId
        this.relativeFilePath = relativeFilePath
        this.serverMsgId = serverMsgId
        this.axolotlFingerprint = fingerprint
        this.read = read
        this.edits = Edit.fromJson(edited)
        this.mOob = oob
        this.mErrorMessage = errorMessage
        // `ReadByMarker.fromJsonString` answers Kotlin's read-only `Set`, so the field keeps the
        // mutable identity Java handed it and only a genuinely read-only argument is copied.
        @Suppress("UNCHECKED_CAST")
        this.readByMarkerSet = (readByMarkers as? MutableSet<ReadByMarker>) ?: CopyOnWriteArraySet(readByMarkers ?: emptySet())
        this.markable = markable
        this.deleted = deleted
        this.mBodyLanguage = bodyLanguage
        this.occupantId = occupantId
        this.reactionList = reactions
        this.timeReceived = timeReceived
        this.subject = subject
        if (payloads != null) this.payloads = payloads
        if (fileParams != null && getSims().isEmpty()) this.mFileParams = FileParams(fileParams)
        this.mRetractId = retractId
        this.mEphemeralTimer = ephemeralTimer
        this.mExpireAt = expireAt

        var resolvedType = type
        if (resolvedType == TYPE_TEXT || resolvedType == TYPE_PRIVATE) {
            val fp = getFileParams()
            val url = fp.url
            if (url != null && url.startsWith("xmpp:")) {
                try {
                    val uri = Uri.parse(url)
                    val schemeSpecificPart = uri.getSchemeSpecificPart()!!
                    val queryStart = schemeSpecificPart.indexOf('?')
                    if (queryStart != -1) {
                        val queryString = schemeSpecificPart.substring(queryStart + 1)
                        val params = Pattern.compile(";").split(queryString)
                        for (param in params) {
                            val keyValue = Pattern.compile("=").split(param, 2)
                            if (keyValue.size == 2 &&
                                keyValue[0] == "node" &&
                                keyValue[1] == "urn:xmpp:pubsub-social-feed:stories:0"
                            ) {
                                resolvedType = TYPE_STORY
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // not a story URI
                }
            }
        }
        this.type = resolvedType
    }

    fun getStoryReference(): androidx.core.util.Pair<Jid, String>? {
        val cached = mStoryReference
        if (cached != null) {
            return cached
        }
        val fp = getFileParams()
        val url = fp.url
        if (url == null || !url.startsWith("xmpp:")) {
            return null
        }
        try {
            val uri = Uri.parse(url)
            val schemeSpecificPart = uri.getSchemeSpecificPart()!!
            val queryStart = schemeSpecificPart.indexOf('?')
            if (queryStart == -1) {
                return null
            }
            val jidString = schemeSpecificPart.substring(0, queryStart)
            val jid = Jid.of(jidString)
            val queryString = schemeSpecificPart.substring(queryStart + 1)
            var item: String? = null
            val params = Pattern.compile(";").split(queryString)
            for (param in params) {
                val keyValue = Pattern.compile("=").split(param, 2)
                if (keyValue.size == 2 && keyValue[0] == "item") {
                    item = keyValue[1]
                    break
                }
            }
            val found = item
            if (found != null) {
                mStoryReference = androidx.core.util.Pair(jid, found)
                return mStoryReference
            }
        } catch (e: Exception) {
            // Not a valid story URI
        }
        return null
    }

    override fun getContentValues(): ContentValues {
        val fp = mFileParams
        val values = ContentValues()
        values.put(AbstractEntity.UUID, uuid)
        values.put(CONVERSATION, conversationUuid)
        val cp = counterpart
        if (cp == null) {
            values.putNull(COUNTERPART)
        } else {
            values.put(COUNTERPART, cp.toString())
        }
        val tcp = trueCounterpart
        if (tcp == null) {
            values.putNull(TRUE_COUNTERPART)
        } else {
            values.put(TRUE_COUNTERPART, tcp.toString())
        }
        val storedBody = body
        if (storedBody == null) {
            values.putNull(BODY)
        } else {
            values.put(
                BODY,
                if (storedBody.length > Config.MAX_STORAGE_MESSAGE_CHARS) {
                    storedBody.substring(0, Config.MAX_STORAGE_MESSAGE_CHARS)
                } else {
                    storedBody
                },
            )
        }
        values.put(TIME_SENT, timeSent)
        values.put(ENCRYPTION, encryption)
        values.put(STATUS, status)
        values.put(TYPE, type)
        values.put(CARBON, if (carbon) 1 else 0)
        values.put(REMOTE_MSG_ID, remoteMsgId)
        values.put(RELATIVE_FILE_PATH, relativeFilePath)
        values.put(SERVER_MSG_ID, serverMsgId)
        values.put(FINGERPRINT, axolotlFingerprint)
        values.put(EPHEMERAL_TIMER, mEphemeralTimer)
        values.put(EXPIRE_AT, mExpireAt)
        values.put(READ, if (read) 1 else 0)
        try {
            values.put(EDITED, Edit.toJson(edits))
        } catch (e: JSONException) {
            Log.e(Config.LOGTAG, "error persisting json for edits", e)
        }
        values.put(OOB, if (mOob) 1 else 0)
        values.put(ERROR_MESSAGE, mErrorMessage)
        values.put(READ_BY_MARKERS, ReadByMarker.toJson(readByMarkerSet).toString())
        values.put(MARKABLE, if (markable) 1 else 0)
        values.put(DELETED, if (deleted) 1 else 0)
        values.put(BODY_LANGUAGE, mBodyLanguage)
        values.put(TRANSLATED_BODY, mTranslatedBody)
        values.put(TRANSLATION_LANG, mTranslationLang)
        values.put(TRANSLATION_STATE, mTranslationState)
        values.put(OCCUPANT_ID, occupantId)
        values.put(REACTIONS, Reaction.toString(reactionList))
        values.put(SUBJECT, subject)
        values.put(FILE_PARAMS, fp?.toString())
        if (fp != null && !fp.isEmpty()) {
            val sims = getSims()
            if (sims.isEmpty()) {
                addPayload(fp.toSims())
            } else {
                sims[0].replaceChildren(fp.toSims().getChildren())
            }
        }
        val payloadString: String? =
            if (payloads.size < 1) null else payloads.joinToString(separator = "") { it.toString() }
        values.put(PAYLOADS, payloadString)
        values.put(OCCUPANTID, occupantId)
        values.put(TIME_RECEIVED, timeReceived)
        values.put(NOTIFICATION_DISMISSED, if (notificationDismissed) 1 else 0)
        values.put(RETRACT_ID, mRetractId)
        values.put(EPHEMERAL_TIMER, mEphemeralTimer)
        values.put(EXPIRE_AT, mExpireAt)
        return values
    }

    fun replyId(): String? {
        val conv = conversation ?: throw NullPointerException()
        if (conv.getMode() == Conversational.MODE_MULTI && !isPrivateMessage()) return getServerMsgId()
        val remote = getRemoteMsgId()
        if (remote == null && getStatus() > STATUS_RECEIVED) return getUuid()
        return remote
    }

    override fun reply(): Message {
        val m = Message(conversation ?: throw NullPointerException(), "", ENCRYPTION_NONE)
        m.setThread(getThread())

        m.updateReplyTo(this, null)
        return m
    }

    @Synchronized
    fun clearReplyReact() {
        mInReplyTo = null
        getReactionsEl()?.let { payloads.remove(it) }
        getReply()?.let { payloads.remove(it) }
        clearFallbacks("urn:xmpp:reply:0", "urn:xmpp:reactions:0")
    }

    fun updateReplyTo(replyTo: Message, body: Spanned?) {
        clearReplyReact()

        val quoted = body ?: SpannableStringBuilder(getBody(false))
        setBody(QuoteHelper.quote(MessageUtils.prepareQuote(replyTo)) + "\n\n")

        val replyId = replyTo.replyId() ?: return

        addPayload(
            Element("reply", "urn:xmpp:reply:0")
                .setAttribute("to", replyTo.getCounterpart())
                .setAttribute("id", replyId),
        )
        val fallback = Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reply:0")
        val current = this.body ?: ""
        fallback.addChild("body", "urn:xmpp:fallback:0")
            .setAttribute("start", "0")
            .setAttribute("end", "" + current.codePointCount(0, current.length))
        addPayload(fallback)

        appendBody(quoted)
        setInReplyTo(replyTo)
    }

    fun updateReaction(reactTo: Message, emoji: String) {
        var emojis: MutableSet<String> = HashSet()
        val conv = conversation
        if (conv is Conversation) emojis = conv.findReactionsTo(reactTo.replyId(), null).toMutableSet()
        emojis.remove(getBody(true))
        emojis.add(emoji)

        updateReplyTo(reactTo, SpannableStringBuilder(emoji))
        val fallback = Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reactions:0")
        fallback.addChild("body", "urn:xmpp:fallback:0")
        addPayload(fallback)
        val reactions = Element("reactions", "urn:xmpp:reactions:0").setAttribute("id", reactTo.replyId())
        for (oneEmoji in emojis) {
            reactions.addChild("reaction", "urn:xmpp:reactions:0").setContent(oneEmoji)
        }
        addPayload(reactions)
    }

    @Synchronized
    fun getReply(): Element? {
        for (el in payloads) {
            if (el.getName().equals("reply") && el.getNamespace().equals("urn:xmpp:reply:0")) {
                return el
            }
        }

        return null
    }

    @Synchronized
    fun isAttention(): Boolean {
        for (el in payloads) {
            if (el.getName().equals("attention") && el.getNamespace().equals("urn:xmpp:attention:0")) {
                return true
            }
        }

        return false
    }

    fun getConversationUuid(): String? = conversationUuid

    override fun getConversation(): Conversational? = conversation

    /** The crypto island's view of the conversation this message belongs to (pair 5, D6). */
    override fun getOmemoConversation(): OmemoConversation? = conversation as Conversation?

    /** The URL the OOB/reference metadata carries, which is not the body of a file message. */
    override fun getFileUrl(): String? = getFileParams().url

    override fun getCounterpart(): Jid? = counterpart

    override fun setCounterpart(counterpart: Jid?) {
        this.counterpart = counterpart
    }

    override fun getContact(): Contact? {
        val conv = conversation ?: throw NullPointerException()
        if (conv.getMode() == Conversational.MODE_SINGLE) {
            val tcp = trueCounterpart
            if (tcp != null) {
                // port-5: `Conversational.getAccount()` is nullable and always was on `Conversation`;
                // without an account there is no roster to resolve the counterpart through, so the
                // message answers `null` where Java's dereference would have thrown.
                val account = conv.getAccount() ?: return null
                return account.getRoster().getContact(tcp)
            }

            return conv.getContact()
        } else {
            val tcp = trueCounterpart
            if (tcp == null) {
                return null
            }
            val account = conv.getAccount() ?: return null
            return account.getRoster().getContactFromContactList(tcp)
        }
    }

    fun getQuoteableBody(): String? {
        if (body == null) return null

        val stripped = bodyMinusFallbacks("http://jabber.org/protocol/address").first
        return stripped.toString()
    }

    override fun getRawBody(): String? = body

    private fun bodyMinusFallbacks(vararg fallbackNames: String): android.util.Pair<StringBuilder, Boolean> {
        val stripped = StringBuilder(this.body ?: "")

        val fallbacks = getFallbacks(*fallbackNames)
        val spans = ArrayList<android.util.Pair<Int, Int>>()
        for (fallback in fallbacks) {
            for (span in fallback.getChildren()) {
                if (!span.getName().equals("body") && !span.getNamespace().equals("urn:xmpp:fallback:0")) continue
                val start = span.getAttribute("start")
                val end = span.getAttribute("end")
                if (start == null || end == null) {
                    return android.util.Pair(StringBuilder(""), true)
                }
                spans.add(
                    android.util.Pair(
                        parseInt(start),
                        parseInt(end),
                    ),
                )
            }
        }
        // Do them in reverse order so that span deletions don't affect the indexes of other spans
        spans.sortWith(Comparator { x, y -> y.first.compareTo(x.first) })
        try {
            for (span in spans) {
                stripped.delete(
                    stripped.offsetByCodePoints(0, span.first),
                    stripped.offsetByCodePoints(0, span.second),
                )
            }
        } catch (e: IndexOutOfBoundsException) {
            spans.clear()
        }

        return android.util.Pair(stripped, spans.isNotEmpty())
    }

    override fun getBody(): String = getBody(false)

    fun getBody(removeQuoteFallbacks: Boolean): String {
        val stored = body ?: return ""

        val fallbacksToRemove = ArrayList<String>()
        fallbacksToRemove.add("http://jabber.org/protocol/address")
        val oob = getOob()
        if (oob != null || isGeoUri()) fallbacksToRemove.add(Namespace.OOB)
        if (removeQuoteFallbacks) fallbacksToRemove.add("urn:xmpp:reply:0")
        val result = bodyMinusFallbacks(*fallbacksToRemove.toTypedArray())
        val stripped = result.first

        val aesgcm = MessageUtils.aesgcmDownloadable(stripped.toString())
        return if (!result.second && aesgcm != null) {
            stripped.toString().replace(aesgcm, "")
        } else if (!result.second && oob != null) {
            stripped.toString().replace(oob.toString(), "")
        } else if (!result.second && isGeoUri()) {
            ""
        } else {
            stripped.toString()
        }
    }

    @Synchronized
    override fun clearFallbacks(vararg includeFor: String) {
        payloads.removeAll(getFallbacks(*includeFor))
    }

    @Synchronized
    override fun setHtml(html: Element?) {
        val oldHtml = getHtml(true)
        if (oldHtml != null) payloads.remove(oldHtml)
        if (html != null) addPayload(html)
    }

    @Synchronized
    fun getOrMakeHtml(): Element {
        var html = getHtml()
        if (html != null) return html
        html = Element("html", "http://jabber.org/protocol/xhtml-im")
        val htmlBody = html.addChild("body", "http://www.w3.org/1999/xhtml")
        // 3.7 pair 2: the markup writer is `:ui`'s, reached through the port `:data` declares.
        XhtmlBody.append(htmlBody, SpannableStringBuilder(getBody(true)))
        addPayload(html)
        return htmlBody
    }

    @Synchronized
    fun setBody(span: Spanned?) {
        // Don't bother removing, we'll edit below
        setBodyPreserveXHTML(span?.toString())
        if (span == null || XhtmlBody.isPlainText(span)) {
            getHtml(true)?.let { payloads.remove(it) }
        } else {
            val htmlBody = getOrMakeHtml()
            htmlBody.clearChildren()
            XhtmlBody.append(htmlBody, span)
        }
    }

    @Synchronized
    private fun setBodyPreserveXHTML(body: String?) {
        this.body = body
        mIsGeoUri = null
        wholeIsKnownURI = null
        mIsEmojisOnly = null
        mTreatAsDownloadable = null
    }

    @Synchronized
    override fun setBody(body: String?) {
        setBodyPreserveXHTML(body)
        getHtml(true)?.let { payloads.remove(it) }
    }

    @Synchronized
    fun appendBody(append: Spanned) {
        if (!XhtmlBody.isPlainText(append) || getHtml() != null) {
            val htmlBody = getOrMakeHtml()
            XhtmlBody.append(htmlBody, append)
        }
        appendBody(append.toString())
    }

    @Synchronized
    override fun appendBody(append: String) {
        // Java's `+=` renders a null body as the four letters `null`; that spelling is kept.
        this.body = (this.body ?: "null") + append
        mIsGeoUri = null
        wholeIsKnownURI = null
        mIsEmojisOnly = null
        mTreatAsDownloadable = null
    }

    override fun getSubject(): String? = subject

    @Synchronized
    override fun setSubject(subject: String?) {
        this.subject = subject
    }

    override fun getThread(): Element? {
        for (el in payloads) {
            if ("thread" == el.getName() && "jabber:client" == el.getNamespace()) {
                return el
            }
        }

        return null
    }

    override fun setThread(thread: Element?) {
        payloads.removeAll { el -> el.getName().equals("thread") && el.getNamespace().equals("jabber:client") }
        addPayload(thread)
    }

    override fun setOccupantId(id: String?) {
        occupantId = id
    }

    override fun getOccupantId(): String? = occupantId

    fun setMucUser(user: MucOptions.User?) {
        mucUserRef = WeakReference(user)
        if (user != null && user.getOccupantId() != null) setOccupantId(user.getOccupantId())
    }

    fun sameMucUser(otherMessage: Message): Boolean {
        val thisUser = mucUserRef?.get()
        val otherUser = otherMessage.mucUserRef?.get()
        return (thisUser != null && thisUser === otherUser) ||
            (getOccupantId() != null && getOccupantId() == otherMessage.getOccupantId())
    }

    override fun getErrorMessage(): String? = mErrorMessage

    override fun setErrorMessage(message: String?): Boolean {
        val changed = (message != null && message != mErrorMessage) ||
            (message == null && mErrorMessage != null)
        mErrorMessage = message
        return changed
    }

    fun getTimeReceived(): Long = timeReceived

    override fun getTimeSent(): Long = timeSent

    override fun getEncryption(): Int = encryption

    override fun setEncryption(encryption: Int) {
        this.encryption = encryption
    }

    override fun getStatus(): Int = status

    override fun setStatus(status: Int) {
        this.status = status
    }

    fun getRelativeFilePath(): String? = relativeFilePath

    override fun setRelativeFilePath(path: String?) {
        this.relativeFilePath = path
    }

    override fun getRemoteMsgId(): String? = remoteMsgId

    override fun setRemoteMsgId(id: String?) {
        this.remoteMsgId = id
    }

    override fun getServerMsgId(): String? = serverMsgId

    override fun setServerMsgId(id: String?) {
        this.serverMsgId = id
    }

    override fun isRead(): Boolean = read

    fun notificationWasDismissed(): Boolean = notificationDismissed

    override fun isDeleted(): Boolean = deleted

    fun getModerated(): Element? {
        for (el in payloads) {
            if (el.getName().equals("moderated") && el.getNamespace().equals("urn:xmpp:message-moderate:0")) {
                return el
            }
        }

        return null
    }

    override fun setDeleted(deleted: Boolean) {
        this.deleted = deleted
    }

    override fun markRead() {
        read = true
    }

    override fun markUnread() {
        read = false
    }

    override fun markNotificationDismissed() {
        notificationDismissed = true
    }

    override fun setTime(time: Long) {
        timeSent = time
    }

    fun setTimeReceived(time: Long) {
        timeReceived = time
    }

    override fun getEncryptedBody(): String? = encryptedBody

    override fun setEncryptedBody(body: String?) {
        encryptedBody = body
    }

    override fun getType(): Int = type

    override fun setType(type: Int) {
        this.type = type
    }

    override fun isCarbon(): Boolean = carbon

    override fun setCarbon(carbon: Boolean) {
        this.carbon = carbon
    }

    override fun putEdited(edited: String?, serverMsgId: String?) {
        val edit = Edit(edited, serverMsgId)
        if (edits.size < 128 && !edits.contains(edit)) {
            edits.add(edit)
        }
    }

    fun getBodyLanguage(): String? = mBodyLanguage

    override fun setBodyLanguage(language: String?) {
        mBodyLanguage = language
    }

    fun getTranslatedBody(): String? = mTranslatedBody

    override fun setTranslatedBody(translatedBody: String?) {
        mTranslatedBody = translatedBody
    }

    fun getTranslationLang(): String? = mTranslationLang

    override fun setTranslationLang(translationLang: String?) {
        mTranslationLang = translationLang
    }

    fun getTranslationState(): Int = mTranslationState

    override fun setTranslationState(translationState: Int) {
        mTranslationState = translationState
    }

    override fun edited(): Boolean = edits.isNotEmpty()

    override fun setTrueCounterpart(trueCounterpart: Jid?) {
        this.trueCounterpart = trueCounterpart
    }

    override fun getTrueCounterpart(): Jid? = trueCounterpart

    // Tulkki: the declared type is the shared one (`uk.xa0.tulkki.libs.Transferable`, 2026-10-08),
    // which is what the field holds and what `MessageRef.getTransferable()` answers. Every reader in
    // `:data`, `:app` and `:ui` reaches `start()`, `getStatus()`, `getProgress()` and `getFileSize()`
    // through it unchanged.
    override fun getTransferable(): Transferable? = transferable

    /**
     * Tulkki: the only setter, and it carries the `@Override` the deleted ref overload used to hold,
     * because it *is* `MessageRef.setTransferable`. It used to exist twice - a model-typed one that
     * assigned and a ref-typed one that narrowed with a cast before delegating - and 2026-10-08 left
     * a single type, so there is no overload left to pick between and `setTransferable(null)` still
     * resolves, now unambiguously.
     */
    @Synchronized
    override fun setTransferable(transferable: Transferable?) {
        this.transferable = transferable
    }

    override fun getRetractId(): String? = mRetractId

    override fun setRetractId(id: String?) {
        mRetractId = id
    }

    override fun getEphemeralTimer(): Int = mEphemeralTimer

    override fun setEphemeralTimer(ephemeralTimer: Int) {
        mEphemeralTimer = ephemeralTimer
    }

    override fun getExpireAt(): Long = mExpireAt

    override fun setExpireAt(expireAt: Long) {
        mExpireAt = expireAt
    }

    override fun isEphemeral(): Boolean = mEphemeralTimer > 0

    override fun isEphemeralIWantOut(): Boolean = mEphemeralIWantOut

    fun setEphemeralIWantOut(ephemeralIWantOut: Boolean) {
        mEphemeralIWantOut = ephemeralIWantOut
    }

    fun addReadByMarker(readByMarker: ReadByMarker): Boolean {
        val realJid = readByMarker.getRealJid()
        if (realJid != null) {
            if (realJid.asBareJid() == trueCounterpart) {
                return false
            }
        } else {
            val fullJid = readByMarker.getFullJid()
            if (fullJid != null) {
                if (fullJid == counterpart) {
                    return false
                }
            }
        }
        if (readByMarkerSet.add(readByMarker)) {
            if (readByMarker.getRealJid() != null && readByMarker.getFullJid() != null) {
                val iterator = readByMarkerSet.iterator()
                while (iterator.hasNext()) {
                    val marker = iterator.next()
                    if (marker.getRealJid() == null && readByMarker.getFullJid() == marker.getFullJid()) {
                        iterator.remove()
                    }
                }
            }
            return true
        } else {
            return false
        }
    }

    fun getReadByMarkers(): Set<ReadByMarker> = ImmutableSet.copyOf(readByMarkerSet)

    override fun getReadyByTrue(): Set<Jid> =
        ImmutableSet.copyOf(readByMarkerSet.mapNotNull { it.getRealJid() })

    fun setInReplyTo(m: Message?) {
        mInReplyTo = m
    }

    override fun getInReplyTo(): Message? = mInReplyTo

    fun similar(message: Message): Boolean {
        val otherServerMsgId = message.getServerMsgId()
        if (!isPrivateMessage() && serverMsgId != null && otherServerMsgId != null) {
            return serverMsgId == otherServerMsgId ||
                Edit.wasPreviouslyEditedServerMsgId(edits, otherServerMsgId)
        } else if (Edit.wasPreviouslyEditedServerMsgId(edits, otherServerMsgId)) {
            return true
        } else if (body == null || counterpart == null) {
            return false
        } else {
            val bodyString: String?
            val otherBody: String?
            if (hasFileOnRemoteHost() && (body == null || "" == body)) {
                bodyString = getFileParams().url
                otherBody = message.body?.trim()
            } else {
                bodyString = body
                otherBody = message.body
            }
            val matchingCounterpart = counterpart == message.getCounterpart()
            val remote = message.getRemoteMsgId()
            if (remote != null) {
                val hasUuid = CryptoHelper.UUID_PATTERN.matcher(remote).matches()
                if (hasUuid && matchingCounterpart && Edit.wasPreviouslyEditedRemoteMsgId(edits, remote)) {
                    return true
                }
                return (remote == remoteMsgId || remote == uuid) &&
                    matchingCounterpart &&
                    (bodyString!! == otherBody || (message.getEncryption() == ENCRYPTION_PGP && hasUuid))
            } else {
                return remoteMsgId == null &&
                    matchingCounterpart &&
                    bodyString!! == otherBody &&
                    Math.abs(getTimeSent() - message.getTimeSent()) < 20_000
            }
        }
    }

    override fun next(): Message? {
        val conv = conversation
        if (conv is Conversation) {
            synchronized(conv.messages) {
                if (mNextMessage == null) {
                    val index = conv.messages.indexOf(this)
                    mNextMessage = if (index < 0 || index >= conv.messages.size - 1) {
                        null
                    } else {
                        conv.messages[index + 1]
                    }
                }
                return mNextMessage
            }
        } else {
            throw AssertionError("Calling next should be disabled for stubs")
        }
    }

    override fun prev(): Message? {
        val conv = conversation
        if (conv is Conversation) {
            synchronized(conv.messages) {
                if (mPreviousMessage == null) {
                    val index = conv.messages.indexOf(this)
                    mPreviousMessage = if (index <= 0 || index > conv.messages.size) {
                        null
                    } else {
                        conv.messages[index - 1]
                    }
                }
            }
            return mPreviousMessage
        } else {
            throw AssertionError("Calling prev should be disabled for stubs")
        }
    }

    fun isLastCorrectableMessage(): Boolean {
        var next = next()
        while (next != null) {
            if (next.isEditable()) {
                return false
            }
            next = next.next()
        }
        return isEditable()
    }

    fun isEditable(): Boolean = status != STATUS_RECEIVED && !isCarbon() && type != TYPE_RTP_SESSION

    fun setCounterparts(counterparts: MutableList<MucOptions.User>?) {
        mCounterparts = counterparts
    }

    fun getCounterparts(): MutableList<MucOptions.User>? = mCounterparts

    override fun getAvatarBackgroundColor(): Int =
        if (type == TYPE_STATUS && getCounterparts() != null && getCounterparts()!!.size > 1) {
            Color.TRANSPARENT
        } else {
            DisplayNames.getColorForName(DisplayNames.getMessageDisplayName(this))
        }

    override fun getAvatarName(): String = Strings.nullToEmpty(DisplayNames.getMessageDisplayName(this))

    override fun isOOb(): Boolean = mOob || getFileParams().url != null

    override fun getReactions(): Collection<Reaction> = reactionList

    fun setReactions(reactions: Element?) {
        getReactionsEl()?.let { payloads.remove(it) }
        addPayload(reactions)
    }

    fun getReactionsEl(): Element? {
        for (el in payloads) {
            if (el.getName().equals("reactions") && el.getNamespace().equals("urn:xmpp:reactions:0")) {
                return el
            }
        }

        return null
    }

    fun isReactionsEmpty(): Boolean = reactionList.isEmpty()

    fun getAggregatedReactions(): Reaction.Aggregated = Reaction.aggregated(reactionList)

    fun setReactions(reactions: Collection<Reaction>) {
        reactionList = reactions
    }

    fun getSpannableBody(thumbnailer: GetThumbnailForCid?, fallbackImg: Drawable?): SpannableStringBuilder =
        getSpannableBody(thumbnailer, fallbackImg, true)

    fun getSpannableBody(
        thumbnailer: GetThumbnailForCid?,
        fallbackImg: Drawable?,
        includeReplyTo: Boolean,
    ): SpannableStringBuilder {
        val spannableBody: SpannableStringBuilder
        val html = getHtml()
        if (html == null || Build.VERSION.SDK_INT < 24) {
            // Tulkki: ask for getBody(true) whether or not the reference resolved. It differs from
            // getBody(false) in exactly one way - it drops the urn:xmpp:reply:0 fallbacks - and that is
            // precisely the text that must not be drawn here: a reply whose referenced message cannot be
            // found locally still carries its quote as a fallback, and rendering it puts the peer's
            // original on screen, which the design forbids. A legacy "> quote" is literal body text
            // rather than a fallback, so it is unaffected and still renders as a quote.
            spannableBody = SpannableStringBuilder(MessageUtils.filterLtrRtl(getBody(true)).trim())
            // Let adapter know it can do more formatting
            spannableBody.setSpan(PLAIN_TEXT_SPAN, 0, spannableBody.length, 0)
        } else {
            val spannable = SpannableStringBuilder(
                Html.fromHtml(
                    MessageUtils.filterLtrRtl(html.toString()).trim(),
                    Html.FROM_HTML_MODE_COMPACT,
                    Html.ImageGetter { source ->
                        try {
                            if (thumbnailer == null || source == null) {
                                return@ImageGetter fallbackImg
                            }
                            val cid = BobCid.cid(URI(source))
                            if (cid == null) {
                                return@ImageGetter fallbackImg
                            }
                            val thumbnail = thumbnailer.getThumbnail(cid)
                            if (thumbnail == null) {
                                return@ImageGetter fallbackImg
                            }
                            thumbnail
                        } catch (e: URISyntaxException) {
                            fallbackImg
                        }
                    },
                    Html.TagHandler { _, _, _, _ -> },
                ),
            )

            // Make images clickable and long-clickable with BetterLinkMovementMethod
            val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
            for (span in imageSpans) {
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)

                val clickSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {}
                }

                spannable.removeSpan(span)
                spannable.setSpan(
                    InlineImageSpan(span.getDrawable(), span.getSource()),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                spannable.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // https://stackoverflow.com/a/10187511/8611
            var i = spannable.length
            while (--i >= 0 && Character.isWhitespace(spannable[i])) {
            }
            spannableBody = spannable.subSequence(0, i + 1) as SpannableStringBuilder
            ViewPorts.linkedText().fixLinks(spannableBody)
        }

        val inReplyTo = getInReplyTo()
        if (includeReplyTo && inReplyTo != null && getModerated() == null) {
            // Don't show quote if it's the message right before us
            val quote = inReplyTo.getSpannableBody(thumbnailer, fallbackImg)
            if ((inReplyTo.isFileOrImage() || inReplyTo.isOOb()) && inReplyTo.getFileParams() != null) {
                quote.insert(0, ":image:")
                val cids = inReplyTo.getFileParams().getCids()
                val cid = if (cids.isEmpty()) null else cids[0]
                var thumbnail = if (thumbnailer == null || cid == null) null else thumbnailer.getThumbnail(cid)
                if (thumbnail == null) thumbnail = fallbackImg
                if (thumbnail != null) {
                    quote.setSpan(
                        InlineImageSpan(thumbnail, cid?.toString()),
                        0,
                        7,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    // Make rich quotes bigger too, to match emoji
                    for (span in quote.getSpans(0, quote.length, InlineImageSpan::class.java)) {
                        quote.setSpan(
                            RelativeSizeSpan(1.6f),
                            quote.getSpanStart(span),
                            quote.getSpanEnd(span),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
            quote.setSpan(android.text.style.QuoteSpan(), 0, quote.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Make rich quotes bigger too, to match emoji
            for (span in quote.getSpans(0, quote.length, InlineImageSpan::class.java)) {
                quote.setSpan(
                    RelativeSizeSpan(1.6f),
                    quote.getSpanStart(span),
                    quote.getSpanEnd(span),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            spannableBody.insert(0, "\n")
            spannableBody.insert(0, quote)
        }

        return spannableBody
    }

    fun getSpannableBody(): SpannableStringBuilder = getSpannableBody(null, null)

    fun hasMeCommand(): Boolean = this.body!!.trim().startsWith(ME_COMMAND)

    fun wasMergedIntoPrevious(xmppConnectionService: XmppConnectionService?): Boolean {
        val prev = prev()
        if (prev != null && getModerated() != null && prev.getModerated() != null) return true
        if (getOccupantId() != null && xmppConnectionService != null) {
            val conv = conversation ?: throw NullPointerException()
            val muted = getStatus() == STATUS_RECEIVED &&
                conv.getMode() == Conversational.MODE_MULTI &&
                xmppConnectionService.isMucUserMuted(
                    conv.getAccount()?.getUuid(),
                    "" + conv.getJid(),
                    getOccupantId() ?: throw NullPointerException("message has no occupant id"),
                )
            if (prev != null && muted && getOccupantId() == prev.getOccupantId()) return true
        }
        return false
    }

    override fun trusted(): Boolean {
        val contact = getContact()
        return status > STATUS_RECEIVED || (contact != null && (contact.showInContactList() || contact.isSelf()))
    }

    override fun fixCounterpart(): Boolean {
        val presences = (conversation ?: throw NullPointerException()).getContact().getPresences()
        val cp = counterpart
        if (cp != null && presences.has(Strings.nullToEmpty(cp.getResource()))) {
            return true
        } else if (presences.isEmpty()) {
            counterpart = null
            return false
        } else {
            counterpart = Counterparts.getNextCounterpart(getContact(), presences.toResourceArray()[0])
            return true
        }
    }

    override fun setUuid(uuid: String?) {
        this.uuid = uuid
    }

    fun isExpanded(): Boolean = mExpanded

    fun setExpanded(expanded: Boolean) {
        mExpanded = expanded
    }

    override fun getEditedId(): String {
        if (edits.isEmpty()) {
            throw IllegalStateException("Attempting to access unedited message")
        }
        // `Edit.getEditedId()` is nullable because Java returned a null there and
        // `Conversation.findSentMessageWithUuidOrRemoteId` compares it null-safely; this override
        // keeps the platform check the old Java return carried (port-5).
        return edits[edits.size - 1].getEditedId()!!
    }

    override fun getEditedIdWireFormat(): String {
        if (edits.isEmpty()) {
            throw IllegalStateException("Attempting to access unedited message")
        }
        return edits[0].getEditedId()!!
    }

    override fun getLinks(): List<URI> {
        // Remove quotes
        val text = SpannableStringBuilder(Regex("^>.*").replace(getBody(true), ""))
        return ViewPorts.linkedText().extractLinks(text)
            .map { url ->
                try {
                    URI(url)
                } catch (e: URISyntaxException) {
                    null
                }
            }
            .filterNotNull()
    }

    override fun getOob(): URI? {
        val url = getFileParams().url
        return try {
            if (url == null) null else URI(url)
        } catch (e: URISyntaxException) {
            null
        }
    }

    @Synchronized
    override fun clearPayloads() {
        payloads.clear()
    }

    @Synchronized
    override fun addPayload(el: Element?) {
        if (el == null) return

        payloads.add(el)
    }

    @Synchronized
    override fun getPayloads(): List<Element> = ArrayList(payloads)

    @Synchronized
    override fun getFallbacks(vararg includeFor: String): List<Element> {
        val fallbacks = ArrayList<Element>()

        for (el in payloads) {
            if (el.getName().equals("fallback") && el.getNamespace().equals("urn:xmpp:fallback:0")) {
                val fallbackFor = el.getAttribute("for") ?: continue
                for (includeOne in includeFor) {
                    if (fallbackFor == includeOne) {
                        fallbacks.add(el)
                        break
                    }
                }
            }
        }

        return fallbacks
    }

    fun getHtml(): Element? = getHtml(false)

    @Synchronized
    fun getHtml(root: Boolean): Element? {
        for (el in payloads) {
            if (el.getName().equals("html") && el.getNamespace().equals("http://jabber.org/protocol/xhtml-im")) {
                return if (root) el else el.getChildren().get(0)
            }
        }

        return null
    }

    @Synchronized
    fun getCommands(): List<Element>? {
        for (el in payloads) {
            if (el.getName().equals("query") &&
                el.getNamespace().equals("http://jabber.org/protocol/disco#items") &&
                el.getAttribute("node").equals("http://jabber.org/protocol/commands")
            ) {
                return el.getChildren()
            }
        }

        return null
    }

    @Synchronized
    fun getLinkDescriptions(): List<Element> {
        val result = ArrayList<Element>()
        for (el in payloads) {
            if (el.getName().equals("Description") &&
                el.getNamespace().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
            ) {
                result.add(el)
            }
        }

        return result
    }

    @Synchronized
    override fun clearLinkDescriptions() {
        payloads.removeAll(getLinkDescriptions())
    }

    override fun getMimeType(): String? {
        val extension: String?
        val path = relativeFilePath
        if (path != null) {
            extension = MimeUtils.extractRelevantExtension(path)
        } else {
            val address = getOob()?.toString() ?: body!!.split("\n")[0]
            val url = URL.tryParse(address) ?: return null
            extension = MimeUtils.extractRelevantExtension(url)
        }
        return MimeUtils.guessMimeTypeFromExtension(extension)
    }

    @Synchronized
    override fun treatAsDownloadable(): Boolean {
        if (mTreatAsDownloadable == null) {
            mTreatAsDownloadable = MessageUtils.treatAsDownloadable(this.body, isOOb())
        }
        return mTreatAsDownloadable!!
    }

    @Synchronized
    override fun hasCustomEmoji(): Boolean {
        if (getHtml() != null) {
            val spannable = getSpannableBody(null, null)
            val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
            return imageSpans.isNotEmpty()
        }

        return false
    }

    @Synchronized
    fun bodyIsOnlyEmojis(): Boolean {
        if (mIsEmojisOnly == null) {
            mIsEmojisOnly = EmoticonText.isOnlyEmoji(getBody())
            if (mIsEmojisOnly == true) return true

            if (getHtml() != null) {
                val spannable = getSpannableBody(null, null)
                val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
                for (span in imageSpans) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    spannable.delete(start, end)
                }
                val after = spannable.toString().replace(Regex("\\s"), "")
                mIsEmojisOnly = after.isEmpty() || EmoticonText.isOnlyEmoji(after)
            }
        }
        return mIsEmojisOnly == true
    }

    @Synchronized
    override fun isGeoUri(): Boolean {
        if (mIsGeoUri == null) {
            mIsGeoUri = GeoUris.GEO_URI.matcher(body).matches()
        }
        return mIsGeoUri == true
    }

    @Synchronized
    fun wholeIsKnownURI(): Uri? {
        val cached = wholeIsKnownURI
        if (cached != null) return cached

        if (Patterns.BITCOIN_URI.matcher(body).matches() ||
            Patterns.BITCOINCASH_URI.matcher(body).matches() ||
            Patterns.ETHEREUM_URI.matcher(body).matches() ||
            Patterns.MONERO_URI.matcher(body).matches() ||
            Patterns.WOWNERO_URI.matcher(body).matches() ||
            Patterns.URI_TALER.matcher(body).matches()
        ) {
            // hack to make query parser work
            wholeIsKnownURI = Uri.parse(body!!.replace(":", "://"))
        }

        return wholeIsKnownURI
    }

    protected fun getSims(): List<Element> =
        payloads.filter { el ->
            el.getName().equals("reference") &&
                el.getNamespace().equals("urn:xmpp:reference:0") &&
                el.findChild("media-sharing", "urn:xmpp:sims:1") != null
        }

    @Synchronized
    override fun resetFileParams() {
        mOob = false
        mFileParams = null
        transferable = null
        payloads.removeAll(getSims())
        clearFallbacks(Namespace.OOB)
        setType(if (isPrivateMessage()) TYPE_PRIVATE else TYPE_TEXT)
    }

    @Synchronized
    fun setFileParams(fileParams: FileParams?) {
        val current = mFileParams
        if (fileParams != null && current != null && current.sims != null && fileParams.sims == null) {
            fileParams.sims = current.sims
        }
        mFileParams = fileParams
        if (fileParams != null && getSims().isEmpty()) {
            addPayload(fileParams.toSims())
        }
    }

    @Synchronized
    override fun getFileParams(): FileParams {
        var fp = mFileParams
        if (fp == null) {
            val sims = getSims()
            fp = if (sims.isEmpty()) FileParams(if (mOob) this.body else "") else FileParams(sims[0])
            val currentTransferable = this.transferable
            if (currentTransferable != null) {
                fp.size = currentTransferable.getFileSize()
            }
            mFileParams = fp
        }

        return fp
    }

    fun untie() {
        mNextMessage = null
        mPreviousMessage = null
    }

    override fun isPrivateMessage(): Boolean = type == TYPE_PRIVATE || type == TYPE_PRIVATE_FILE

    override fun isFileOrImage(): Boolean =
        type == TYPE_FILE || type == TYPE_IMAGE || type == TYPE_PRIVATE_FILE

    fun isTypeText(): Boolean = type == TYPE_TEXT || type == TYPE_PRIVATE

    override fun hasFileOnRemoteHost(): Boolean = isFileOrImage() && getFileParams().url != null

    override fun needsUploading(): Boolean = isFileOrImage() && getFileParams().url == null

    fun setOob(isOob: Boolean) {
        mOob = isOob
    }

    fun getEditedList(): List<Edit> = edits

    override fun setFingerprint(fingerprint: String?) {
        axolotlFingerprint = fingerprint
    }

    override fun getFingerprint(): String? = axolotlFingerprint

    fun isTrusted(): Boolean {
        // port-5: `getAccount()` is nullable; a message with no account has no OMEMO service to be
        // trusted by, so it answers `false` where Java's dereference would have thrown.
        val account = (conversation ?: throw NullPointerException()).getAccount() ?: return false
        val axolotlService = account.getAxolotlService()
        val s: FingerprintStatus? = axolotlService?.getFingerprintTrust(axolotlFingerprint)
        return s != null && s.isTrusted()
    }

    private fun getPreviousEncryption(): Int {
        var iterator = prev()
        while (iterator != null) {
            if (!iterator.isCarbon() && iterator.getStatus() != STATUS_RECEIVED) {
                return iterator.getEncryption()
            }
            iterator = iterator.prev()
        }
        return ENCRYPTION_NONE
    }

    private fun getNextEncryption(): Int {
        val conv = conversation
        if (conv is Conversation) {
            var iterator = next()
            while (iterator != null) {
                if (!iterator.isCarbon() && iterator.getStatus() != STATUS_RECEIVED) {
                    return iterator.getEncryption()
                }
                iterator = iterator.next()
            }
            return conv.getNextEncryption()
        } else {
            throw AssertionError(
                "This should never be called since isInValidSession should be disabled for stubs",
            )
        }
    }

    open fun isValidInSession(): Boolean {
        val pastEncryption = getCleanedEncryption(getPreviousEncryption())
        val futureEncryption = getCleanedEncryption(getNextEncryption())

        val inUnencryptedSession =
            pastEncryption == ENCRYPTION_NONE ||
                futureEncryption == ENCRYPTION_NONE ||
                pastEncryption != futureEncryption

        return inUnencryptedSession || getCleanedEncryption(getEncryption()) == pastEncryption
    }

    @Suppress("UNCHECKED_CAST")
    override fun replaceReactions(reactions: Collection<out ReactionRef>?) {
        setReactions(reactions as Collection<Reaction>)
    }

    /**
     * Tulkki: `markable` is the one member of `MessageRef` that is a **field** here. An interface has
     * no fields, so the ref carries the setter the island's one assignment site needs and this is its
     * body. The field itself keeps its name, its `public` visibility and its three `:ui`/`:data`
     * readers.
     */
    override fun setMarkable(markable: Boolean) {
        this.markable = markable
    }

    override fun isMarkable(): Boolean = this.markable

    override fun getAggregatedOurReactions(): Set<String> = getAggregatedReactions().ourReactions

    // Tulkki: 3.7 pair 9, part 12. `MessageRef`'s adapter bodies, and nothing else moves.
    //
    // Three are *overloads*, and each is legal for one reason: the two parameter types do not erase
    // alike (`Message`/`MessageRef`, `MucOptions.User`/`MucOptionsRef.UserRef`, `FileParams`/
    // `FileParamsRef`), so javac sees distinct signatures and the model's own methods keep serving
    // `:data`'s code untouched. `replaceReactions` is a differently-named member rather than an
    // overload because it *would* erase alike: `setReactions(Collection<Reaction>)` and any
    // `setReactions(Collection<...Ref>)` are the same `setReactions(Collection)` to javac, and that is
    // a name clash, not an overload. `addReadByMarker` is no longer an overload at all: port-13
    // retired `ReadByMarkerRef`, so the island's two `Jid`s arrive and the model builds the marker.

    override fun addReadByMarker(fullJid: Jid, realJid: Jid?): Boolean =
        addReadByMarker(ReadByMarker.from(fullJid, realJid))

    override fun sameMucUser(otherMessage: MessageRef?): Boolean = sameMucUser(otherMessage as Message)

    override fun setInReplyTo(message: MessageRef?) {
        setInReplyTo(message as Message?)
    }

    override fun setMucUser(user: MucOptionsRef.UserRef?) {
        setMucUser(user as MucOptions.User?)
    }

    override fun setFileParams(fileParams: MessageRef.FileParamsRef?) {
        setFileParams(fileParams as FileParams?)
    }

    class FileParams : MessageRef.FileParamsRef {
        @JvmField
        var url: String? = null

        @JvmField
        var size: Long? = null

        @JvmField
        var width = 0

        @JvmField
        var height = 0

        @JvmField
        var runtime = 0

        @JvmField
        var sims: Element? = null

        /**
         * Tulkki: the public field `url`, as a member - an interface has no fields, so
         * `MessageRef.FileParamsRef` declares the accessor and this is its body. Nothing else about
         * the field changes: `:data`'s own serialisation, `toSims()` and `Message`'s download checks
         * all read it directly.
         */
        override fun url(): String? = url

        // Tulkki: 3.7 pair 9, part 16 - the write side of the fields above, for the island's
        // link-preview path. `setName` already existed for `:data`'s own readers.

        override fun setUrl(url: String?) {
            this.url = url
        }

        override fun setSize(size: Long?) {
            this.size = size
        }

        constructor()

        constructor(el: Element) {
            if (el.getName().equals("x") && el.getNamespace().equals(Namespace.OOB)) {
                this.url = el.findChildContent("url", Namespace.OOB)
            }
            if (el.getName().equals("reference") && el.getNamespace().equals("urn:xmpp:reference:0")) {
                sims = el
                val refUri = el.getAttribute("uri")
                if (refUri != null) url = refUri
                val mediaSharing = el.findChild("media-sharing", "urn:xmpp:sims:1")
                if (mediaSharing != null) {
                    var file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5")
                    if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4")
                    if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3")
                    if (file != null) {
                        try {
                            val sizeS = file.findChildContent("size", file.getNamespace())
                            if (sizeS != null) size = sizeS.toLong()
                            val widthS = file.findChildContent("width", "https://schema.org/")
                            if (widthS != null) width = parseInt(widthS)
                            val heightS = file.findChildContent("height", "https://schema.org/")
                            if (heightS != null) height = parseInt(heightS)
                            val durationS = file.findChildContent("duration", "https://schema.org/")
                            if (durationS != null) runtime = (Duration.parse(durationS).toMillis() / 1000L).toInt()
                        } catch (e: NumberFormatException) {
                            Log.w(Config.LOGTAG, "Trouble parsing as number: " + e)
                        }
                    }

                    val sources = mediaSharing.findChild("sources", "urn:xmpp:sims:1")
                    if (sources != null) {
                        val ref = sources.findChild("reference", "urn:xmpp:reference:0")
                        if (ref != null) url = ref.getAttribute("uri")
                    }
                }
            }
        }

        constructor(ser: String?) {
            val parts: Array<String> = if (ser == null) emptyArray() else Pattern.compile("\\|").split(ser)
            when (parts.size) {
                1 -> try {
                    this.size = parts[0].toLong()
                } catch (e: NumberFormatException) {
                    this.url = URL.tryParse(parts[0])
                }

                // Java's switch fell through: 5 ran 5, 4 and 2; 4 ran 4 and 2.
                5 -> {
                    this.runtime = parseInt(parts[4])
                    this.width = parseInt(parts[2])
                    this.height = parseInt(parts[3])
                    this.url = URL.tryParse(parts[0])
                    this.size = Longs.tryParse(parts[1])
                }

                4 -> {
                    this.width = parseInt(parts[2])
                    this.height = parseInt(parts[3])
                    this.url = URL.tryParse(parts[0])
                    this.size = Longs.tryParse(parts[1])
                }

                2 -> {
                    this.url = URL.tryParse(parts[0])
                    this.size = Longs.tryParse(parts[1])
                }

                3 -> {
                    this.size = Longs.tryParse(parts[0])
                    this.width = parseInt(parts[1])
                    this.height = parseInt(parts[2])
                }
            }
        }

        fun isEmpty(): Boolean =
            StringUtils.nullOnEmpty(toString()) == null &&
                StringUtils.nullOnEmpty(toSims().getContent()) == null

        override fun getSize(): Long = size ?: 0

        override fun getName(): String? {
            val file = getFileElement() ?: return null

            return file.findChildContent("name", file.getNamespace())
        }

        override fun setName(name: String?) {
            if (sims == null) toSims()
            val file = getFileElement() ?: throw NullPointerException()

            for (child in file.getChildren()) {
                if (child.getName().equals("name") && child.getNamespace().equals(file.getNamespace())) {
                    file.removeChild(child)
                }
            }

            if (name != null) {
                file.addChild("name", file.getNamespace()).setContent(name)
            }
        }

        override fun getMediaType(): String? {
            val file = getFileElement() ?: return null

            return file.findChildContent("media-type", file.getNamespace())
        }

        fun setMediaType(mime: String?) {
            if (sims == null) toSims()
            val file = getFileElement() ?: throw NullPointerException()

            for (child in file.getChildren()) {
                if (child.getName().equals("media-type") && child.getNamespace().equals(file.getNamespace())) {
                    file.removeChild(child)
                }
            }

            if (mime != null) {
                file.addChild("media-type", file.getNamespace()).setContent(mime)
            }
        }

        fun toSims(): Element {
            var s = sims
            if (s == null) {
                s = Element("reference", "urn:xmpp:reference:0")
                sims = s
            }
            s.setAttribute("type", "data")
            val mediaSharing = s.findChild("media-sharing", "urn:xmpp:sims:1")
                ?: s.addChild("media-sharing", "urn:xmpp:sims:1")

            var file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5")
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4")
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3")
            val fileElement = file ?: mediaSharing.addChild("file", "urn:xmpp:jingle:apps:file-transfer:5")

            fileElement.removeChild(fileElement.findChild("size", fileElement.getNamespace()))
            val sz = size
            if (sz != null) fileElement.addChild("size", fileElement.getNamespace()).setContent(sz.toString())

            fileElement.removeChild(fileElement.findChild("width", "https://schema.org/"))
            if (width > 0) {
                fileElement.addChild("width", "https://schema.org/").setContent(width.toString())
            }

            fileElement.removeChild(fileElement.findChild("height", "https://schema.org/"))
            if (height > 0) {
                fileElement.addChild("height", "https://schema.org/").setContent(height.toString())
            }

            fileElement.removeChild(fileElement.findChild("duration", "https://schema.org/"))
            if (runtime > 0) {
                fileElement.addChild("duration", "https://schema.org/").setContent("PT" + runtime + "S")
            }

            val u = url
            if (u != null) {
                val sources = mediaSharing.findChild("sources", mediaSharing.getNamespace())
                    ?: mediaSharing.addChild("sources", mediaSharing.getNamespace())

                val source = sources.findChild("reference", "urn:xmpp:reference:0")
                    ?: sources.addChild("reference", "urn:xmpp:reference:0")
                source.setAttribute("type", "data")
                source.setAttribute("uri", u)
            }

            return s
        }

        protected fun getFileElement(): Element? {
            val s = sims ?: return null

            val mediaSharing = s.findChild("media-sharing", "urn:xmpp:sims:1") ?: return null
            var file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5")
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4")
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3")
            return file
        }

        @Throws(NoSuchAlgorithmException::class)
        fun setCids(cids: Iterable<Cid>) {
            if (sims == null) toSims()
            val file = getFileElement() ?: throw NullPointerException()

            for (child in file.getChildren()) {
                if (child.getName().equals("hash") && child.getNamespace().equals("urn:xmpp:hashes:2")) {
                    file.removeChild(child)
                }
            }

            for (cid in cids) {
                file.addChild("hash", "urn:xmpp:hashes:2")
                    .setAttribute("algo", CryptoHelper.multihashAlgo(cid.getType()))
                    .setContent(Base64.encodeToString(cid.getHash(), Base64.NO_WRAP))
            }
        }

        override fun getCids(): MutableList<Cid> {
            val cids = ArrayList<Cid>()
            val file = getFileElement() ?: return cids

            for (child in file.getChildren()) {
                if (child.getName().equals("hash") && child.getNamespace().equals("urn:xmpp:hashes:2")) {
                    try {
                        cids.add(
                            CryptoHelper.cid(
                                Base64.decode(child.getContent(), Base64.DEFAULT),
                                child.getAttribute("algo") ?: throw NullPointerException(),
                            ),
                        )
                    } catch (e: NoSuchAlgorithmException) {
                    } catch (e: IllegalStateException) {
                    }
                }
            }

            cids.sortWith(Comparator { x, y -> y.getType().compareTo(x.getType()) })

            return cids
        }

        fun addThumbnail(width: Int, height: Int, mimeType: String, uri: String) {
            for (thumb in getThumbnails()) {
                if (uri == thumb.getAttribute("uri")) return
            }

            if (sims == null) toSims()
            val file = getFileElement() ?: throw NullPointerException()
            file.addChild(
                Element("thumbnail", "urn:xmpp:thumbs:1")
                    .setAttribute("width", Integer.toString(width))
                    .setAttribute("height", Integer.toString(height))
                    .setAttribute("type", mimeType)
                    .setAttribute("uri", uri),
            )
        }

        override fun getThumbnails(): MutableList<Element> {
            val thumbs = ArrayList<Element>()
            val file = getFileElement() ?: return thumbs

            for (child in file.getChildren()) {
                if (child.getName().equals("thumbnail") && child.getNamespace().equals("urn:xmpp:thumbs:1")) {
                    thumbs.add(child)
                }
            }

            return thumbs
        }

        override fun toString(): String {
            val builder = StringBuilder()
            val u = url
            if (u != null) builder.append(u)
            val sz = size
            if (sz != null) builder.append('|').append(sz.toString())
            if (width > 0 || height > 0 || runtime > 0) builder.append('|').append(width)
            if (height > 0 || runtime > 0) builder.append('|').append(height)
            if (runtime > 0) builder.append('|').append(runtime)
            return builder.toString()
        }

        override fun equals(other: Any?): Boolean {
            if (other !is FileParams) return false
            val u = url ?: return false

            return u == other.url
        }

        override fun hashCode(): Int {
            val u = url
            return u?.hashCode() ?: super.hashCode()
        }
    }

    class PlainTextSpan

    companion object {

        const val TABLENAME = "messages"

        /**
         * [AbstractEntity.UUID], restated on this class. Java read `Message.UUID` through
         * inheritance and every `:data` query object still writes it (`DeliveryQueries:27`,
         * `MessagesQueries:39`, `Schema76:161` and a dozen more) - Kotlin does not inherit a Java
         * static into a subclass's scope, and none of those files' `const val` initialisers is a
         * compile-time constant without it. The value is the superclass's own, not a copy that can
         * drift.
         */
        const val UUID = AbstractEntity.UUID

        const val STATUS_DUMMY = -1
        const val STATUS_RECEIVED = 0
        const val STATUS_UNSEND = 1
        const val STATUS_SEND = 2
        const val STATUS_SEND_FAILED = OmemoMessage.STATUS_SEND_FAILED
        const val STATUS_WAITING = 5
        const val STATUS_OFFERED = 6
        const val STATUS_SEND_RECEIVED = 7
        const val STATUS_SEND_DISPLAYED = 8

        const val ENCRYPTION_NONE = OmemoMessage.ENCRYPTION_NONE
        const val ENCRYPTION_PGP = OmemoMessage.ENCRYPTION_PGP
        const val ENCRYPTION_OTR = 2
        const val ENCRYPTION_DECRYPTED = OmemoMessage.ENCRYPTION_DECRYPTED
        const val ENCRYPTION_DECRYPTION_FAILED = OmemoMessage.ENCRYPTION_DECRYPTION_FAILED
        const val ENCRYPTION_AXOLOTL = OmemoMessage.ENCRYPTION_AXOLOTL
        const val ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE = 6
        const val ENCRYPTION_AXOLOTL_FAILED = 7

        const val TYPE_TEXT = 0
        const val TYPE_IMAGE = 1
        const val TYPE_FILE = 2
        const val TYPE_STATUS = 3
        const val TYPE_PRIVATE = 4
        const val TYPE_PRIVATE_FILE = 5
        const val TYPE_RTP_SESSION = 6
        const val TYPE_STORY = 7

        // Tulkki translation state for this message. TRANSLATION_NONE is the default for every
        // message, including all outgoing ones; the values are persisted in translation_state.
        const val TRANSLATION_NONE = 0
        const val TRANSLATION_DONE = 1
        const val TRANSLATION_SAME_LANGUAGE = 2
        const val TRANSLATION_FAILED = 3

        const val CONVERSATION = "conversationUuid"
        const val COUNTERPART = "counterpart"
        const val TRUE_COUNTERPART = "trueCounterpart"
        const val BODY = "body"
        const val BODY_LANGUAGE = "bodyLanguage"
        const val TIME_SENT = "timeSent"
        const val ENCRYPTION = "encryption"
        const val STATUS = "status"
        const val TYPE = "type"
        const val CARBON = "carbon"
        const val OOB = "oob"
        const val EDITED = "edited"
        const val REMOTE_MSG_ID = "remoteMsgId"
        const val SERVER_MSG_ID = "serverMsgId"
        const val RELATIVE_FILE_PATH = "relativeFilePath"
        const val FINGERPRINT = "axolotl_fingerprint"
        const val READ = "read"
        const val ERROR_MESSAGE = "errorMsg"
        const val READ_BY_MARKERS = "readByMarkers"
        const val MARKABLE = "markable"
        const val DELETED = "deleted"
        const val OCCUPANT_ID = "occupantId"
        const val REACTIONS = "reactions"
        const val ME_COMMAND = "/me "
        const val PAYLOADS = "payloads"
        const val TIME_RECEIVED = "timeReceived"
        const val SUBJECT = "subject"
        const val FILE_PARAMS = "fileParams"
        const val OCCUPANTID = "occupant_id"
        const val NOTIFICATION_DISMISSED = "notificationDismissed"
        const val RETRACT_ID = "retractId"
        const val EPHEMERAL_TIMER = "ephemeral_timer"
        const val EXPIRE_AT = "expire_at"
        const val TRANSLATED_BODY = "translated_body"
        const val TRANSLATION_LANG = "translation_lang"
        const val TRANSLATION_STATE = "translation_state"

        const val ERROR_MESSAGE_CANCELLED = "eu.siacs.conversations.cancelled"

        /**
         * The body a date-separator row carries. Pair 8b's move: the constant used to be declared by
         * `uk.xa0.tulkki.ui.adapter.MessageAdapter`, which `:data`'s
         * `IndividualMessage.createDateSeparator` then named - a forbidden `:data` -> `:ui` import
         * (D9) for one literal. It is a message sentinel like [DELETED_MESSAGE_BODY] and belongs with
         * the row it describes; the adapter keeps the same name as an alias.
         */
        const val DATE_SEPARATOR_BODY = "DATE_SEPARATOR"

        const val DELETED_MESSAGE_BODY = "de.monocles.chat.message_deleted"

        @JvmField
        val PLAIN_TEXT_SPAN: Any = PlainTextSpan()

        @JvmStatic
        @Throws(IOException::class)
        fun fromCursor(cursor: Cursor, conversation: Conversation): Message {
            val payloadsStr = cursor.getString(cursor.getColumnIndexOrThrow(PAYLOADS))
            val payloads = ArrayList<Element>()
            if (payloadsStr != null) {
                val xmlReader = XmlReader()
                xmlReader.setInputStream(ByteSource.wrap(payloadsStr.toByteArray()).openStream())
                try {
                    while (true) {
                        val tag = xmlReader.readTag() ?: break
                        payloads.add(xmlReader.readElement(tag))
                    }
                } catch (e: IOException) {
                    Log.e(Config.LOGTAG, "Failed to parse: " + payloadsStr, e)
                }
            }

            val m = Message(
                conversation,
                cursor.getString(cursor.getColumnIndexOrThrow(AbstractEntity.UUID)),
                cursor.getString(cursor.getColumnIndexOrThrow(CONVERSATION)),
                fromString(cursor.getString(cursor.getColumnIndexOrThrow(COUNTERPART))),
                fromString(cursor.getString(cursor.getColumnIndexOrThrow(TRUE_COUNTERPART))),
                cursor.getString(cursor.getColumnIndexOrThrow(BODY)),
                cursor.getLong(cursor.getColumnIndexOrThrow(TIME_SENT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(ENCRYPTION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(TYPE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(CARBON)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(REMOTE_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(RELATIVE_FILE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(SERVER_MSG_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(FINGERPRINT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(READ)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(EDITED)),
                cursor.getInt(cursor.getColumnIndexOrThrow(OOB)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(ERROR_MESSAGE)),
                ReadByMarker.fromJsonString(cursor.getString(cursor.getColumnIndexOrThrow(READ_BY_MARKERS))),
                cursor.getInt(cursor.getColumnIndexOrThrow(MARKABLE)) > 0,
                cursor.getInt(cursor.getColumnIndexOrThrow(DELETED)) > 0,
                cursor.getString(cursor.getColumnIndexOrThrow(BODY_LANGUAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(OCCUPANT_ID)),
                Reaction.fromString(cursor.getString(cursor.getColumnIndexOrThrow(REACTIONS))),
                cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        if (cursor.isNull(cursor.getColumnIndexOrThrow(TIME_RECEIVED))) {
                            TIME_SENT
                        } else {
                            TIME_RECEIVED
                        },
                    ),
                ),
                cursor.getString(cursor.getColumnIndexOrThrow(SUBJECT)),
                cursor.getString(cursor.getColumnIndexOrThrow(FILE_PARAMS)),
                payloads,
                cursor.getString(cursor.getColumnIndexOrThrow(RETRACT_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(EPHEMERAL_TIMER)),
                cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRE_AT)),
            )
            val legacyOccupant = cursor.getString(cursor.getColumnIndexOrThrow(OCCUPANTID))
            if (legacyOccupant != null) m.setOccupantId(legacyOccupant)
            if (cursor.getInt(cursor.getColumnIndexOrThrow(NOTIFICATION_DISMISSED)) > 0) {
                m.markNotificationDismissed()
            }
            m.setTranslatedBody(cursor.getString(cursor.getColumnIndexOrThrow(TRANSLATED_BODY)))
            m.setTranslationLang(cursor.getString(cursor.getColumnIndexOrThrow(TRANSLATION_LANG)))
            m.setTranslationState(cursor.getInt(cursor.getColumnIndexOrThrow(TRANSLATION_STATE)))
            return m
        }

        @JvmStatic
        fun createStatusMessage(conversation: Conversation, body: String?): Message {
            val message = Message(conversation)
            message.setType(TYPE_STATUS)
            message.setStatus(STATUS_RECEIVED)
            message.body = body
            return message
        }

        @JvmStatic
        fun createLoadMoreMessage(conversation: Conversation): Message {
            val message = Message(conversation)
            message.setType(TYPE_STATUS)
            message.body = "LOAD_MORE"
            return message
        }

        @JvmStatic
        fun configurePrivateMessage(message: Message) {
            configurePrivateMessage(message, false)
        }

        @JvmStatic
        fun configurePrivateFileMessage(message: Message): Boolean =
            configurePrivateMessage(message, true)

        private fun configurePrivateMessage(message: Message, isFile: Boolean): Boolean {
            val conv = message.conversation
            if (conv is Conversation) {
                if (conv.getMode() == Conversational.MODE_MULTI) {
                    val nextCounterpart = conv.getNextCounterpart()
                    return configurePrivateMessage(conv, message, nextCounterpart, isFile)
                }
            }
            return false
        }

        @JvmStatic
        fun configurePrivateMessage(message: Message, counterpart: Jid?) {
            val conv = message.conversation
            if (conv is Conversation) {
                configurePrivateMessage(conv, message, counterpart, false)
            }
        }

        private fun configurePrivateMessage(
            conversation: Conversation,
            message: Message,
            counterpart: Jid?,
            isFile: Boolean,
        ): Boolean {
            if (counterpart == null) {
                return false
            }
            message.setCounterpart(counterpart)
            val mucOptions = conversation.getMucOptions()
            if (counterpart == mucOptions.getSelf().getFullJid()) {
                message.setTrueCounterpart(conversation.getAccount()!!.getJid().asBareJid())
            } else {
                val user = mucOptions.findUserByFullJid(counterpart)
                if (user != null) {
                    message.setTrueCounterpart(user.getRealJid())
                    message.setOccupantId(user.getOccupantId())
                }
            }
            message.setType(if (isFile) TYPE_PRIVATE_FILE else TYPE_PRIVATE)
            return true
        }

        private fun fromString(value: String?): Jid? {
            try {
                if (value != null) {
                    return Jid.of(value)
                }
            } catch (e: IllegalArgumentException) {
                return null
            }
            return null
        }

        private fun getCleanedEncryption(encryption: Int): Int {
            if (encryption == ENCRYPTION_DECRYPTED || encryption == ENCRYPTION_DECRYPTION_FAILED) {
                return ENCRYPTION_PGP
            }
            if (encryption == ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE ||
                encryption == ENCRYPTION_AXOLOTL_FAILED
            ) {
                return ENCRYPTION_AXOLOTL
            }
            return encryption
        }
    }
}

/**
 * Java's `Integer.parseInt`, with the same `NumberFormatException` answer of `0`. A file-private
 * function rather than a companion member, so the nested [Message.FileParams] reaches it without a
 * synthetic accessor.
 */
private fun parseInt(value: String): Int =
    try {
        value.toInt()
    } catch (e: NumberFormatException) {
        0
    }
