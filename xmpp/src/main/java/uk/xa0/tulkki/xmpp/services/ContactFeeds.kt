package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the contact write-through and the pubsub feed query, lifted out of `XmppConnectionService`
 *.
 *
 * Both bodies reach only public service members, so the service travels in whole the way
 * `DeletedFileCheck` and `ConversationChannels` already do: the contact write goes through the
 * public `databaseBackend` slot (chunk C02's), the UI fan-out through the public
 * `updateConversationUi` delegations (chunk C48's), and the query through the public
 * `getIqGenerator()` and `sendIqPacket`. Nothing was widened and no private field moved.
 *
 * The Java's guard order is kept exactly: the write only runs when the backend accepted it, an
 * account with no live roster contact still writes but does not refresh the UI, and a `server` that
 * names no account falls back to the first online account before the callback is told it failed.
 * `server` and the callback are nullable because the Java tested neither — `retrievePubsubItems`
 * itself takes a nullable server.
 */
object ContactFeeds {

    @JvmStatic
    fun updateContact(service: XmppConnectionService, contact: ContactRef) {
        if ((service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateContact(contact)) {
            val account = XmppConnectionService.dataStatics().accounts()
                .findAccountByUuid(contact.getAccount().getUuid())
            if (account != null) {
                val rosterContact = account.getRoster().getContact(contact.getJid())
                if (rosterContact != null) {
                    rosterContact.setCallsDisabled(contact.areCallsDisabled())
                    service.updateConversationUi()
                    service.updateConversationUi(true)
                }
            }
        }
    }

    @JvmStatic
    fun fetchPubsubItems(
        service: XmppConnectionService,
        server: Jid?,
        node: String,
        callback: OnPubsubItemsFetched?,
    ) {
        var account = if (server != null) {
            XmppConnectionService.dataStatics().accounts().findAccountByJid(server)
        } else {
            null
        }
        if (account == null) {
            for (acc in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                if (acc.isOnlineAndConnected()) {
                    account = acc
                    break
                }
            }
        }
        val resolved = account
        if (resolved == null) {
            callback?.onPubsubItemsFetchFailed()
            return
        }
        val request = service.getIqGenerator().retrievePubsubItems(server, node)
        service.sendIqPacket(resolved, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val pubsub = response.findChild("pubsub", Namespace.PUBSUB)
                if (pubsub != null && callback != null) {
                    callback.onPubsubItemsFetched(pubsub.toString())
                } else if (callback != null) {
                    callback.onPubsubItemsFetchFailed()
                }
            } else {
                if (callback != null) {
                    callback.onPubsubItemsFetchFailed()
                }
            }
        }
    }
}
