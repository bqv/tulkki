package uk.xa0.tulkki.xmpp.models.processor

import android.text.TextUtils
import android.util.Log
import java.util.function.Consumer
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: the resource-binding follow-up, run on the connection's own thread. Converted from the
 * Java.
 *
 * The field is the ref, so this file names neither the `Account` model nor `:data`; the Java's
 * `Account` import was already gone. Two `Jid + String` log concatenations become string templates,
 * because the Java `Jid` has no Kotlin `plus`; every call keeps the Java's spelling and order. The
 * IQ callback is the Java `Consumer<Iq>` the connection takes.
 */
class BindProcessor(
    private val service: XmppConnectionService,
    private val account: AccountRef,
) : Runnable {

    override fun run() {
        val connection: XmppConnection =
            account.getXmppConnection() ?: throw NullPointerException("account has no connection")
        service.cancelAvatarFetches(account)
        val loggedInSuccessfully =
            account.setOption(AccountRef.OPTION_LOGGED_IN_SUCCESSFULLY, true)
        val gainedFeature =
            account.setOption(
                AccountRef.OPTION_HTTP_UPLOAD_AVAILABLE,
                connection.getFeatures().httpUpload(0),
            )
        if (loggedInSuccessfully || gainedFeature) {
            (service.databaseBackend ?: throw NullPointerException("database backend is not open"))
                .updateAccount(account)
        }

        if (loggedInSuccessfully) {
            if (!TextUtils.isEmpty(account.getDisplayName())) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: display name wasn't empty on first log" +
                        " in. publishing",
                )
                service.publishDisplayName(account)
            }
        }

        account.getRoster().clearPresences()
        // Tulkki: the three conference sets take their own lock inside the model, so the island
        // asks instead of reaching into a field (3.7 pair 9, cluster a).
        account.clearConferenceJoinsInProgress()
        account.clearConferencePingsInProgress()
        service.getJingleConnectionManager().notifyRebound(account)
        service.getContactListSyncService().considerSyncBackground(false)

        connection.fetchRoster()

        if (connection.getFeatures().bookmarks2()) {
            service.fetchBookmarks2(account)
        } else if (!connection.getFeatures().bookmarksConversion()) {
            service.fetchBookmarks(account)
        }

        if (connection.getFeatures().mds()) {
            service.fetchMessageDisplayedSynchronization(account)
        } else {
            Log.d(Config.LOGTAG, "${account.getJid()}: server has no support for mds")
        }
        val features = connection.getFeatures()
        val bind2 = features.bind2()
        val flexible = features.flexibleOfflineMessageRetrieval()
        val catchup = service.getMessageArchiveService().inCatchup(account)
        val trackOfflineMessageRetrieval: Boolean
        if (!bind2 && flexible && catchup && connection.isMamPreferenceAlways()) {
            trackOfflineMessageRetrieval = false
            connection.sendIqPacket(
                IqGenerator.purgeOfflineMessages(),
                Consumer { packet ->
                    if (packet.getType() == Iq.Type.RESULT) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: successfully purged offline" +
                                " messages",
                        )
                    }
                },
            )
        } else {
            trackOfflineMessageRetrieval = true
        }
        service.sendPresence(account)
        connection.trackOfflineMessageRetrieval(trackOfflineMessageRetrieval)
        if (service.getPushManagementService().available(account)) {
            service.getPushManagementService().registerPushTokenOnServer(account)
        }
        service.connectMultiModeConversationList(account)
        service.syncDirtyContacts(account)

        service.getUnifiedPushBroker().renewUnifiedPushEndpointsOnBind(account)
    }
}
