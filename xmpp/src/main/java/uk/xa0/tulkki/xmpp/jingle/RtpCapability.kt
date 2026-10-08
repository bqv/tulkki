package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ImmutableSet
import java.util.Collections
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.PresencesRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef

/**
 * Whether a contact's client can take an RTP call, and which media it advertises.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **It is an `object`**, so `RtpCapability.check(...)` stays a static call for the Java and
 *    Kotlin callers (`ConversationFragment`, `Account`, `Contact`, `RtpSessionActivity`,
 *    `PresenceSelector`); every Java-visible entry point is `@JvmStatic`, and the nested
 *    `Capability` keeps the `RtpCapability.Capability` spelling `ContactRef` declares.
 * 2. **`jmiSupport` is rewritten with Kotlin's `none`.** Java built
 *    `Collections2.transform(Collections2.filter(presences, p -> check(p) != NONE), p -> disco !=
 *    null && disco.getFeatures().contains(JINGLE_MESSAGE))` and asked `.contains(false)`; "no
 *    RTP-capable presence lacks JMI" is the same predicate, and both short-circuit.
 * 3. **`check(ContactRef, boolean)` returns `Capability` through the platform type of
 *    `contact.getRtpCapability()`**, whose own doc says it may answer null; Kotlin passes that
 *    straight out, exactly as Java did.
 * 4. **The `Set`/`List` locals stay Kotlin's immutable types** (`ImmutableSet.copyOf` over
 *    `Collections.emptySet()`), while `containsAll` reads the `List` fields Java read.
 */
object RtpCapability {

    private val BASIC_RTP_REQUIREMENTS: List<String> =
        listOf(
            Namespace.JINGLE,
            Namespace.JINGLE_TRANSPORT_ICE_UDP,
            Namespace.JINGLE_APPS_RTP,
            Namespace.JINGLE_APPS_DTLS,
        )
    private val VIDEO_REQUIREMENTS: Collection<String> =
        listOf(Namespace.JINGLE_FEATURE_AUDIO, Namespace.JINGLE_FEATURE_VIDEO)

    @JvmStatic
    fun check(presence: PresenceRef): Capability {
        val disco = presence.getServiceDiscoveryResult()
        val features: Set<String> =
            if (disco == null) {
                Collections.emptySet()
            } else {
                ImmutableSet.copyOf(disco.getFeatures())
            }
        if (features.containsAll(BASIC_RTP_REQUIREMENTS)) {
            if (features.containsAll(VIDEO_REQUIREMENTS)) {
                return Capability.VIDEO
            }
            if (features.contains(Namespace.JINGLE_FEATURE_AUDIO)) {
                return Capability.AUDIO
            }
        }
        return Capability.NONE
    }

    @JvmStatic
    fun filterPresences(contact: ContactRef, required: Capability): Array<String> {
        val presences: PresencesRef = contact.getPresences()
        val resources = ArrayList<String>()
        for (presence in presences.getPresencesMap().entries) {
            val capability = check(presence.value)
            if (capability == Capability.NONE) {
                continue
            }
            if (required == Capability.AUDIO || capability == required) {
                resources.add(presence.key)
            }
        }
        return resources.toTypedArray()
    }

    @JvmStatic
    fun check(contact: ContactRef): Capability? = check(contact, true)

    @JvmStatic
    fun check(contact: ContactRef, allowFallback: Boolean): Capability? {
        val presences: PresencesRef = contact.getPresences()

        if (presences.isEmpty() && allowFallback && contact.getAccount().isEnabled()) {
            val gateway =
                contact.getAccount().getRoster().getContact(Jid.of(contact.getJid().getDomain()))
            if (gateway.showInRoster() && gateway.getPresences().anyIdentity("gateway", "pstn")) {
                return Capability.AUDIO
            }

            return contact.getRtpCapability()
        }
        var result = Capability.NONE
        for (presence in presences.getPresences()) {
            val capability = check(presence)
            if (capability == Capability.VIDEO) {
                result = capability
            } else if (capability == Capability.AUDIO && result == Capability.NONE) {
                result = capability
            }
        }
        return result
    }

    @JvmStatic
    fun jmiSupport(contact: ContactRef): Boolean =
        contact.getPresences().getPresences().none { p ->
            check(p) != Capability.NONE &&
                (p.getServiceDiscoveryResult()?.getFeatures()?.contains(Namespace.JINGLE_MESSAGE) !=
                    true)
        }

    enum class Capability {
        NONE,
        AUDIO,
        VIDEO;

        companion object {
            @JvmStatic
            fun of(value: String?): Capability {
                if (value.isNullOrEmpty()) {
                    return NONE
                }
                try {
                    return Capability.valueOf(value)
                } catch (e: IllegalArgumentException) {
                    return NONE
                }
            }
        }
    }
}
