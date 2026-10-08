package uk.xa0.tulkki.parser

import android.net.Uri
import android.os.Build
import android.text.Html
import android.util.Log
import android.util.Pair
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Objects
import java.util.UUID
import java.util.function.Consumer
import net.java.otr4j.session.SessionStatus
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.crypto.OmemoFailure
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.crypto.OmemoWire
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.mam.ReceiptRequest
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.axolotl.Encrypted
import uk.xa0.tulkki.xmpp.models.carbons.Received
import uk.xa0.tulkki.xmpp.models.carbons.Sent
import uk.xa0.tulkki.xmpp.models.forward.Forwarded
import uk.xa0.tulkki.xmpp.models.markers.Displayed
import uk.xa0.tulkki.xmpp.models.occupant.OccupantId
import uk.xa0.tulkki.xmpp.models.reactions.Reactions
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.models.stanza.Stanza
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.pep.UserTune
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.LocalizedContent
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the message parser - the incoming stanza's whole life, from the archive query that carried
 * it to the notification it may raise.
 *
 * Ported from `MessageParser.java`,
 * island classification: the Java-visible surface survives member for member. It is the last of the
 * four parser files and the one the whole row was waiting on.
 *
 * - **`deviceIds` stays a `MutableSet`.** `IqParser.deviceIds` answers one and
 *   `OmemoSessionPort.registerDevices` takes one; the local is never narrowed, because a read-only
 *   declaration here would refuse the call. (The same hole reddened `:crypto` for `IqParser`.)
 * - **Jid-plus-String concatenation at the log sites becomes a template.** A Java `Jid` is not a
 *   `CharSequence` operand Kotlin's `+` accepts, so every `jid + ": ..."` is `"$jid: ..."`; a
 *   String-on-the-left concatenation (`"Conversation not found for JID: " + from.asBareJid()`) is
 *   left alone, since Kotlin resolves `String.plus(Any?)` exactly as Java did.
 * - **`getAttribute(...)/getNamespace()` dereferences are kept.** `Element.getAttribute` and
 *   `getNamespace` answer `String?` here, and the Java called `.equals(...)`/`.getScheme().equals(...)`
 *   on them without a test. Those sites carry `?: throw NullPointerException()`, never `!!` and never
 *   a null-safe `==` that would silently answer `false` where the Java threw. The guarded sites
 *   (`x != null && x.equals(y)`) keep their null test.
 * - **The two same-named `Encrypted` classes stay apart.** `axolotl.Encrypted` is imported and named
 *   `Encrypted::class.java`; the PGP one is named by its full package at its one site. `receipts.
 *   Received` is likewise fully qualified beside the imported `carbons.Received`.
 * - **`parseTimestamp(Element, Long)` answers `Long?`** and this file is one of the callers that
 *   passes `null` and reads the `null` back; the primitive one-argument form is not used here.
 * - **The two forced shapes.** `message` starts nullable because `parseOtrChat` answers `MessageRef?`
 *   and the Java tested it before use; the timestamp is hoisted to a primitive `Long` once, because
 *   `setTime(long)`/`deliverMessage(..., long)` take primitives and the Java's unboxing would throw
 *   the same `NullPointerException` on the (impossible) null path.
 * - A few dead-in-fact checks survive their Java spelling: `items` is dereferenced through the same
 *   throw where only `node` proves it non-null, `ofrom` keeps `j.isValid(null) == true` followed by
 *   the dereference, and `query`'s non-null proof in the MAM-reload arm is stated rather than assumed.
 * - The unused Java imports (`ArrayList` is used; `HashMap`, `Arrays`) do not come across.
 */
class MessageParser(
    service: XmppConnectionService,
    account: AccountRef,
) : AbstractParser(service, account),
    Consumer<Message> {

    companion object {

        private val CLIENTS_SENDING_HTML_IN_OTR = listOf("Pidgin", "Adium", "Trillian")

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

        private val JINGLE_MESSAGE_ELEMENT_NAMES =
            listOf("accept", "propose", "proceed", "reject", "retract", "ringing", "finish")

        @JvmStatic
        private fun extractStanzaId(
            packet: Element,
            isTypeGroupChat: Boolean,
            conversation: ConversationRef,
        ): String? {
            val by: Jid
            val safeToExtract: Boolean
            if (isTypeGroupChat) {
                by = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                safeToExtract = conversation.getMucOptions().hasFeature(Namespace.STANZA_IDS)
            } else {
                val account =
                    conversation.getAccount() ?: throw NullPointerException("conversation has no account")
                by = account.getJid().asBareJid()
                safeToExtract =
                    (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                        .getFeatures()
                        .stanzaIds()
            }
            return if (safeToExtract) extractStanzaId(packet, by) else null
        }

        @JvmStatic
        private fun extractStanzaId(account: AccountRef, packet: Element): String? {
            val safeToExtract =
                (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                    .getFeatures()
                    .stanzaIds()
            return if (safeToExtract) extractStanzaId(packet, account.getJid().asBareJid()) else null
        }

        @JvmStatic
        private fun extractStanzaId(packet: Element, by: Jid): String? {
            for (child in packet.getChildren()) {
                if (child.getName() == "stanza-id" &&
                    Namespace.STANZA_IDS == child.getNamespace() &&
                    by == Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("by"))
                ) {
                    return child.getAttribute("id")
                }
            }
            return null
        }

        @JvmStatic
        private fun getTrueCounterpart(mucUserElement: Element?, fallback: Jid?): Jid? {
            val item = if (mucUserElement == null) null else mucUserElement.findChild("item")
            val result =
                if (item == null) {
                    null
                } else {
                    Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"))
                }
            return result ?: fallback
        }

        @JvmStatic
        private fun clientMightSendHtml(account: AccountRef, from: Jid): Boolean {
            val resource = from.getResource()
            if (resource == null) {
                return false
            }
            val presence =
                account.getRoster().getContact(from).getPresences().getPresencesMap().get(resource)
            val disco = presence?.getServiceDiscoveryResult()
            if (disco == null) {
                return false
            }
            return hasIdentityKnowForSendingHtml(disco.getIdentities())
        }

        @JvmStatic
        private fun hasIdentityKnowForSendingHtml(
            identities: List<out ServiceDiscoveryResultRef.IdentityRef>,
        ): Boolean {
            for (identity in identities) {
                val name = identity.getName()
                if (name != null) {
                    if (CLIENTS_SENDING_HTML_IN_OTR.contains(name)) {
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        private fun getForwardedMessagePacket(
            original: Message,
            clazz: Class<out Extension>,
        ): Pair<Message, Long?>? {
            val extension: Extension? = original.getExtension(clazz)
            val forwarded = extension?.getExtension(Forwarded::class.java)
            if (forwarded == null) {
                return null
            }
            val timestamp = AbstractParser.parseTimestamp(forwarded, null)
            val forwardedMessage = forwarded.getMessage()
            if (forwardedMessage == null) {
                return null
            }
            return Pair(forwardedMessage, timestamp)
        }

        @JvmStatic
        private fun getForwardedMessagePacket(
            original: Message,
            name: String,
            namespace: String,
        ): Pair<Message, Long?>? {
            val wrapper = original.findChild(name, namespace)
            val forwardedElement = wrapper?.findChild("forwarded", Namespace.FORWARD)
            if (forwardedElement is Forwarded) {
                val timestamp = AbstractParser.parseTimestamp(forwardedElement, null)
                val forwardedMessage = forwardedElement.getMessage()
                if (forwardedMessage == null) {
                    return null
                }
                return Pair(forwardedMessage, timestamp)
            }
            return null
        }

        @JvmStatic
        private fun parseInt(value: String): Int {
            return try {
                Integer.parseInt(value)
            } catch (e: NumberFormatException) {
                0
            }
        }
    }

    /**
     * Moved out of `Jid.Invalid` (2026-10-08, lane `G`) when `Jid` left the island for `:libs`: the
     * one member that named a wire type was `hasValidFrom(Stanza)`, and `:libs` sits below `:xmpp`
     * and may name nothing of it. The body is the Java's own, unchanged - a `from` attribute that is
     * absent answers `false`, and one that does not parse is caught exactly as before - so its three
     * callers in this file keep the same argument.
     */
    private fun hasValidFrom(stanza: Stanza): Boolean {
        val from = stanza.getAttribute("from") ?: return false
        try {
            Jid.of(from)
            return true
        } catch (e: IllegalArgumentException) {
            return false
        }
    }

    private fun extractChatState(
        c: ConversationRef?,
        isTypeGroupChat: Boolean,
        packet: Message,
    ): Boolean {
        val state = ChatState.parse(packet)
        if (state != null && c != null) {
            val account = c.getAccount() ?: throw NullPointerException("conversation has no account")
            val from = packet.getFrom() ?: throw NullPointerException()
            if (from.asBareJid() == account.getJid().asBareJid()) {
                c.setOutgoingChatState(state)
                if (state == ChatState.ACTIVE || state == ChatState.COMPOSING) {
                    if (c.getContact().isSelf()) {
                        return false
                    }
                    mXmppConnectionService.markRead(c)
                    activateGracePeriod(account)
                }
                return false
            } else {
                if (isTypeGroupChat) {
                    val user = c.getMucOptions().findUserByFullJid(from)
                    if (user != null) {
                        return user.setChatState(state)
                    } else {
                        return false
                    }
                } else {
                    return c.setIncomingChatState(state)
                }
            }
        }
        return false
    }

    private fun parseOtrChat(
        body: String,
        from: Jid,
        id: String?,
        conversation: ConversationRef,
    ): MessageRef? {
        val presence: String =
            if (from.isBareJid()) "" else (from.getResource() ?: throw NullPointerException("jid has no resource"))
        if (body.matches("^\\?OTRv\\d{1,2}\\?.*".toRegex())) {
            conversation.endOtrIfNeeded()
        }
        if (!conversation.hasValidOtrSession()) {
            conversation.startOtrSession(presence, false)
        } else {
            val foreignPresence =
                (conversation.getOtrSession() ?: throw NullPointerException("conversation has no otr session"))
                    .getSessionID()
                    .getUserID()
            if (!foreignPresence.equals(presence)) {
                conversation.endOtrIfNeeded()
                conversation.startOtrSession(presence, false)
            }
        }
        try {
            conversation.setLastReceivedOtrMessageId(id)
            val otrSession =
                conversation.getOtrSession() ?: throw NullPointerException("conversation has no otr session")
            var body2: String? = otrSession.transformReceiving(body)
            val status = otrSession.getSessionStatus()
            if (body2 == null && status == SessionStatus.ENCRYPTED) {
                mXmppConnectionService.onOtrSessionEstablished(conversation)
                return null
            } else if (body2 == null && status == SessionStatus.FINISHED) {
                conversation.resetOtrSession()
                mXmppConnectionService.updateConversationUi()
                return null
            } else if (body2 == null || (body2.isEmpty())) {
                return null
            }
            if (body2.startsWith(CryptoHelper.FILETRANSFER)) {
                val key = body2.substring(CryptoHelper.FILETRANSFER.length)
                conversation.setSymmetricKey(CryptoHelper.hexToBytes(key))
                return null
            }
            val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
            if (clientMightSendHtml(account, from)) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: received OTR message from bad behaving client. escaping HTML…",
                )
                body2 =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Html.fromHtml(body2, Html.FROM_HTML_MODE_LEGACY).toString()
                    } else {
                        Html.fromHtml(body2).toString()
                    }
            }

            val otrService =
                account.getOtrPeer() ?: throw NullPointerException("account has no otr peer")
            val finishedMessage =
                XmppConnectionService.dataStatics()
                    .newMessage(
                        conversation,
                        body2,
                        MessageRef.ENCRYPTION_OTR,
                        MessageRef.STATUS_RECEIVED,
                    )
            finishedMessage.setFingerprint(otrService.getFingerprint(otrSession.getRemotePublicKey()))
            conversation.setLastReceivedOtrMessageId(null)

            return finishedMessage
        } catch (e: Exception) {
            conversation.resetOtrSession()
            return null
        }
    }

    private fun parseAxolotlChat(
        axolotlMessage: Encrypted,
        from: Jid,
        conversation: ConversationRef,
        status: Int,
        checkedForDuplicates: Boolean,
        postpone: Boolean,
    ): MessageRef? {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val service = account.getOmemoSession() ?: throw NullPointerException("account has no omemo session")
        val xmppAxolotlMessage: OmemoWire
        try {
            xmppAxolotlMessage = service.parseWire(axolotlMessage, from.asBareJid())
        } catch (e: Exception) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: invalid omemo message received ${e.message}",
            )
            return null
        }
        if (xmppAxolotlMessage.hasPayload()) {
            val plaintextMessage: OmemoSessionPort.Plaintext?
            try {
                plaintextMessage =
                    service.processReceivingPayloadMessage(xmppAxolotlMessage, postpone)
            } catch (e: OmemoFailure.BrokenSession) {
                if (checkedForDuplicates) {
                    if (service.trustedOrPreviouslyResponded(from.asBareJid())) {
                        service.reportBrokenSessionException(e, postpone)
                        return XmppConnectionService.dataStatics()
                            .newMessage(
                                conversation,
                                "",
                                MessageRef.ENCRYPTION_AXOLOTL_FAILED,
                                status,
                            )
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "ignoring broken session exception because contact was not trusted",
                        )
                        return XmppConnectionService.dataStatics()
                            .newMessage(
                                conversation,
                                "",
                                MessageRef.ENCRYPTION_AXOLOTL_FAILED,
                                status,
                            )
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "ignoring broken session exception because checkForDuplicates failed",
                    )
                    return null
                }
            } catch (e: OmemoFailure.NotEncryptedForThisDevice) {
                return XmppConnectionService.dataStatics()
                    .newMessage(
                        conversation,
                        "",
                        MessageRef.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
                        status,
                    )
            } catch (e: OmemoFailure.OutdatedSender) {
                return XmppConnectionService.dataStatics()
                    .newMessage(conversation, "", MessageRef.ENCRYPTION_AXOLOTL_FAILED, status)
            }
            if (plaintextMessage != null) {
                val finishedMessage =
                    XmppConnectionService.dataStatics()
                        .newMessage(
                            conversation,
                            plaintextMessage.getPlaintext(),
                            MessageRef.ENCRYPTION_AXOLOTL,
                            status,
                        )
                finishedMessage.setFingerprint(plaintextMessage.getFingerprint())
                Log.d(
                    Config.LOGTAG,
                    "${service.logPrefix()} Received Message with session fingerprint: ${plaintextMessage.getFingerprint()}",
                )
                return finishedMessage
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: received OMEMO key transport message",
            )
            service.processReceivingKeyTransportMessage(xmppAxolotlMessage, postpone)
        }
        return null
    }

    private fun extractInvite(message: Element): Invite? {
        val mucUser = message.findChild("x", Namespace.MUC_USER)
        if (mucUser != null) {
            val invite = mucUser.findChild("invite")
            if (invite != null) {
                val password = mucUser.findChildContent("password")
                val from = Jid.Invalid.getNullForInvalid(invite.getAttributeAsJid("from"))
                val to = Jid.Invalid.getNullForInvalid(invite.getAttributeAsJid("to"))
                if (to != null && from == null) {
                    Log.d(Config.LOGTAG, "do not parse outgoing mediated invite $message")
                    return null
                }
                val room = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"))
                if (room == null) {
                    return null
                }
                return Invite(room, password, false, from)
            }
        }
        val conference = message.findChild("x", "jabber:x:conference")
        if (conference != null) {
            val from = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"))
            val room = Jid.Invalid.getNullForInvalid(conference.getAttributeAsJid("jid"))
            if (room == null) {
                return null
            }
            return Invite(room, conference.getAttribute("password"), true, from)
        }
        return null
    }

    private fun parseEvent(event: Element, from: Jid, account: AccountRef) {
        val items = event.findChild("items")
        val node = items?.getAttribute("node")
        if (Namespace.AVATAR_METADATA == node) {
            val avatar = Avatar.parseMetadata(items ?: throw NullPointerException())
            if (avatar != null) {
                avatar.owner = from.asBareJid()
                if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                    if (account.getJid().asBareJid() == from) {
                        if (account.setAvatar(avatar.getFilename())) {
                            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                            mXmppConnectionService.notifyAccountAvatarHasChanged(account)
                        }
                        mXmppConnectionService.getAvatarService().clear(account)
                        mXmppConnectionService.updateConversationUi()
                        mXmppConnectionService.updateAccountUi()
                    } else {
                        val contact = account.getRoster().getContact(from)
                        if (contact.setAvatar(avatar)) {
                            mXmppConnectionService.syncRoster(account)
                            mXmppConnectionService.getAvatarService().clear(contact)
                            mXmppConnectionService.updateConversationUi()
                            mXmppConnectionService.updateRosterUi(
                                uk.xa0.tulkki.xmpp.services.UpdateRosterReason.AVATAR,
                            )
                        }
                    }
                } else if (mXmppConnectionService.isDataSaverDisabled()) {
                    mXmppConnectionService.fetchAvatar(account, avatar)
                }
            }
        } else if (Namespace.NICK == node) {
            val i = (items ?: throw NullPointerException()).findChild("item")
            val nick = if (i == null) null else i.findChildContent("nick", Namespace.NICK)
            if (nick != null) {
                setNick(account, from, nick)
            }
        } else if (OmemoSessionPort.PEP_DEVICE_LIST == node) {
            val item = (items ?: throw NullPointerException()).findChild("item")
            val deviceIds: MutableSet<Int> = IqParser.deviceIds(item)
            val axolotlService =
                account.getOmemoSession() ?: throw NullPointerException("account has no omemo session")
            Log.d(
                Config.LOGTAG,
                "${axolotlService.logPrefix()}Received PEP device list $deviceIds update from $from, processing... ",
            )
            axolotlService.registerDevices(from, deviceIds)
        } else if (Namespace.BOOKMARKS == node && account.getJid().asBareJid() == from) {
            val connection =
                account.getXmppConnection() ?: throw NullPointerException("account has no connection")
            if (connection.getFeatures().bookmarksConversion()) {
                if (connection.getFeatures().bookmarks2()) {
                    Log.w(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: received storage:bookmark notification even though we opted into bookmarks:1",
                    )
                }
                val i = (items ?: throw NullPointerException()).findChild("item")
                val storage = i?.findChild("storage", Namespace.BOOKMARKS)
                val bookmarks =
                    XmppConnectionService.dataStatics()
                        .parseBookmarksFromStorage(
                            storage ?: throw NullPointerException("no bookmark storage"),
                            account)
                mXmppConnectionService.processBookmarksInitial(account, bookmarks, true)
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: processing bookmark PEP event",
                )
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignoring bookmark PEP event because bookmark conversion was not detected",
                )
            }
        } else if (Namespace.BOOKMARKS2 == node && account.getJid().asBareJid() == from) {
            val item = (items ?: throw NullPointerException()).findChild("item")
            val retract = (items ?: throw NullPointerException()).findChild("retract")
            if (item != null) {
                val bookmark =
                    XmppConnectionService.dataStatics().parseBookmarkFromItem(item, account)
                if (bookmark != null) {
                    account.putBookmark(bookmark)
                    mXmppConnectionService.processModifiedBookmark(bookmark)
                    mXmppConnectionService.updateConversationUi()
                }
            }
            if (retract != null) {
                val id = Jid.Invalid.getNullForInvalid(retract.getAttributeAsJid("id"))
                if (id != null) {
                    account.removeBookmark(id)
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: deleted bookmark for $id",
                    )
                    mXmppConnectionService.processDeletedBookmark(account, id)
                    mXmppConnectionService.updateConversationUi()
                }
            }
        } else if (Config.MESSAGE_DISPLAYED_SYNCHRONIZATION &&
            Namespace.MDS_DISPLAYED == node &&
            account.getJid().asBareJid() == from
        ) {
            val item = (items ?: throw NullPointerException()).findChild("item")
            mXmppConnectionService.processMdsItem(account, item)
        } else if (Namespace.PUBSUB_STORIES == node) {
            val retract = (items ?: throw NullPointerException()).findChild("retract")
            if (retract != null) {
                val id = retract.getAttribute("id")
                if (id != null) {
                    mXmppConnectionService.onStoryRetracted(id)
                }
            } else {
                val children = (items ?: throw NullPointerException()).getChildren()
                for (item in children) {
                    if ("item" == item.getName()) {
                        val story =
                            XmppConnectionService.dataStatics().newStory(item, from)
                        if (story != null) {
                            mXmppConnectionService.onStoryReceived(story)
                        }
                    }
                }
            }
        } else if (Namespace.USER_TUNE == node) {
            val conversation =
                mXmppConnectionService.find(account, from.asBareJid())
            if (conversation != null) { // Check if conversation exists
                val contact = conversation.getContact()
                if (contact != null) { // Check if contact exists
                    val lastTune = contact.getUserTune()
                    val thisTune = UserTune.parse(items)

                    if (!Objects.equals(lastTune, thisTune)) {
                        contact.setUserTune(UserTune.parse(items))
                        mXmppConnectionService.updateConversationUi()
                    }
                } else {
                    Log.w(
                        Config.LOGTAG,
                        "Contact not found for conversation: ${conversation.getJid()}",
                    )
                }
            } else {
                Log.w(Config.LOGTAG, "Conversation not found for JID: ${from.asBareJid()}")
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()} received pubsub notification for node=$node",
            )
        }
    }

    private fun parseDeleteEvent(event: Element, from: Jid, account: AccountRef) {
        val delete = event.findChild("delete")
        val node = delete?.getAttribute("node")
        if (Namespace.NICK == node) {
            Log.d(Config.LOGTAG, "parsing nick delete event from $from")
            setNick(account, from, null)
        } else if (Namespace.BOOKMARKS2 == node && account.getJid().asBareJid() == from) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: deleted bookmarks node")
            deleteAllBookmarks(account)
        } else if (Namespace.AVATAR_METADATA == node &&
            account.getJid().asBareJid() == from
        ) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: deleted avatar metadata node")
        }
    }

    private fun parsePurgeEvent(event: Element, from: Jid, account: AccountRef) {
        val purge = event.findChild("purge")
        val node = purge?.getAttribute("node")
        if (Namespace.BOOKMARKS2 == node && account.getJid().asBareJid() == from) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: purged bookmarks")
            deleteAllBookmarks(account)
        }
    }

    private fun deleteAllBookmarks(account: AccountRef) {
        val previous = account.getBookmarkedJids()
        account.replaceBookmarks(Collections.emptyMap<Jid, BookmarkRef>())
        mXmppConnectionService.processDeletedBookmarks(account, previous)
    }

    private fun setNick(account: AccountRef, user: Jid, nick: String?) {
        if (user.asBareJid() == account.getJid().asBareJid()) {
            account.setDisplayName(nick)
            if (mXmppConnectionService.getContactListSyncService().quicksy()) {
                mXmppConnectionService.getAvatarService().clear(account)
            }
            mXmppConnectionService.checkMucRequiresRename()
        } else {
            val contact = account.getRoster().getContact(user)
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account)
                mXmppConnectionService.getAvatarService().clear(contact)
            }
        }
        mXmppConnectionService.updateConversationUi()
        mXmppConnectionService.updateAccountUi()
    }

    private fun handleErrorMessage(account: AccountRef, packet: Message): Boolean {
        if (packet.getType() == Message.Type.ERROR) {
            if (packet.fromServer(account)) {
                val forwarded = getForwardedMessagePacket(packet, "received", Namespace.CARBONS)
                if (forwarded != null) {
                    return handleErrorMessage(account, forwarded.first)
                }
            }
            val from = packet.getFrom()
            val id = packet.getId()
            if (from != null && id != null) {
                // Tulkki: an `<error/>` names a message this device need not hold - a MAM gap,
                // another resource, a deleted or archived row - and the guard further down
                // (`if (message != null)`) is the tolerance its author already wrote; the nullable
                // lookup is what makes that guard real instead of dead against a non-null type. A
                // remote peer chooses this id, so its absence is not an internal impossibility.
                val message =
                    mXmppConnectionService.markMessageOrNull(
                        account,
                        from.asBareJid(),
                        packet.getId(),
                        MessageRef.STATUS_SEND_FAILED,
                        extractErrorMessage(packet),
                    )
                if (id.startsWith(AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    val sessionId =
                        id.substring(
                            AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length,
                        )
                    mXmppConnectionService
                        .getJingleConnectionManager()
                        .updateProposedSessionDiscovered(
                            account,
                            from,
                            sessionId,
                            JingleConnectionManager.DeviceDiscoveryState.FAILED,
                        )
                    return true
                }
                if (id.startsWith(AbstractJingleConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX)) {
                    val sessionId =
                        id.substring(
                            AbstractJingleConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX.length,
                        )
                    val errorMessage = extractErrorMessage(packet)
                    mXmppConnectionService
                        .getJingleConnectionManager()
                        .failProceed(account, from, sessionId, errorMessage)
                    return true
                }
                mXmppConnectionService.markMessageOrNull(
                    account,
                    from.asBareJid(),
                    id,
                    MessageRef.STATUS_SEND_FAILED,
                    extractErrorMessage(packet),
                )
                val error = packet.findChild("error")
                val pingWorthyError =
                    error != null &&
                        (error.hasChild("not-acceptable") ||
                            error.hasChild("remote-server-timeout") ||
                            error.hasChild("remote-server-not-found"))
                if (pingWorthyError) {
                    val conversation = mXmppConnectionService.find(account, from)
                    if (conversation != null &&
                        conversation.getMode() == ConversationalRef.MODE_MULTI
                    ) {
                        if (conversation.getMucOptions().online()) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: received ping worthy error for seemingly online muc at $from",
                            )
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation)
                        }
                    }
                }
                if (message != null) {
                    if (message.getEncryption() == MessageRef.ENCRYPTION_OTR) {
                        val conversation = message.getConversation() as ConversationRef
                        conversation.endOtrIfNeeded()
                    }
                }
            }
            return true
        }
        return false
    }

    private fun processLiveLocationUpdate(updateElement: Element) {
        val sessionId = updateElement.getAttribute("id")
        if (sessionId != null) {
            try {
                val lat = java.lang.Double.parseDouble(updateElement.getAttribute("lat"))
                val lon = java.lang.Double.parseDouble(updateElement.getAttribute("lon"))
                val mgr = mXmppConnectionService.liveLocationHook()
                if (!mgr.hasSession(sessionId)) {
                    // Recover session after restart: 1 hour default expiry
                    mgr.registerIncomingSession(
                        sessionId,
                        null,
                        null,
                        lat,
                        lon,
                        System.currentTimeMillis() + 3600_000L,
                    )
                }
                mgr.updateIncomingPosition(sessionId, lat, lon)
                val conversationUuid = mgr.sessionConversationUuid(sessionId)
                val messageUuid = mgr.sessionMessageUuid(sessionId)
                if (conversationUuid != null && messageUuid != null) {
                    mXmppConnectionService.updateMessageGeoPayload(
                        conversationUuid,
                        messageUuid,
                        lat,
                        lon,
                    )
                }
                if (mgr.isPreviewRefreshDue(sessionId, 300_000L)) {
                    mXmppConnectionService.updateConversationUi()
                }
            } catch (ignored: Exception) {
            }
        }
    }

    override fun accept(original: Message) {
        if (handleErrorMessage(account, original)) {
            return
        }

        val packet: Message
        var timestamp: Long? = null
        val isForwarded: Boolean
        var isCarbon = false
        var serverMsgId: String? = null
        val fin =
            original.findChild("fin", MessageArchiveService.Version.MAM_0.namespace)
        if (fin != null) {
            mXmppConnectionService
                .getMessageArchiveService()
                .processFinLegacy(fin, original.getFrom() ?: throw NullPointerException())
            return
        }
        val result = MessageArchiveService.Version.findResult(original)
        val queryId = result?.getAttribute("queryid")
        // Tulkki: a MAM result whose query id belongs to a language sample is read here and dropped,
        // before the lookup below can hand it to the insertion path. A query id this service knows is
        // exactly what makes upstream store what the archive returned, and a sample must never be
        // stored, shown, notified or counted.
        if (mXmppConnectionService.incomingMessageHook().offerLanguageSample(queryId, original)) {
            return
        }
        val query =
            if (queryId == null) {
                null
            } else {
                mXmppConnectionService.getMessageArchiveService().findQuery(queryId)
            }
        val offlineMessagesRetrieved =
            (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                .isOfflineMessagesRetrieved()
        if (query != null && query.validFrom(original.getFrom())) {
            val f = getForwardedMessagePacket(original, "result", query.version.namespace)
            if (f == null) {
                return
            }
            timestamp = f.second
            packet = f.first
            isForwarded = true
            serverMsgId = (result ?: throw NullPointerException()).getAttribute("id")
            query.incrementMessageCount()
            if (handleErrorMessage(account, packet)) {
                return
            }
            val packetFrom = packet.getFrom()
            val contact =
                if (packetFrom == null || packetFrom is Jid.Invalid) {
                    null
                } else {
                    account.getRoster().getContact(packetFrom)
                }
            if (contact != null && contact.isBlocked()) {
                Log.d(Config.LOGTAG, "Got MAM result from blocked contact, ignoring...")
                return
            }
        } else if (query != null) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: received mam result with invalid from (${original.getFrom()}) or queryId ($queryId)",
            )
            return
        } else if (original.fromServer(account) &&
            original.getType() != Message.Type.GROUPCHAT
        ) {
            var f = getForwardedMessagePacket(original, Received::class.java)
            if (f == null) {
                f = getForwardedMessagePacket(original, Sent::class.java)
            }
            packet = if (f != null) f.first else original
            if (handleErrorMessage(account, packet)) {
                return
            }
            timestamp = if (f != null) f.second else null
            isCarbon = f != null
            isForwarded = isCarbon
        } else {
            packet = original
            isForwarded = false
        }

        val timestampFinal: Long? =
            timestamp
                ?: AbstractParser.parseTimestamp(original, AbstractParser.parseTimestamp(packet))

        val mucUserElement = packet.findChild("x", Namespace.MUC_USER)
        val isTypeGroupChat = packet.getType() == Message.Type.GROUPCHAT
        val encrypted =
            packet.getOnlyExtension(uk.xa0.tulkki.xmpp.models.pgp.Encrypted::class.java)
        val pgpEncrypted = encrypted?.getContent()

        var replaceElement = packet.findChild("replace", "urn:xmpp:message-correct:0")
        val attachments: MutableSet<MessageRef.FileParamsRef> = LinkedHashSet()
        for (child in packet.getChildren()) {
            // SIMS first so they get preference in the set
            if (child.getName() == "reference" &&
                (child.getNamespace() ?: throw NullPointerException())
                    .equals("urn:xmpp:reference:0")
            ) {
                if (child.findChild("media-sharing", "urn:xmpp:sims:1") != null) {
                    attachments.add(XmppConnectionService.dataStatics().newFileParams(child))
                }
            }
        }
        for (child in packet.getChildren()) {
            if (child.getName() == "x" &&
                (child.getNamespace() ?: throw NullPointerException()).equals(Namespace.OOB)
            ) {
                attachments.add(XmppConnectionService.dataStatics().newFileParams(child))
            }
        }
        var replacementId = replaceElement?.getAttribute("id")
        if (replacementId == null) {
            val fasten = packet.findChild("apply-to", "urn:xmpp:fasten:0")
            if (fasten != null) {
                replaceElement = fasten.findChild("retract", "urn:xmpp:message-retract:0")
                if (replaceElement == null) {
                    replaceElement = fasten.findChild("moderated", "urn:xmpp:message-moderate:0")
                }
            }
            if (replaceElement == null) {
                replaceElement = packet.findChild("retract", "urn:xmpp:message-retract:1")
            }
            if (replaceElement == null) {
                replaceElement = packet.findChild("moderate", "urn:xmpp:message-moderate:1")
            }
            if (replaceElement != null) {
                var reason =
                    replaceElement.findChildContent("reason", "urn:xmpp:message-moderate:0")
                if (reason == null) {
                    reason = replaceElement.findChildContent("reason", "urn:xmpp:message-moderate:1")
                }
                replacementId = (if (fasten == null) replaceElement else fasten).getAttribute("id")
                packet.setBody(if (reason == null) "" else reason) // TODO: fix this
            }
        }
        var body = packet.getBody()

        val reactions = packet.getExtension(Reactions::class.java)

        val axolotlEncrypted = packet.getOnlyExtension(Encrypted::class.java)
        var status: Int
        val counterpart: Jid
        val to = packet.getTo()
        val from = packet.getFrom()
        val originId = packet.findChild("origin-id", Namespace.STANZA_IDS)
        val remoteMsgId: String?
        if (originId != null && originId.getAttribute("id") != null) {
            remoteMsgId = originId.getAttribute("id")
        } else {
            remoteMsgId = packet.getId()
        }
        var notify = false

        var html = packet.findChild("html", "http://jabber.org/protocol/xhtml-im")
        if (html != null && html.findChild("body", "http://www.w3.org/1999/xhtml") == null) {
            html = null
        }

        if (from == null || !Jid.Invalid.isValid(from) || !Jid.Invalid.isValid(to)) {
            Log.e(Config.LOGTAG, "encountered invalid message from='$from' to='$to'")
            return
        }
        if (query != null && !query.muc() && isTypeGroupChat) {
            Log.e(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: received groupchat ($from) message on regular MAM request. skipping",
            )
            return
        }
        val mucTrueCounterPart: Jid?
        val occupant: OccupantId?
        if (isTypeGroupChat) {
            val conversation =
                mXmppConnectionService.find(account, from.asBareJid())
            val mucTrueCounterPartByPresence: Jid?
            if (conversation != null) {
                val mucOptions = conversation.getMucOptions()
                occupant =
                    if (mucOptions.occupantId()) {
                        packet.getExtension(OccupantId::class.java)
                    } else {
                        null
                    }
                val user =
                    if (occupant == null) {
                        null
                    } else {
                        mucOptions.findUserByOccupantId(occupant.getId(), from)
                    }
                mucTrueCounterPartByPresence = if (user == null) null else user.getRealJid()
            } else {
                occupant = null
                mucTrueCounterPartByPresence = null
            }
            mucTrueCounterPart =
                getTrueCounterpart(
                    if (query != null && query.safeToExtractTrueCounterpart()) {
                        mucUserElement
                    } else {
                        null
                    },
                    mucTrueCounterPartByPresence,
                )
        } else if (mucUserElement != null) {
            val conversation =
                mXmppConnectionService.find(account, from.asBareJid())
            if (conversation != null) {
                val mucOptions = conversation.getMucOptions()
                occupant =
                    if (mucOptions.occupantId()) {
                        packet.getExtension(OccupantId::class.java)
                    } else {
                        null
                    }
            } else {
                occupant = null
            }
            mucTrueCounterPart = null
        } else {
            mucTrueCounterPart = null
            occupant = null
        }
        val isProperlyAddressed =
            (to != null) && (!to.isBareJid() || account.countPresences() == 0)
        val isMucStatusMessage =
            hasValidFrom(packet) &&
                from.isBareJid() &&
                mucUserElement != null &&
                mucUserElement.hasChild("status")
        var selfAddressed: Boolean
        if (packet.fromAccount(account)) {
            status = MessageRef.STATUS_SEND
            selfAddressed = to == null || account.getJid().asBareJid() == to.asBareJid()
            if (selfAddressed) {
                counterpart = from
            } else {
                counterpart = to ?: account.getJid()
            }
        } else {
            status = MessageRef.STATUS_RECEIVED
            counterpart = from
            selfAddressed = false
        }

        val invite = extractInvite(packet)
        if (invite != null) {
            val inviteJid = invite.jid ?: throw NullPointerException()
            if (inviteJid.asBareJid() == account.getJid().asBareJid()) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignore invite to $inviteJid because it matches account",
                )
            } else if (isTypeGroupChat) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignoring invite to $inviteJid because it was received as group chat",
                )
            } else if (invite.direct &&
                (mucUserElement != null ||
                    invite.inviter == null ||
                    mXmppConnectionService.isMuc(account, invite.inviter))
            ) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignoring direct invite to $inviteJid because it was received in MUC",
                )
            } else {
                invite.execute(account)
                return
            }
        }

        val conversationIsProbablyMuc =
            isTypeGroupChat ||
                mucUserElement != null ||
                (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                    .getMucServersWithholdAccount()
                    .contains(counterpart.getDomain().toString())

        val ephemeralElement = packet.findChild("ephemeral", Namespace.EPHEMERAL)
        val hasIWantOut = packet.hasChild("i-want-out", Namespace.EPHEMERAL)
        if (ephemeralElement != null || hasIWantOut) {
            val conversation =
                mXmppConnectionService.findOrCreateConversation(
                    account,
                    counterpart.asBareJid(),
                    conversationIsProbablyMuc,
                    false,
                    query,
                    false,
                )
            if (ephemeralElement != null) {
                try {
                    val timer = Integer.parseInt(ephemeralElement.getAttribute("timer"))
                    if (conversation.getMode() != ConversationalRef.MODE_MULTI ||
                        conversation.isPrivateAndNonAnonymous()
                    ) {
                        conversation.setEphemeralTimer(timer)
                        if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                            conversation.setEphemeralBy(
                                if (from.isBareJid()) null else from.getResource(),
                            )
                        } else {
                            conversation.setEphemeralBy(null)
                        }
                        (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            } else {
                conversation.setEphemeralTimer(0)
                (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
            }
            mXmppConnectionService.updateConversationUi()
        }

        // Basic visibility for voice requests
        if (body == null &&
            html == null &&
            pgpEncrypted == null &&
            axolotlEncrypted == null &&
            !isMucStatusMessage
        ) {
            val formEl = packet.findChild("x", "jabber:x:data")
            if (formEl != null) {
                val form = Data.parse(formEl) ?: throw NullPointerException()
                val role = form.getValue("muc#role")
                val nick = form.getValue("muc#roomnick")
                if ("http://jabber.org/protocol/muc#request" == form.getFormType() &&
                    "participant" == role
                ) {
                    body =
                        LocalizedContent(
                            "$nick ${mXmppConnectionService.getString(R.string.is_requesting_to_speak)}",
                            "en",
                            1,
                        )
                }
            }
        }

        // Handle live location updates silently — do not store as messages
        val liveLocUpdate = packet.findChild("live-location-update", Namespace.LIVE_LOCATION)
        if (liveLocUpdate != null && status == MessageRef.STATUS_RECEIVED && query == null) {
            processLiveLocationUpdate(liveLocUpdate)
            return
        }

        val liveLocStop = packet.findChild("live-location-stop", Namespace.LIVE_LOCATION)
        if (liveLocStop != null && status == MessageRef.STATUS_RECEIVED && query == null) {
            val sessionId = liveLocStop.getAttribute("id")
            if (sessionId != null) {
                mXmppConnectionService.liveLocationHook().expireIncomingSession(sessionId)
                mXmppConnectionService.updateConversationUi()
            }
            return
        }

        if (reactions == null &&
            (body != null ||
                pgpEncrypted != null ||
                (axolotlEncrypted != null && axolotlEncrypted.hasChild("payload")) ||
                !attachments.isEmpty() ||
                html != null ||
                (packet.hasChild("subject") && packet.hasChild("thread"))) &&
            !isMucStatusMessage
        ) {
            val conversation =
                mXmppConnectionService.findOrCreateConversation(
                    account,
                    counterpart.asBareJid(),
                    conversationIsProbablyMuc,
                    false,
                    query,
                    false,
                )
            val conversationMultiMode = conversation.getMode() == ConversationalRef.MODE_MULTI

            if (serverMsgId == null) {
                serverMsgId = extractStanzaId(packet, isTypeGroupChat, conversation)
            }

            if (selfAddressed) {
                // don’t store serverMsgId on reflections for edits
                val reflectedServerMsgId =
                    if (Strings.isNullOrEmpty(replacementId)) serverMsgId else null
                if (mXmppConnectionService.markMessage(
                        conversation,
                        remoteMsgId,
                        MessageRef.STATUS_SEND_RECEIVED,
                        reflectedServerMsgId,
                    )
                ) {
                    return
                }
                status = MessageRef.STATUS_RECEIVED
                if (remoteMsgId != null &&
                    conversation.findMessageWithRemoteId(remoteMsgId, counterpart) != null
                ) {
                    return
                }
            }

            if (isTypeGroupChat) {
                if (conversation.getMucOptions().isSelf(counterpart)) {
                    status = MessageRef.STATUS_SEND_RECEIVED
                    isCarbon = true // not really carbon but received from another resource
                    // don’t store serverMsgId on reflections for edits
                    val reflectedServerMsgId =
                        if (Strings.isNullOrEmpty(replacementId)) serverMsgId else null
                    if (mXmppConnectionService.markMessage(
                            conversation,
                            remoteMsgId,
                            status,
                            reflectedServerMsgId,
                            body,
                            html,
                            packet.findChildContent("subject"),
                            packet.findChild("thread"),
                            attachments,
                        )
                    ) {
                        return
                    } else if (remoteMsgId == null || Config.IGNORE_ID_REWRITE_IN_MUC) {
                        if (body != null) {
                            val message = conversation.findSentMessageWithBody(body.content)
                            if (message != null) {
                                mXmppConnectionService.markMessage(message, status)
                                return
                            }
                        }
                    }
                } else {
                    status = MessageRef.STATUS_RECEIVED
                }
            }
            val message: MessageRef?
            if (body != null && body.content.startsWith("?OTR") && Config.supportOtr()) {
                if (!isForwarded &&
                    !isTypeGroupChat &&
                    isProperlyAddressed &&
                    !conversationMultiMode
                ) {
                    message = parseOtrChat(body.content, from, remoteMsgId, conversation)
                    if (message == null) {
                        return
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: ignoring OTR message from $from isForwarded=$isForwarded, isProperlyAddressed=$isProperlyAddressed",
                    )
                    message =
                        XmppConnectionService.dataStatics()
                            .newMessage(
                                conversation,
                                body.content,
                                MessageRef.ENCRYPTION_NONE,
                                status,
                            )
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language)
                    }
                }
            } else if (pgpEncrypted != null && Config.supportOpenPgp()) {
                message =
                    XmppConnectionService.dataStatics()
                        .newMessage(
                            conversation,
                            pgpEncrypted,
                            MessageRef.ENCRYPTION_PGP,
                            status,
                        )
            } else if (axolotlEncrypted != null && Config.supportOmemo()) {
                var origin: Jid?
                var fallbacksBySourceId: Set<Jid> = Collections.emptySet<Jid>()
                if (conversationMultiMode) {
                    val fallback =
                        conversation.getMucOptions().getTrueCounterpart(counterpart)
                    origin =
                        getTrueCounterpart(
                            if (query != null) mucUserElement else null,
                            fallback,
                        )
                    if (origin == null) {
                        try {
                            fallbacksBySourceId =
                                (account.getOmemoSession() ?: throw NullPointerException("account has no omemo session"))
                                    .findCounterpartsBySourceId(
                                        (account.getOmemoSession() ?: throw NullPointerException("account has no omemo session"))
                                            .wireSourceId(axolotlEncrypted),
                                    )
                        } catch (e: IllegalArgumentException) {
                            // ignoring
                        }
                    }
                    if (origin == null && fallbacksBySourceId.isEmpty()) {
                        Log.d(
                            Config.LOGTAG,
                            "axolotl message in anonymous conference received and no possible fallbacks",
                        )
                        return
                    }
                } else {
                    fallbacksBySourceId = Collections.emptySet<Jid>()
                    origin = from
                }

                val liveMessage =
                    query == null && !isTypeGroupChat && mucUserElement == null
                val checkedForDuplicates =
                    liveMessage ||
                        (serverMsgId != null &&
                            remoteMsgId != null &&
                            !conversation.possibleDuplicate(serverMsgId, remoteMsgId))

                if (origin != null) {
                    message =
                        parseAxolotlChat(
                            axolotlEncrypted,
                            origin,
                            conversation,
                            status,
                            checkedForDuplicates,
                            query != null,
                        )
                } else {
                    var trial: MessageRef? = null
                    for (fallback in fallbacksBySourceId) {
                        trial =
                            parseAxolotlChat(
                                axolotlEncrypted,
                                fallback,
                                conversation,
                                status,
                                checkedForDuplicates && fallbacksBySourceId.size == 1,
                                query != null,
                            )
                        if (trial != null) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: decoded muc message using fallback",
                            )
                            origin = fallback
                            break
                        }
                    }
                    message = trial
                }
                if (message == null) {
                    if (query == null &&
                        extractChatState(
                            mXmppConnectionService.find(account, counterpart.asBareJid()),
                            isTypeGroupChat,
                            packet,
                        )
                    ) {
                        mXmppConnectionService.updateConversationUi()
                    }
                    if (query != null &&
                        status == MessageRef.STATUS_SEND &&
                        remoteMsgId != null
                    ) {
                        val previouslySent = conversation.findSentMessageWithUuid(remoteMsgId)
                        if (previouslySent != null &&
                            previouslySent.getServerMsgId() == null &&
                            serverMsgId != null
                        ) {
                            previouslySent.setServerMsgId(serverMsgId)
                            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(
                                previouslySent,
                                false,
                            )
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: encountered previously sent OMEMO message without serverId. updating...",
                            )
                        }
                    }
                    return
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin)
                }
            } else if (body == null && !attachments.isEmpty()) {
                message =
                    XmppConnectionService.dataStatics()
                        .newMessage(conversation, "", MessageRef.ENCRYPTION_NONE, status)
            } else {
                message =
                    XmppConnectionService.dataStatics()
                        .newMessage(
                            conversation,
                            body?.content,
                            MessageRef.ENCRYPTION_NONE,
                            status,
                        )
                if (body != null && body.count > 1) {
                    message.setBodyLanguage(body.language)
                }
            }

            val addresses = packet.findChild("addresses", "http://jabber.org/protocol/address")
            if (status == MessageRef.STATUS_RECEIVED && addresses != null) {
                for (address in addresses.getChildren()) {
                    if (address.getName() != "address" ||
                        (address.getNamespace() ?: throw NullPointerException()) !=
                        "http://jabber.org/protocol/address"
                    ) {
                        continue
                    }

                    if ((address.getAttribute("type") ?: throw NullPointerException())
                            .equals("ofrom") &&
                        address.getAttribute("jid") != null
                    ) {
                        val ofrom = address.getAttributeAsJid("jid")
                        if (Jid.Invalid.isValid(ofrom) &&
                            (ofrom ?: throw NullPointerException()).getDomain() ==
                            counterpart.getDomain() &&
                            (conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
                                .getRoster()
                                .getContact(counterpart.getDomain())
                                .getPresences()
                                .anySupport("http://jabber.org/protocol/address")
                        ) {
                            message.setTrueCounterpart(ofrom)
                        }
                    }
                }
            }

            if (html != null) message.addPayload(html)
            message.setSubject(packet.findChildContent("subject"))
            message.setCounterpart(counterpart)
            message.setRemoteMsgId(remoteMsgId)
            message.setServerMsgId(serverMsgId)
            message.setCarbon(isCarbon)
            message.setTime(timestampFinal ?: throw NullPointerException("timestamp"))

            if (ephemeralElement != null) {
                try {
                    val timer = Integer.parseInt(ephemeralElement.getAttribute("timer"))
                    message.setEphemeralTimer(timer)
                } catch (e: Exception) {
                    // ignore invalid timer
                }
            }

            if (isCarbon && status == MessageRef.STATUS_SEND && message.getEphemeralTimer() > 0) {
                message.setExpireAt(message.getTimeSent() + message.getEphemeralTimer() * 1000L)
            }

            if (!attachments.isEmpty()) {
                message.setFileParams(attachments.iterator().next())
                if (CryptoHelper.isPgpEncryptedUrl(message.getFileParams().url())) {
                    message.setEncryption(MessageRef.ENCRYPTION_DECRYPTED)
                }
            }
            message.setMarkable(packet.hasChild("markable", "urn:xmpp:chat-markers:0"))
            for (el in packet.getChildren()) {
                if ((el.getName() == "query" &&
                        (el.getNamespace() ?: throw NullPointerException()) ==
                        "http://jabber.org/protocol/disco#items" &&
                        (el.getAttribute("node") ?: throw NullPointerException())
                            .equals("http://jabber.org/protocol/commands")) ||
                    (el.getName() == "fallback" &&
                        (el.getNamespace() ?: throw NullPointerException()) ==
                        "urn:xmpp:fallback:0")
                ) {
                    message.addPayload(el)
                }
                if (el.getName() == "thread" &&
                    (el.getNamespace() == null || el.getNamespace() == "jabber:client")
                ) {
                    el.setAttribute("xmlns", "jabber:client")
                    message.addPayload(el)
                }
                if (el.getName() == "reply" &&
                    el.getNamespace() != null &&
                    el.getNamespace() == "urn:xmpp:reply:0"
                ) {
                    message.addPayload(el)
                    val replyId = el.getAttribute("id")
                    if (replyId != null) {
                        for (parent in mXmppConnectionService
                            .getMessageFuzzyIds(conversation, listOf(replyId))
                            .entries) {
                            message.setInReplyTo(parent.value)
                        }
                    }
                }
                if (el.getName() == "attention" &&
                    el.getNamespace() != null &&
                    el.getNamespace() == "urn:xmpp:attention:0"
                ) {
                    message.addPayload(el)
                }
                if (el.getName() == "Description" &&
                    el.getNamespace() != null &&
                    el.getNamespace() == "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                ) {
                    message.addPayload(el)
                }
            }
            if (conversationMultiMode) {
                val mucOptions = conversation.getMucOptions()
                if (occupant != null) {
                    message.setOccupantId(occupant.getId())
                }
                message.setMucUser(mucOptions.findUserByFullJid(counterpart))
                val fallback = mucOptions.getTrueCounterpart(counterpart)
                val trueCounterpart: Jid?
                if (message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL) {
                    trueCounterpart = message.getTrueCounterpart()
                } else if (query != null && query.safeToExtractTrueCounterpart()) {
                    trueCounterpart = getTrueCounterpart(mucUserElement, fallback)
                } else {
                    trueCounterpart = fallback
                }
                if (trueCounterpart != null && isTypeGroupChat) {
                    if (trueCounterpart.asBareJid() == account.getJid().asBareJid()) {
                        status =
                            if (isTypeGroupChat) {
                                MessageRef.STATUS_SEND_RECEIVED
                            } else {
                                MessageRef.STATUS_SEND
                            }
                    } else {
                        status = MessageRef.STATUS_RECEIVED
                        message.setCarbon(false)
                    }
                }
                message.setStatus(status)
                message.setTrueCounterpart(trueCounterpart)
                if (!isTypeGroupChat) {
                    message.setType(MessageRef.TYPE_PRIVATE)
                }
            } else {
                updateLastseen(account, from)
            }

            if (replacementId != null && mXmppConnectionService.allowMessageCorrection()) {
                val replacedMessage =
                    conversation.findSentMessageWithUuidOrRemoteId(replacementId, true, true)
                if (replacedMessage != null) {
                    val isRetraction =
                        replaceElement != null && replaceElement.getName() != "replace"
                    val fingerprintsMatch =
                        isRetraction ||
                            replacedMessage.getFingerprint() == null ||
                            replacedMessage.getFingerprint() == message.getFingerprint()
                    val replacedTrueCounterpart = replacedMessage.getTrueCounterpart()
                    val messageTrueCounterpart = message.getTrueCounterpart()
                    val trueCountersMatch =
                        replacedTrueCounterpart != null &&
                            messageTrueCounterpart != null &&
                            replacedTrueCounterpart.asBareJid() ==
                            messageTrueCounterpart.asBareJid()
                    val occupantIdMatch =
                        replacedMessage.getOccupantId() != null &&
                            replacedMessage.getOccupantId().equals(message.getOccupantId())
                    val mucUserMatches =
                        query == null &&
                            replacedMessage.sameMucUser(message) // can not be checked when using mam
                    val duplicate = conversation.hasDuplicateMessage(message)
                    if (fingerprintsMatch &&
                        (trueCountersMatch ||
                            occupantIdMatch ||
                            !conversationMultiMode ||
                            mucUserMatches ||
                            counterpart.isBareJid()) &&
                        !duplicate
                    ) {
                        synchronized(replacedMessage) {
                            val uuid =
                                replacedMessage.getUuid() ?: throw NullPointerException("message has no uuid")
                            // Tulkki: the queue and the translation write-back are keyed by the
                            // message uuid, so rotating it while a translation is queued would leave
                            // the item pointing at a uuid the row no longer has - an orphan that is
                            // translated into nothing. The edit is the same message; it keeps its
                            // uuid while the translation layer is still working on it.
                            if (!mXmppConnectionService.translationQueue().hasQueued(uuid)) {
                                replacedMessage.setUuid(UUID.randomUUID().toString())
                            }
                            replacedMessage.setBody(message.getBody())
                            replacedMessage.setSubject(message.getSubject())
                            replacedMessage.setThread(message.getThread())
                            replacedMessage.putEdited(
                                replacedMessage.getRemoteMsgId(),
                                replacedMessage.getServerMsgId(),
                            )
                            if (isRetraction) {
                                val liveSessionId =
                                    mXmppConnectionService
                                        .liveLocationHook()
                                        .sessionIdForMessage(
                                            replacedMessage.getUuid() ?: throw NullPointerException("message has no uuid"),
                                        )
                                if (liveSessionId != null) {
                                    mXmppConnectionService
                                        .liveLocationHook()
                                        .expireIncomingSession(liveSessionId)
                                }
                                mXmppConnectionService.getFileBackend().deleteFile(replacedMessage)
                                mXmppConnectionService.evictPreview(message.getUuid())
                                val thumbs =
                                    if (replacedMessage.getFileParams() != null) {
                                        replacedMessage.getFileParams().getThumbnails()
                                    } else {
                                        null
                                    }
                                if (thumbs != null && !thumbs.isEmpty()) {
                                    for (thumb in thumbs) {
                                        val uri = Uri.parse(thumb.getAttribute("uri"))
                                        if (uri.getScheme().equals("cid")) {
                                            val cid =
                                                mXmppConnectionService.bobTransfer().cid(uri)
                                            if (cid != null) {
                                                val f = mXmppConnectionService.getFileForCid(cid)
                                                if (f != null) {
                                                    mXmppConnectionService.evictPreview(f as File)
                                                    f.delete()
                                                }
                                            }
                                        }
                                    }
                                }
                                replacedMessage.clearPayloads()
                                replacedMessage.setFileParams(null)
                                replacedMessage.addPayload(replaceElement)

                                replacedMessage.setDeleted(true)
                                replacedMessage.setRetractId(replacementId)
                                mXmppConnectionService.deleteFileIfUnused(replacedMessage)
                                mXmppConnectionService.updateMessage(
                                    replacedMessage,
                                    replacedMessage.getUuid() ?: throw NullPointerException("message has no uuid"),
                                )
                                mXmppConnectionService
                                    .getNotificationService()
                                    .markRetracted(replacedMessage)
                            } else {
                                replacedMessage.clearPayloads()
                                for (p in message.getPayloads()) {
                                    replacedMessage.addPayload(p)
                                }
                            }
                            replacedMessage.setInReplyTo(message.getInReplyTo())

                            // we store the IDs of the replacing message. This is essentially unused
                            // today (only the fact that there are _some_ edits causes the edit icon
                            // to appear)
                            replacedMessage.putEdited(
                                message.getRemoteMsgId(),
                                message.getServerMsgId(),
                            )

                            // we used to call
                            // `replacedMessage.setServerMsgId(message.getServerMsgId());` so during
                            // catchup we could start from the edit; not the original message
                            // however this caused problems for things like reactions that refer to
                            // the serverMsgId

                            replacedMessage.setEncryption(message.getEncryption())
                            if (replacedMessage.getStatus() == MessageRef.STATUS_RECEIVED) {
                                replacedMessage.markUnread()
                            }
                            extractChatState(
                                mXmppConnectionService.find(account, counterpart.asBareJid()),
                                isTypeGroupChat,
                                packet,
                            )
                            mXmppConnectionService.updateMessage(replacedMessage, uuid)
                            // Tulkki: an edit replaces the text the stored pair describes, so the
                            // pair goes with it or the bubble shows a translation of words that are
                            // no longer the message. The corrected text is then treated as what it
                            // is - a live message from someone else - and queued once, under the same
                            // rules and the same cap as anything else. An edit is content that
                            // arrived now, not history, so covering it would take away a message the
                            // owner could read a moment ago.
                            mXmppConnectionService.translationQueue().forget(uuid)
                            mXmppConnectionService
                                .translationQueue()
                                .forget(replacedMessage.getUuid())
                            replacedMessage.setTranslatedBody(null)
                            replacedMessage.setTranslationLang(null)
                            replacedMessage.setTranslationState(MessageRef.TRANSLATION_NONE)
                            if (replacedMessage.getStatus() == MessageRef.STATUS_RECEIVED) {
                                // Only somebody else's edit is Tulkki's to translate: our own sent
                                // message goes through the send path, and asking here would log a
                                // refusal for a message that is not this pass's business.
                                mXmppConnectionService
                                    .incomingMessageHook()
                                    .onIncomingMessage(
                                        replacedMessage,
                                        query != null,
                                        packet.hasChild("delay", Namespace.DELAY) ||
                                            original.hasChild("delay", Namespace.DELAY),
                                        false,
                                    )
                            }
                            if (mXmppConnectionService.confirmMessages() &&
                                replacedMessage.getStatus() == MessageRef.STATUS_RECEIVED &&
                                (replacedMessage.trusted() ||
                                    replacedMessage.isPrivateMessage()) // TODO do we really want
                                // to send receipts for all
                                // PMs?
                                &&
                                remoteMsgId != null &&
                                !selfAddressed &&
                                !isTypeGroupChat
                            ) {
                                processMessageReceipts(account, packet, remoteMsgId, query)
                            }
                            if (replacedMessage.getEncryption() == MessageRef.ENCRYPTION_PGP) {
                                val pgpService =
                                    (conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
                                        .getPgpDecryptionService()
                                        ?: throw NullPointerException("account has no pgp decryption service")
                                pgpService.discardMessage(replacedMessage)
                                pgpService.decryptMessage(replacedMessage, false)
                            }
                        }
                        mXmppConnectionService.getNotificationService().updateNotification()
                        return
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: received message correction but verification didn't check out",
                        )
                    }
                } else if (message.getBody() == null ||
                    message.getBody() == "" ||
                    message.getBody() == " "
                ) {
                    return
                }
                if (replaceElement != null && replaceElement.getName() != "replace") return
            }

            val checkForDuplicates =
                (isTypeGroupChat && packet.hasChild("delay", "urn:xmpp:delay")) ||
                    message.isPrivateMessage() ||
                    message.getServerMsgId() != null ||
                    (query == null &&
                        mXmppConnectionService
                            .getMessageArchiveService()
                            .isCatchupInProgress(conversation))
            if (checkForDuplicates) {
                val duplicate = conversation.findDuplicateMessage(message)
                if (duplicate != null) {
                    val serverMsgIdUpdated: Boolean
                    if (duplicate.getStatus() != MessageRef.STATUS_RECEIVED &&
                        duplicate.getUuid() == message.getRemoteMsgId() &&
                        duplicate.getServerMsgId() == null &&
                        message.getServerMsgId() != null
                    ) {
                        duplicate.setServerMsgId(message.getServerMsgId())
                        if ((mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(
                                duplicate,
                                false,
                            )
                        ) {
                            serverMsgIdUpdated = true
                        } else {
                            serverMsgIdUpdated = false
                            Log.e(Config.LOGTAG, "failed to update message")
                        }
                    } else {
                        serverMsgIdUpdated = false
                    }
                    Log.d(
                        Config.LOGTAG,
                        "skipping duplicate message with ${message.getCounterpart()}. serverMsgIdUpdated=$serverMsgIdUpdated",
                    )
                    return
                }
            }

            if (query != null &&
                query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE
            ) {
                conversation.prepend(query.getActualInThisQuery(), message)
            } else {
                conversation.add(message)
            }
            if (query != null) {
                query.incrementActualMessageCount()
            }

            if (query == null || query.isCatchup()) { // either no mam or catchup
                if (status == MessageRef.STATUS_SEND || status == MessageRef.STATUS_SEND_RECEIVED) {
                    mXmppConnectionService.markRead(conversation)
                    if (query == null) {
                        activateGracePeriod(account)
                    }
                } else {
                    message.markUnread()
                    notify = true
                }
            }

            if (message.getEncryption() == MessageRef.ENCRYPTION_PGP) {
                val pgpService =
                    (conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
                        .getPgpDecryptionService()
                        ?: throw NullPointerException("account has no pgp decryption service")
                notify = pgpService.decryptMessage(message, notify)
            } else if (message.getEncryption() ==
                MessageRef.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE ||
                message.getEncryption() == MessageRef.ENCRYPTION_AXOLOTL_FAILED
            ) {
                notify = false
            }

            if (query == null) {
                extractChatState(
                    mXmppConnectionService.find(account, counterpart.asBareJid()),
                    isTypeGroupChat,
                    packet,
                )
                mXmppConnectionService.updateConversationUi()
            }

            if (mXmppConnectionService.confirmMessages() &&
                message.getStatus() == MessageRef.STATUS_RECEIVED &&
                (message.trusted() || message.isPrivateMessage()) &&
                remoteMsgId != null &&
                !selfAddressed &&
                !isTypeGroupChat
            ) {
                processMessageReceipts(account, packet, remoteMsgId, query)
            }

            if (message.getFileParams() != null) {
                for (cid in message.getFileParams().getCids()) {
                    // Tulkki: 3.7 C5-E3 - `getFileForCid` answers the ref now. `canRead()` and
                    // `getAbsolutePath()` are both on it, so this local is the ref and no `asFile()`
                    // is needed.
                    val f = mXmppConnectionService.getFileForCid(cid)
                    if (f != null && f.canRead()) {
                        message.setRelativeFilePath(f.getAbsolutePath())
                        mXmppConnectionService.getFileBackend().updateFileParams(message, null, false)
                        break
                    }
                }
            }

            val receivedOtrSession = conversation.getOtrSession()
            if (message.getStatus() == MessageRef.STATUS_RECEIVED &&
                receivedOtrSession != null &&
                !receivedOtrSession
                    .getSessionID()
                    .getUserID()
                    .equals(
                        (message.getCounterpart() ?: throw NullPointerException("message has no counterpart"))
                            .getResource(),
                    )
            ) {
                conversation.endOtrIfNeeded()
            }

            // Register incoming live-location sessions
            if (status == MessageRef.STATUS_RECEIVED && message.isGeoUri()) {
                val liveLocEl = packet.findChild("live-location", Namespace.LIVE_LOCATION)
                if (liveLocEl != null) {
                    val sessionId = liveLocEl.getAttribute("id")
                    val expiresAtStr = liveLocEl.getAttribute("expires")
                    if (sessionId != null && expiresAtStr != null) {
                        try {
                            val expiresAt = AbstractParser.parseTimestamp(expiresAtStr)
                            // Pair 15: the pattern itself is `:data`'s (GeoUris, pair 8a's move), but a
                            // static cannot live on a ref and an import line would be an
                            // island-imports-ours site, so it comes from `DataStatics` like
                            // `Patterns.AUTOLINK_WEB_URL` before it.
                            val geoMatcher =
                                XmppConnectionService.dataStatics()
                                    .geoUri()
                                    .matcher(message.getRawBody())
                            if (geoMatcher.matches()) {
                                val lat = java.lang.Double.parseDouble(geoMatcher.group(1))
                                val lon = java.lang.Double.parseDouble(geoMatcher.group(2))
                                mXmppConnectionService
                                    .liveLocationHook()
                                    .registerIncomingSession(
                                        sessionId,
                                        (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                                            .getUuid(),
                                        message.getUuid(),
                                        lat,
                                        lon,
                                        expiresAt,
                                    )
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                    message.addPayload(liveLocEl)
                }
            }

            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message)
            if (message.isEphemeral()) {
                mXmppConnectionService.scheduleNextExpiry()
            }
            // Tulkki: only a message that arrived now may be translated; the archive is never
            // translated. `query` is the MAM query that carried this message, and a XEP-0203 delay
            // stamp is how upstream itself tells a message that was delivered late from a live one
            // (see the checkForDuplicates condition above). Either signal alone disqualifies.
            mXmppConnectionService
                .incomingMessageHook()
                .onIncomingMessage(
                    message,
                    query != null,
                    packet.hasChild("delay", Namespace.DELAY) ||
                        original.hasChild("delay", Namespace.DELAY),
                    replacementId != null,
                )
            val manager = mXmppConnectionService.getHttpConnectionManager()
            if (message.trusted() &&
                message.treatAsDownloadable() &&
                manager.getAutoAcceptFileSize() > 0
            ) {
                val oob = message.getOob()
                if (oob != null &&
                    "cid".equals(oob.getScheme(), ignoreCase = true)
                ) {
                    mXmppConnectionService.bobTransfer().attachTo(message, mXmppConnectionService)
                } else {
                    manager.createNewDownloadConnection(message)
                }
            } else if (notify) {
                if (query != null && query.isCatchup()) {
                    mXmppConnectionService.getNotificationService().pushFromBacklog(message)
                } else {
                    mXmppConnectionService.getNotificationService().push(message)
                }
            }
        } else if (!packet.hasChild("body")) { // no body
            val conversation =
                mXmppConnectionService.find(account, from.asBareJid())
            if (axolotlEncrypted != null) {
                val origin: Jid
                if (conversation != null &&
                    conversation.getMode() == ConversationalRef.MODE_MULTI
                ) {
                    val fallback =
                        conversation.getMucOptions().getTrueCounterpart(counterpart)
                    val trueCounterpart =
                        getTrueCounterpart(
                            if (query != null) mucUserElement else null,
                            fallback,
                        )
                    if (trueCounterpart == null) {
                        Log.d(
                            Config.LOGTAG,
                            "omemo key transport message in anonymous conference received",
                        )
                        return
                    }
                    origin = trueCounterpart
                } else if (isTypeGroupChat) {
                    return
                } else {
                    origin = from
                }
                try {
                    val omemoSession =
                        account.getOmemoSession() ?: throw NullPointerException("account has no omemo session")
                    val xmppAxolotlMessage =
                        omemoSession.parseWire(axolotlEncrypted, origin.asBareJid())
                    omemoSession.processReceivingKeyTransportMessage(xmppAxolotlMessage, query != null)
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: omemo key transport message received from $origin",
                    )
                } catch (e: Exception) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: invalid omemo key transport message received ${e.message}",
                    )
                    return
                }
            }

            if (query == null &&
                extractChatState(
                    mXmppConnectionService.find(account, counterpart.asBareJid()),
                    isTypeGroupChat,
                    packet,
                )
            ) {
                mXmppConnectionService.updateConversationUi()
            }

            if (isTypeGroupChat) {
                if (packet.hasChild("subject") &&
                    !packet.hasChild("thread")
                ) { // We already know it has no body per above
                    if (conversation != null &&
                        conversation.getMode() == ConversationalRef.MODE_MULTI
                    ) {
                        conversation.setHasMessagesLeftOnServer(conversation.countMessages() > 0)
                        val subject =
                            packet.findInternationalizedChildContentInDefaultNamespace(
                                "subject",
                            )
                        if (subject != null &&
                            conversation.getMucOptions().setSubject(subject.content)
                        ) {
                            mXmppConnectionService.updateConversation(conversation)
                        }
                        mXmppConnectionService.updateConversationUi()
                        return
                    }
                }
            }
            if (conversation != null &&
                mucUserElement != null &&
                hasValidFrom(packet) &&
                from.isBareJid()
            ) {
                for (child in mucUserElement.getChildren()) {
                    if ("status" == child.getName()) {
                        try {
                            val code = Integer.parseInt(child.getAttribute("code"))
                            if ((code >= 170 && code <= 174) || (code >= 102 && code <= 104)) {
                                mXmppConnectionService.fetchConferenceConfiguration(conversation)
                                break
                            }
                        } catch (e: Exception) {
                            // ignored
                        }
                    } else if ("item" == child.getName()) {
                        // Tulkki: part 12 - the base class already answers the island's participant
                        // ref, so the staging cast part 11 left here is gone with the shadow field.
                        val user = AbstractParser.parseItem(conversation, child)
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid()}: changing affiliation for ${user.getRealJid()} to ${user.affiliation()} in ${(conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()}",
                        )
                        if (!user.realJidMatchesAccount()) {
                            val mucOptions = conversation.getMucOptions()
                            val isNew = mucOptions.updateUser(user)
                            val avatarService = mXmppConnectionService.getAvatarService()
                            if (Strings.isNullOrEmpty(mucOptions.getAvatar())) {
                                avatarService.clear(mucOptions)
                            }
                            avatarService.clear(user)
                            mXmppConnectionService.updateMucRosterUi()
                            mXmppConnectionService.updateConversationUi()
                            val contact = user.getContact()
                            val realJid = user.getRealJid()
                            if (!user.affiliation().ranks(MucOptionsRef.AffiliationRef.MEMBER)) {
                                val jid = user.getRealJid()
                                val cryptoTargets = ArrayList(conversation.getAcceptedCryptoTargets())
                                if (cryptoTargets.remove(
                                        user.getRealJid()
                                            ?: throw NullPointerException("user has no real jid"),
                                    )
                                ) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "${account.getJid().asBareJid()}: removed $jid from crypto targets of ${conversation.getName()}",
                                    )
                                    conversation.setAcceptedCryptoTargets(cryptoTargets)
                                    mXmppConnectionService.updateConversation(conversation)
                                }
                            } else if (isNew &&
                                realJid != null &&
                                conversation.getMucOptions().isPrivateAndNonAnonymous() &&
                                (contact == null || !contact.mutualPresenceSubscription()) &&
                                (account.getOmemoSession()
                                    ?: throw NullPointerException("account has no omemo session"))
                                    .hasEmptyDeviceList(realJid)
                            ) {
                                (account.getOmemoSession()
                                    ?: throw NullPointerException("account has no omemo session"))
                                    .fetchDeviceIds(realJid)
                            }
                        }
                    }
                }
            }
            if (!isTypeGroupChat) {
                for (child in packet.getChildren()) {
                    if (Namespace.JINGLE_MESSAGE == child.getNamespace() &&
                        JINGLE_MESSAGE_ELEMENT_NAMES.contains(child.getName())
                    ) {
                        val action = child.getName()
                        val sessionId = child.getAttribute("id")
                        if (sessionId == null) {
                            break
                        }
                        if (query == null && offlineMessagesRetrieved) {
                            if (serverMsgId == null) {
                                serverMsgId = extractStanzaId(account, packet)
                            }
                            mXmppConnectionService
                                .getJingleConnectionManager()
                                .deliverMessage(
                                    account,
                                    packet.getTo() ?: throw NullPointerException(),
                                    packet.getFrom() ?: throw NullPointerException(),
                                    child,
                                    remoteMsgId,
                                    serverMsgId,
                                    timestampFinal
                                        ?: throw NullPointerException("timestamp"),
                                )
                            val contact = account.getRoster().getContact(from)
                            // this is the same condition that is found in JingleRtpConnection for
                            // the 'ringing' response. Responding with delivery receipts predates
                            // the 'ringing' spec'd
                            val sendReceipts =
                                contact.showInContactList() ||
                                    Config.JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK
                            if (remoteMsgId != null && !contact.isSelf() && sendReceipts) {
                                processMessageReceipts(account, packet, remoteMsgId, null)
                            }
                        } else if ((query != null && query.isCatchup()) ||
                            !offlineMessagesRetrieved
                        ) {
                            if ("propose" == action) {
                                val description = child.findChild("description")
                                val namespace = description?.getNamespace()
                                if (Namespace.JINGLE_APPS_RTP == namespace) {
                                    val c =
                                        mXmppConnectionService.findOrCreateConversation(
                                            account,
                                            counterpart.asBareJid(),
                                            false,
                                            false,
                                        )
                                    val preExistingMessage = c.findRtpSession(sessionId, status)
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId)
                                        mXmppConnectionService.updateMessage(preExistingMessage)
                                        break
                                    }
                                    val message1 =
                                        XmppConnectionService.dataStatics()
                                            .newMessage(
                                                c,
                                                status,
                                                MessageRef.TYPE_RTP_SESSION,
                                                sessionId,
                                            )
                                    message1.setServerMsgId(serverMsgId)
                                    message1.setTime(timestampFinal ?: throw NullPointerException("timestamp"))
                                    message1.setBody(
                                        XmppConnectionService.dataStatics()
                                            .newRtpSessionStatus(false, 0),
                                    )
                                    message1.markUnread()
                                    c.add(message1)
                                    mXmppConnectionService
                                        .getNotificationService()
                                        .possiblyMissedCall(c.getUuid() + sessionId, message1)
                                    if (query != null) query.incrementActualMessageCount()
                                    (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message1)
                                    if (message1.isEphemeral()) {
                                        mXmppConnectionService.scheduleNextExpiry()
                                    }
                                }
                            } else if ("proceed" == action) {
                                // status needs to be flipped to find the original propose
                                val c =
                                    mXmppConnectionService.findOrCreateConversation(
                                        account,
                                        counterpart.asBareJid(),
                                        false,
                                        false,
                                    )
                                val s =
                                    if (packet.fromAccount(account)) {
                                        MessageRef.STATUS_RECEIVED
                                    } else {
                                        MessageRef.STATUS_SEND
                                    }
                                val message2 = c.findRtpSession(sessionId, s)
                                if (message2 != null) {
                                    message2.setBody(
                                        XmppConnectionService.dataStatics()
                                            .newRtpSessionStatus(true, 0),
                                    )
                                    if (serverMsgId != null) {
                                        message2.setServerMsgId(serverMsgId)
                                    }
                                    message2.setTime(timestampFinal ?: throw NullPointerException("timestamp"))
                                    message2.markRead()
                                    mXmppConnectionService
                                        .getNotificationService()
                                        .possiblyMissedCall(c.getUuid() + sessionId, message2)
                                    if (query != null) query.incrementActualMessageCount()
                                    mXmppConnectionService.updateMessage(message2, true)
                                } else {
                                    Log.d(
                                        Config.LOGTAG,
                                        "unable to find original rtp session message for received propose",
                                    )
                                }
                            } else if ("finish" == action) {
                                Log.d(
                                    Config.LOGTAG,
                                    "received JMI 'finish' during MAM catch-up. Can be used to update success/failure and duration",
                                )
                            }
                        } else {
                            // MAM reloads (non catchups
                            if ("propose" == action) {
                                val description = child.findChild("description")
                                val namespace = description?.getNamespace()
                                if (Namespace.JINGLE_APPS_RTP == namespace) {
                                    val c =
                                        mXmppConnectionService.findOrCreateConversation(
                                            account,
                                            counterpart.asBareJid(),
                                            false,
                                            false,
                                        )
                                    val preExistingMessage = c.findRtpSession(sessionId, status)
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId)
                                        mXmppConnectionService.updateMessage(preExistingMessage)
                                        break
                                    }
                                    val message3 =
                                        XmppConnectionService.dataStatics()
                                            .newMessage(
                                                c,
                                                status,
                                                MessageRef.TYPE_RTP_SESSION,
                                                sessionId,
                                            )
                                    message3.setServerMsgId(serverMsgId)
                                    message3.setTime(timestampFinal ?: throw NullPointerException("timestamp"))
                                    message3.setBody(
                                        XmppConnectionService.dataStatics()
                                            .newRtpSessionStatus(true, 0),
                                    )
                                    val q = query ?: throw NullPointerException()
                                    if (q.getPagingOrder() ==
                                        MessageArchiveService.PagingOrder.REVERSE
                                    ) {
                                        c.prepend(q.getActualInThisQuery(), message3)
                                    } else {
                                        c.add(message3)
                                    }
                                    if (query != null) query.incrementActualMessageCount()
                                    (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message3)
                                    if (message3.isEphemeral()) {
                                        mXmppConnectionService.scheduleNextExpiry()
                                    }
                                }
                            }
                        }
                        break
                    }
                }
            }

            val received =
                packet.getExtension(uk.xa0.tulkki.xmpp.models.receipts.Received::class.java)
            if (received != null) {
                processReceived(received, packet, query, from)
            }
            val displayed = packet.getExtension(Displayed::class.java)
            if (displayed != null) {
                processDisplayed(
                    displayed,
                    packet,
                    selfAddressed,
                    counterpart,
                    query,
                    isTypeGroupChat,
                    conversation,
                    mucUserElement,
                    from,
                )
            }

            // end no body
        }

        if (reactions != null) {
            processReactions(
                reactions,
                mXmppConnectionService.find(account, counterpart.asBareJid()),
                isTypeGroupChat,
                occupant,
                counterpart,
                mucTrueCounterPart,
                status,
                packet,
            )
        }

        val event =
            original.findChild("event", "http://jabber.org/protocol/pubsub#event")
        if (event != null) {
            val items = event.findChild("items")
            if (items != null) {
                val node = items.getAttribute("node")
                if (node != null && node == Namespace.ATOM ||
                    node != null && node.startsWith("urn:xmpp:microblog:0") ||
                    node != null && node.startsWith(Namespace.PUBSUB_SOCIAL_FEED)
                ) {
                    for (child in items.getChildren()) {
                        if ("item" == child.getName()) {
                            val entry = child.findChild("entry", Namespace.ATOM)
                            if (entry != null) {
                                try {
                                    val inReplyTo =
                                        entry.findChild(
                                            "in-reply-to",
                                            "http://purl.org/syndication/thread/1.0",
                                        )
                                    if (inReplyTo != null) {
                                        val comment =
                                            XmppConnectionService.dataStatics()
                                                .newComment(entry)
                                        var originalPostUuid = inReplyTo.getAttribute("ref")
                                        if (originalPostUuid != null &&
                                            originalPostUuid.startsWith("urn:uuid:")
                                        ) {
                                            originalPostUuid = originalPostUuid.substring(9)
                                        }
                                        mXmppConnectionService.notifyOnCommentReceived(
                                            originalPostUuid,
                                            comment,
                                        )
                                    } else {
                                        // Handle items that are not comments as new posts.
                                        val post =
                                            XmppConnectionService.dataStatics()
                                                .newPost(child)
                                        if (post != null) {
                                            mXmppConnectionService.onPostReceived(post, account)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "error creating post/comment from pubsub item in message",
                                        e,
                                    )
                                }
                            }
                        } else if ("retract" == child.getName()) {
                            val postId = child.getAttribute("id")
                            if (postId != null) {
                                mXmppConnectionService.onPostRetracted(postId)
                            }
                        }
                    }
                } else {
                    parseEvent(event, original.getFrom() ?: throw NullPointerException(), account)
                }
            } else if (event.hasChild("delete")) {
                parseDeleteEvent(
                    event,
                    original.getFrom() ?: throw NullPointerException(),
                    account,
                )
            } else if (event.hasChild("purge")) {
                parsePurgeEvent(
                    event,
                    original.getFrom() ?: throw NullPointerException(),
                    account,
                )
            }
        }

        val nick = packet.findChildContent("nick", Namespace.NICK)
        if (nick != null && hasValidFrom(original)) {
            if (mXmppConnectionService.isMuc(account, from)) {
                return
            }
            val contact = account.getRoster().getContact(from)
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account)
                mXmppConnectionService.getAvatarService().clear(contact)
            }
        }
    }

    private fun processReceived(
        received: uk.xa0.tulkki.xmpp.models.receipts.Received,
        packet: Message,
        query: MessageArchiveService.Query?,
        from: Jid,
    ) {
        val id = received.getId()
        if (packet.fromAccount(account)) {
            if (query != null && id != null && packet.getTo() != null) {
                query.removePendingReceiptRequest(ReceiptRequest(packet.getTo(), id))
            }
        } else if (id != null) {
            if (id.startsWith(AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                val sessionId =
                    id.substring(AbstractJingleConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length)
                mXmppConnectionService
                    .getJingleConnectionManager()
                    .updateProposedSessionDiscovered(
                        account,
                        from,
                        sessionId,
                        JingleConnectionManager.DeviceDiscoveryState.DISCOVERED,
                    )
            } else {
                // Tulkki: a `<received/>` (XEP-0184) for a uuid this device does not hold - a MAM
                // gap, another resource, a deleted or archived row - marks nothing and is not
                // fatal: the peer chose the id, so its absence here is an ordinary answer.
                mXmppConnectionService.markMessageOrNull(
                    account,
                    from.asBareJid(),
                    id,
                    MessageRef.STATUS_SEND_RECEIVED,
                )
            }
        }
    }

    private fun processDisplayed(
        displayed: Displayed,
        packet: Message,
        selfAddressed: Boolean,
        counterpart: Jid,
        query: MessageArchiveService.Query?,
        isTypeGroupChat: Boolean,
        conversation: ConversationRef?,
        mucUserElement: Element?,
        from: Jid,
    ) {
        val id = displayed.getId()
        // TODO we don’t even use 'sender' any more. Remove this!
        val sender = Jid.Invalid.getNullForInvalid(displayed.getAttributeAsJid("sender"))
        if (packet.fromAccount(account) && !selfAddressed) {
            val c = mXmppConnectionService.find(account, counterpart.asBareJid())
            val message =
                if (c == null || id == null) null else c.findReceivedWithRemoteId(id)
            if (message != null && (query == null || query.isCatchup())) {
                mXmppConnectionService.markReadUpTo(c, message)
            }
            if (query == null) {
                activateGracePeriod(account)
            }
        } else if (isTypeGroupChat) {
            val message: MessageRef?
            if (conversation != null && id != null) {
                if (sender != null) {
                    message = conversation.findMessageWithRemoteId(id, sender)
                } else {
                    message = conversation.findMessageWithServerMsgId(id)
                }
            } else {
                message = null
            }
            if (message != null) {
                // `message` is only ever assigned when `conversation` was non-null, so this is the
                // Java's dereference stated rather than assumed.
                val c = conversation ?: throw NullPointerException()
                // TODO use occupantId to extract true counterpart from presence
                val fallback = c.getMucOptions().getTrueCounterpart(counterpart)
                // TODO try to externalize mucTrueCounterpart
                val trueJid =
                    getTrueCounterpart(
                        if (query != null && query.safeToExtractTrueCounterpart()) {
                            mucUserElement
                        } else {
                            null
                        },
                        fallback,
                    )
                val trueJidMatchesAccount =
                    account.getJid().asBareJid() == trueJid?.asBareJid()
                if (trueJidMatchesAccount || c.getMucOptions().isSelf(counterpart)) {
                    if (!message.isRead() &&
                        (query == null || query.isCatchup())
                    ) { // checking if message is
                        // unread fixes race conditions
                        // with reflections
                        mXmppConnectionService.markReadUpTo(c, message)
                    }
                } else if (!counterpart.isBareJid() && trueJid != null) {
                    if (message.addReadByMarker(counterpart, trueJid)) {
                        val mucOptions = c.getMucOptions()
                        val everyone = ImmutableSet.copyOf(mucOptions.getMembers(false))
                        val readyBy = message.getReadyByTrue()
                        val mStatus = message.getStatus()
                        if (mucOptions.isPrivateAndNonAnonymous() &&
                            (mStatus == MessageRef.STATUS_SEND_RECEIVED ||
                                mStatus == MessageRef.STATUS_SEND) &&
                            readyBy.containsAll(everyone)
                        ) {
                            message.setStatus(MessageRef.STATUS_SEND_DISPLAYED)
                        }
                        mXmppConnectionService.updateMessage(message, false)
                    }
                }
            }
        } else {
            // Tulkki: the nullable lookup, not the throwing overload. A `<displayed/>` for a uuid
            // this device does not hold - a MAM gap, another resource, an archived or deleted row -
            // is not an error: there is nothing here to mark, and the receipt must not kill the
            // connection thread before the stanza can be acknowledged and replayed forever.
            val displayedMessage =
                mXmppConnectionService.markMessageOrNull(
                    account,
                    from.asBareJid(),
                    id,
                    MessageRef.STATUS_SEND_DISPLAYED,
                )
            if (displayedMessage != null) {
                var message = displayedMessage.prev()
                while (message != null &&
                    message.getStatus() == MessageRef.STATUS_SEND_RECEIVED &&
                    message.getTimeSent() < displayedMessage.getTimeSent()
                ) {
                    mXmppConnectionService.markMessage(message, MessageRef.STATUS_SEND_DISPLAYED)
                    message = message.prev()
                }
                if (selfAddressed) {
                    dismissNotification(account, counterpart, query, id)
                }
            }
        }
    }

    private fun processReactions(
        reactions: Reactions,
        conversation: ConversationRef?,
        isTypeGroupChat: Boolean,
        occupant: OccupantId?,
        counterpart: Jid,
        mucTrueCounterPart: Jid?,
        status: Int,
        packet: Message,
    ) {
        val reactingTo = reactions.getId()
        if (conversation != null && reactingTo != null) {
            if (isTypeGroupChat && conversation.getMode() == ConversationalRef.MODE_MULTI) {
                val mucOptions = conversation.getMucOptions()
                val occupantId = occupant?.getId()
                if (occupantId != null) {
                    val isReceived = !mucOptions.isSelf(occupantId)
                    val inMemoryMessage = conversation.findMessageWithServerMsgId(reactingTo)
                    val message =
                        inMemoryMessage
                            ?: (
                                mXmppConnectionService.databaseBackend
                                    ?: throw NullPointerException("database backend is not open")
                            )
                                .getMessageWithServerMsgId(conversation, reactingTo)
                    if (message != null) {
                        if (message.isDeleted()) {
                            Log.d(
                                Config.LOGTAG,
                                "ignoring reaction to deleted/expired message $reactingTo",
                            )
                            return
                        }
                        val newReactions = HashSet(reactions.getReactions())
                        // Kotlin collection ops rather than the Java stream: `reaction()` is
                        // `String?` now, and a null reaction matched no set element anyway.
                        newReactions.removeAll(
                            message
                                .getReactions()
                                .filter { occupantId == it.occupantId() }
                                .mapNotNull { it.reaction() },
                        )
                        val combinedReactions =
                            XmppConnectionService.dataStatics()
                                .reactionsWithOccupantId(
                                    message.getReactions(),
                                    reactions.getReactions(),
                                    isReceived,
                                    counterpart,
                                    mucTrueCounterPart,
                                    occupantId,
                                    message.getRemoteMsgId(),
                                )
                        message.replaceReactions(combinedReactions)
                        mXmppConnectionService.updateMessage(message, false)
                        if (isReceived) {
                            mXmppConnectionService
                                .getNotificationService()
                                .push(message, counterpart, occupantId, newReactions)
                        }
                    } else {
                        Log.d(Config.LOGTAG, "message with id $reactingTo not found")
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "received reaction in channel w/o occupant ids. ignoring",
                    )
                }
            } else {
                val inMemoryMessage = conversation.findMessageWithUuidOrRemoteId(reactingTo)
                val message =
                    inMemoryMessage
                        ?: (
                            mXmppConnectionService.databaseBackend
                                ?: throw NullPointerException("database backend is not open")
                        )
                            .getMessageWithUuidOrRemoteId(conversation, reactingTo)
                if (message == null) {
                    Log.d(Config.LOGTAG, "message with id $reactingTo not found")
                    return
                }
                if (message.isDeleted()) {
                    Log.d(
                        Config.LOGTAG,
                        "ignoring reaction to deleted/expired message $reactingTo",
                    )
                    return
                }
                val isReceived: Boolean
                val reactionFrom: Jid
                if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                    Log.d(
                        Config.LOGTAG,
                        "received reaction as MUC PM. triggering validation",
                    )
                    val mucOptions = conversation.getMucOptions()
                    val occupantId = occupant?.getId()
                    if (occupantId == null) {
                        Log.d(
                            Config.LOGTAG,
                            "received reaction via PM channel w/o occupant ids. ignoring",
                        )
                        return
                    }
                    isReceived = !mucOptions.isSelf(occupantId)
                    if (isReceived) {
                        reactionFrom = counterpart
                    } else {
                        if (occupantId != message.getOccupantId()) {
                            Log.d(
                                Config.LOGTAG,
                                "reaction received via MUC PM did not pass validation",
                            )
                            return
                        }
                        reactionFrom = account.getJid().asBareJid()
                    }
                } else {
                    if (packet.fromAccount(account)) {
                        isReceived = false
                        reactionFrom = account.getJid().asBareJid()
                    } else {
                        isReceived = true
                        reactionFrom = counterpart
                    }
                }
                val newReactions = HashSet(reactions.getReactions())
                newReactions.removeAll(
                    message
                        .getReactions()
                        .filter { reactionFrom == it.from() }
                        .mapNotNull { it.reaction() },
                )
                val combinedReactions =
                    XmppConnectionService.dataStatics()
                        .reactionsWithFrom(
                            message.getReactions(),
                            reactions.getReactions(),
                            isReceived,
                            reactionFrom,
                            message.getRemoteMsgId(),
                        )
                message.replaceReactions(combinedReactions)
                mXmppConnectionService.updateMessage(message, false)
                if (status < MessageRef.STATUS_SEND) {
                    mXmppConnectionService
                        .getNotificationService()
                        .push(message, counterpart, null, newReactions)
                }
            }
        }
    }

    private fun dismissNotification(
        account: AccountRef,
        counterpart: Jid,
        query: MessageArchiveService.Query?,
        id: String?,
    ) {
        val conversation =
            mXmppConnectionService.find(account, counterpart.asBareJid())
        if (conversation != null && (query == null || query.isCatchup())) {
            val displayableId = conversation.findMostRecentRemoteDisplayableId()
            if (displayableId != null && displayableId == id) {
                mXmppConnectionService.markRead(conversation)
            } else {
                Log.w(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: received dismissing display marker that did not match our last id in that conversation",
                )
            }
        }
    }

    private fun processMessageReceipts(
        account: AccountRef,
        packet: Message,
        remoteMsgId: String?,
        query: MessageArchiveService.Query?,
    ) {
        val markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0")
        val request = packet.hasChild("request", "urn:xmpp:receipts")
        if (query == null) {
            val receiptsNamespaces = ArrayList<String>()
            if (markable) {
                receiptsNamespaces.add("urn:xmpp:chat-markers:0")
            }
            if (request) {
                receiptsNamespaces.add("urn:xmpp:receipts")
            }
            if (receiptsNamespaces.size > 0) {
                val receipt =
                    mXmppConnectionService
                        .getMessageGenerator()
                        .received(
                            account,
                            packet.getFrom() ?: throw NullPointerException(),
                            remoteMsgId,
                            receiptsNamespaces,
                            packet.getType() ?: throw NullPointerException(),
                        )
                mXmppConnectionService.sendMessagePacket(account, receipt)
            }
        } else if (query.isCatchup()) {
            if (request) {
                query.addPendingReceiptRequest(ReceiptRequest(packet.getFrom(), remoteMsgId))
            }
        }
    }

    private fun activateGracePeriod(account: AccountRef) {
        val duration =
            mXmppConnectionService.getLongPreference(
                "grace_period_length",
                R.integer.grace_period,
            ) * 1000
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: activating grace period till ${TIME_FORMAT.format(Date(System.currentTimeMillis() + duration))}",
        )
        account.activateGracePeriod(duration)
    }

    private inner class Invite(
        val jid: Jid?,
        val password: String?,
        val direct: Boolean,
        val inviter: Jid?,
    ) {
        fun execute(account: AccountRef): Boolean {
            if (this.jid == null) {
                return false
            }
            val contact =
                if (this.inviter != null) account.getRoster().getContact(this.inviter) else null
            if (contact != null && contact.isBlocked()) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignore invite from ${contact.getJid()} because contact is blocked",
                )
                return false
            }
            val conversation =
                mXmppConnectionService.findOrCreateConversation(account, jid, true, false)
            conversation.setAttribute("inviter", (inviter ?: throw NullPointerException()).toString())
            if (conversation.getMucOptions().online()) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: received invite to $jid but muc is considered to be online",
                )
                mXmppConnectionService.mucSelfPingAndRejoin(conversation)
            } else {
                conversation.getMucOptions().setPassword(password)
                (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
                mXmppConnectionService.joinMuc(
                    conversation,
                    contact != null && contact.showInContactList(),
                )
                mXmppConnectionService.updateConversationUi()
            }
            return true
        }
    }
}
