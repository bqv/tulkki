package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the muc self ping and the three join entry points, lifted out of `XmppConnectionService`
 *.
 *
 * The one seam is chunk `C36b`'s **private** three-argument join, the real 148-line join. It does
 * not move with this chunk, so it travels in as [MucJoiner] and the service supplies it with its
 * own `this::joinMuc` method reference: no visibility is widened, and the private 148-line body
 * stays where the next chunk will take it. The public entry points keep their names and signatures
 * on the service as one-line delegations, so no call site moves.
 *
 * The self ping's guard order is the Java's: a join already under way cancels the ping, a ping
 * already under way cancels it, and the rejoin is attempted only for an error the server did not
 * name as ignorable. The response callback removes the in-progress marker on every path, as before.
 */
object MucJoinEntry {

    /**
     * Chunk `C36b`'s private three-argument join, supplied by the service's delegations.
     * `onConferenceJoined` is nullable because the public entry points pass null for it — the
     * private 148-line join accepts that, and the Java passed it unchanged.
     */
    fun interface MucJoiner {
        fun join(
            conversation: ConversationRef,
            onConferenceJoined: OnConferenceJoined?,
            followedInvite: Boolean,
        )
    }

    @JvmStatic
    fun joinMuc(conversation: ConversationRef, followedInvite: Boolean, joiner: MucJoiner) {
        joiner.join(conversation, null, followedInvite)
    }

    @JvmStatic
    fun joinMucWithCallback(
        conversation: ConversationRef,
        onConferenceJoined: OnConferenceJoined,
        joiner: MucJoiner,
    ) {
        joiner.join(conversation, onConferenceJoined, false)
    }

    @JvmStatic
    fun mucSelfPingAndRejoin(
        service: XmppConnectionService,
        conversation: ConversationRef,
        joiner: MucJoiner,
    ) {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        if (account.isConferenceJoinInProgress(conversation)) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: canceling muc self ping because join is already under way",
            )
            return
        }
        if (!account.addConferencePingInProgress(conversation)) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: canceling muc self ping because ping is already under way",
            )
            return
        }
        val self = conversation.getMucOptions().getSelf().getFullJid()
        val ping = Iq(Iq.Type.GET)
        ping.setTo(self)
        ping.addChild("ping", Namespace.PING)
        service.sendIqPacket(account, ping) { response ->
            if (response.getType() == Iq.Type.ERROR) {
                val error = response.getError()
                if (error == null
                    || error.hasChild("service-unavailable")
                    || error.hasChild("feature-not-implemented")
                    || error.hasChild("item-not-found")
                ) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: ping to $self came back as ignorable error",
                    )
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: ping to $self failed. attempting rejoin",
                    )
                    joinMuc(conversation, false, joiner)
                }
            } else if (response.getType() == Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: ping to $self came back fine",
                )
            }
            account.removeConferencePingInProgress(conversation)
        }
    }
}
