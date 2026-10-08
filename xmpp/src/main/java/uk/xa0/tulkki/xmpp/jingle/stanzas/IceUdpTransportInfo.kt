package uk.xa0.tulkki.xmpp.jingle.stanzas

import android.util.Log
import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Multimap
import java.util.Arrays
import java.util.Collections
import java.util.Hashtable
import java.util.Locale
import java.util.UUID
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.jingle.SessionDescription
import uk.xa0.tulkki.xmpp.jingle.transports.Transport
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The ICE-UDP transport element (XEP-0176) and its candidates, fingerprints and credentials.
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`STUB` and `IceOption.WELL_KNOWN` are `@JvmField`**, so `IceUdpTransportInfo.STUB` and
 *    `IceOption.WELL_KNOWN` stay real static fields (`RtpContentMap:359` reads the former).
 * 2. **`Credentials.ufrag`/`password` are `@JvmField`** public fields, as Java declared them, and
 *    nullable because Java's came from `getAttribute`.
 * 3. **`Candidate`'s static factories are `@JvmStatic`** (`JingleRtpConnection`,
 *    `WebRTCDataChannelTransport`, `WebRtcIceCandidateTest` call `Candidate.fromSdpAttribute*` and
 *    `Candidate.upgrade` as statics).
 * 4. **`checkNotNullNoWhitespace` is a private *top-level* function.** Java had it `private static`
 *    and `Candidate` (a static nested class) called it; Kotlin will not let a nested class read the
 *    outer class's private member, so it moved out of the class entirely. It now returns the value it
 *    checked, which is how `toSdpAttribute` gets its non-null locals; the checks and their messages
 *    are unchanged.
 * 5. **`Fingerprint`'s constructor and its three-argument `of` are `internal`.** Java had both
 *    private, but `IceUdpTransportInfo.modifyCredentials`/`of(credentials, …)` call them and Kotlin
 *    forbids the outer class reaching a nested class's private members. `internal` is the module
 *    mapping this tree uses; the two-argument `upgrade` paths stay as Java left them.
 * 6. **`Strings.isNullOrEmpty` becomes Kotlin's `isNullOrEmpty()`** in `isStub` and
 *    `checkNotNullNoWhitespace` (identical predicate; the latter needs the smart cast).
 * 7. `withCandidates` keeps Java's deliberate copy: `replaceChildren(this.getChildren())`, which the
 *    `ElementBackingTest` source pin names.
 * 8. **The candidate's SDP parameter string is built with `joinToString`.** Java joined a
 *    `Collections2.transform` view with `Joiner.on(' ')`; Kotlin's `joinToString(" ")` over the same
 *    entries produces the identical string, and `Joiner`/`Collections2` leave the imports.
 * 9. The class stays `open`, because `OmemoVerifiedIceUdpTransportInfo` extends it.
 */
open class IceUdpTransportInfo :
    GenericTransportInfo("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP) {
    fun getFingerprint(): Fingerprint? {
        val fingerprint = this.findChild("fingerprint", Namespace.JINGLE_APPS_DTLS)
        return fingerprint?.let { Fingerprint.upgrade(it) }
    }

    fun getIceOptions(): List<String> {
        val optionBuilder = ImmutableList.builder<String>()
        for (child in getChildren()) {
            if (Namespace.JINGLE_TRANSPORT_ICE_OPTION == child.getNamespace()
                && IceOption.WELL_KNOWN.contains(child.getName())
            ) {
                optionBuilder.add(
                    SessionDescription.checkNoWhitespace(
                        child.getName(),
                        "Ice options should not contain whitespace",
                    )
                )
            }
        }
        return optionBuilder.build()
    }

    fun getCredentials(): Credentials {
        val ufrag = this.getAttribute("ufrag")
        val password = this.getAttribute("pwd")
        return Credentials(ufrag, password)
    }

    fun isStub(): Boolean =
        this.getAttribute("ufrag").isNullOrEmpty() &&
            this.getAttribute("pwd").isNullOrEmpty() &&
            getChildren().isEmpty()

    fun getCandidates(): List<Candidate> {
        val builder = ImmutableList.builder<Candidate>()
        for (child in getChildren()) {
            if ("candidate" == child.getName()) {
                builder.add(Candidate.upgrade(child))
            }
        }
        return builder.build()
    }

    fun cloneWrapper(): IceUdpTransportInfo {
        val transportInfo = IceUdpTransportInfo()
        transportInfo.setAttributes(Hashtable(getAttributes()))
        return transportInfo
    }

    fun modifyCredentials(credentials: Credentials, setup: Setup): IceUdpTransportInfo {
        val transportInfo = IceUdpTransportInfo()
        transportInfo.setAttribute("ufrag", credentials.ufrag)
        transportInfo.setAttribute("pwd", credentials.password)
        for (child in getChildren()) {
            if (child.getName().equals("fingerprint")
                && Namespace.JINGLE_APPS_DTLS == child.getNamespace()
            ) {
                val fingerprint = Fingerprint()
                fingerprint.setAttributes(Hashtable(child.getAttributes()))
                fingerprint.setContent(child.getContent())
                fingerprint.setAttribute("setup", setup.toString().lowercase(Locale.ROOT))
                transportInfo.addChild(fingerprint)
            }
        }
        for (iceOption in this.getIceOptions()) {
            transportInfo.addChild(IceOption(iceOption))
        }
        return transportInfo
    }

    fun withCandidates(candidates: ImmutableCollection<Candidate>): IceUdpTransportInfo {
        val transportInfo = IceUdpTransportInfo()
        transportInfo.setAttributes(Hashtable(getAttributes()))
        transportInfo.replaceChildren(this.getChildren())
        for (candidate in candidates) {
            transportInfo.addChild(candidate)
        }
        return transportInfo
    }

    class Credentials(
        @JvmField val ufrag: String?,
        @JvmField val password: String?,
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as Credentials
            return Objects.equal(ufrag, that.ufrag) && Objects.equal(password, that.password)
        }

        override fun hashCode(): Int = Objects.hashCode(ufrag, password)

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("ufrag", ufrag)
                .add("password", password)
                .toString()
    }

    class Candidate private constructor() : Element("candidate"), Transport.Candidate {

        fun getComponent(): Int = getAttributeAsInt("component")

        fun getFoundation(): Int = getAttributeAsInt("foundation")

        fun getGeneration(): Int = getAttributeAsInt("generation")

        fun getId(): String? = getAttribute("id")

        fun getIp(): String? = getAttribute("ip")

        fun getNetwork(): Int = getAttributeAsInt("network")

        fun getPort(): Int = getAttributeAsInt("port")

        fun getPriority(): Int = getAttributeAsInt("priority")

        fun getProtocol(): String? = getAttribute("protocol")

        fun getRelAddr(): String? = getAttribute("rel-addr")

        fun getRelPort(): Int = getAttributeAsInt("rel-port")

        fun getType(): String? = getAttribute("type") // TODO might be converted to enum

        private fun getAttributeAsInt(name: String): Int {
            val value = this.getAttribute(name)
            if (value == null) {
                return 0
            }
            try {
                return Integer.parseInt(value)
            } catch (e: NumberFormatException) {
                return 0
            }
        }

        fun toSdpAttribute(ufrag: String?): String {
            val foundation = checkNotNullNoWhitespace(this.getAttribute("foundation"), "foundation")
            val component = checkNotNullNoWhitespace(this.getAttribute("component"), "component")
            val protocol = checkNotNullNoWhitespace(this.getAttribute("protocol"), "protocol")
            val transport = protocol.lowercase(Locale.ROOT)
            if ("udp" != transport) {
                throw IllegalArgumentException(
                    String.format("'%s' is not a supported protocol", transport)
                )
            }
            val priority = checkNotNullNoWhitespace(this.getAttribute("priority"), "priority")
            val connectionAddress = checkNotNullNoWhitespace(this.getAttribute("ip"), "ip")
            val port = checkNotNullNoWhitespace(this.getAttribute("port"), "port")
            val additionalParameter = LinkedHashMap<String, String>()
            val relAddr = this.getAttribute("rel-addr")
            val type = this.getAttribute("type")
            if (type != null) {
                additionalParameter.put("typ", type)
            }
            if (relAddr != null) {
                additionalParameter.put("raddr", relAddr)
            }
            val relPort = this.getAttribute("rel-port")
            if (relPort != null) {
                additionalParameter.put("rport", relPort)
            }
            val generation = this.getAttribute("generation")
            if (generation != null) {
                additionalParameter.put("generation", generation)
            }
            if (ufrag != null) {
                additionalParameter.put("ufrag", ufrag)
            }
            val parametersString =
                additionalParameter.entries.joinToString(" ") { input ->
                    String.format("%s %s", input.key, input.value)
                }
            return String.format(
                "candidate:%s %s %s %s %s %s %s",
                foundation,
                component,
                transport,
                priority,
                connectionAddress,
                port,
                parametersString,
            )
        }

        companion object {
            @JvmStatic
            fun upgrade(element: Element): Candidate {
                Preconditions.checkArgument("candidate" == element.getName())
                val candidate = Candidate()
                candidate.bindTo(element)
                return candidate
            }

            // https://tools.ietf.org/html/draft-ietf-mmusic-ice-sip-sdp-39#section-5.1
            @JvmStatic
            fun fromSdpAttribute(attribute: String, currentUfrag: String?): Candidate? {
                val pair = attribute.split(":", limit = 2)
                if (pair.size == 2 && "candidate" == pair[0]) {
                    return fromSdpAttributeValue(pair[1], currentUfrag)
                }
                return null
            }

            @JvmStatic
            fun fromSdpAttributeValue(value: String, currentUfrag: String?): Candidate? {
                val segments = value.split(" ")
                if (segments.size < 6) {
                    return null
                }
                val id = UUID.randomUUID().toString()
                val foundation = segments[0]
                val component = segments[1]
                val transport = segments[2].lowercase(Locale.ROOT)
                val priority = segments[3]
                val connectionAddress = segments[4]
                val port = segments[5]
                val additional = HashMap<String, String>()
                var i = 6
                while (i < segments.size - 1) {
                    additional.put(segments[i], segments[i + 1])
                    i = i + 2
                }
                val ufrag = additional.get("ufrag")
                if (currentUfrag != null && ufrag != null && ufrag != currentUfrag) {
                    return null
                }
                val candidate = Candidate()
                candidate.setAttribute("component", component)
                candidate.setAttribute("foundation", foundation)
                candidate.setAttribute("generation", additional.get("generation"))
                candidate.setAttribute("rel-addr", additional.get("raddr"))
                candidate.setAttribute("rel-port", additional.get("rport"))
                candidate.setAttribute("id", id)
                candidate.setAttribute("ip", connectionAddress)
                candidate.setAttribute("port", port)
                candidate.setAttribute("priority", priority)
                candidate.setAttribute("protocol", transport)
                candidate.setAttribute("type", additional.get("typ"))
                return candidate
            }
        }
    }

    class Fingerprint internal constructor() : Element("fingerprint", Namespace.JINGLE_APPS_DTLS) {

        fun getHash(): String? = this.getAttribute("hash")

        fun getSetup(): Setup? {
            val setup = this.getAttribute("setup")
            return if (setup == null) null else Setup.of(setup)
        }

        companion object {
            @JvmStatic
            fun upgrade(element: Element): Fingerprint {
                Preconditions.checkArgument("fingerprint" == element.getName())
                Preconditions.checkArgument(Namespace.JINGLE_APPS_DTLS == element.getNamespace())
                val fingerprint = Fingerprint()
                fingerprint.setAttributes(element.getAttributes())
                fingerprint.setContent(element.getContent())
                return fingerprint
            }

            private fun of(attributes: Multimap<String, String>): Fingerprint? {
                val fingerprint = Iterables.getFirst(attributes.get("fingerprint"), null)
                val setup = Iterables.getFirst(attributes.get("setup"), null)
                if (setup != null && fingerprint != null) {
                    val fingerprintParts = fingerprint.split(" ", limit = 2)
                    if (fingerprintParts.size == 2) {
                        val hash = fingerprintParts[0]
                        val actualFingerprint = fingerprintParts[1]
                        val element = Fingerprint()
                        element.setAttribute("hash", hash)
                        element.setAttribute("setup", setup)
                        element.setContent(actualFingerprint)
                        return element
                    }
                }
                return null
            }

            @JvmStatic
            fun of(
                sessionDescription: SessionDescription,
                media: SessionDescription.Media,
            ): Fingerprint? {
                val fingerprint = of(media.attributes)
                return if (fingerprint == null) of(sessionDescription.attributes) else fingerprint
            }

            internal fun of(setup: Setup, hash: String, content: String): Fingerprint {
                val fingerprint = Fingerprint()
                fingerprint.setContent(content)
                fingerprint.setAttribute("hash", hash)
                fingerprint.setAttribute("setup", setup.toString().lowercase(Locale.ROOT))
                return fingerprint
            }
        }
    }

    enum class Setup {
        ACTPASS,
        PASSIVE,
        ACTIVE;

        fun flip(): Setup {
            if (this == PASSIVE) {
                return ACTIVE
            }
            if (this == ACTIVE) {
                return PASSIVE
            }
            throw IllegalStateException(this.name + " can not be flipped")
        }

        companion object {
            @JvmStatic
            fun of(setup: String): Setup? =
                try {
                    Setup.valueOf(setup.uppercase(Locale.ROOT))
                } catch (e: IllegalArgumentException) {
                    null
                }
        }
    }

    class IceOption(name: String) : Element(name, Namespace.JINGLE_TRANSPORT_ICE_OPTION) {

        companion object {
            @JvmField
            val WELL_KNOWN: List<String> = Arrays.asList("trickle", "renomination")

            @JvmStatic
            fun of(media: SessionDescription.Media): Collection<String> {
                val iceOptions = Iterables.getFirst(media.attributes.get("ice-options"), null)
                if (iceOptions.isNullOrEmpty()) {
                    return Collections.emptyList()
                }
                val optionBuilder = ImmutableList.builder<String>()
                for (iceOption in Splitter.on(' ').split(iceOptions)) {
                    if (WELL_KNOWN.contains(iceOption)) {
                        optionBuilder.add(iceOption)
                    } else {
                        Log.w(Config.LOGTAG, "unrecognized ice option: $iceOption")
                    }
                }
                return optionBuilder.build()
            }
        }
    }

    companion object {
        @JvmField
        val STUB = IceUdpTransportInfo()

        @JvmStatic
        fun upgrade(element: Element): IceUdpTransportInfo {
            Preconditions.checkArgument(
                "transport" == element.getName(),
                "Name of provided element is not transport",
            )
            Preconditions.checkArgument(
                Namespace.JINGLE_TRANSPORT_ICE_UDP == element.getNamespace(),
                "Element does not match ice-udp transport namespace",
            )
            val transportInfo = IceUdpTransportInfo()
            transportInfo.bindTo(element)
            return transportInfo
        }

        @JvmStatic
        fun of(
            sessionDescription: SessionDescription,
            media: SessionDescription.Media,
        ): IceUdpTransportInfo {
            val ufrag = Iterables.getFirst(media.attributes.get("ice-ufrag"), null)
            val pwd = Iterables.getFirst(media.attributes.get("ice-pwd"), null)
            val iceUdpTransportInfo = IceUdpTransportInfo()
            if (ufrag != null) {
                iceUdpTransportInfo.setAttribute("ufrag", ufrag)
            }
            if (pwd != null) {
                iceUdpTransportInfo.setAttribute("pwd", pwd)
            }
            val fingerprint = Fingerprint.of(sessionDescription, media)
            if (fingerprint != null) {
                iceUdpTransportInfo.addChild(fingerprint)
            }
            for (iceOption in IceOption.of(media)) {
                iceUdpTransportInfo.addChild(IceOption(iceOption))
            }
            for (candidate in media.attributes.get("candidate")) {
                Candidate.fromSdpAttributeValue(candidate, ufrag)?.let {
                    iceUdpTransportInfo.addChild(it)
                }
            }
            return iceUdpTransportInfo
        }

        @JvmStatic
        fun of(
            credentials: Credentials,
            iceOptions: Collection<String>,
            setup: Setup,
            hash: String,
            fingerprint: String,
        ): IceUdpTransportInfo {
            val iceUdpTransportInfo = IceUdpTransportInfo()
            iceUdpTransportInfo.addChild(Fingerprint.of(setup, hash, fingerprint))
            iceUdpTransportInfo.setAttribute("ufrag", credentials.ufrag)
            iceUdpTransportInfo.setAttribute("pwd", credentials.password)
            for (iceOption in iceOptions) {
                iceUdpTransportInfo.addChild(IceOption(iceOption))
            }
            return iceUdpTransportInfo
        }
    }
}

/** Java's `private static IceUdpTransportInfo.checkNotNullNoWhitespace`, moved out of the class. */
private fun checkNotNullNoWhitespace(value: String?, name: String): String {
    if (value.isNullOrEmpty()) {
        throw IllegalArgumentException(String.format("Parameter %s is missing or empty", name))
    }
    return SessionDescription.checkNoWhitespace(
        value,
        String.format("Parameter %s contains white spaces", name),
    )
}
