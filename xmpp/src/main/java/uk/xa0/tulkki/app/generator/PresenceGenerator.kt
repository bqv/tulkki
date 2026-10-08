package uk.xa0.tulkki.app.generator

import android.text.TextUtils
import uk.xa0.tulkki.xmpp.models.stanza.Presence
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the presence stanzas this app puts on the wire.
 *
 * Ported from `PresenceGenerator.java`. It is *ours* rather than the island's, so it is
 * converted in place; only the spelling changes, and every method's descriptor is the one the Java
 * had.
 *
 * The Java-visible surface is read off the callers, not off the types:
 *
 *  * the class stays **open to Java** — the base's package-private constructor and its
 *    `protected mXmppConnectionService` and package-private `getCapHash` are all read from this
 *    file, and all three live in the same package, so the Java base needs no widening;
 *  * `getTimestamp` is **not** declared here. Java lets a static be reached through a subclass and
 *    `MucJoin.kt:116` did exactly that (`PresenceGenerator.getTimestamp(…)`); once this class is
 *    Kotlin and no longer a Java class, Kotlin resolution looks only at this class's own companion
 *    and the call goes unresolved. The call site is retargeted to `AbstractGenerator.getTimestamp`,
 *    which is what it always meant. Verified by the `MucJoin.kt` override fault, not by inspection.
 *  * `preAuth` and `nickname` are the only nullable parameters: the Java tests both for `null`
 *    before it builds the child, and every caller passes a non-null contact, account and status.
 *
 * The two `selfPresence` overloads keep their Java split rather than becoming a Kotlin default
 * argument, because `XmppConnectionService` and `:ui` call the three-argument method by name.
 */
class PresenceGenerator(service: XmppConnectionService) : AbstractGenerator(service) {

    private fun subscription(type: String, contact: ContactRef): Presence {
        val packet = Presence()
        packet.setAttribute("type", type)
        packet.setTo(contact.getJid())
        packet.setFrom(contact.getAccount().getJid().asBareJid())
        return packet
    }

    fun requestPresenceUpdatesFrom(contact: ContactRef): Presence =
        requestPresenceUpdatesFrom(contact, null)

    /**
     * Tulkki: 3.7 C5-B - the `Contact` half of this file is ref-typed now.
     *
     * Parts 11 and 15 carried ref-typed **overloads** beside the model-typed methods and cast in
     * the delegation (`(Contact) contact`), because a parameter type is not covariant and the
     * callers sat on different sides of the boundary. With the model name gone from this file each
     * pair is the same method twice and javac rejects it as a duplicate, so the model-typed half of
     * every pair is deleted and one ref-typed method remains. No caller changes: the `:ui` sites in
     * `ConversationFragment`/`ContactDetailsActivity` and `XmppConnectionService`'s part-15 sites
     * pass model objects, and `Contact implements ContactRef`.
     */
    fun requestPresenceUpdatesFrom(contact: ContactRef, preAuth: String?): Presence {
        val packet = subscription("subscribe", contact)
        val displayName = contact.getAccount().getDisplayName()
        if (!TextUtils.isEmpty(displayName)) {
            packet.addChild("nick", Namespace.NICK).setContent(displayName)
        }
        if (preAuth != null) {
            packet.addChild("preauth", Namespace.PARS).setAttribute("token", preAuth)
        }
        return packet
    }

    fun stopPresenceUpdatesFrom(contact: ContactRef): Presence =
        subscription("unsubscribe", contact)

    fun stopPresenceUpdatesTo(contact: ContactRef): Presence =
        subscription("unsubscribed", contact)

    fun sendPresenceUpdatesTo(contact: ContactRef): Presence =
        subscription("subscribed", contact)

    /**
     * Tulkki: 3.7 C5-D - the last two `:data` names in this file go the same way the `Contact` half
     * went in C5-B. The account is `AccountRef` (it needed `getPgpSignature`,
     * `getPresenceStatusMessage` and `getCapHash`, which already took the ref) and the status is the
     * island's [PresenceRef.StatusRef], mapped in `Presence.Status.toRef()` and read here only for
     * `toShowString()`. No caller changes: `Account implements AccountRef`, and the seven
     * `XmppConnectionService` sites pass `PresenceRef.StatusRef.ONLINE` - a constant of the island
     * enum - or `getTargetPresence()`, retyped with them.
     */
    fun selfPresence(account: AccountRef, status: PresenceRef.StatusRef): Presence =
        selfPresence(account, status, true, null)

    fun selfPresence(
        account: AccountRef,
        status: PresenceRef.StatusRef,
        personal: Boolean,
        nickname: String?,
    ): Presence {
        val packet = Presence()
        if (personal) {
            val sig = account.getPgpSignature()
            val message = account.getPresenceStatusMessage()
            val show = status.toShowString()
            if (show != null) {
                packet.addChild("show").setContent(show)
            }
            if (!TextUtils.isEmpty(message)) {
                packet.addChild(Element("status").setContent(message))
            }
            if (sig != null && mXmppConnectionService.getPgpEngine() != null) {
                packet.addChild("x", "jabber:x:signed").setContent(sig)
            }
        }
        if (nickname != null) {
            packet.addChild("nick", "http://jabber.org/protocol/nick").setContent(nickname)
        }
        val capHash = getCapHash(account)
        if (capHash != null) {
            val cap = packet.addChild("c", "http://jabber.org/protocol/caps")
            cap.setAttribute("hash", "sha-1")
            // Tulkki: the XEP-0115 node is our own identity on the wire, not upstream's - a URN,
            // so it names no domain we do not own, and other clients cache it as ours. The `ver`
            // hash is unchanged by this: `AbstractGenerator.getCapHash` hashes the identity
            // type/name and the feature list, never the node.
            cap.setAttribute("node", "urn:tulkki:caps")
            cap.setAttribute("ver", capHash)
        }
        return packet
    }

    fun leave(mucOptions: MucOptionsRef): Presence {
        val presence = Presence()
        presence.setTo(mucOptions.getSelf().getFullJid())
        presence.setFrom(mucOptions.getAccount().getJid())
        presence.setAttribute("type", "unavailable")
        return presence
    }

    fun sendOfflinePresence(account: AccountRef): Presence {
        val packet = Presence()
        packet.setFrom(account.getJid())
        packet.setAttribute("type", "unavailable")
        return packet
    }
}
