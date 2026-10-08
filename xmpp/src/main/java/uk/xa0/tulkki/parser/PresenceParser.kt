package uk.xa0.tulkki.parser

import android.util.Log
import com.google.common.base.Strings
import java.util.ArrayList
import java.util.function.Consumer
import org.openintents.openpgp.util.OpenPgpUtils
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.app.generator.PresenceGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.models.occupant.OccupantId
import uk.xa0.tulkki.xmpp.models.stanza.Presence
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: conference and contact presence.
 *
 * Ported from `PresenceParser.java`,
 * island classification: the Java-visible surface survives member for member. It is the second of the
 * parser family to move, so `AbstractParser`'s companion members are already Kotlin and are called
 * unqualified exactly as the Java's inherited statics were.
 *
 * - `accept` keeps its `Consumer<Presence>` shape; `XmppConnection` constructs the class from Java.
 * - **Java's implicit unboxing is kept as a throw, not a `!!`.** `setLastseen(long)` takes a
 *   primitive and the three-argument `parseTimestamp` answers `Long?`; the Java's `Long` unboxed on
 *   the way in, so the call site throws the same `NullPointerException` the unboxing would have. (The
 *   value cannot in fact be null - the call passes `0L`, which is the method's own default - but the
 *   failure mode is preserved rather than papered over with a substitute value.)
 * - `x`, the MUC user element, is dereferenced in the `unavailable` branch without a null test exactly
 *   as the Java did; the Kotlin local states that with the same throw, at the first use, so the order
 *   of the two preceding reads is unmoved.
 * - The unused Java imports (`Spannable`, `SpannableString`, `RelativeSizeSpan`, `View`) do not come
 *   across; they named nothing in the body.
 */
class PresenceParser(
    service: XmppConnectionService,
    account: AccountRef,
) : AbstractParser(service, account),
    Consumer<Presence> {

    fun parseConferencePresence(packet: Presence, account: AccountRef) {
        val from = packet.getFrom()
        val conversation =
            if (from == null) {
                null
            } else {
                mXmppConnectionService.find(account, from.asBareJid())
            }
        if (conversation == null) {
            return
        }
        val mucOptions = conversation.getMucOptions()
        val before = mucOptions.online()
        val count = mucOptions.getUserCount()
        // Tulkki: 3.7 pair 9, part 11 - the wildcard is forced, not decorative. `MucOptions.getUsers`
        // returns `List<User>` and `MucOptionsRef.getUsers` declares `List<? extends UserRef>`;
        // generics are invariant, so the locals have to adopt the wildcard (the same fact that first
        // bit QuickLoader). The elements are `User`s either way.
        val tileUserBefore: List<MucOptionsRef.UserRef> = mucOptions.getUsers(5)
        processConferencePresence(packet, conversation)
        val tileUserAfter: List<MucOptionsRef.UserRef> = mucOptions.getUsers(5)
        if (Strings.isNullOrEmpty(mucOptions.getAvatar()) && tileUserAfter != tileUserBefore) {
            mXmppConnectionService.getAvatarService().clear(mucOptions)
        }
        if (before != mucOptions.online() ||
            (mucOptions.online() && count != mucOptions.getUserCount())
        ) {
            mXmppConnectionService.updateConversationUi()
        } else if (mucOptions.online()) {
            mXmppConnectionService.updateMucRosterUi()
        }
    }

    private fun processConferencePresence(packet: Presence, conversation: ConversationRef) {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val mucOptions = conversation.getMucOptions()
        val jid = account.getJid()
        val from = packet.getFrom() ?: throw NullPointerException()
        if (!from.isBareJid()) {
            val type = packet.getAttribute("type")
            val x = packet.findChild("x", Namespace.MUC_USER)
            val nick = packet.findChild("nick", Namespace.NICK)
            var hats = packet.findChild("hats", "urn:xmpp:hats:0")
            if (hats == null) {
                hats = packet.findChild("hats", "xmpp:prosody.im/protocol/hats:1")
            }
            if (hats == null) {
                hats = Element("hats", "urn:xmpp:hats:0")
            }
            val occupantIdEl = packet.findChild("occupant-id", "urn:xmpp:occupant-id:0")
            val avatar = Avatar.parsePresence(packet.findChild("x", "vcard-temp:x:update"))
            val codes = getStatusCodes(x)
            if (type == null) {
                if (x != null) {
                    val item = x.findChild("item")
                    if (item != null && !from.isBareJid()) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.NONE)
                        val user =
                            parseItem(
                                conversation,
                                item,
                                from,
                                occupantIdEl,
                                if (nick == null) null else nick.getContent(),
                                hats,
                            )
                        val occupant = packet.getExtension(OccupantId::class.java)
                        val occupantId =
                            if (mucOptions.occupantId() && occupant != null) {
                                occupant.getId()
                            } else {
                                null
                            }
                        user.setOccupantId(occupantId)
                        if (codes.contains(MucOptionsRef.STATUS_CODE_SELF_PRESENCE) ||
                            (codes.contains(MucOptionsRef.STATUS_CODE_ROOM_CREATED) &&
                                jid ==
                                Jid.Invalid.getNullForInvalid(
                                    item.getAttributeAsJid("jid"),
                                ))
                        ) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: got self-presence from" +
                                    " ${user.getFullJid()}. occupant-id=$occupantId",
                            )
                            if (mucOptions.setOnline()) {
                                mXmppConnectionService.getAvatarService().clear(mucOptions)
                            }
                            val current = mucOptions.getSelf().getFullJid()
                            if (mucOptions.setSelf(user)) {
                                Log.d(Config.LOGTAG, "role or affiliation changed")
                                (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
                            }
                            val modified = current == null || current != user.getFullJid()
                            mXmppConnectionService.persistSelfNick(user, modified)
                            invokeRenameListener(mucOptions, true)
                        }
                        val isNew = mucOptions.updateUser(user)
                        val axolotlService =
                            account.getOmemoSession()
                                ?: throw NullPointerException("account has no omemo session")
                        val contact = user.getContact()
                        val realJid = user.getRealJid()
                        if (isNew &&
                            realJid != null &&
                            mucOptions.isPrivateAndNonAnonymous() &&
                            (contact == null || !contact.mutualPresenceSubscription()) &&
                            axolotlService.hasEmptyDeviceList(realJid)
                        ) {
                            axolotlService.fetchDeviceIds(realJid)
                        }
                        if (codes.contains(MucOptionsRef.STATUS_CODE_ROOM_CREATED) &&
                            mucOptions.autoPushConfiguration()
                        ) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: room '" +
                                    "${(mucOptions.getConversation().getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()}" +
                                    "' created. pushing default configuration",
                            )
                            mXmppConnectionService.pushConferenceConfiguration(
                                mucOptions.getConversation(),
                                IqGenerator.defaultChannelConfiguration(),
                                null,
                            )
                        }
                        val pgp = mXmppConnectionService.getPgpEngine()
                        if (pgp != null) {
                            val signed = packet.findChild("x", "jabber:x:signed")
                            if (signed != null) {
                                val status = packet.findChild("status")
                                val msg = if (status == null) "" else status.getContent()
                                val keyId =
                                    pgp.fetchKeyId(
                                        mucOptions.getAccount(),
                                        msg,
                                        signed.getContent(),
                                    )
                                if (keyId != 0L) {
                                    user.setPgpKeyId(keyId)
                                }
                            }
                        }
                        if (avatar != null) {
                            avatar.owner = from
                            if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                                if (user.setAvatar(avatar)) {
                                    mXmppConnectionService.getAvatarService().clear(user)
                                }
                                if (realJid != null) {
                                    val c = account.getRoster().getContact(realJid)
                                    if (c.setAvatar(avatar)) {
                                        mXmppConnectionService.syncRoster(account)
                                        mXmppConnectionService.getAvatarService().clear(c)
                                    }
                                    mXmppConnectionService.updateRosterUi(
                                        uk.xa0.tulkki.xmpp.services.UpdateRosterReason.AVATAR,
                                    )
                                }
                            } else if (mXmppConnectionService.isDataSaverDisabled()) {
                                mXmppConnectionService.fetchAvatar(mucOptions.getAccount(), avatar)
                            }
                        }
                    }
                }
            } else if (type == "unavailable") {
                val fullJidMatches = from == mucOptions.getSelf().getFullJid()
                // The Java dereferenced `x` here without a test; state the same failure at the same
                // point rather than turning it into a `?.` that would answer false.
                val mucUserElement = x ?: throw NullPointerException()
                if (mucUserElement.hasChild("destroy") && fullJidMatches) {
                    val destroy = mucUserElement.findChild("destroy")
                    val alternate =
                        if (destroy == null) {
                            null
                        } else {
                            Jid.Invalid.getNullForInvalid(
                                destroy.getAttributeAsJid("jid"),
                            )
                        }
                    mucOptions.setError(MucOptionsRef.ErrorRef.DESTROYED)
                    if (alternate != null) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: muc destroyed. alternate location" +
                                " $alternate",
                        )
                    }
                } else if (codes.contains(MucOptionsRef.STATUS_CODE_SHUTDOWN) && fullJidMatches) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.SHUTDOWN)
                } else if (codes.contains(MucOptionsRef.STATUS_CODE_SELF_PRESENCE)) {
                    if (codes.contains(MucOptionsRef.STATUS_CODE_TECHNICAL_REASONS)) {
                        val wasOnline = mucOptions.online()
                        mucOptions.setError(MucOptionsRef.ErrorRef.TECHNICAL_PROBLEMS)
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: received status code 333 in room" +
                                " ${(mucOptions.getConversation().getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()}" +
                                " online=$wasOnline",
                        )
                        if (wasOnline) {
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation)
                        }
                    } else if (codes.contains(MucOptionsRef.STATUS_CODE_KICKED)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.KICKED)
                    } else if (codes.contains(MucOptionsRef.STATUS_CODE_BANNED)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.BANNED)
                    } else if (codes.contains(MucOptionsRef.STATUS_CODE_LOST_MEMBERSHIP)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.MEMBERS_ONLY)
                    } else if (codes.contains(MucOptionsRef.STATUS_CODE_AFFILIATION_CHANGE)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.MEMBERS_ONLY)
                    } else if (codes.contains(MucOptionsRef.STATUS_CODE_SHUTDOWN)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.SHUTDOWN)
                    } else if (!codes.contains(MucOptionsRef.STATUS_CODE_CHANGED_NICK)) {
                        mucOptions.setError(MucOptionsRef.ErrorRef.UNKNOWN)
                        Log.d(Config.LOGTAG, "unknown error in conference: $packet")
                    }
                } else if (!from.isBareJid()) {
                    val item = mucUserElement.findChild("item")
                    if (item != null) {
                        mucOptions.updateUser(
                            parseItem(
                                conversation,
                                item,
                                from,
                                occupantIdEl,
                                if (nick == null) null else nick.getContent(),
                                hats,
                            ),
                        )
                    }
                    val user = mucOptions.deleteUser(from)
                    if (user != null && occupantIdEl == null) {
                        mXmppConnectionService.getAvatarService().clear(user)
                    }
                }
            } else if (type == "error") {
                val error = packet.findChild("error")
                if (error == null) {
                    return
                }
                if (error.hasChild("conflict")) {
                    if (mucOptions.online()) {
                        invokeRenameListener(mucOptions, false)
                    } else {
                        mucOptions.setError(MucOptionsRef.ErrorRef.NICK_IN_USE)
                    }
                } else if (error.hasChild("not-authorized")) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.PASSWORD_REQUIRED)
                } else if (error.hasChild("forbidden")) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.BANNED)
                } else if (error.hasChild("registration-required")) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.MEMBERS_ONLY)
                } else if (error.hasChild("resource-constraint")) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.RESOURCE_CONSTRAINT)
                } else if (error.hasChild("remote-server-timeout")) {
                    mucOptions.setError(MucOptionsRef.ErrorRef.REMOTE_SERVER_TIMEOUT)
                } else if (error.hasChild("gone")) {
                    val gone = error.findChildContent("gone")
                    val alternate: Jid?
                    if (gone != null) {
                        val xmppUri = XmppUri(gone)
                        alternate = if (xmppUri.isValidJid()) xmppUri.getJid() else null
                    } else {
                        alternate = null
                    }
                    mucOptions.setError(MucOptionsRef.ErrorRef.DESTROYED)
                    if (alternate != null) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: muc destroyed." +
                                " alternate location $alternate",
                        )
                    }
                } else {
                    val text = error.findChildContent("text")
                    if (text != null && text.contains("attribute 'to'")) {
                        if (mucOptions.online()) {
                            invokeRenameListener(mucOptions, false)
                        } else {
                            mucOptions.setError(MucOptionsRef.ErrorRef.INVALID_NICK)
                        }
                    } else {
                        mucOptions.setError(MucOptionsRef.ErrorRef.UNKNOWN)
                        Log.d(Config.LOGTAG, "unknown error in conference: $packet")
                    }
                }
            }
        }
    }

    /**
     * Tulkki: 3.7 pair 9, part 11 - the field became an accessor and a nullable setter.
     *
     * `onRenameListener` is a **public mutable field** on the model, so no interface could carry
     * it. The ref carries `onRenameListener()` and an overloaded `setOnRenameListener(...)`, and this
     * body reads the value once into a local, which is also what the old double read did implicitly
     * (the field could not change between the test and the call on one thread).
     */
    private fun invokeRenameListener(options: MucOptionsRef, success: Boolean) {
        val listener = options.onRenameListener()
        if (listener != null) {
            if (success) {
                listener.onSuccess()
            } else {
                listener.onFailure()
            }
            options.setOnRenameListener(null)
        }
    }

    private fun getStatusCodes(x: Element?): List<String> {
        val codes = ArrayList<String>()
        if (x != null) {
            for (child in x.getChildren()) {
                if (child.getName() == "status") {
                    val code = child.getAttribute("code")
                    if (code != null) {
                        codes.add(code)
                    }
                }
            }
        }
        return codes
    }

    private fun parseContactPresence(packet: Presence, account: AccountRef) {
        val mPresenceGenerator = mXmppConnectionService.getPresenceGenerator()
        val from = packet.getFrom()
        if (from == null || from == account.getJid()) {
            return
        }
        val type = packet.getAttribute("type")
        val contact = account.getRoster().getContact(from)
        if (type == null) {
            val resource = if (from.isBareJid()) "" else from.getResource() ?: throw NullPointerException("jid has no resource")
            val avatar =
                Avatar.parsePresence(packet.findChild("x", "vcard-temp:x:update"))
            if (avatar != null && (!contact.isSelf() || account.getAvatar() == null)) {
                avatar.owner = from.asBareJid()
                if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                    if (avatar.owner == account.getJid().asBareJid()) {
                        account.setAvatar(avatar.getFilename())
                        (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                        mXmppConnectionService.getAvatarService().clear(account)
                        mXmppConnectionService.updateConversationUi()
                        mXmppConnectionService.updateAccountUi()
                    } else {
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

            if (mXmppConnectionService.isMuc(account, from)) {
                return
            }

            val sizeBefore = contact.getPresences().size()

            val show = packet.findChildContent("show")
            val caps = packet.findChild("c", "http://jabber.org/protocol/caps")
            val message = packet.findChildContent("status")
            val presence =
                XmppConnectionService.dataStatics().parsePresence(show, caps, message)
            contact.updatePresence(resource, presence)
            if (presence.hasCaps()) {
                mXmppConnectionService.fetchCaps(account, from, presence)
            }

            val idle = packet.findChild("idle", Namespace.IDLE)
            if (idle != null) {
                try {
                    val since = idle.getAttribute("since")
                    contact.setLastseen(parseTimestamp(since))
                    contact.flagInactive()
                } catch (throwable: Throwable) {
                    if (contact.setLastseen(parseTimestamp(packet))) {
                        contact.flagActive()
                    }
                }
            } else {
                if (contact.setLastseen(parseTimestamp(packet))) {
                    contact.flagActive()
                }
            }

            val pgp = mXmppConnectionService.getPgpEngine()
            val x = packet.findChild("x", "jabber:x:signed")
            if (pgp != null && x != null) {
                val status = packet.findChildContent("status")
                val keyId = pgp.fetchKeyId(account, status, x.getContent())
                if (keyId != 0L && contact.setPgpKeyId(keyId)) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: found OpenPGP key id for" +
                            " ${contact.getJid()} " +
                            OpenPgpUtils.convertKeyIdToHex(keyId),
                    )
                    mXmppConnectionService.syncRoster(account)
                }
            }

            val online = sizeBefore < contact.getPresences().size()
            mXmppConnectionService.onContactStatusChanged.onContactStatusChanged(contact, online)
        } else if (type == "unavailable") {
            val lastseen = parseTimestamp(packet, 0L, true)
            if (contact.setLastseen(lastseen ?: throw NullPointerException())) {
                contact.flagInactive()
            }
            if (from.isBareJid()) {
                contact.clearPresences()
            } else {
                contact.removePresence(from.getResource() ?: throw NullPointerException("jid has no resource"))
            }
            // Tulkki: 3.7 pair 9, part 11 - `shownStatus()`, not `getShownStatus()`, and the constant
            // is the island enum. See ContactRef.shownStatus() for why the name had to adapt while the
            // identity could not: the comparison below and the value it compares are both the
            // island's, so it is true for exactly the cases the model enum's `==` was.
            if (contact.shownStatus() == PresenceRef.StatusRef.OFFLINE) {
                contact.flagInactive()
            }
            mXmppConnectionService.onContactStatusChanged.onContactStatusChanged(contact, false)
        } else if (type == "subscribe") {
            if (contact.isBlocked()) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ignoring 'subscribe' presence from blocked" +
                        " $from",
                )
                return
            }
            if (contact.setPresenceName(packet.findChildContent("nick", Namespace.NICK))) {
                mXmppConnectionService.syncRoster(account)
                mXmppConnectionService.getAvatarService().clear(contact)
            }
            if (contact.getOption(ContactRef.OptionsRef.PREEMPTIVE_GRANT)) {
                mXmppConnectionService.sendPresencePacket(
                    account,
                    mPresenceGenerator.sendPresenceUpdatesTo(contact),
                )
            } else {
                contact.setOption(ContactRef.OptionsRef.PENDING_SUBSCRIPTION_REQUEST)
                val conversation =
                    mXmppConnectionService.findOrCreateConversation(
                        account,
                        contact.getJid().asBareJid(),
                        false,
                        false,
                    )
                val statusMessage = packet.findChildContent("status")
                if (statusMessage != null &&
                    statusMessage.isNotEmpty() &&
                    conversation.countMessages() == 0
                ) {
                    // Tulkki: 3.7 pair 9, part 11 - the parser is the third thing that *constructs* a
                    // `:data` object. An interface cannot be `new`ed, so the port owns it; the two
                    // constants travel as `int`s copied onto MessageRef, because they are compile-time
                    // constants with no identity and reading them from the model would be an
                    // `island-imports-ours` site for a number.
                    conversation.add(
                        XmppConnectionService.dataStatics()
                            .newMessage(
                                conversation,
                                statusMessage,
                                MessageRef.ENCRYPTION_NONE,
                                MessageRef.STATUS_RECEIVED,
                            ),
                    )
                }
            }
        }
        mXmppConnectionService.updateRosterUi(uk.xa0.tulkki.xmpp.services.UpdateRosterReason.PRESENCE, contact)
    }

    override fun accept(packet: Presence) {
        if (packet.hasChild("x", Namespace.MUC_USER)) {
            this.parseConferencePresence(packet, account)
        } else if (packet.hasChild("x", "http://jabber.org/protocol/muc")) {
            this.parseConferencePresence(packet, account)
        } else if ("error" == packet.getAttribute("type") &&
            mXmppConnectionService.isMuc(account, packet.getFrom())
        ) {
            this.parseConferencePresence(packet, account)
        } else {
            this.parseContactPresence(packet, account)
        }
    }
}
