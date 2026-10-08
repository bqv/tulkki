package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.AccountRef

/** Tulkki: what the bind path and the account screens ask of the push registration. */
interface PushManagementPort {

    fun registerPushTokenOnServer(account: AccountRef)

    fun available(account: AccountRef): Boolean

    fun isStub(): Boolean

    fun availableAndUseful(account: AccountRef): Boolean
}
