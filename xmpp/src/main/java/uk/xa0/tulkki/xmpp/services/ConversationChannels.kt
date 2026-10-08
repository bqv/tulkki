package uk.xa0.tulkki.xmpp.services

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import java.util.function.Consumer
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * Tulkki: creating channels and ad-hoc conferences, lifted out of `XmppConnectionService`
 *.
 *
 * Both creation paths build the conversation through the public `findOrCreateConversation` and then
 * dial the **private** two-argument `joinMuc(ConversationRef, OnConferenceJoined)`, which is C36b's
 * real join and has not moved yet: it goes in as [MucJoinerWithCallback], a `fun interface` the
 * service implements with `this::joinMuc`, so that private body stays where the next chunk will take
 * it. `checkIfMuc` reads the **private** `mIqGenerator` (C55) and takes it by hand.
 *
 * Everything else the three bodies reach is a public service member (`findOrCreateConversation`,
 * `joinMuc(ConversationRef)`, `pushConferenceConfiguration`, `saveConversationAsBookmark`,
 * `archiveConversation`, `invite`, `directInvite`) resolved on the caller's side of the seam and
 * passed in as the service itself, exactly as `RosterSync` does.
 *
 * The Java's guard order is the Java's: `createAdhocConference` answers `false` before it builds
 * anything unless the account is ONLINE, the server lookup may answer null, and the
 * `IllegalArgumentException` from an unpronounceable JID is caught and reported, never thrown.
 */
object ConversationChannels {

    /** The private two-argument join, bound by the service to its own `joinMuc` method reference. */
    fun interface MucJoinerWithCallback {
        fun join(
            conversation: ConversationRef,
            callback: OnConferenceJoined,
        )
    }

    @JvmStatic
    fun createPublicChannel(
        service: XmppConnectionService,
        account: AccountRef,
        name: String?,
        address: Jid,
        callback: UiCallbackPort<ConversationRef>,
        joiner: MucJoinerWithCallback,
    ) {
        joiner.join(
            service.findOrCreateConversation(account, address, true, false, true),
        ) { conversation ->
            val configuration = IqGenerator.defaultChannelConfiguration()
            if (!TextUtils.isEmpty(name)) {
                configuration.putString("muc#roomconfig_roomname", name)
            }
            service.pushConferenceConfiguration(
                conversation,
                configuration,
                object : OnConfigurationPushed {
                    override fun onPushSucceeded() {
                        service.saveConversationAsBookmark(conversation, name)
                        callback.success(conversation)
                    }

                    override fun onPushFailed() {
                        if (conversation.getMucOptions()
                                .getSelf()
                                .affiliation()
                                .ranks(MucOptionsRef.AffiliationRef.OWNER)
                        ) {
                            callback.error(
                                R.string.unable_to_set_channel_configuration,
                                conversation,
                            )
                        } else {
                            callback.error(R.string.joined_an_existing_channel, conversation)
                        }
                    }
                },
            )
        }
    }

    @JvmStatic
    fun createAdhocConference(
        service: XmppConnectionService,
        account: AccountRef,
        name: String?,
        jids: Iterable<@JvmSuppressWildcards Jid>,
        callback: UiCallbackPort<ConversationRef>?,
        joiner: MucJoinerWithCallback,
    ): Boolean {
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + ": creating adhoc conference with " + jids.toString(),
        )
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            try {
                val server = service.findConferenceServer(account)
                if (server == null) {
                    if (callback != null) {
                        callback.error(R.string.no_conference_server_found, null)
                    }
                    return false
                }
                val jid = Jid.of(CryptoHelper.pronounceable(), server, null)
                val conversation = service.findOrCreateConversation(account, jid, true, false, true)
                joiner.join(
                    conversation,
                    object : OnConferenceJoined {
                        override fun onConferenceJoined(conversation: ConversationRef) {
                            val configuration = IqGenerator.defaultGroupChatConfiguration()
                            if (!TextUtils.isEmpty(name)) {
                                configuration.putString("muc#roomconfig_roomname", name)
                            }
                            service.pushConferenceConfiguration(
                                conversation,
                                configuration,
                                object : OnConfigurationPushed {
                                    override fun onPushSucceeded() {
                                        for (invite in jids) {
                                            service.invite(conversation, invite)
                                        }
                                        for (resource in account.getSelfContact()
                                            .getPresences()
                                            .toResourceArray()
                                        ) {
                                            val other = account.getJid().withResource(resource)
                                            Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid().toString()
                                                    + ": sending direct invite to " + other,
                                            )
                                            service.directInvite(conversation, other)
                                        }
                                        service.saveConversationAsBookmark(conversation, name)
                                        if (callback != null) {
                                            callback.success(conversation)
                                        }
                                    }

                                    override fun onPushFailed() {
                                        service.archiveConversation(conversation)
                                        if (callback != null) {
                                            callback.error(
                                                R.string.conference_creation_failed,
                                                conversation,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
                return true
            } catch (e: IllegalArgumentException) {
                if (callback != null) {
                    callback.error(R.string.conference_creation_failed, null)
                }
                return false
            }
        } else {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null)
            }
            return false
        }
    }

    @JvmStatic
    fun checkIfMuc(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        cb: Consumer<Boolean>,
        iqGenerator: IqGenerator,
    ) {
        if (jid.isDomainJid()) {
            // Spec basically says MUC needs to have a node
            // And also specifies that MUC and MUC service should have the same identity...
            cb.accept(false)
            return
        }

        val request = iqGenerator.queryDiscoInfo(jid.asBareJid())
        service.sendIqPacket(account, request) { reply ->
            val result = XmppConnectionService.dataStatics().newServiceDiscoveryResult(reply)
            cb.accept(
                result.getFeatures().contains("http://jabber.org/protocol/muc")
                    && result.hasIdentity("conference", null),
            )
        }
    }
}
