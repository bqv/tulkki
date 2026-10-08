package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.util.concurrent.Executor
import uk.xa0.tulkki.app.generator.MessageGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * Tulkki: `updateConversation`, the account reconnect and the two invites, lifted out of
 * `XmppConnectionService`.
 *
 * Three **private** things arrive by hand instead of being widened: the database-writer executor
 * (C02), the message generator (C55), and `disconnect(AccountRef, boolean)` (C41), which has not
 * moved yet and goes in as [AccountDisconnector], a `fun interface` the service implements with
 * `this::disconnect`. Everything else (`createConnection`, `hasInternetConnection`,
 * `scheduleWakeUpCall`, `sendMessagePacket`, `changeAffiliationInConference`) is a public service
 * member resolved through the passed service.
 *
 * `reconnectAccount` holds `synchronized (account)` for the whole body, exactly as the Java did, and
 * `reconnectAccountInBackground` still starts a **raw** thread — the replacing-executor refactor is
 * not this chunk's to make.
 */
object ConversationLifecycle {

    /** The private `disconnect(AccountRef, boolean)`, bound by the service to its own method. */
    fun interface AccountDisconnector {
        fun disconnect(account: AccountRef, force: Boolean)
    }

    @JvmStatic
    fun updateConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        writerExecutor: Executor,
    ) {
        writerExecutor.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation) }
    }

    @JvmStatic
    fun reconnectAccount(
        service: XmppConnectionService,
        account: AccountRef,
        force: Boolean,
        interactive: Boolean,
        disconnector: AccountDisconnector,
    ) {
        synchronized(account) {
            val existingConnection = account.getXmppConnection()
            val connection: XmppConnection
            if (existingConnection != null) {
                connection = existingConnection
            } else if (account.isConnectionEnabled()) {
                connection = service.createConnection(account)
                account.setXmppConnection(connection)
            } else {
                return
            }
            val hasInternet = service.hasInternetConnection()
            if (account.isConnectionEnabled() && hasInternet) {
                if (!force) {
                    disconnector.disconnect(account, false)
                }
                val thread = Thread(connection)
                connection.setInteractive(interactive)
                connection.prepareNewConnection()
                connection.interrupt()
                thread.start()
                service.scheduleWakeUpCall(
                    Config.CONNECT_DISCO_TIMEOUT,
                    account.getUuid().hashCode(),
                )
            } else {
                disconnector.disconnect(
                    account,
                    force || account.getTrueStatusRef().isError() || !hasInternet,
                )
                account.getRoster().clearPresences()
                connection.resetEverything()
                val axolotlService = account.getOmemoSession()
                if (axolotlService != null) {
                    axolotlService.resetBrokenness()
                }
                if (!hasInternet) {
                    account.setStatusRef(AccountRef.StateRef.NO_INTERNET)
                }
            }
        }
    }

    @JvmStatic
    fun reconnectAccountInBackground(
        service: XmppConnectionService,
        account: AccountRef,
        disconnector: AccountDisconnector,
    ) {
        Thread { reconnectAccount(service, account, false, true, disconnector) }.start()
    }

    @JvmStatic
    fun invite(
        service: XmppConnectionService,
        conversation: ConversationRef,
        contact: Jid,
        messageGenerator: MessageGenerator,
    ) {
        Log.d(
            Config.LOGTAG,
            (conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account"))
                .getJid().asBareJid().toString()
                + ": inviting " + contact + " to " +
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid(),
        )
        val user = conversation.getMucOptions().findUserByRealJid(contact.asBareJid())
        if (user == null || user.affiliation() == MucOptionsRef.AffiliationRef.OUTCAST) {
            service.changeAffiliationInConference(
                conversation,
                contact,
                MucOptionsRef.AffiliationRef.NONE,
                null,
            )
        }
        val packet = messageGenerator.invite(conversation, contact)
        service.sendMessagePacket(
            conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
            packet,
        )
    }

    @JvmStatic
    fun directInvite(
        service: XmppConnectionService,
        conversation: ConversationRef,
        jid: Jid,
        messageGenerator: MessageGenerator,
    ) {
        val packet = messageGenerator.directInvite(conversation, jid)
        service.sendMessagePacket(
            conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
            packet,
        )
    }
}
