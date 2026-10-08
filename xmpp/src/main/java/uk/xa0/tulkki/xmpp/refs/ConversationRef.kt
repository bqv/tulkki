package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.libs.FilePathInfoRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.mam.MamReference
import java.util.concurrent.atomic.AtomicBoolean
import net.java.otr4j.session.SessionImpl
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Conversation`.
 *
 * Declared in the island, implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). It grew in three waves and always by the same rule: a
 * member arrives with the island call site that reads it and never ahead of one (ruling 3).
 *
 * Part 9's `AbstractParser` contributed the room's JID and its `MucOptions`; part 11's
 * `PresenceParser` contributed the account, the message count and the append, and made this
 * interface **extend [ConversationalRef]**, mirroring the model's own hierarchy
 * (`Conversation implements Conversational`) so that a `ConversationRef` can be handed to
 * anything that takes the model's `Conversational` - `DataStatics.newMessage`, above all.
 * `ConversationalRef` declares no members of its own beyond the two part 12 added, so `:data` pays
 * nothing for the edge.
 *
 * Part 12, `MessageParser`, is the largest wave by far: it is the only island file that holds a
 * conversation across two hundred lines and asks it to find messages, start and end OTR sessions,
 * carry a symmetric key, and remember its attributes. Every member below is a call site in that
 * file.
 *
 * Where a `:ui` file has to name this type it must write it **fully qualified in the
 * signature and import nothing** (rounds 151/161, pair 11's four `extends` clauses).
 */
interface ConversationRef : ConversationalRef {

    override fun getJid(): Jid?

    fun getMucOptions(): MucOptionsRef

    fun canInferPresence(): Boolean

    // -- part 11: what `PresenceParser` reads and writes ------------------------------------------
    fun countMessages(): Int

    /**
     * The overload the island calls; `:data` adapts it to its own `add(Message)` with one cast. The
     * parameter types do not erase alike, so the overload is legal - and it must exist, because a
     * parameter type is not covariant in Java.
     */
    fun add(message: MessageRef)

    // -- part 12: the message-lookup surface `MessageParser` works in ------------------------------
    fun getContact(): ContactRef

    override fun getMode(): Int

    /** The model answers a `CharSequence`; the island only ever concatenates it into a log line. */
    fun getName(): CharSequence

    fun isPrivateAndNonAnonymous(): Boolean

    fun hasValidOtrSession(): Boolean

    fun endOtrIfNeeded(): Boolean

    fun getOtrSession(): SessionImpl?

    fun startOtrSession(presence: String, sendStart: Boolean): SessionImpl?

    fun resetOtrSession()

    fun setLastReceivedOtrMessageId(id: String?)

    fun setSymmetricKey(key: ByteArray?)

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun setIncomingChatState(state: ChatState): Boolean

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun setOutgoingChatState(state: ChatState): Boolean

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun setEphemeralTimer(timer: Int): Boolean

    /** The island passes `null` to clear it; the model's own overload is `setEphemeralBy(String)`. */
    fun setEphemeralBy(by: String?)

    fun setHasMessagesLeftOnServer(value: Boolean)

    fun setAttribute(key: String, value: String?): Boolean

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun possibleDuplicate(serverMsgId: String?, remoteMsgId: String?): Boolean

    /**
     * Read-only on purpose, decided by the implementation: `Conversation.getAcceptedCryptoTargets()`
     * answers `Collections.singletonList(...)` in `MODE_SINGLE` - a genuinely **immutable** list,
     * whose `add`/`remove` throw - and only the `MODE_MULTI` branch answers a fresh `ArrayList`
     * (`getJidListAttribute`). `MutableList` would therefore be a lie for every one-to-one
     * conversation, so the declaration keeps the tolerance and the two mutating MUC callers
     * (`MessageParser`, `MucJoin`) copy it into their own list, as `TrustKeysActivity` already does.
     */
    fun getAcceptedCryptoTargets(): List<@JvmSuppressWildcards Jid>

    fun setAcceptedCryptoTargets(acceptedTargets: List<@JvmSuppressWildcards Jid>)

    fun findSentMessageWithUuid(id: String): MessageRef?

    fun findSentMessageWithUuidOrRemoteId(id: String, ignoreStatus: Boolean, withEdits: Boolean): MessageRef?

    fun findSentMessageWithBody(body: String): MessageRef?

    fun findMessageWithRemoteId(id: String, counterpart: Jid): MessageRef?

    fun findReceivedWithRemoteId(id: String): MessageRef?

    fun findMessageWithServerMsgId(id: String?): MessageRef?

    fun findMessageWithUuidOrRemoteId(id: String): MessageRef?

    fun findRtpSession(sessionId: String, status: Int): MessageRef?

    fun findDuplicateMessage(message: MessageRef): MessageRef?

    fun hasDuplicateMessage(message: MessageRef): Boolean

    fun findMostRecentRemoteDisplayableId(): String?

    fun prepend(offset: Int, message: MessageRef)

    // -- part 17: what the jingle and generator half of the island reads ----------------------------

    /**
     * Part 17: `MessageGenerator`, `JingleRtpConnection`, `JingleConnectionManager` and
     * `NickValidityChecker`. `MODE_SINGLE`/`MODE_MULTI`, which the same files test, are **inherited**
     * from [ConversationalRef] and deliberately not redeclared here.
     */
    fun isSingleOrPrivateAndNonAnonymous(): Boolean

    fun getOutgoingChatState(): ChatState

    fun getNextEncryption(): Int

    fun hasMessageWithCounterpart(counterpart: Jid): Boolean

    fun isWithStranger(): Boolean

    // -- part 17: the `XmppConnectionService` half -------------------------------------------------
    //
    // Every member below is a call site in `XmppConnectionService` in the same commit (ruling 3).
    // Return-position drags are noted on the member; the rest are the model's own declarations,
    // mirrored, so `Conversation` satisfies them with no body of its own.

    /**
     * Tulkki: the nested listener moves **here** from the model, and the move is the whole device.
     * `Conversation` inherits its superinterface's member types, so deleting the nested declaration
     * from the model leaves every `OnMessageFound` in its four finder methods resolving to this one -
     * no model method changes - while the island gets a name for the type it alone implements. The
     * model's `Conversation.OnMessageFound` still resolves, through the class, to this interface.
     */
    interface OnMessageFound {

        fun onMessageFound(message: MessageRef)


    }

    fun getAccountUuid(): String?

    /** Parameter widened: the model takes `Account`, the island only ever holds the ref. */
    fun setAccount(account: AccountRef?)

    /** Return-position drag: the model answers `Bookmark`, which implements [BookmarkRef]. */
    fun getBookmark(): BookmarkRef?

    fun getStatus(): Int

    fun setStatus(status: Int)

    /** The `boolean` overload; the `String` one is already above. Both answer the model's flag. */
    fun setAttribute(key: String, value: Boolean): Boolean

    fun setMode(mode: Int)

    fun setContactJid(jid: Jid)

    fun resetMucOptions()

    fun setNextMessage(input: String?): Boolean

    fun setMutedTill(value: Long)

    fun getBooleanAttribute(key: String, defaultValue: Boolean): Boolean

    fun hasMessagesLeftOnServer(): Boolean

    fun getLastMessageTransmitted(): MamReference

    fun getLastClearHistory(): MamReference

    fun setLastClearHistory(time: Long, reference: String?)

    /** The model answers the first MAM reference's *id*, a `String`, not the reference itself. */
    fun getFirstMamReference(): String?

    fun setFirstMamReference(reference: String?)

    fun getDisplayState(): String?

    fun setDisplayState(stanzaId: String?)

    fun startOtrIfNeeded(): Boolean

    /** Return-position drag: the model answers `Message`, which implements [MessageRef]. */
    fun getReplyTo(): MessageRef?

    fun getThread(): Element?

    fun getCaption(): String?

    fun getNextCounterpart(): Jid?

    /** Return-position drag: the model answers `Message`. */
    fun getLatestMessage(): MessageRef

    fun findUnsentMessageWithUuid(uuid: String): MessageRef?

    fun findMessageWithUuid(uuid: String): MessageRef?

    fun findSentMessageWithUuidOrRemoteId(id: String): MessageRef?

    fun findWaitingMessages(onMessageFound: ConversationRef.OnMessageFound)

    fun findMessagesAndCallsToNotify(onMessageFound: ConversationRef.OnMessageFound)

    fun findUnsentMessagesWithEncryption(encryptionType: Int, onMessageFound: ConversationRef.OnMessageFound)

    fun findUnsentTextMessages(onMessageFound: ConversationRef.OnMessageFound)

    fun markAsDeleted(uuids: List<String>): Boolean

    fun markAsChanged(files: List<FilePathInfoRef>): Boolean

    fun markRead(upToUuid: String?): List<@JvmSuppressWildcards MessageRef>

    fun clearMessages()

    fun expireOldMessages(timestamp: Long)

    fun sort()

    fun unreadCount(xmppConnectionService: XmppConnectionService?): Int

    fun remove(message: MessageRef)

    fun addAll(index: Int, messageRefs: List<MessageRef>, fromPagination: Boolean)

    fun jumpToHistoryPart(messages: List<MessageRef>)

    /**
     * The model's mutable `AtomicBoolean messagesLoaded` field is read twice by the island
     * (`set(true)`, `compareAndSet(true, false)`); a field cannot live on an interface, so the ref
     * carries the accessor and the model's `Conversation` answers the field.
     */
    fun messagesLoaded(): AtomicBoolean

    /** The second public `AtomicBoolean` field the widened `OnMoreMessagesLoaded` body writes. */
    fun historyPartLoadedForward(): AtomicBoolean

    /**
     * Tulkki: distinct from the model's `compareTo(Conversation)` for the reason `Hashtable` and
     * friends overload rather than override - the parameter types differ, so this is a legal
     * overload and `Collections.sort(List<ConversationRef>)`'s caller can use the natural order.
     */
    fun compareTo(other: ConversationRef): Int

    companion object {
        @JvmField
        val STATUS_AVAILABLE: Int = 0

        @JvmField
        val STATUS_ARCHIVED: Int = 1

        @JvmField
        val ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS: String = "formerly_private_non_anonymous"

    }
}
