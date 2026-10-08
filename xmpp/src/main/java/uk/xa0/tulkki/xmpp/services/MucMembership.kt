package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.app.generator.PresenceGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PresenceRef

/**
 * Tulkki: the muc self nick and membership, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk reaches two **private** things beside its own refs and the public service members: the
 * shared `conversationList` (C29) — which `checkMucRequiresRename()` still locks with
 * `synchronized (list)`, on the very list object the caller holds — and `mPresenceGenerator` (C55).
 * Both arrive by hand, as C16 passed its executor. Everything else (`sendPresencePacket`,
 * `maybeRegisterWithMuc`, `createBookmark`, `joinMuc`, `dataStatics()`) is a public service member
 * resolved through the passed service.
 *
 * The two private methods keep their Java names as the service's own one-line delegations, because
 * both are called from outside the chunk (`checkMucRequiresRename(ConversationRef)` from the
 * bookmark push, `leaveMuc(ConversationRef, boolean)` from `disconnect`'s room walk). The guard
 * order of every body is the Java's, including `renameInMuc`'s early `false` on a colliding join
 * JID and `findConferenceServer`'s fallback walk that answers null rather than an empty string.
 */
object MucMembership {

    @JvmStatic
    fun persistSelfNick(
        service: XmppConnectionService,
        self: MucOptionsRef.UserRef,
        modified: Boolean,
    ) {
        val conversation = self.getConversation()
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val full = self.getFullJid() ?: throw NullPointerException("muc self has no full jid")
        if (!full.equals(conversation.getJid())) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": persisting full jid " + full)
            conversation.setContactJid(full)
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
        }

        val nick = self.getNick()
        val bookmark = conversation.getBookmark()
        if (bookmark == null || !modified) {
            return
        }
        val defaultNick = XmppConnectionService.dataStatics().defaultNick(account)
        if (nick.equals(defaultNick) || nick.equals(bookmark.getNick())) {
            return
        }
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString()
                + ": persist nick '" + full.getResource()
                + "' into bookmark for "
                + (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid(),
        )
        bookmark.setNick(nick)
        service.createBookmark(bookmark.getAccount(), bookmark)
    }

    @JvmStatic
    fun presenceToMuc(
        service: XmppConnectionService,
        conversation: ConversationRef,
        presenceGenerator: PresenceGenerator,
    ) {
        val options = conversation.getMucOptions()
        if (options.online()) {
            val account =
                conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account")
            val joinJid = options.getSelf().getFullJid()
            val packet = presenceGenerator.selfPresence(
                account,
                PresenceRef.StatusRef.ONLINE,
                options.nonanonymous(),
                options.getSelf().getNick(),
            )
            packet.setTo(joinJid)
            service.sendPresencePacket(account, packet)
        }
    }

    @JvmStatic
    fun renameInMuc(
        service: XmppConnectionService,
        conversation: ConversationRef,
        nick: String,
        callback: UiCallbackPort<ConversationRef>,
        presenceGenerator: PresenceGenerator,
    ): Boolean {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val bookmark = conversation.getBookmark()
        val options = conversation.getMucOptions()
        val joinJid = options.createJoinJid(nick)
        if (joinJid == null) {
            return false
        }
        if (options.online()) {
            service.maybeRegisterWithMuc(conversation, nick)
            options.setOnRenameListener(
                object : MucOptionsRef.OnRenameListenerRef {
                    override fun onSuccess() {
                        val packet = presenceGenerator.selfPresence(
                            account,
                            PresenceRef.StatusRef.ONLINE,
                            options.nonanonymous(),
                            nick,
                        )
                        packet.setTo(joinJid)
                        service.sendPresencePacket(account, packet)
                        callback.success(conversation)
                    }

                    override fun onFailure() {
                        callback.error(R.string.nick_in_use, conversation)
                    }
                },
            )

            val packet = presenceGenerator.selfPresence(
                account,
                PresenceRef.StatusRef.ONLINE,
                options.nonanonymous(),
                nick,
            )
            packet.setTo(joinJid)
            service.sendPresencePacket(account, packet)
            if (nick.equals(XmppConnectionService.dataStatics().defaultNick(account))
                && bookmark != null
                && bookmark.getNick() != null
            ) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString()
                        + ": removing nick from bookmark for " + bookmark.getJid(),
                )
                bookmark.setNick(null)
                service.createBookmark(account, bookmark)
            }
        } else {
            conversation.setContactJid(joinJid)
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
            if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
                if (bookmark != null) {
                    bookmark.setNick(nick)
                    service.createBookmark(account, bookmark)
                }
                service.joinMuc(conversation)
            }
        }
        return true
    }

    @JvmStatic
    fun checkMucRequiresRename(
        service: XmppConnectionService,
        conversationList: List<ConversationRef>,
        presenceGenerator: PresenceGenerator,
    ) {
        synchronized(conversationList) {
            for (conversation in conversationList) {
                if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                    checkMucRequiresRename(service, conversation, presenceGenerator)
                }
            }
        }
    }

    @JvmStatic
    fun checkMucRequiresRename(
        service: XmppConnectionService,
        conversation: ConversationRef,
        presenceGenerator: PresenceGenerator,
    ) {
        val options = conversation.getMucOptions()
        if (!options.online()) {
            return
        }
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val current = options.getActualNick()
        val proposed = options.getProposedNickPure()
        if (current == null || current.equals(proposed)) {
            return
        }
        val joinJid = options.createJoinJid(proposed)
        Log.d(
            Config.LOGTAG,
            String.format(
                "%s: muc rename required %s (was: %s)",
                account.getJid().asBareJid(),
                joinJid,
                current,
            ),
        )
        val packet = presenceGenerator.selfPresence(
            account,
            PresenceRef.StatusRef.ONLINE,
            options.nonanonymous(),
            proposed,
        )
        packet.setTo(joinJid)
        service.sendPresencePacket(account, packet)
    }

    @JvmStatic
    fun leaveMuc(
        service: XmppConnectionService,
        conversation: ConversationRef,
        now: Boolean,
        presenceGenerator: PresenceGenerator,
    ) {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        account.removeConferenceJoinPending(conversation)
        account.removeConferenceLeavePending(conversation)
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE || now) {
            service.sendPresencePacket(
                account,
                presenceGenerator.leave(conversation.getMucOptions()),
            )
            conversation.getMucOptions().setOffline()
            val bookmark = conversation.getBookmark()
            if (bookmark != null) {
                bookmark.setConversation(null)
            }
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString()
                    + ": leaving muc " + conversation.getJid(),
            )
        } else {
            account.addConferenceLeavePending(conversation)
        }
    }

    @JvmStatic
    fun findConferenceServer(account: AccountRef): String? {
        var server: String?
        val connection = account.getXmppConnection()
        if (connection != null) {
            server = connection.getMucServer()
            if (server != null) {
                return server
            }
        }
        for (other in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            val otherConnection = other.getXmppConnection()
            if (other !== account && otherConnection != null) {
                server = otherConnection.getMucServer()
                if (server != null) {
                    return server
                }
            }
        }
        return null
    }
}
