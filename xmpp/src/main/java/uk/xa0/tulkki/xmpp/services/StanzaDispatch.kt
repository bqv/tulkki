package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.app.generator.MessageGenerator
import uk.xa0.tulkki.app.generator.PresenceGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.models.stanza.Presence
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: sending packets and the presence refresh, lifted out of `XmppConnectionService`
 * — the one doorway every stanza leaves through.
 *
 * The four senders are the Java's: the account ref is re-bound to a local for the model-typed
 * parameter, a missing connection drops the packet, and `sendIqPacket`'s timeout overload answers a
 * null callback with `Iq.TIMEOUT` rather than throwing. The private presence state comes in by
 * value — the presence generator, the last-activity stamp, and chunk `C15`'s two **private**
 * preference reads as suppliers, so `manuallyChangePresence` still short-circuits the
 * `getTargetPresence` call exactly as the Java `if`/`else` did.
 *
 * The three generator getters answer the private fields back to their callers; chunk `C55` has not
 * moved them, and the fields are built in place on the service, so they are handed in by hand.
 */
object StanzaDispatch {

    @JvmStatic
    fun sendMessagePacket(accountRef: AccountRef, packet: Message) {
        // Tulkki: 3.7 pair 9 - the island's parameter is the ref now (the parsers' call sites pass
        // one); the body still works in the model type.
        val account = accountRef
        val connection = account.getXmppConnection()
        if (connection != null) {
            connection.sendMessagePacket(packet)
        }
    }

    @JvmStatic
    fun sendPresencePacket(accountRef: AccountRef, packet: Presence) {
        // Tulkki: 3.7 pair 9 - the island's parameter is the ref now (the parsers' call sites pass
        // one); the body still works in the model type.
        val account = accountRef
        val connection = account.getXmppConnection()
        if (connection != null) {
            connection.sendPresencePacket(packet)
        }
    }

    @JvmStatic
    fun sendCreateAccountWithCaptchaPacket(account: AccountRef, id: String?, data: Data?) {
        val connection = account.getXmppConnection()
        if (connection == null) {
            return
        }
        connection.sendCreateAccountWithCaptchaPacket(id, data)
    }

    @JvmStatic
    fun sendIqPacket(accountRef: AccountRef, packet: Iq, callback: Consumer<Iq>?) {
        // Tulkki: 3.7 pair 9 - the island's parameter is the ref now (the parsers' call sites pass
        // one); the body still works in the model type.
        val account = accountRef
        sendIqPacket(account, packet, callback, null)
    }

    @JvmStatic
    fun sendIqPacket(accountRef: AccountRef, packet: Iq, callback: Consumer<Iq>?, timeout: Long?) {
        // Tulkki: 3.7 pair 9 - the island's parameter is the ref now (the parsers' call sites pass
        // one); the body still works in the model type.
        val account = accountRef
        val connection = account.getXmppConnection()
        if (connection != null) {
            connection.sendIqPacket(packet, callback, timeout)
        } else if (callback != null) {
            callback.accept(Iq.TIMEOUT)
        }
    }

    @JvmStatic
    fun sendPresence(
        service: XmppConnectionService,
        account: AccountRef,
        presenceGenerator: PresenceGenerator,
        lastActivity: Long,
        manuallyChangePresence: BooleanSupplier,
        targetPresence: Supplier<PresenceRef.StatusRef>,
    ) {
        sendPresence(
            service,
            account,
            service.checkListeners() && service.broadcastLastActivity(),
            presenceGenerator,
            lastActivity,
            manuallyChangePresence,
            targetPresence,
        )
    }

    @JvmStatic
    fun sendPresence(
        service: XmppConnectionService,
        account: AccountRef,
        includeIdleTimestamp: Boolean,
        presenceGenerator: PresenceGenerator,
        lastActivity: Long,
        manuallyChangePresence: BooleanSupplier,
        targetPresence: Supplier<PresenceRef.StatusRef>,
    ) {
        val status =
            if (manuallyChangePresence.asBoolean) {
                account.getPresenceStatusRef()
            } else {
                targetPresence.get()
            }
        val packet = presenceGenerator.selfPresence(account, status)
        if (lastActivity > 0 && includeIdleTimestamp) {
            val since = Math.min(lastActivity, System.currentTimeMillis()) // don't send future dates
            packet
                .addChild("idle", Namespace.IDLE)
                .setAttribute("since", AbstractGenerator.getTimestamp(since))
        }
        sendPresencePacket(account, packet)
    }

    @JvmStatic
    fun deactivateGracePeriod(service: XmppConnectionService) {
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            account.deactivateGracePeriod()
        }
    }

    @JvmStatic
    fun refreshAllPresences(
        service: XmppConnectionService,
        presenceGenerator: PresenceGenerator,
        lastActivity: Long,
        manuallyChangePresence: BooleanSupplier,
        targetPresence: Supplier<PresenceRef.StatusRef>,
    ) {
        val includeIdleTimestamp = service.checkListeners() && service.broadcastLastActivity()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.isConnectionEnabled()) {
                sendPresence(
                    service,
                    account,
                    includeIdleTimestamp,
                    presenceGenerator,
                    lastActivity,
                    manuallyChangePresence,
                    targetPresence,
                )
            }
        }
    }

    @JvmStatic
    fun refreshAllFcmTokens(
        service: XmppConnectionService,
        pushManagementService: PushManagementPort,
    ) {
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.isOnlineAndConnected() && pushManagementService.available(account)) {
                pushManagementService.registerPushTokenOnServer(account)
            }
        }
    }

    @JvmStatic
    fun sendOfflinePresence(account: AccountRef, presenceGenerator: PresenceGenerator) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": sending offline presence")
        sendPresencePacket(account, presenceGenerator.sendOfflinePresence(account))
    }

    @JvmStatic
    fun messageGenerator(generator: MessageGenerator): MessageGenerator = generator

    @JvmStatic
    fun presenceGenerator(generator: PresenceGenerator): PresenceGenerator = generator

    @JvmStatic
    fun iqGenerator(generator: IqGenerator): IqGenerator = generator
}
