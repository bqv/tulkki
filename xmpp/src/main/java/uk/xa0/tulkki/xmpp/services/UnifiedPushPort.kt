package uk.xa0.tulkki.xmpp.services

import android.os.Messenger
import com.google.common.base.Optional
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the UnifiedPush broker, in island vocabulary.
 *
 * <p>{@link Transport} and {@link PushTarget} are markers: both are the broker's own nested
 * classes, and the island only hands them back to the broker, so a marker is the whole of the
 * contract. The two factories are the two places the island builds one.
 */
interface UnifiedPushPort {

    interface Transport

    interface PushTarget

    /**
     * The object a renewal is in flight for, built by `:app` from the two strings the intent
     * carried. It took a ref until port-13's `PushTargetRef` deletion: the island read no field of
     * it then and reads none now, so the pair crosses as the two strings it is made of.
     */
    fun pushTarget(application: String, instance: String, messenger: Messenger): PushTarget

    fun renewUnifiedPushEndpoints(pushTarget: PushTarget?): Optional<Transport>

    fun renewUnifiedPushEndpointsOnBind(account: AccountRef)

    fun reconfigurePushDistributor(): Boolean

    fun processPushMessage(account: AccountRef, transport: Jid, push: Element): Boolean

    /**
     * The `messenger` is nullable because `StartCommand` reads it out of the intent
     * (`getParcelableExtra`) and passes on whatever it got; the broker's body and `sendEndpoint`
     * already take `Messenger?`.
     */
    fun rebroadcastEndpoint(messenger: Messenger?, instance: String, transport: Transport)
}
