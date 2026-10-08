package uk.xa0.tulkki.xmpp.models.disco.external

import android.util.Log
import uk.xa0.tulkki.annotation.XmlElement
import java.util.Objects
import org.webrtc.PeerConnection
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.libs.IP

/**
 * XEP-0215 external service discovery: the `<services/>` answer whose `<service/>` children become
 * WebRTC ICE servers. The package namespace moves onto the class, because Kotlin cannot annotate a
 * package; the derived (`services`, `urn:xmpp:extdisco:2`) pair comes from the `@XmlElement`
 * annotation.
 *
 * The Guava shapes are substituted where the declared signature allows it: the
 * `ImmutableSet.Builder` becomes a `LinkedHashSet` (same insertion order and dedup),
 * `Ints.tryParse` becomes `toIntOrNull`, `Collections2.transform` becomes `map`, and
 * `Strings.isNullOrEmpty`/`Arrays.asList(...).contains` become `isNullOrEmpty`/`in`. The private
 * nested `IceServerWrapper` keeps its private visibility and its explicit `equals`/`hashCode`;
 * `hashCode` reproduces upstream's two-`urls`-and-no-`username` shape rather than repairing it.
 */
@XmlElement(namespace = Namespace.EXTERNAL_SERVICE_DISCOVERY)
class Services : Extension(Services::class.java) {

    fun getServices(): Collection<Service> = getExtensions(Service::class.java)

    fun getIceServers(): Collection<PeerConnection.IceServer> {
        val builder = LinkedHashSet<IceServerWrapper>()
        for (service in getServices()) {
            val type = service.getAttribute("type")
            val host = service.getAttribute("host")
            val sport = service.getAttribute("port")
            val port = sport?.toIntOrNull()
            val transport = service.getAttribute("transport")
            val username = service.getAttribute("username")
            val password = service.getAttribute("password")
            if (host.isNullOrEmpty() || port == null) {
                continue
            }
            if (port < 0 || port > 65535) {
                continue
            }

            if (type != null && type in ICE_TYPES && transport != null && transport in TRANSPORTS) {
                if (type in TLS_TYPES && transport == "udp") {
                    Log.w(
                        Config.LOGTAG,
                        "skipping invalid combination of udp/tls in external services",
                    )
                    continue
                }

                // STUN URLs do not support a query section since M110
                val uri: String
                if (type in STUN_TYPES) {
                    uri = String.format("%s:%s:%s", type, IP.wrapIPv6(host), port)
                } else {
                    uri = String.format(
                        "%s:%s:%s?transport=%s",
                        type,
                        IP.wrapIPv6(host),
                        port,
                        transport,
                    )
                }

                val iceServerBuilder = PeerConnection.IceServer.builder(uri)
                iceServerBuilder.setTlsCertPolicy(
                    PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK,
                )
                if (username != null && password != null) {
                    iceServerBuilder.setUsername(username)
                    iceServerBuilder.setPassword(password)
                } else if (type in TURN_TYPES) {
                    // The WebRTC spec requires throwing an
                    // InvalidAccessError on empty username or password
                    // https://chromium.googlesource.com/external/webrtc/+/master/pc/ice_server_parsing.cc
                    Log.w(
                        Config.LOGTAG,
                        "skipping " + type + "/" + transport + " without username and password",
                    )
                    continue
                }
                val iceServer = IceServerWrapper(iceServerBuilder.createIceServer())
                Log.w(Config.LOGTAG, "discovered ICE Server: " + iceServer)
                builder.add(iceServer)
            }
        }
        Log.d(Config.LOGTAG, "discovered " + builder.size + " ice servers")
        return builder.map { it.iceServer }
    }

    private class IceServerWrapper(val iceServer: PeerConnection.IceServer) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IceServerWrapper) return false
            return iceServer.urls == other.iceServer.urls &&
                iceServer.username == other.iceServer.username &&
                iceServer.password == other.iceServer.password
        }

        override fun hashCode(): Int =
            Objects.hash(iceServer.urls, iceServer.urls, iceServer.password)

        override fun toString(): String = iceServer.toString()
    }
}

private val ICE_TYPES = setOf("stun", "stuns", "turn", "turns")
private val TRANSPORTS = setOf("udp", "tcp")
private val TLS_TYPES = setOf("stuns", "turns")
private val STUN_TYPES = setOf("stun", "stuns")
private val TURN_TYPES = setOf("turn", "turns")
