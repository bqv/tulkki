package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import android.util.Pair
import com.google.common.base.CharMatcher
import com.google.common.base.Joiner
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Multimap
import java.util.Collections
import java.util.Locale
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.jingle.stanzas.FileTransferDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.WebRTCDataChannelTransportInfo

private const val HARDCODED_MEDIA_PROTOCOL = "UDP/TLS/RTP/SAVPF"
private const val HARDCODED_APPLICATION_PROTOCOL = "UDP/DTLS/SCTP"
private const val FORMAT_WEBRTC_DATA_CHANNEL = "webrtc-datachannel"
private const val HARDCODED_MEDIA_PORT = 9
private const val HARDCODED_CONNECTION = "IN IP4 0.0.0.0"
private val HARDCODED_ICE_OPTIONS: Collection<String> = Collections.singleton("trickle")

/** Java's `private static SessionDescription.appendAttributes`, kept file-private for both users. */
private fun appendAttributes(s: StringBuilder, attributes: Multimap<String, String>) {
    for (attribute in attributes.entries()) {
        val key = attribute.key
        val value = attribute.value
        s.append("a=").append(key)
        if (!value.isNullOrEmpty()) {
            s.append(':').append(value)
        }
        s.append(SessionDescription.LINE_DIVIDER)
    }
}

/** Java's `private static SessionDescription.transportInfoMediaAttributes(IceUdpTransportInfo)`. */
private fun transportInfoMediaAttributes(transport: IceUdpTransportInfo): Multimap<String, String> {
    val mediaAttributes = ArrayListMultimap.create<String, String>()
    val ufrag = transport.getAttribute("ufrag")
    val pwd = transport.getAttribute("pwd")
    if (ufrag.isNullOrEmpty()) {
        throw IllegalArgumentException("Transport element is missing required ufrag attribute")
    }
    SessionDescription.checkNoWhitespace(ufrag, "ufrag value must not contain any whitespaces")
    mediaAttributes.put("ice-ufrag", ufrag)
    if (pwd.isNullOrEmpty()) {
        throw IllegalArgumentException("Transport element is missing required pwd attribute")
    }
    SessionDescription.checkNoWhitespace(pwd, "pwd value must not contain any whitespaces")
    mediaAttributes.put("ice-pwd", pwd)
    val negotiatedIceOptions = transport.getIceOptions()
    val iceOptions: Collection<String> =
        if (negotiatedIceOptions.isEmpty()) HARDCODED_ICE_OPTIONS else negotiatedIceOptions
    mediaAttributes.put("ice-options", Joiner.on(' ').join(iceOptions))
    val fingerprint = transport.getFingerprint()
    if (fingerprint != null) {
        val hashFunction = fingerprint.getHash()
        val hash = fingerprint.getContent()
        if (hashFunction.isNullOrEmpty() || hash.isNullOrEmpty()) {
            throw IllegalArgumentException("DTLS-SRTP missing hash")
        }
        SessionDescription.checkNoWhitespace(
            hashFunction,
            "DTLS-SRTP hash function must not contain whitespace",
        )
        SessionDescription.checkNoWhitespace(hash, "DTLS-SRTP hash must not contain whitespace")
        mediaAttributes.put("fingerprint", "$hashFunction $hash")
        val setup = fingerprint.getSetup()
        if (setup != null) {
            mediaAttributes.put("setup", setup.toString().lowercase(Locale.ROOT))
        }
    }
    return ImmutableMultimap.copyOf(mediaAttributes)
}

/** Java's `private static SessionDescription.transportInfoMediaAttributes(WebRTCDataChannelTransportInfo)`. */
private fun transportInfoMediaAttributes(
    transport: WebRTCDataChannelTransportInfo,
): Multimap<String, String> {
    val mediaAttributes = ArrayListMultimap.create<String, String>()
    val iceUdpTransportInfo = transport.innerIceUdpTransportInfo()
    if (iceUdpTransportInfo == null) {
        throw IllegalArgumentException("Transport element is missing inner ice-udp transport")
    }
    mediaAttributes.putAll(transportInfoMediaAttributes(iceUdpTransportInfo))
    val sctpPort = transport.getSctpPort()
    if (sctpPort == null) {
        throw IllegalArgumentException("Transport element is missing required sctp-port attribute")
    }
    mediaAttributes.put("sctp-port", sctpPort.toString())
    val maxMessageSize = transport.getMaxMessageSize()
    if (maxMessageSize == null) {
        throw IllegalArgumentException("Transport element is missing required max-message-size")
    }
    mediaAttributes.put("max-message-size", maxMessageSize.toString())
    return ImmutableMultimap.copyOf(mediaAttributes)
}

/**
 * One SDP document: the session-level lines and the media sections.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The two public fields `version`/`name`/`connectionData`/`attributes`/`media` stay fields**
 *    (`@JvmField`): `JingleRtpConnection` iterates `answer.media`, and the already-Kotlin stanzas
 *    (`Content`, `RtpDescription`, `IceUdpTransportInfo`, `WebRTCDataChannelTransportInfo`) read
 *    `media.media` and `media.attributes` directly. `attributes` is the concrete
 *    `ArrayListMultimap` Java declared.
 * 2. **`name`/`connectionData` answer `String?`**, because Java left both unset on some parse paths
 *    and `toString` appends them through `StringBuilder.append(String?)`.
 * 3. **`checkNoWhitespace` takes `String?` and returns `String`.** Java's parameter was unannotated
 *    and `RtpDescription:249` passes the `String?` `Preconditions` had only *asserted* non-null; the
 *    body then runs `CharMatcher.matchesAnyOf`, which dereferences, so a null input throws exactly
 *    the `NullPointerException` Java threw, and the surviving value is non-null — which is what
 *    `IceUdpTransportInfo:67` and its private `checkNotNullNoWhitespace` need from the return.
 * 4. **`ignorantIntParser` takes `String?`**: `Integer.parseInt(null)` throws
 *    `NumberFormatException`, which the catch turns into `0`, exactly as Java answered.
 * 5. **The private static helpers are file-private top-level functions.** Java's `private static`
 *    `appendAttributes` and both `transportInfoMediaAttributes` overloads are reachable from the
 *    companion's `@JvmStatic` factories as well as from instance code, and Kotlin will not let the
 *    outer class reach a companion's private member — the `IceUdpTransportInfo` precedent. A
 *    top-level private is visible to both and stays off the Java surface.
 * 6. `LINE_DIVIDER` is a `const val` on the companion, so it is the same `public static final
 *    String` Java declared.
 * 7. Kotlin's `split(delimiter, limit = 2)` is Java's `String.split`, because neither delimiter
 *    (`"\r\n"`, `"="`, `":"`, `" "`, `"/"`) is a regex metacharacter.
 */
class SessionDescription(
    @JvmField val version: Int,
    @JvmField val name: String?,
    @JvmField val connectionData: String?,
    @JvmField val attributes: ArrayListMultimap<String, String>,
    @JvmField val media: List<Media>,
) {

    override fun toString(): String {
        val s =
            StringBuilder()
                .append("v=")
                .append(version)
                .append(LINE_DIVIDER)
                // TODO randomize or static
                .append("o=- 8770656990916039506 2 IN IP4 127.0.0.1")
                .append(LINE_DIVIDER) // what ever that means
                .append("s=")
                .append(name)
                .append(LINE_DIVIDER)
                .append("t=0 0")
                .append(LINE_DIVIDER)
        appendAttributes(s, attributes)
        for (media in this.media) {
            s.append("m=")
                .append(media.media)
                .append(' ')
                .append(media.port)
                .append(' ')
                .append(media.protocol)
                .append(' ')
                .append(media.format)
                .append(LINE_DIVIDER)
            s.append("c=").append(media.connectionData).append(LINE_DIVIDER)
            appendAttributes(s, media.attributes)
        }
        return s.toString()
    }

    class Media(
        @JvmField val media: String,
        @JvmField val port: Int,
        @JvmField val protocol: String,
        @JvmField val format: String,
        @JvmField val connectionData: String,
        @JvmField val attributes: Multimap<String, String>,
    )

    companion object {
        const val LINE_DIVIDER = "\r\n"

        @JvmStatic
        fun parse(input: String): SessionDescription {
            val sessionDescriptionBuilder = SessionDescriptionBuilder()
            var currentMediaBuilder: MediaBuilder? = null
            var attributeMap: ArrayListMultimap<String, String> = ArrayListMultimap.create()
            val mediaBuilder = ImmutableList.builder<Media>()
            for (line in input.split(LINE_DIVIDER)) {
                val pair = line.trim().split("=", limit = 2)
                if (pair.size < 2 || pair[0].length != 1) {
                    Log.d(Config.LOGTAG, "skipping sdp parsing on line $line")
                    continue
                }
                val key = pair[0][0]
                val value = pair[1]
                when (key) {
                    'v' -> sessionDescriptionBuilder.setVersion(ignorantIntParser(value))
                    'c' -> {
                        val builder = currentMediaBuilder
                        if (builder != null) {
                            builder.setConnectionData(value)
                        } else {
                            sessionDescriptionBuilder.setConnectionData(value)
                        }
                    }
                    's' -> sessionDescriptionBuilder.setName(value)
                    'a' -> {
                        val attribute = parseAttribute(value)
                        attributeMap.put(attribute.first, attribute.second)
                    }
                    'm' -> {
                        val previousMediaBuilder = currentMediaBuilder
                        if (previousMediaBuilder == null) {
                            sessionDescriptionBuilder.setAttributes(attributeMap)
                        } else {
                            previousMediaBuilder.setAttributes(attributeMap)
                            mediaBuilder.add(previousMediaBuilder.createMedia())
                        }
                        attributeMap = ArrayListMultimap.create()
                        val newMediaBuilder = MediaBuilder()
                        currentMediaBuilder = newMediaBuilder
                        val parts = value.split(" ")
                        if (parts.size >= 3) {
                            newMediaBuilder.setMedia(parts[0])
                            newMediaBuilder.setPort(ignorantIntParser(parts[1]))
                            newMediaBuilder.setProtocol(parts[2])
                            val formats = ImmutableList.builder<Int>()
                            for (i in 3 until parts.size) {
                                formats.add(ignorantIntParser(parts[i]))
                            }
                            newMediaBuilder.setFormats(formats.build())
                        } else {
                            Log.d(Config.LOGTAG, "skipping media line $line")
                        }
                    }
                }
            }
            val builder = currentMediaBuilder
            if (builder != null) {
                builder.setAttributes(attributeMap)
                mediaBuilder.add(builder.createMedia())
            } else {
                sessionDescriptionBuilder.setAttributes(attributeMap)
            }
            sessionDescriptionBuilder.setMedia(mediaBuilder.build())
            return sessionDescriptionBuilder.createSessionDescription()
        }

        @JvmStatic
        fun of(contentMap: FileTransferContentMap): SessionDescription {
            val sessionDescriptionBuilder = SessionDescriptionBuilder()
            val attributeMap = ArrayListMultimap.create<String, String>()
            val mediaListBuilder = ImmutableList.builder<Media>()

            val group = contentMap.group
            if (group != null) {
                val semantics = group.getSemantics()
                checkNoWhitespace(semantics, "group semantics value must not contain any whitespace")
                val idTags = group.getIdentificationTags()
                for (content in idTags) {
                    checkNoWhitespace(content, "group content names must not contain any whitespace")
                }
                attributeMap.put("group", group.getSemantics() + " " + Joiner.on(' ').join(idTags))
            }

            // TODO my-media-stream can be removed I think
            attributeMap.put("msid-semantic", " WMS my-media-stream")

            for (entry in contentMap.contents.entries) {
                val descriptionTransport = entry.value
                val transport = descriptionTransport.transport
                if (transport !is WebRTCDataChannelTransportInfo) {
                    throw IllegalArgumentException("Transport is not of type WebRTCDataChannel")
                }
                val name = entry.key
                checkNoWhitespace(name, "content name must not contain any whitespace")

                val mediaBuilder = MediaBuilder()
                mediaBuilder.setMedia("application")
                mediaBuilder.setConnectionData(HARDCODED_CONNECTION)
                mediaBuilder.setPort(HARDCODED_MEDIA_PORT)
                mediaBuilder.setProtocol(HARDCODED_APPLICATION_PROTOCOL)
                mediaBuilder.setAttributes(transportInfoMediaAttributes(transport))
                mediaBuilder.setFormat(FORMAT_WEBRTC_DATA_CHANNEL)
                mediaListBuilder.add(mediaBuilder.createMedia())
            }

            sessionDescriptionBuilder.setVersion(0)
            sessionDescriptionBuilder.setName("-")
            sessionDescriptionBuilder.setMedia(mediaListBuilder.build())
            sessionDescriptionBuilder.setAttributes(attributeMap)
            return sessionDescriptionBuilder.createSessionDescription()
        }

        @JvmStatic
        fun of(contentMap: RtpContentMap, isInitiatorContentMap: Boolean): SessionDescription {
            val sessionDescriptionBuilder = SessionDescriptionBuilder()
            val attributeMap = ArrayListMultimap.create<String, String>()
            val mediaListBuilder = ImmutableList.builder<Media>()
            val group = contentMap.group
            if (group != null) {
                val semantics = group.getSemantics()
                checkNoWhitespace(semantics, "group semantics value must not contain any whitespace")
                val idTags = group.getIdentificationTags()
                for (content in idTags) {
                    checkNoWhitespace(content, "group content names must not contain any whitespace")
                }
                attributeMap.put("group", group.getSemantics() + " " + Joiner.on(' ').join(idTags))
            }

            // TODO my-media-stream can be removed I think
            attributeMap.put("msid-semantic", " WMS my-media-stream")

            for (entry in contentMap.contents.entries) {
                val name = entry.key
                checkNoWhitespace(name, "content name must not contain any whitespace")
                // https://groups.google.com/g/discuss-webrtc/c/VG406JMTBI4/m/MrSex_q7AgAJ
                if (name.length > 16) {
                    throw IllegalArgumentException("mid should not be longer than 16 chars")
                }
                val descriptionTransport = entry.value
                val description = descriptionTransport.description ?: throw NullPointerException()
                val mediaAttributes = ArrayListMultimap.create<String, String>()
                mediaAttributes.putAll(transportInfoMediaAttributes(descriptionTransport.transport))
                val formatBuilder = ImmutableList.builder<Int>()
                for (payloadType in description.getPayloadTypes()) {
                    val id = payloadType.getId()
                    if (id.isNullOrEmpty()) {
                        throw IllegalArgumentException("Payload type is missing id")
                    }
                    if (!isInt(id)) {
                        throw IllegalArgumentException("Payload id is not numeric")
                    }
                    formatBuilder.add(payloadType.getIntId())
                    mediaAttributes.put("rtpmap", payloadType.toSdpAttribute())
                    val parameters = payloadType.getParameters()
                    if (parameters.size == 1) {
                        mediaAttributes.put(
                            "fmtp",
                            RtpDescription.Parameter.toSdpString(id, parameters[0]),
                        )
                    } else if (parameters.isNotEmpty()) {
                        mediaAttributes.put(
                            "fmtp",
                            RtpDescription.Parameter.toSdpString(id, parameters),
                        )
                    }
                    for (feedbackNegotiation in payloadType.getFeedbackNegotiations()) {
                        val type = feedbackNegotiation.getType()
                        val subtype = feedbackNegotiation.getSubType()
                        if (type.isNullOrEmpty()) {
                            throw IllegalArgumentException(
                                "a feedback for payload-type $id negotiation is missing type",
                            )
                        }
                        checkNoWhitespace(
                            type,
                            "feedback negotiation type must not contain whitespace",
                        )
                        if (subtype.isNullOrEmpty()) {
                            mediaAttributes.put("rtcp-fb", "$id $type")
                        } else {
                            checkNoWhitespace(
                                subtype,
                                "feedback negotiation subtype must not contain whitespace",
                            )
                            mediaAttributes.put("rtcp-fb", "$id $type $subtype")
                        }
                    }
                    for (feedbackNegotiationTrrInt in payloadType.feedbackNegotiationTrrInts()) {
                        mediaAttributes.put(
                            "rtcp-fb",
                            "$id trr-int ${feedbackNegotiationTrrInt.getValue()}",
                        )
                    }
                }

                for (feedbackNegotiation in description.getFeedbackNegotiations()) {
                    val type = feedbackNegotiation.getType()
                    val subtype = feedbackNegotiation.getSubType()
                    if (type.isNullOrEmpty()) {
                        throw IllegalArgumentException("a feedback negotiation is missing type")
                    }
                    checkNoWhitespace(type, "feedback negotiation type must not contain whitespace")
                    if (subtype.isNullOrEmpty()) {
                        mediaAttributes.put("rtcp-fb", "* $type")
                    } else {
                        checkNoWhitespace(
                            subtype,
                            "feedback negotiation subtype must not contain whitespace",
                        )
                        mediaAttributes.put("rtcp-fb", "* $type $subtype")
                    }
                }
                for (feedbackNegotiationTrrInt in description.feedbackNegotiationTrrInts()) {
                    mediaAttributes.put(
                        "rtcp-fb",
                        "* trr-int ${feedbackNegotiationTrrInt.getValue()}",
                    )
                }
                for (extension in description.getHeaderExtensions()) {
                    val id = extension.getId()
                    val uri = extension.getUri()
                    if (id.isNullOrEmpty()) {
                        throw IllegalArgumentException("A header extension is missing id")
                    }
                    checkNoWhitespace(id, "header extension id must not contain whitespace")
                    if (uri.isNullOrEmpty()) {
                        throw IllegalArgumentException("A header extension is missing uri")
                    }
                    checkNoWhitespace(uri, "feedback negotiation uri must not contain whitespace")
                    mediaAttributes.put("extmap", "$id $uri")
                }

                if (description.hasChild("extmap-allow-mixed", Namespace.JINGLE_RTP_HEADER_EXTENSIONS)) {
                    mediaAttributes.put("extmap-allow-mixed", "")
                }

                for (sourceGroup in description.getSourceGroups()) {
                    val semantics = sourceGroup.getSemantics()
                    val groups = sourceGroup.getSsrcs()
                    if (semantics.isNullOrEmpty()) {
                        throw IllegalArgumentException("A SSRC group is missing semantics attribute")
                    }
                    checkNoWhitespace(
                        semantics,
                        "source group semantics must not contain whitespace",
                    )
                    if (groups.isEmpty()) {
                        throw IllegalArgumentException("A SSRC group is missing SSRC ids")
                    }
                    for (source in groups) {
                        checkNoWhitespace(source, "Sources must not contain whitespace")
                    }
                    mediaAttributes.put(
                        "ssrc-group",
                        String.format("%s %s", semantics, Joiner.on(' ').join(groups)),
                    )
                }
                for (source in description.getSources()) {
                    for (parameter in source.getParameters()) {
                        val id = source.getSsrcId()
                        val parameterName = parameter.getParameterName()
                        val parameterValue = parameter.getParameterValue()
                        if (id.isNullOrEmpty()) {
                            throw IllegalArgumentException(
                                "A source specific media attribute is missing the id",
                            )
                        }
                        checkNoWhitespace(
                            id,
                            "A source specific media attributes must not contain whitespaces",
                        )
                        if (parameterName.isNullOrEmpty()) {
                            throw IllegalArgumentException(
                                "A source specific media attribute is missing its name",
                            )
                        }
                        if (parameterValue.isNullOrEmpty()) {
                            throw IllegalArgumentException(
                                "A source specific media attribute is missing its value",
                            )
                        }
                        checkNoWhitespace(
                            parameterName,
                            "A source specific media attribute name not not contain whitespace",
                        )
                        checkNoNewline(
                            parameterValue,
                            "A source specific media attribute value must not contain new lines",
                        )
                        mediaAttributes.put(
                            "ssrc",
                            "$id $parameterName:${parameterValue.trim()}",
                        )
                    }
                }

                mediaAttributes.put("mid", name)

                mediaAttributes.put(
                    descriptionTransport.senders.asMediaAttribute(isInitiatorContentMap),
                    "",
                )
                if (description.hasChild("rtcp-mux", Namespace.JINGLE_APPS_RTP) || group != null) {
                    mediaAttributes.put("rtcp-mux", "")
                }

                // random additional attributes
                mediaAttributes.put("rtcp", "9 IN IP4 0.0.0.0")

                val mediaBuilder = MediaBuilder()
                mediaBuilder.setMedia(description.getMedia().toString().lowercase(Locale.ROOT))
                mediaBuilder.setConnectionData(HARDCODED_CONNECTION)
                mediaBuilder.setPort(HARDCODED_MEDIA_PORT)
                mediaBuilder.setProtocol(HARDCODED_MEDIA_PROTOCOL)
                mediaBuilder.setAttributes(mediaAttributes)
                mediaBuilder.setFormats(formatBuilder.build())
                mediaListBuilder.add(mediaBuilder.createMedia())
            }
            sessionDescriptionBuilder.setVersion(0)
            sessionDescriptionBuilder.setName("-")
            sessionDescriptionBuilder.setMedia(mediaListBuilder.build())
            sessionDescriptionBuilder.setAttributes(attributeMap)

            return sessionDescriptionBuilder.createSessionDescription()
        }

        @JvmStatic
        fun checkNoWhitespace(input: String?, message: String): String {
            if (input == null) {
                throw NullPointerException()
            }
            if (CharMatcher.whitespace().matchesAnyOf(input)) {
                throw IllegalArgumentException(message)
            }
            return input
        }

        @JvmStatic
        fun checkNoNewline(input: String, message: String): String {
            if (CharMatcher.anyOf("\r\n").matchesAnyOf(message)) {
                throw IllegalArgumentException(message)
            }
            return input
        }

        @JvmStatic
        fun ignorantIntParser(input: String?): Int =
            try {
                Integer.parseInt(input)
            } catch (e: NumberFormatException) {
                0
            }

        @JvmStatic
        fun isInt(input: String?): Boolean {
            if (input == null) {
                return false
            }
            try {
                Integer.parseInt(input)
                return true
            } catch (e: NumberFormatException) {
                return false
            }
        }

        @JvmStatic
        fun parseAttribute(input: String): Pair<String, String> {
            val pair = input.split(":", limit = 2)
            return if (pair.size == 2) {
                Pair(pair[0], pair[1])
            } else {
                Pair(pair[0], "")
            }
        }
    }
}
