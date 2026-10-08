package uk.xa0.tulkki.xmpp.jingle

import java.util.Collections
import org.webrtc.PeerConnection
import uk.xa0.tulkki.xmpp.models.disco.external.Services
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * Reads the ICE servers out of an external-service-discovery result.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **It is an `object`**: Java's only members were the private constructor and the static
 *    `parse`, and no caller ever wrote `new IceServers()`.
 * 2. **`parse` answers Kotlin's `Collection`**, matching `Services.getIceServers()`'s own return, so
 *    both `Collections.emptySet()` and that call fit without a conversion. Java's declared type was
 *    the same `java.util.Collection`.
 */
object IceServers {

    @JvmStatic
    fun parse(response: Iq): Collection<PeerConnection.IceServer> {
        if (response.getType() != Iq.Type.RESULT) {
            return Collections.emptySet()
        }
        val services = response.getExtension(Services::class.java)
        if (services == null) {
            return Collections.emptySet()
        }
        return services.getIceServers()
    }
}
