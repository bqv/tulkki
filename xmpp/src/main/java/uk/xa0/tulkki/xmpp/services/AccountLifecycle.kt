package uk.xa0.tulkki.xmpp.services

import android.security.KeyChain
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnStatusChanged
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.ReplacingTaskManager
import uk.xa0.tulkki.xml.Namespace
import com.google.common.base.Optional
import java.security.cert.CertificateException
import java.util.concurrent.Executor
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Tulkki: the account lifecycle, lifted out of `XmppConnectionService`
 *.
 *
 * Every field the chunk reaches stays on the service because other chunks read it, and travels in
 * **by value** or as the bound function the Java called: the live `conversationList` (C29) is the
 * same object `deleteAccount` locks and mutates, C74's `unifiedPushBroker`/`mChannelDiscoveryService`
 * travel as `Supplier`s, C70's nullable `mNotificationService` arrives nullable so the two bare
 * dereferences keep the Java's NPE, C02's `mDatabaseWriterExecutor` and C28's `mRosterSyncTaskManager`
 * travel by value, C38's private `hasEnabledAccounts()` goes in as a `BooleanSupplier`, C41's private
 * `disconnect` as a `BiConsumer`, and C76's private `callIntegration()`, `systemEvent()`,
 * `profilePictureActivityPort()` as `Supplier`s (so their `require` still throws where the Java threw).
 * `statusListener` (C04) arrives by value. `getUnifiedPushBroker()` stays a plain getter of C74's
 * field, and `createAccount`/`updateAccount`/`deleteAccount` call the service's own
 * `syncEnabledAccountSetting` through the `Runnable` this object also provides, so the setting write
 * happens once.
 *
 * The Java's order and guard order are kept: `updateAccount`'s colour write and its `statusListener`
 * call before the database write, `deleteAccount`'s whole body inside `synchronized(conversationList)`,
 * and the two raw `new Thread`s started where the Java started them. `conversation.getAccount() ==
 * account` is `===`.
 */
object AccountLifecycle {

    @JvmStatic
    fun createAccount(
        service: XmppConnectionService,
        account: AccountRef,
        callIntegration: Supplier<CallIntegrationFactory>,
        syncEnabledAccountSetting: Runnable,
    ) {
        account.initAccountServices(service)
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).createAccount(account)
        if (callIntegration.get().hasSystemFeature(service)) {
            callIntegration.get().togglePhoneAccountAsync(service, account)
        }
        XmppConnectionService.dataStatics().accounts().add(account)
        // Activate show own account name when there is more than one account
        if (XmppConnectionService.dataStatics().accounts().getAccounts().size > 1) {
            val editor = service.getPreferences().edit()
            editor.putBoolean("show_own_accounts", true).apply()
            editor.apply()
        }
        service.reconnectAccountInBackground(account)
        service.updateAccountUi()
        syncEnabledAccountSetting.run()
        service.toggleForegroundService()
    }

    @JvmStatic
    fun syncEnabledAccountSetting(
        service: XmppConnectionService,
        systemEvent: Supplier<SystemEventPort>,
        hasEnabledAccounts: BooleanSupplier,
        toggleSetProfilePictureActivity: Consumer<Boolean>,
    ) {
        val enabled = hasEnabledAccounts.asBoolean
        service.getPreferences()
            .edit()
            .putBoolean(systemEvent.get().settingEnabledAccounts(), enabled)
            .apply()
        toggleSetProfilePictureActivity.accept(enabled)
    }

    @JvmStatic
    fun toggleSetProfilePictureActivity(
        service: XmppConnectionService,
        enabled: Boolean,
        profilePictureActivityPort: Supplier<ProfilePictureActivityPort>,
    ) {
        // Pair 11 (D4): the component to enable or disable is a `:ui` activity, so the island names
        // no class here. The port owns the `ComponentName`, the two states and the
        // `DONT_KILL_APP` flag; the island owns the decision and the caller.
        profilePictureActivityPort.get().setEnabled(service, enabled)
    }

    @JvmStatic
    fun reconfigurePushDistributor(broker: UnifiedPushPort): Boolean =
        broker.reconfigurePushDistributor()

    @JvmStatic
    fun renewUnifiedPushEndpoints(
        broker: UnifiedPushPort,
        pushTargetMessenger: UnifiedPushPort.PushTarget?,
    ): Optional<UnifiedPushPort.Transport> =
        broker.renewUnifiedPushEndpoints(pushTargetMessenger)

    @JvmStatic
    fun provisionAccount(
        service: XmppConnectionService,
        address: String,
        password: String,
        createAccount: Consumer<AccountRef>,
    ) {
        val jid = Jid.of(address)
        val account = XmppConnectionService.dataStatics().accounts().create(jid, password)
        account.setOption(AccountRef.OPTION_DISABLED, true)
        Log.d(Config.LOGTAG, jid.asBareJid().toString() + ": provisioning account")
        createAccount.accept(account)
    }

    @JvmStatic
    fun createAccountFromKey(
        service: XmppConnectionService,
        alias: String,
        callback: OnAccountCreated,
        createAccount: Consumer<AccountRef>,
    ) {
        Thread {
            try {
                val chain = KeyChain.getCertificateChain(service, alias)
                val cert = if (chain != null && chain.size > 0) chain[0] else null
                if (cert == null) {
                    callback.informUser(R.string.unable_to_parse_certificate)
                } else {
                    val info = CryptoHelper.extractJidAndName(cert)
                    if (info == null) {
                        callback.informUser(R.string.certificate_does_not_contain_jid)
                    } else if (XmppConnectionService.dataStatics().accounts().findAccountByJid(info.first) == null) {
                        val account = XmppConnectionService.dataStatics().accounts().create(info.first, "")
                        account.setPrivateKeyAlias(alias)
                        account.setOption(AccountRef.OPTION_DISABLED, true)
                        account.setOption(AccountRef.OPTION_FIXED_USERNAME, true)
                        account.setDisplayName(info.second)
                        createAccount.accept(account)
                        callback.onAccountCreated(account)
                        if (Config.X509_VERIFICATION) {
                            try {
                                service.getMemorizingTrustManager()
                                    .getNonInteractive(account.getServer(), null, 0, null, false)
                                    .checkClientTrusted(chain, "RSA")
                            } catch (e: CertificateException) {
                                callback.informUser(R.string.certificate_chain_is_not_trusted)
                            }
                        }
                    } else {
                        callback.informUser(R.string.account_already_exists)
                    }
                }
            } catch (e: Exception) {
                callback.informUser(R.string.unable_to_parse_certificate)
            }
        }.start()
    }

    @JvmStatic
    fun updateKeyInAccount(service: XmppConnectionService, account: AccountRef, alias: String) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": update key in account " + alias)
        try {
            val chain = KeyChain.getCertificateChain(service, alias)
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + " loaded certificate chain")
            val info =
                CryptoHelper.extractJidAndName(
                    (chain ?: throw NullPointerException("certificate chain"))[0],
                )
            if (info == null) {
                service.showErrorToastInUi(R.string.certificate_does_not_contain_jid)
                return
            }
            if (account.getJid().asBareJid().equals(info.first)) {
                account.setPrivateKeyAlias(alias)
                account.setDisplayName(info.second)
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                if (Config.X509_VERIFICATION) {
                    try {
                        service.getMemorizingTrustManager()
                            .getNonInteractive()
                            .checkClientTrusted(chain, "RSA")
                    } catch (e: CertificateException) {
                        service.showErrorToastInUi(R.string.certificate_chain_is_not_trusted)
                    }
                    (account.getOmemoSession()
                        ?: throw NullPointerException("account has no omemo session"))
                        .regenerateKeys(true)
                }
            } else {
                service.showErrorToastInUi(R.string.jid_does_not_match_certificate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun updateAccount(
        service: XmppConnectionService,
        account: AccountRef,
        statusListener: OnStatusChanged,
        callIntegration: Supplier<CallIntegrationFactory>,
        channelDiscovery: Supplier<ChannelDiscoveryPort>,
        syncEnabledAccountSetting: Runnable,
    ): Boolean {
        if ((service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)) {
            val color = account.getColorToSave()
            if (color == null) {
                service.getPreferences().edit().remove("account_color:" + account.getUuid()).commit()
            } else {
                service.getPreferences().edit().putInt("account_color:" + account.getUuid(), color.toInt()).commit()
            }
            account.setShowErrorNotification(true)
            statusListener.onStatusChanged(account)
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            service.reconnectAccountInBackground(account)
            service.updateAccountUi()
            service.getNotificationService().updateErrorNotification()
            service.toggleForegroundService()
            syncEnabledAccountSetting.run()
            channelDiscovery.get().cleanCache()
            if (callIntegration.get().hasSystemFeature(service)) {
                callIntegration.get().togglePhoneAccountAsync(service, account)
            }
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun updateAccountPasswordOnServer(
        service: XmppConnectionService,
        account: AccountRef,
        newPassword: String,
        callback: OnAccountPasswordChanged,
    ) {
        val iq = service.getIqGenerator().generateSetPassword(account, newPassword)
        service.sendIqPacket(
            account,
            iq,
        ) { packet ->
            if (packet.getType() == Iq.Type.RESULT) {
                account.setPassword(newPassword)
                account.setOption(AccountRef.OPTION_MAGIC_CREATE, false)
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                callback.onPasswordChangeSucceeded()
            } else {
                callback.onPasswordChangeFailed()
            }
        }
    }

    @JvmStatic
    fun unregisterAccount(
        service: XmppConnectionService,
        account: AccountRef,
        callback: Consumer<Boolean>,
    ) {
        val iqPacket = Iq(Iq.Type.SET)
        val query = iqPacket.addChild("query", Namespace.REGISTER)
        query.addChild("remove")
        service.sendIqPacket(
            account,
            iqPacket,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                service.deleteAccount(account)
                callback.accept(true)
            } else {
                callback.accept(false)
            }
        }
    }

    @JvmStatic
    fun deleteAccount(
        service: XmppConnectionService,
        account: AccountRef,
        conversationList: MutableList<ConversationRef>,
        notificationService: NotificationPort?,
        databaseWriterExecutor: Executor,
        rosterSyncTaskManager: ReplacingTaskManager,
        disconnect: BiConsumer<AccountRef, Boolean>,
        callIntegration: Supplier<CallIntegrationFactory>,
        syncEnabledAccountSetting: Runnable,
    ) {
        service.getPreferences().edit().remove("onboarding_continued").commit()
        val connected = account.getStatusRef() == AccountRef.StateRef.ONLINE
        synchronized(conversationList) {
            if (connected) {
                (account.getOmemoSession()
                    ?: throw NullPointerException("account has no omemo session"))
                    .deleteOmemoIdentity()
            }
            for (conversation in conversationList) {
                if (conversation.getAccount() === account) {
                    if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                        if (connected) {
                            service.leaveMuc(conversation)
                        }
                    }
                    conversationList.remove(conversation)
                    notificationService!!.clear(conversation)
                }
            }
            Thread {
                for (contact in account.getRoster().getContacts()) {
                    contact.unregisterAsPhoneAccount(service)
                }
            }.start()
            if (account.getXmppConnection() != null) {
                Thread { disconnect.accept(account, !connected) }.start()
            }
            val runnable =
                Runnable {
                    if (!(service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteAccount(account)) {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() + ": unable to delete account",
                        )
                    }
                }
            databaseWriterExecutor.execute(runnable)
            XmppConnectionService.dataStatics().accounts().removeByUuid(account.getUuid())
            if (callIntegration.get().hasSystemFeature(service)) {
                callIntegration.get().unregisterPhoneAccount(service, account)
            }
            rosterSyncTaskManager.clear(account)
            service.updateAccountUi()
            notificationService!!.updateErrorNotification()
            syncEnabledAccountSetting.run()
            service.toggleForegroundService()
        }
    }
}
