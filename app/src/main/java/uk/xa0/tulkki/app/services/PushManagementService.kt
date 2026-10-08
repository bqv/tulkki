package uk.xa0.tulkki.app.services

import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The push-registration port's no-op implementation: this flavour has no push provider, so every
 * entry answers "no" or does nothing.
 *
 * <p>The constructor stays public and one-argument, because the composition root - `XmppTulkkiHost`
 * - builds it with `new PushManagementService(service)`. The four interface members take `override`;
 * `unregisterChannel` is the Java's own package-private extra, which Kotlin spells as an ordinary
 * member (nothing calls it: the only other `unregisterChannel` in the tree is
 * `IqGenerator.unregisterChannelOnAppServer`).
 *
 * <p>The Java's unused `import uk.xa0.tulkki.data.model.Conversation` is not carried over.
 *
 * <p>Name-string audit: 0 hits for `PushManagementService` in the manifest, `res/xml`,
 * `res/layout*`, `preferences_*.xml` or the ProGuard rules.
 */
class PushManagementService(service: XmppConnectionService) :
        uk.xa0.tulkki.xmpp.services.PushManagementPort {

    protected val mXmppConnectionService: XmppConnectionService = service

    override fun registerPushTokenOnServer(account: AccountRef) {
        // stub implementation. only affects playstore flavor
    }

    fun unregisterChannel(account: AccountRef, hash: String) {
        // stub implementation. only affects playstore flavor
    }

    override fun available(account: AccountRef): Boolean {
        return false
    }

    override fun isStub(): Boolean {
        return true
    }

    override fun availableAndUseful(account: AccountRef): Boolean {
        return false
    }
}
