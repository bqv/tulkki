package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.LongConsumer
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.mam.SyncEvents
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * Tulkki: foreground and background switching, lifted out of `XmppConnectionService`
 *.
 *
 * The Java's order is kept: the soft-disable flag, the last-activity read, the per-conversation chat
 * state reset, then every online account's CSI active/inactive and the sync engine's phase. The
 * conversation list is handed in as the service's live list, and `connectMultiModeConversationList`
 * keeps the Java's **reference** identity test (`===`, never `==`) between the account it was given
 * and the conversation's own. The last-activity write is the service's own field and arrives as a
 * `LongConsumer`; chunk `C74`'s nullable sync engine and the notification port come in by hand, the
 * latter as a non-null value exactly where the Java dereferenced it.
 */
object ForegroundState {

    @JvmStatic
    fun switchToForeground(
        service: XmppConnectionService,
        conversationRefs: List<ConversationRef>,
        toggleSoftDisabled: Consumer<Boolean>,
        syncEvents: SyncEvents?,
        sendPresence: BiConsumer<AccountRef, Boolean>,
    ) {
        toggleSoftDisabled.accept(false)
        val broadcastLastActivity = service.broadcastLastActivity()
        for (conversation in conversationRefs) {
            if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                conversation.getMucOptions().resetChatState()
            } else {
                conversation.setIncomingChatState(Config.DEFAULT_CHAT_STATE)
            }
        }
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
                account.deactivateGracePeriod()
                val connection = account.getXmppConnection()
                if (connection != null) {
                    if (connection.getFeatures().csi()) {
                        connection.sendActive()
                    }
                    // Tulkki S5-4: CSI active is the cheapest reconcile point - a region that was
                    // recorded DEGRADED while the app was away is re-opened from its own gap_start,
                    // and nothing else is owed (section 2.2). Reported for every account whose
                    // connection says it sent the stanza.
                    if (syncEvents != null) {
                        syncEvents.onClientStateChanged(account, true)
                    }
                    if (broadcastLastActivity) {
                        sendPresence.accept(account, false) // send new presence but don't include idle
                    }
                }
            }
        }
        Log.d(Config.LOGTAG, "app switched into foreground")
    }

    @JvmStatic
    fun switchToBackground(
        service: XmppConnectionService,
        setLastActivity: LongConsumer,
        notificationService: NotificationPort,
        settingLastActivityTs: String,
        syncEvents: SyncEvents?,
        sendPresence: BiConsumer<AccountRef, Boolean>,
    ) {
        val broadcastLastActivity = service.broadcastLastActivity()
        if (broadcastLastActivity) {
            val now = System.currentTimeMillis()
            setLastActivity.accept(now)
            val editor = service.getPreferences().edit()
            editor.putLong(settingLastActivityTs, now)
            editor.apply()
        }
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
                val connection = account.getXmppConnection()
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence.accept(account, true)
                    }
                    if (connection.getFeatures().csi()) {
                        connection.sendInactive()
                    }
                    // Tulkki S5-4: CSI inactive opens no gap and owes nothing; the engine records the
                    // phase so a screen can tell "away" from "live" (section 2.1).
                    if (syncEvents != null) {
                        syncEvents.onClientStateChanged(account, false)
                    }
                }
            }
        }
        notificationService.setIsInForeground(false)
        Log.d(Config.LOGTAG, "app switched into background")
    }

    @JvmStatic
    fun connectMultiModeConversationList(
        conversationRefs: List<ConversationRef>,
        account: AccountRef,
        joinMuc: Consumer<ConversationRef>,
    ) {
        for (conversation in conversationRefs) {
            if (conversation.getMode() == ConversationalRef.MODE_MULTI
                && conversation.getAccount() === account
            ) {
                joinMuc.accept(conversation)
            }
        }
    }
}
