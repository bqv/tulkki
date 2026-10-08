package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.mam.SyncEvents
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import java.util.function.Consumer

/**
 * Tulkki: conference administration - voice requests, affiliation and role changes, moderation, room
 * destruction and the account disconnect - lifted out of `XmppConnectionService`
 *.
 *
 * Three members it reached belong to other chunks and had no public spelling, so each travels in
 * rather than having a visibility widened: the **private** `mIqGenerator` (chunk `C55`) arrives as an
 * [IqGenerator]; the **private** `leaveMuc(ConversationRef, boolean)` (chunk `C39`) arrives as a
 * [Consumer] the service binds with the `true` its only call site passed; and the **private**
 * `sendOfflinePresence(AccountRef)` (chunk `C54`) arrives as a [Consumer] the service binds with its
 * own method reference. `disconnect` also reaches the **private**, nullable `syncEvents` (chunk
 * `C74`), which travels in by value and keeps the Java's null test at the same point. Everything
 * else is public on the service.
 *
 * [disconnect]'s guard order is the Java's - the connection is read first and answers when it is
 * null, the forced path skips the room walk and the offline presence, and the walk keeps the Java's
 * **reference** comparison of the conversation's account (`===`, never Kotlin's `==`). The three
 * callbacks are nullable exactly where the Java null-checked them; no call site moves.
 */
object ConferenceAdmin {

    @JvmStatic
    fun requestVoice(service: XmppConnectionService, account: AccountRef, jid: Jid) {
        val packet = service.getMessageGenerator().requestVoice(jid)
        service.sendMessagePacket(account, packet)
    }

    @JvmStatic
    fun changeAffiliationInConference(
        service: XmppConnectionService,
        conference: ConversationRef,
        user: Jid,
        affiliation: MucOptionsRef.AffiliationRef,
        callback: OnAffiliationChanged?,
        iqGenerator: IqGenerator,
    ) {
        val jid = user.asBareJid()
        val request = iqGenerator.changeAffiliation(conference, jid, affiliation.toString())
        service.sendIqPacket(
            conference.getAccount() ?: throw NullPointerException("conference has no account"),
            request,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val mucOptions = conference.getMucOptions()
                mucOptions.changeAffiliation(jid, affiliation)
                service.getAvatarService().clear(mucOptions)
                if (callback != null) {
                    callback.onAffiliationChangedSuccessful(jid)
                } else {
                    Log.d(Config.LOGTAG, "changed affiliation of $user to $affiliation")
                }
            } else if (callback != null) {
                callback.onAffiliationChangeFailed(jid, R.string.could_not_change_affiliation)
            } else {
                Log.d(Config.LOGTAG, "unable to change affiliation")
            }
        }
    }

    @JvmStatic
    fun changeRoleInConference(
        service: XmppConnectionService,
        conference: ConversationRef,
        nick: String,
        role: MucOptionsRef.RoleRef,
        iqGenerator: IqGenerator,
    ) {
        val account =
            conference.getAccount() ?: throw NullPointerException("conference has no account")
        val request = iqGenerator.changeRole(conference, nick, role.toString())
        service.sendIqPacket(account, request) { packet ->
            if (packet.getType() != Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, "${account.getJid().asBareJid()} unable to change role of $nick")
            }
        }
    }

    @JvmStatic
    fun moderateMessage(
        service: XmppConnectionService,
        account: AccountRef,
        m: MessageRef,
        reason: String?,
        iqGenerator: IqGenerator,
    ) {
        val request = iqGenerator.moderateMessage(account, m, reason)
        service.sendIqPacket(account, request) { packet ->
            if (packet.getType() != Iq.Type.RESULT) {
                service.showErrorToastInUi(R.string.unable_to_moderate)
                Log.d(Config.LOGTAG, "${account.getJid().asBareJid()} unable to moderate: $packet")
            }
        }
    }

    @JvmStatic
    fun destroyRoom(
        service: XmppConnectionService,
        conversation: ConversationRef,
        callback: OnRoomDestroy?,
    ) {
        val request = Iq(Iq.Type.SET)
        request.setTo(
            (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                .asBareJid(),
        )
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy")
        service.sendIqPacket(
            conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
            request,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                if (callback != null) {
                    callback.onRoomDestroySucceeded()
                }
            } else if (response.getType() == Iq.Type.ERROR) {
                if (callback != null) {
                    callback.onRoomDestroyFailed()
                }
            }
        }
    }

    @JvmStatic
    fun disconnect(
        service: XmppConnectionService,
        account: AccountRef,
        force: Boolean,
        leaveMuc: Consumer<ConversationRef>,
        sendOfflinePresence: Consumer<AccountRef>,
        syncEvents: SyncEvents?,
    ) {
        val connection = account.getXmppConnection()
        if (connection == null) {
            return
        }
        if (!force) {
            val conversationList = service.getConversationList()
            for (conversation in conversationList) {
                if (conversation.getAccount() === account) {
                    if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                        leaveMuc.accept(conversation)
                    }
                }
            }
            sendOfflinePresence.accept(account)
        }
        connection.disconnect(force)
        // Tulkki S5-4: the session is over, so every region it still had open is a region nothing can
        // prove - the ledger records them DEGRADED and the anchors stay where they are. A process
        // death instead leaves the rows OPEN, which is the same answer for the next session.
        if (syncEvents != null) {
            syncEvents.onSessionEnded(account)
        }
    }
}
