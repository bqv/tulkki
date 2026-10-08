package uk.xa0.tulkki.xmpp.services

import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.common.base.Strings
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnStatusChanged
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import java.util.function.BiConsumer
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Tulkki: the account connection state machine and the scheduled-message pass, lifted out of
 * `XmppConnectionService`.
 *
 * The chunk's two fields split the way the Java reads them. `mLastMucPing` is read and written by
 * nothing outside `manageAccountConnectionStates`, so it moves **whole** as this object's private
 * state; `mScheduledMessages` stays a field of the service because C42's `MessageContacts` and C25b's
 * store path read the same map, so it travels in **by value** and the same map is iterated.
 *
 * Every private reach arrives as the bound function the Java called: C76's `wakeLock()` and
 * `phoneHelper()` as `Supplier`s resolved **inside** the body, so their `require` still throws where
 * the Java threw; C45's private `reconnectAccount` as a `BiConsumer` bound to the `true` its call
 * sites passed; C55's private `hasJingleRtpConnection` as a `Predicate`; and C01's private
 * `ACTION_POST_CONNECTIVITY_CHANGE` as the string it is. C04's `statusListener` and
 * `mLowPingTimeoutMode` travel by value, so `statusListener.onStatusChanged` and the low-ping
 * monitor are the same objects the Java used.
 *
 * `manageAccountConnectionStates` was a `synchronized` method, i.e. it locked the **service instance**,
 * so the body runs inside `synchronized(service)` - the C51 `ReadMarkers` shape. The Java's ping
 * arithmetic, its `pingNow |=` accumulation and the short-circuit that skips `phoneHelper()` when no
 * pushed account hash arrived are kept; `sendScheduledMessages`'s
 * `conversation.getAccount() == account` is `===`.
 */
object AccountConnectionStates {

    private var lastMucPing = 0L

    @JvmStatic
    fun quickLog(service: XmppConnectionService, message: String) {
        if (Strings.isNullOrEmpty(message)) {
            return
        }
        if (Config.BUG_REPORTS == null) {
            // Tulkki has no support address to log into; the caller only runs this behind
            // Config.QUICK_LOG, but a null address must never reach the conversation lookup.
            return
        }
        // Tulkki: C5-E1 renamed this lookup `getFirstNotDisabled` - the two `getFirstEnabled`
        // overloads this class used to carry erase to one signature now that both take a list,
        // and they ask different questions. The ref-typed `findOrCreateConversation` below takes
        // it with no cast at this site.
        val account = AccountUtils.getFirstNotDisabled(XmppConnectionService.dataStatics().accounts().getAccounts())
            ?: return
        val conversation = service.findOrCreateConversation(account, Config.BUG_REPORTS, false, true)
        val report = XmppConnectionService.dataStatics().newMessage(conversation, message, MessageRef.ENCRYPTION_NONE)
        report.setStatus(MessageRef.STATUS_RECEIVED)
        conversation.add(report)
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(report)
        service.updateConversationUi()
    }

    @JvmStatic
    fun manageAccountConnectionStatesInternal(
        service: XmppConnectionService,
        wakeLockPort: Supplier<WakeLockPort>,
        wakeLock: PowerManager.WakeLock,
        phoneHelper: Supplier<PhoneHelperPort>,
        lowPingTimeoutMode: MutableSet<Jid>,
        statusListener: OnStatusChanged,
        reconnectAccount: BiConsumer<AccountRef, Boolean>,
        hasJingleRtpConnection: Predicate<AccountRef>,
        postConnectivityChangeAction: String,
    ) {
        manageAccountConnectionStates(
            service,
            wakeLockPort,
            wakeLock,
            phoneHelper,
            lowPingTimeoutMode,
            statusListener,
            reconnectAccount,
            hasJingleRtpConnection,
            postConnectivityChangeAction,
            ServiceActions.ACTION_INTERNAL_PING,
            null,
        )
    }

    @JvmStatic
    fun manageAccountConnectionStates(
        service: XmppConnectionService,
        wakeLockPort: Supplier<WakeLockPort>,
        wakeLock: PowerManager.WakeLock,
        phoneHelper: Supplier<PhoneHelperPort>,
        lowPingTimeoutMode: MutableSet<Jid>,
        statusListener: OnStatusChanged,
        reconnectAccount: BiConsumer<AccountRef, Boolean>,
        hasJingleRtpConnection: Predicate<AccountRef>,
        postConnectivityChangeAction: String,
        action: String,
        extras: Bundle?,
    ) {
        synchronized(service) {
            val pushedAccountHash = extras?.getString("account")
            val interactive = java.util.Objects.equals(ServiceActions.ACTION_TRY_AGAIN, action)
            wakeLockPort.get().acquire(wakeLock)
            var pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION == action ||
                    (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0 &&
                        postConnectivityChangeAction == action)
            val pingCandidates = HashSet<AccountRef>()
            val androidId = if (pushedAccountHash == null) null else phoneHelper.get().getAndroidId(service)
            for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                val pushWasMeantForThisAccount =
                    androidId != null &&
                        CryptoHelper.getAccountFingerprint(account, androidId).equals(pushedAccountHash)
                pingNow =
                    pingNow or
                        processAccountState(
                            service,
                            account,
                            interactive,
                            "ui" == action,
                            pushWasMeantForThisAccount,
                            pingCandidates,
                            lowPingTimeoutMode,
                            statusListener,
                            reconnectAccount,
                            hasJingleRtpConnection,
                        )
            }
            if (pingNow) {
                for (account in pingCandidates) {
                    val connection =
                        account.getXmppConnection()
                            ?: throw NullPointerException("account has no connection")
                    val lowTimeout =
                        ServiceSeams.isInLowPingTimeoutMode(account, lowPingTimeoutMode)
                    val delta =
                        (SystemClock.elapsedRealtime() - connection.getLastPacketReceived()) / 1000L
                    connection.sendPing()
                    Log.d(
                        Config.LOGTAG,
                        String.format(
                            "%s: send ping (action=%s,lowTimeout=%s,interval=%s)",
                            account.getJid().asBareJid(),
                            action,
                            lowTimeout,
                            delta,
                        ),
                    )
                    service.scheduleWakeUpCall(
                        if (lowTimeout) Config.LOW_PING_TIMEOUT else Config.PING_TIMEOUT,
                        account.getUuid().hashCode(),
                    )
                }
            }
            val msToMucPing =
                (lastMucPing + (Config.PING_MAX_INTERVAL * 2000L)) - SystemClock.elapsedRealtime()
            if (pingNow || ("ui" == action && msToMucPing <= 0) || msToMucPing < -300000) {
                Log.d(Config.LOGTAG, "ping MUCs")
                lastMucPing = SystemClock.elapsedRealtime()
                for (c in service.getConversationList()) {
                    if (c.getMode() == ConversationalRef.MODE_MULTI &&
                        (c.getMucOptions().online() ||
                            c.getMucOptions().error() == MucOptionsRef.ErrorRef.SHUTDOWN)
                    ) {
                        service.mucSelfPingAndRejoin(c)
                    }
                }
            }
            wakeLockPort.get().release(wakeLock)
        }
    }

    @JvmStatic
    fun sendScheduledMessages(
        service: XmppConnectionService,
        scheduledMessages: Map<String, MessageRef>,
    ) {
        Log.d(Config.LOGTAG, "looking for and sending scheduled messages")

        for (message in ArrayList(scheduledMessages.values)) {
            if (message.getTimeSent() > System.currentTimeMillis()) continue

            val conversation =
                message.getConversation()
                    ?: throw NullPointerException("message has no conversation")
            val account =
                conversation.getAccount() ?: throw NullPointerException("conversation has no account")
            val inProgressJoin = account.isConferenceJoinInProgress(conversation)
            val pendingJoin = account.isConferenceJoinPending(conversation)
            if (conversation.getAccount() === account && !pendingJoin && !inProgressJoin) {
                service.resendMessage(message, false)
            }
        }
    }

    @JvmStatic
    fun handleOrbotStartedEvent(
        service: XmppConnectionService,
        reconnectAccount: BiConsumer<AccountRef, Boolean>,
    ) {
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.getStatusRef() == AccountRef.StateRef.TOR_NOT_AVAILABLE) {
                reconnectAccount.accept(account, false)
            }
        }
    }

    private fun processAccountState(
        service: XmppConnectionService,
        account: AccountRef,
        interactive: Boolean,
        isUiAction: Boolean,
        isAccountPushed: Boolean,
        pingCandidates: HashSet<AccountRef>,
        lowPingTimeoutMode: MutableSet<Jid>,
        statusListener: OnStatusChanged,
        reconnectAccount: BiConsumer<AccountRef, Boolean>,
        hasJingleRtpConnection: Predicate<AccountRef>,
    ): Boolean {
        if (!account.getStatusRef().isAttemptReconnect()) {
            return false
        }
        val requestCode = account.getUuid().hashCode()
        if (!service.hasInternetConnection()) {
            account.setStatusRef(AccountRef.StateRef.NO_INTERNET)
            statusListener.onStatusChanged(account)
        } else {
            if (account.getStatusRef() == AccountRef.StateRef.NO_INTERNET) {
                account.setStatusRef(AccountRef.StateRef.OFFLINE)
                statusListener.onStatusChanged(account)
            }
            if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
                synchronized(lowPingTimeoutMode) {
                    val connection =
                        account.getXmppConnection()
                            ?: throw NullPointerException("account has no connection")
                    val lastReceived = connection.getLastPacketReceived()
                    val lastSent = connection.getLastPingSent()
                    val pingInterval =
                        if (isUiAction) {
                            Config.PING_MIN_INTERVAL * 1000
                        } else {
                            Config.PING_MAX_INTERVAL * 1000
                        }
                    val msToNextPing =
                        (Math.max(lastReceived, lastSent) + pingInterval) -
                            SystemClock.elapsedRealtime()
                    val pingTimeout =
                        if (lowPingTimeoutMode.contains(account.getJid().asBareJid())) {
                            Config.LOW_PING_TIMEOUT * 1000
                        } else {
                            Config.PING_TIMEOUT * 1000
                        }
                    val pingTimeoutIn =
                        (lastSent + pingTimeout) - SystemClock.elapsedRealtime()
                    if (lastSent > lastReceived) {
                        if (pingTimeoutIn < 0) {
                            Log.d(
                                Config.LOGTAG,
                                "" + account.getJid().asBareJid() + ": ping timeout",
                            )
                            reconnectAccount.accept(account, interactive)
                        } else {
                            service.scheduleWakeUpCall(pingTimeoutIn, requestCode)
                        }
                    } else {
                        pingCandidates.add(account)
                        if (isAccountPushed) {
                            if (lowPingTimeoutMode.add(account.getJid().asBareJid())) {
                                Log.d(
                                    Config.LOGTAG,
                                    "" + account.getJid().asBareJid() +
                                        ": entering low ping timeout mode",
                                )
                            }
                            return true
                        } else if (msToNextPing <= 0) {
                            return true
                        } else {
                            service.scheduleWakeUpCall(msToNextPing, requestCode)
                            if (lowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                Log.d(
                                    Config.LOGTAG,
                                    "" + account.getJid().asBareJid() +
                                        ": leaving low ping timeout mode",
                                )
                            }
                        }
                    }
                }
            } else if (account.getStatusRef() == AccountRef.StateRef.OFFLINE) {
                reconnectAccount.accept(account, interactive)
            } else if (account.getStatusRef() == AccountRef.StateRef.CONNECTING) {
                val connection =
                    account.getXmppConnection()
                        ?: throw NullPointerException("account has no connection")
                val connectionDuration = connection.getConnectionDuration()
                val discoDuration = connection.getDiscoDuration()
                val connectionTimeout = Config.CONNECT_TIMEOUT * 1000L - connectionDuration
                val discoTimeout = Config.CONNECT_DISCO_TIMEOUT * 1000L - discoDuration
                if (connectionTimeout < 0) {
                    connection.triggerConnectionTimeout()
                } else if (discoTimeout < 0) {
                    connection.sendDiscoTimeout()
                    service.scheduleWakeUpCall(discoTimeout, requestCode)
                } else {
                    service.scheduleWakeUpCall(Math.min(connectionTimeout, discoTimeout), requestCode)
                }
            } else {
                val aggressive =
                    account.getStatusRef() == AccountRef.StateRef.SEE_OTHER_HOST ||
                        hasJingleRtpConnection.test(account)
                if ((account.getXmppConnection()
                        ?: throw NullPointerException("account has no connection"))
                        .getTimeToNextAttempt(aggressive) <= 0
                ) {
                    reconnectAccount.accept(account, interactive)
                }
            }
        }
        return false
    }
}
