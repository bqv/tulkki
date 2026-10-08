package uk.xa0.tulkki.xmpp.services

import android.os.Bundle
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.utils.StringUtils
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the room configuration - its disco#info fetch, the node and room pushes and the subject -
 * lifted out of `XmppConnectionService`.
 *
 * The chunk owns no field and widens no visibility: everything it reaches outside itself is a
 * **public** service member - `getIqGenerator()`, `getPresenceGenerator()`, `getMessageGenerator()`,
 * `sendIqPacket`, `sendPresencePacket`, `sendMessagePacket`, `updateConversation`,
 * `updateConversationUi`, `createBookmark` and the public static `dataStatics()`. The two callbacks
 * and the error condition are nullable exactly where the Java null-checked them, `Data.parse` and
 * the `Bundle` reads keep the Java's order, and `onFetchFailed` still receives the stanza's own
 * error condition.
 */
object ConferenceConfiguration {

    @JvmStatic
    fun fetchConferenceConfiguration(
        service: XmppConnectionService,
        conversation: ConversationRef,
    ) {
        fetchConferenceConfiguration(service, conversation, null)
    }

    @JvmStatic
    fun fetchConferenceConfiguration(
        service: XmppConnectionService,
        conversation: ConversationRef,
        callback: OnConferenceConfigurationFetched?,
    ) {
        val jid = conversation.getJid() ?: throw NullPointerException("conversation has no jid")
        val request = service.getIqGenerator().queryDiscoInfo(jid.asBareJid())
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        service.sendIqPacket(account, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val mucOptions = conversation.getMucOptions()
                val bookmark = conversation.getBookmark()
                val sameBefore =
                    StringUtils.equals(
                        bookmark?.getBookmarkName(),
                        mucOptions.getName(),
                    )

                val hadOccupantId = mucOptions.occupantId()
                if (mucOptions.updateConfiguration(
                        XmppConnectionService.dataStatics().newServiceDiscoveryResult(response),
                    )
                ) {
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString()
                            + ": muc configuration changed for "
                            + jid.asBareJid(),
                    )
                    service.updateConversation(conversation)
                }

                val hasOccupantId = mucOptions.occupantId()

                if (!hadOccupantId && hasOccupantId && mucOptions.online()) {
                    val me = mucOptions.getSelf().getFullJid()
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString()
                            + ": gained support for occupant-id in "
                            + me
                            + ". resending presence",
                    )
                    val packet =
                        service.getPresenceGenerator().selfPresence(
                            account,
                            PresenceRef.StatusRef.ONLINE,
                            mucOptions.nonanonymous(),
                            mucOptions.getSelf().getNick(),
                        )
                    packet.setTo(me)
                    service.sendPresencePacket(account, packet)
                }

                if (bookmark != null && (sameBefore || bookmark.getBookmarkName() == null)) {
                    if (bookmark.setBookmarkName(StringUtils.nullOnEmpty(mucOptions.getName()))) {
                        service.createBookmark(account, bookmark)
                    }
                }

                if (callback != null) {
                    callback.onConferenceConfigurationFetched(conversation)
                }

                service.updateConversationUi()
            } else if (response.getType() == Iq.Type.TIMEOUT) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString()
                        + ": received timeout waiting for conference configuration"
                        + " fetch",
                )
            } else {
                if (callback != null) {
                    callback.onFetchFailed(conversation, response.getErrorCondition())
                }
            }
        }
    }

    @JvmStatic
    fun pushNodeConfiguration(
        service: XmppConnectionService,
        account: AccountRef,
        node: String,
        options: Bundle,
        callback: OnConfigurationPushed?,
    ) {
        pushNodeConfiguration(
            service,
            account,
            account.getJid().asBareJid(),
            node,
            options,
            callback,
        )
    }

    @JvmStatic
    fun pushNodeConfiguration(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        node: String,
        options: Bundle,
        callback: OnConfigurationPushed?,
    ) {
        Log.d(Config.LOGTAG, "pushing node configuration")
        service.sendIqPacket(
            account,
            service.getIqGenerator().requestPubsubConfiguration(jid, node),
        ) { responseToRequest ->
            if (responseToRequest.getType() == Iq.Type.RESULT) {
                val pubsub =
                    responseToRequest.findChild(
                        "pubsub",
                        "http://jabber.org/protocol/pubsub#owner",
                    )
                val configuration = pubsub?.findChild("configure")
                val x = configuration?.findChild("x", Namespace.DATA)
                if (x != null) {
                    val data = Data.parse(x) ?: throw NullPointerException("Data.parse returned null")
                    data.submit(options)
                    service.sendIqPacket(
                        account,
                        service.getIqGenerator()
                            .publishPubsubConfiguration(jid, node, data),
                    ) { responseToPublish ->
                        if (responseToPublish.getType() == Iq.Type.RESULT && callback != null) {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString()
                                    + ": successfully changed node"
                                    + " configuration for node "
                                    + node,
                            )
                            callback.onPushSucceeded()
                        } else if (responseToPublish.getType() == Iq.Type.ERROR && callback != null) {
                            callback.onPushFailed()
                        }
                    }
                } else if (callback != null) {
                    callback.onPushFailed()
                }
            } else if (responseToRequest.getType() == Iq.Type.ERROR && callback != null) {
                callback.onPushFailed()
            }
        }
    }

    @JvmStatic
    fun pushConferenceConfiguration(
        service: XmppConnectionService,
        conversation: ConversationRef,
        options: Bundle,
        callback: OnConfigurationPushed?,
    ) {
        if (options.getString("muc#roomconfig_whois", "moderators").equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true)
            service.updateConversation(conversation)
        }
        if (options.containsKey("muc#roomconfig_moderatedroom")) {
            val moderated = "1" == options.getString("muc#roomconfig_moderatedroom")
            options.putString("members_by_default", if (moderated) "0" else "1")
        }
        if (options.containsKey("muc#roomconfig_allowpm")) {
            // ejabberd :-/
            val allow = "anyone" == options.getString("muc#roomconfig_allowpm")
            options.putString("allow_private_messages", if (allow) "1" else "0")
            options.putString("allow_private_messages_from_visitors", if (allow) "anyone" else "nobody")
        }
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val jid = conversation.getJid() ?: throw NullPointerException("conversation has no jid")
        val request = Iq(Iq.Type.GET)
        request.setTo(jid.asBareJid())
        request.query("http://jabber.org/protocol/muc#owner")
        service.sendIqPacket(account, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val data =
                    Data.parse(response.query().findChild("x", Namespace.DATA))
                        ?: throw NullPointerException("Data.parse returned null")
                data.submit(options)
                val set = Iq(Iq.Type.SET)
                set.setTo(jid.asBareJid())
                set.query("http://jabber.org/protocol/muc#owner").addChild(data)
                service.sendIqPacket(account, set) { packet ->
                    if (callback != null) {
                        if (packet.getType() == Iq.Type.RESULT) {
                            callback.onPushSucceeded()
                        } else {
                            Log.d(Config.LOGTAG, "failed: " + packet.toString())
                            callback.onPushFailed()
                        }
                    }
                }
            } else {
                if (callback != null) {
                    callback.onPushFailed()
                }
            }
        }
    }

    @JvmStatic
    fun pushSubjectToConference(
        service: XmppConnectionService,
        conference: ConversationRef,
        subject: String?,
    ) {
        val packet =
            service.getMessageGenerator()
                .conferenceSubject(conference, StringUtils.nullOnEmpty(subject))
        service.sendMessagePacket(
            conference.getAccount() ?: throw NullPointerException("conference has no account"),
            packet,
        )
    }
}
