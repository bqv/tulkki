package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnContactStatusChanged
import uk.xa0.tulkki.xmpp.OnStatusChanged
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.utils.Random
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Tulkki: the account-state callback and the contact-presence callback, lifted out of
 * `XmppConnectionService`.
 *
 * C04's two fields keep their names and their declared types on the service, because other chunks
 * read them: C13's `processAccountState` calls `statusListener.onStatusChanged`, C24's
 * `ConnectionScheduling` takes the listener by value, and `PresenceParser` calls
 * `onContactStatusChanged`. The low-ping set (`mLowPingTimeoutMode`) is C05's
 * `ServiceSeams.isInLowPingTimeoutMode` parameter and C13's monitor, so it stays a service field and
 * arrives here **by value**; the lock taken is that same object. `mLastActivity`/`mLastMucPing` are
 * C24's/C13's and this chunk's bodies never read them, so they stay on the service too.
 *
 * Every private reach arrives as the bound function the Java would have called: C25c's private
 * `sendUnsentMessages` as a `Consumer`, C45's private `reconnectAccount` as a `Consumer` bound to
 * the `true, false` its every call site passed, C13's private
 * `manageAccountConnectionStatesInternal` as a `Runnable`, C55's private `hasJingleRtpConnection` as
 * a `Predicate`, and C08's private `sendLiveLocationStopForOrphanedSessions` as a `Consumer`. The
 * `contactListSyncService` field is assigned in `onCreate`, after this listener is built, so it
 * arrives as a `Supplier` and the Java's bare-field dereference keeps its own point. The `internalPingExecutor`
 * is `final` at declaration, so it travels by value. Everything else is a public service member.
 *
 * Master's `shuttingDown` guard (`03165687d6`, merged as `7b5c661cfc`) moves here with the ping it
 * guards: the delayed aggressive-reconnect schedule below is the one site that genuinely races
 * `onDestroy` from a connection thread, so it goes through the private `scheduleInternalPing`, which
 * rethrows a rejection that is not the teardown. The flag itself stays the service's private
 * `volatile boolean`; it arrives as a `BooleanSupplier` because it flips in `onDestroy` after this
 * listener is constructed, and no visibility is widened.
 *
 * The Java's order is kept exactly: the low-ping drop, the error-notification reset, the pending
 * archive queries, the CSI active/inactive choice, the per-conversation OTR/send sweep, the pending
 * conference leaves and joins, the own-story fetch, the orphaned live-location stop and the wake-up
 * call all live inside the ONLINE branch; the reference test `conversation.getAccount() == account`
 * is `===` in Kotlin, and the two `getStatusRef()` reads stay two reads.
 */
class AccountStatusListener(
    private val service: XmppConnectionService,
    private val lowPingTimeoutMode: MutableSet<Jid>,
    private val contactListSync: Supplier<ContactListSyncPort>,
    private val sendUnsentMessages: Consumer<ConversationRef>,
    private val reconnectAccount: Consumer<AccountRef>,
    private val runAccountStatePass: Runnable,
    private val hasJingleRtpConnection: Predicate<AccountRef>,
    private val stopOrphanedLiveLocation: Consumer<AccountRef>,
    private val internalPingExecutor: ScheduledExecutorService,
    private val shuttingDown: BooleanSupplier,
) : OnStatusChanged {

    override fun onStatusChanged(accountRef: AccountRef) {
        val account = accountRef
        val connection = account.getXmppConnection()
        service.updateAccountUi()

        if (account.getStatusRef() == AccountRef.StateRef.ONLINE ||
            account.getStatusRef().isError()
        ) {
            contactListSync.get().signalAccountStateChange()
        }

        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            synchronized(lowPingTimeoutMode) {
                if (lowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                    Log.d(
                        Config.LOGTAG,
                        "" + account.getJid().asBareJid() + ": leaving low ping timeout mode",
                    )
                }
            }
            if (account.setShowErrorNotification(true)) {
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            }
            service.getMessageArchiveService().executePendingQueries(account)
            if (connection != null && connection.getFeatures().csi()) {
                if (service.checkListeners()) {
                    Log.d(
                        Config.LOGTAG,
                        "" + account.getJid().asBareJid() + " sending csi//inactive",
                    )
                    connection.sendInactive()
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "" + account.getJid().asBareJid() + " sending csi//active",
                    )
                    connection.sendActive()
                }
            }
            val conversationList = service.getConversationList()
            for (conversation in conversationList) {
                val inProgressJoin = account.isConferenceJoinInProgress(conversation)
                val pendingJoin = account.isConferenceJoinPending(conversation)
                if (conversation.getAccount() === account && !pendingJoin && !inProgressJoin) {
                    if (!conversation.startOtrIfNeeded()) {
                        Log.d(
                            Config.LOGTAG,
                            "" + account.getJid().asBareJid() +
                                ": couldn't start OTR with " +
                                conversation.getContact().getJid() +
                                " when needed",
                        )
                    }
                    sendUnsentMessages.accept(conversation)
                }
            }
            val pendingLeaves = account.drainPendingConferenceLeaves()
            for (conversation in pendingLeaves) {
                service.leaveMuc(conversation)
            }
            val pendingJoins = account.drainPendingConferenceJoins()
            for (conversation in pendingJoins) {
                service.joinMuc(conversation)
            }
            service.fetchOwnStories(account)
            stopOrphanedLiveLocation.accept(account)
            service.scheduleWakeUpCall(
                Config.PING_MAX_INTERVAL * 1000L,
                account.getUuid().hashCode(),
            )
        } else if (account.getStatusRef() == AccountRef.StateRef.OFFLINE ||
            account.getStatusRef() == AccountRef.StateRef.DISABLED ||
            account.getStatusRef() == AccountRef.StateRef.LOGGED_OUT
        ) {
            service.resetSendingToWaiting(account)
            if (account.isConnectionEnabled() &&
                ServiceSeams.isInLowPingTimeoutMode(account, lowPingTimeoutMode)
            ) {
                Log.d(
                    Config.LOGTAG,
                    "" + account.getJid().asBareJid() +
                        ": went into offline state during low ping mode." +
                        " reconnecting now",
                )
                reconnectAccount.accept(account)
            } else {
                val timeToReconnect = Random.SECURE_RANDOM.nextInt(10) + 2
                service.scheduleWakeUpCall(timeToReconnect, account.getUuid().hashCode())
            }
        } else if (account.getStatusRef() == AccountRef.StateRef.REGISTRATION_SUCCESSFUL) {
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            reconnectAccount.accept(account)
        } else if (account.getStatusRef() != AccountRef.StateRef.CONNECTING &&
            account.getStatusRef() != AccountRef.StateRef.NO_INTERNET
        ) {
            service.resetSendingToWaiting(account)
            if (connection != null && account.getStatusRef().isAttemptReconnect()) {
                val aggressive =
                    account.getStatusRef() == AccountRef.StateRef.SEE_OTHER_HOST ||
                        hasJingleRtpConnection.test(account)
                val next = connection.getTimeToNextAttempt(aggressive)
                val inLowPingTimeout =
                    ServiceSeams.isInLowPingTimeoutMode(account, lowPingTimeoutMode)
                if (next <= 0) {
                    Log.d(
                        Config.LOGTAG,
                        "" + account.getJid().asBareJid() +
                            ": error connecting account. reconnecting now." +
                            " lowPingTimeout=" +
                            inLowPingTimeout,
                    )
                    reconnectAccount.accept(account)
                } else {
                    val attempt = connection.getAttempt() + 1
                    Log.d(
                        Config.LOGTAG,
                        "" + account.getJid().asBareJid() +
                            ": error connecting account. try again in " +
                            next +
                            "s for the " +
                            attempt +
                            " time. lowPingTimeout=" +
                            inLowPingTimeout +
                            ", aggressive=" +
                            aggressive,
                    )
                    service.scheduleWakeUpCall(next, account.getUuid().hashCode())
                    if (aggressive) {
                        scheduleInternalPing(
                            runAccountStatePass,
                            (next * 1000L) + 50,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
        }
        service.getNotificationService().updateErrorNotification()
    }

    /**
     * Schedules one delayed run of the account-state pass on `internalPingExecutor`, tolerating the
     * shutdown race. Master's `XmppConnectionService.scheduleInternalPing` moved here with its only
     * caller: this runs on the status listener's own thread (or on `internalPingExecutor` itself),
     * so `onDestroy` can shut that pool down while it runs; the rejection that follows means the
     * service is going away and is ignored. A rejection that is *not* the teardown is rethrown, so a
     * real shutdown bug is not hidden behind this catch.
     */
    private fun scheduleInternalPing(task: Runnable, delay: Long, unit: TimeUnit) {
        if (shuttingDown.asBoolean) {
            return
        }
        try {
            internalPingExecutor.schedule(task, delay, unit)
        } catch (e: RejectedExecutionException) {
            if (!shuttingDown.asBoolean) {
                throw e
            }
            Log.d(Config.LOGTAG, "service is shutting down; not scheduling ping")
        }
    }

    companion object {
        /**
         * C04's `onContactStatusChanged`: the Java lambda's body with the Java's dereferences. The
         * presence-resource test keeps `toResourceArray()`'s Java semantics (the array's own
         * `contains`), and the private `sendUnsentMessages` arrives as the bound `Consumer`.
         */
        @JvmStatic
        fun onContactStatusChanged(
            service: XmppConnectionService,
            contactRef: ContactRef,
            online: Boolean,
            sendUnsentMessages: Consumer<ConversationRef>,
        ) {
            val contact = contactRef
            val conversation = service.find(contact) ?: return
            if (online) {
                conversation.endOtrIfNeeded()
                if (contact.getPresences().size() == 1) {
                    sendUnsentMessages.accept(conversation)
                }
                service.fetchStories(
                    conversation.getAccount()
                        ?: throw NullPointerException("conversation has no account"),
                    conversation.getContact(),
                )
            } else {
                if (conversation.hasValidOtrSession()) {
                    val otrResource =
                        (conversation.getOtrSession()
                            ?: throw NullPointerException("conversation has no otr session"))
                            .getSessionID()
                            .getUserID()
                    if (!contact.getPresences().toResourceArray().contains(otrResource)) {
                        conversation.endOtrIfNeeded()
                    }
                }
            }
        }
    }
}
