package uk.xa0.tulkki.xmpp.jingle.stanzas

import android.util.Pair
import com.google.common.base.Preconditions
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Sets
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.SessionDescription
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The Jingle RTP description (XEP-0167) and its payload types, parameters, sources and extensions.
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`FeedbackNegotiationTrrInt(int)` and `Parameter.ofSdpString` are `internal`.** Java had the
 *    constructor private and the factory package-private, and `RtpDescription.of` (the outer class)
 *    calls both; Kotlin will not let the outer class reach a nested class's private member. The
 *    other private constructors (`FeedbackNegotiation()`, `FeedbackNegotiationTrrInt()`,
 *    `RtpHeaderExtension()`, `PayloadType()`, `Parameter()`, `Source()`, `SourceGroup()`) are used
 *    only within their own class and stay private.
 * 2. **`PayloadType.addParameters` takes `List<Parameter>?`**, because
 *    `parameterMap.get(payloadType.getId())` can answer null and Java handed it straight to
 *    `addChildren`, which throws on null. The parameter was unannotated in Java.
 * 3. **The static factories are `@JvmStatic`**: `stub`, `of`, `upgrade`, `PayloadType.of`/
 *    `ofSdpString`, `Parameter.of`/`toSdpString` (both overloads, read by `SessionDescription:228,231`),
 *    `RtpHeaderExtension.upgrade`/`ofSdpString`, `Source.upgrade`, `Source.Parameter.upgrade`,
 *    `SourceGroup.upgrade`, and the two `fromChildren` helpers.
 * 4. **Kotlin's `String.split` answers a `List`**, so `parts.size`/`parts[i]` replace Java's arrays
 *    throughout `of`; `limit` is named because Kotlin's second positional parameter is `ignoreCase`.
 * 5. `SourceGroup.getSsrcs` reads `this.children`, the protected `Element` field Java read; `Element`
 *    is still Java, so the field is reachable and no getter is introduced.
 * 6. `Strings.isNullOrEmpty` becomes Kotlin's identical `isNullOrEmpty()`.
 * 7. **`payloadType.getId()` is hoisted with `?: throw NullPointerException()`** before the parameter
 *    and feedback lookups: Kotlin's `Map`/`Multimap` take a non-null key where Java handed the
 *    attribute string straight through, and `PayloadType.ofSdpString` always sets it.
 */
class RtpDescription : GenericDescription {

    private constructor(media: String) : super("description", Namespace.JINGLE_APPS_RTP) {
        this.setAttribute("media", media)
    }

    private constructor() : super("description", Namespace.JINGLE_APPS_RTP)

    fun getMedia(): Media = Media.of(this.getAttribute("media"))

    fun getPayloadTypes(): List<PayloadType> {
        val builder = ImmutableList.builder<PayloadType>()
        for (child in getChildren()) {
            if ("payload-type" == child.getName()) {
                builder.add(PayloadType.of(child))
            }
        }
        return builder.build()
    }

    fun getFeedbackNegotiations(): List<FeedbackNegotiation> =
        FeedbackNegotiation.fromChildren(this.getChildren())

    fun feedbackNegotiationTrrInts(): List<FeedbackNegotiationTrrInt> =
        FeedbackNegotiationTrrInt.fromChildren(this.getChildren())

    fun getHeaderExtensions(): List<RtpHeaderExtension> {
        val builder = ImmutableList.builder<RtpHeaderExtension>()
        for (child in getChildren()) {
            if ("rtp-hdrext" == child.getName()
                && Namespace.JINGLE_RTP_HEADER_EXTENSIONS == child.getNamespace()
            ) {
                builder.add(RtpHeaderExtension.upgrade(child))
            }
        }
        return builder.build()
    }

    fun getSources(): List<Source> {
        val builder = ImmutableList.builder<Source>()
        for (child in getChildren()) {
            if ("source" == child.getName()
                && Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES == child.getNamespace()
            ) {
                builder.add(Source.upgrade(child))
            }
        }
        return builder.build()
    }

    fun getSourceGroups(): List<SourceGroup> {
        val builder = ImmutableList.builder<SourceGroup>()
        for (child in getChildren()) {
            if ("ssrc-group" == child.getName()
                && Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES == child.getNamespace()
            ) {
                builder.add(SourceGroup.upgrade(child))
            }
        }
        return builder.build()
    }

    class FeedbackNegotiation : Element {

        private constructor() :
            super("rtcp-fb", Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION)

        constructor(type: String, subType: String?) :
            super("rtcp-fb", Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION) {
            this.setAttribute("type", type)
            if (subType != null) {
                this.setAttribute("subtype", subType)
            }
        }

        fun getType(): String? = this.getAttribute("type")

        fun getSubType(): String? = this.getAttribute("subtype")

        companion object {
            private fun upgrade(element: Element): FeedbackNegotiation {
                Preconditions.checkArgument("rtcp-fb" == element.getName())
                Preconditions.checkArgument(
                    Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION == element.getNamespace()
                )
                val feedback = FeedbackNegotiation()
                feedback.bindTo(element)
                return feedback
            }

            @JvmStatic
            fun fromChildren(children: List<Element>): List<FeedbackNegotiation> {
                val builder = ImmutableList.builder<FeedbackNegotiation>()
                for (child in children) {
                    if ("rtcp-fb" == child.getName()
                        && Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION == child.getNamespace()
                    ) {
                        builder.add(upgrade(child))
                    }
                }
                return builder.build()
            }
        }
    }

    class FeedbackNegotiationTrrInt : Element {

        internal constructor(value: Int) :
            super("rtcp-fb-trr-int", Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION) {
            this.setAttribute("value", value)
        }

        private constructor() :
            super("rtcp-fb-trr-int", Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION)

        fun getValue(): Int {
            val value = getAttribute("value")
            return Integer.parseInt(value)
        }

        companion object {
            private fun upgrade(element: Element): FeedbackNegotiationTrrInt {
                Preconditions.checkArgument("rtcp-fb-trr-int" == element.getName())
                Preconditions.checkArgument(
                    Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION == element.getNamespace()
                )
                val trr = FeedbackNegotiationTrrInt()
                trr.bindTo(element)
                return trr
            }

            @JvmStatic
            fun fromChildren(children: List<Element>): List<FeedbackNegotiationTrrInt> {
                val builder = ImmutableList.builder<FeedbackNegotiationTrrInt>()
                for (child in children) {
                    if ("rtcp-fb-trr-int" == child.getName()
                        && Namespace.JINGLE_RTP_FEEDBACK_NEGOTIATION == child.getNamespace()
                    ) {
                        builder.add(upgrade(child))
                    }
                }
                return builder.build()
            }
        }
    }

    // XEP-0294: Jingle RTP Header Extensions Negotiation
    // maps to `extmap:$id $uri`
    class RtpHeaderExtension : Element {

        private constructor() :
            super("rtp-hdrext", Namespace.JINGLE_RTP_HEADER_EXTENSIONS)

        constructor(id: String, uri: String) :
            super("rtp-hdrext", Namespace.JINGLE_RTP_HEADER_EXTENSIONS) {
            this.setAttribute("id", id)
            this.setAttribute("uri", uri)
        }

        fun getId(): String? = this.getAttribute("id")

        fun getUri(): String? = this.getAttribute("uri")

        companion object {
            @JvmStatic
            fun upgrade(element: Element): RtpHeaderExtension {
                Preconditions.checkArgument("rtp-hdrext" == element.getName())
                Preconditions.checkArgument(
                    Namespace.JINGLE_RTP_HEADER_EXTENSIONS == element.getNamespace()
                )
                val extension = RtpHeaderExtension()
                extension.bindTo(element)
                return extension
            }

            @JvmStatic
            fun ofSdpString(sdp: String): RtpHeaderExtension? {
                val pair = sdp.split(" ", limit = 2)
                if (pair.size == 2) {
                    val id = pair[0]
                    val uri = pair[1]
                    return RtpHeaderExtension(id, uri)
                } else {
                    return null
                }
            }
        }
    }

    // maps to `rtpmap:$id $name/$clockrate/$channels`
    class PayloadType : Element {

        private constructor() : super("payload-type", Namespace.JINGLE_APPS_RTP)

        constructor(id: String, name: String, clockRate: Int, channels: Int) :
            super("payload-type", Namespace.JINGLE_APPS_RTP) {
            this.setAttribute("id", id)
            this.setAttribute("name", name)
            this.setAttribute("clockrate", clockRate)
            if (channels != 1) {
                this.setAttribute("channels", channels)
            }
        }

        fun toSdpAttribute(): String {
            val channels = getChannels()
            val name = getPayloadTypeName()
            Preconditions.checkArgument(name != null, "Payload-type name must not be empty")
            SessionDescription.checkNoWhitespace(
                name,
                "payload-type name must not contain whitespaces",
            )
            return getId() +
                " " +
                name +
                "/" +
                getClockRate() +
                (if (channels == 1) "" else "/$channels")
        }

        fun getIntId(): Int {
            val id = this.getAttribute("id")
            return if (id == null) 0 else SessionDescription.ignorantIntParser(id)
        }

        fun getId(): String? = this.getAttribute("id")

        fun getPayloadTypeName(): String? = this.getAttribute("name")

        fun getClockRate(): Int {
            val clockRate = this.getAttribute("clockrate")
            if (clockRate == null) {
                return 0
            }
            try {
                return Integer.parseInt(clockRate)
            } catch (e: NumberFormatException) {
                return 0
            }
        }

        fun getChannels(): Int {
            val channels = this.getAttribute("channels")
            if (channels == null) {
                return 1 // The number of channels; if omitted, it MUST be assumed to contain one
                // channel
            }
            try {
                return Integer.parseInt(channels)
            } catch (e: NumberFormatException) {
                return 1
            }
        }

        fun getParameters(): List<Parameter> {
            val builder = ImmutableList.builder<Parameter>()
            for (child in getChildren()) {
                if ("parameter" == child.getName()) {
                    builder.add(Parameter.of(child))
                }
            }
            return builder.build()
        }

        fun getFeedbackNegotiations(): List<FeedbackNegotiation> =
            FeedbackNegotiation.fromChildren(this.getChildren())

        fun feedbackNegotiationTrrInts(): List<FeedbackNegotiationTrrInt> =
            FeedbackNegotiationTrrInt.fromChildren(this.getChildren())

        fun addParameters(parameters: List<Parameter>?) {
            addChildren(parameters)
        }

        companion object {
            @JvmStatic
            fun of(element: Element): PayloadType {
                Preconditions.checkArgument(
                    "payload-type" == element.getName(),
                    "element name must be called payload-type",
                )
                val payloadType = PayloadType()
                payloadType.bindTo(element)
                return payloadType
            }

            @JvmStatic
            fun ofSdpString(sdp: String): PayloadType? {
                val pair = sdp.split(" ", limit = 2)
                if (pair.size == 2) {
                    val id = pair[0]
                    val parts = pair[1].split("/")
                    if (parts.size >= 2) {
                        val name = parts[0]
                        val clockRate = SessionDescription.ignorantIntParser(parts[1])
                        val channels: Int =
                            if (parts.size >= 3) {
                                SessionDescription.ignorantIntParser(parts[2])
                            } else {
                                1
                            }
                        return PayloadType(id, name, clockRate, channels)
                    }
                }
                return null
            }
        }
    }

    // map to `fmtp $id key=value;key=value
    // where id is the id of the parent payload-type
    class Parameter : Element {

        private constructor() : super("parameter", Namespace.JINGLE_APPS_RTP)

        constructor(name: String, value: String) :
            super("parameter", Namespace.JINGLE_APPS_RTP) {
            this.setAttribute("name", name)
            this.setAttribute("value", value)
        }

        fun getParameterName(): String? = this.getAttribute("name")

        fun getParameterValue(): String? = this.getAttribute("value")

        companion object {
            @JvmStatic
            fun of(element: Element): Parameter {
                Preconditions.checkArgument(
                    "parameter" == element.getName(),
                    "element name must be called parameter",
                )
                val parameter = Parameter()
                parameter.bindTo(element)
                return parameter
            }

            @JvmStatic
            fun toSdpString(id: String, parameters: List<Parameter>): String {
                val stringBuilder = StringBuilder()
                stringBuilder.append(id).append(' ')
                for (i in parameters.indices) {
                    val p = parameters[i]
                    val name = p.getParameterName()
                    Preconditions.checkArgument(
                        name != null,
                        String.format("parameter for %s must have a name", id),
                    )
                    SessionDescription.checkNoWhitespace(
                        name,
                        String.format("parameter names for %s must not contain whitespaces", id),
                    )

                    val value = p.getParameterValue()
                    Preconditions.checkArgument(
                        value != null,
                        String.format("parameter for %s must have a value", id),
                    )
                    SessionDescription.checkNoWhitespace(
                        value,
                        String.format("parameter values for %s must not contain whitespaces", id),
                    )

                    stringBuilder.append(name).append('=').append(value)
                    if (i != parameters.size - 1) {
                        stringBuilder.append(';')
                    }
                }
                return stringBuilder.toString()
            }

            @JvmStatic
            fun toSdpString(id: String, parameter: Parameter): String {
                val name = parameter.getParameterName()
                val value = parameter.getParameterValue()
                Preconditions.checkArgument(
                    value != null,
                    String.format("parameter for %s must have a value", id),
                )
                SessionDescription.checkNoWhitespace(
                    value,
                    String.format("parameter values for %s must not contain whitespaces", id),
                )
                return if (name.isNullOrEmpty()) {
                    String.format("%s %s", id, value)
                } else {
                    String.format("%s %s=%s", id, name, value)
                }
            }

            internal fun ofSdpString(sdp: String): Pair<String, List<Parameter>>? {
                val pair = sdp.split(" ")
                if (pair.size == 2) {
                    val id = pair[0]
                    val builder = ImmutableList.builder<Parameter>()
                    for (parameter in pair[1].split(";")) {
                        val parts = parameter.split("=", limit = 2)
                        if (parts.size == 2) {
                            builder.add(Parameter(parts[0], parts[1]))
                        }
                    }
                    return Pair(id, builder.build())
                } else {
                    return null
                }
            }
        }
    }

    // XEP-0339: Source-Specific Media Attributes in Jingle
    // maps to `a=ssrc:<ssrc-id> <attribute>:<value>`
    class Source : Element {

        private constructor() :
            super("source", Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES)

        constructor(ssrcId: String, parameters: Collection<Parameter>) :
            super("source", Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES) {
            this.setAttribute("ssrc", ssrcId)
            for (parameter in parameters) {
                this.addChild(parameter)
            }
        }

        fun getSsrcId(): String? = this.getAttribute("ssrc")

        fun getParameters(): List<Parameter> {
            val builder = ImmutableList.builder<Parameter>()
            for (child in getChildren()) {
                if ("parameter" == child.getName()) {
                    builder.add(Parameter.upgrade(child))
                }
            }
            return builder.build()
        }

        class Parameter : Element {

            private constructor() : super("parameter")

            constructor(attribute: String, value: String?) : super("parameter") {
                this.setAttribute("name", attribute)
                if (value != null) {
                    this.setAttribute("value", value)
                }
            }

            fun getParameterName(): String? = this.getAttribute("name")

            fun getParameterValue(): String? = this.getAttribute("value")

            companion object {
                @JvmStatic
                fun upgrade(element: Element): Parameter {
                    Preconditions.checkArgument("parameter" == element.getName())
                    val parameter = Parameter()
                    parameter.bindTo(element)
                    return parameter
                }
            }
        }

        companion object {
            @JvmStatic
            fun upgrade(element: Element): Source {
                Preconditions.checkArgument("source" == element.getName())
                Preconditions.checkArgument(
                    Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES ==
                        element.getNamespace()
                )
                val source = Source()
                source.bindTo(element)
                return source
            }
        }
    }

    class SourceGroup : Element {

        constructor(semantics: String, ssrcs: List<String>) : this() {
            this.setAttribute("semantics", semantics)
            for (ssrc in ssrcs) {
                this.addChild("source").setAttribute("ssrc", ssrc)
            }
        }

        private constructor() :
            super("ssrc-group", Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES)

        fun getSemantics(): String? = this.getAttribute("semantics")

        fun getSsrcs(): List<String> {
            val builder = ImmutableList.builder<String>()
            for (child in this.children) {
                if ("source" == child.getName()) {
                    val ssrc = child.getAttribute("ssrc")
                    if (ssrc.isNullOrEmpty()) {
                        continue
                    }
                    builder.add(
                        SessionDescription.checkNoNewline(
                            ssrc,
                            "Source Specific media attributes can not contain newline",
                        )
                    )
                }
            }
            return builder.build()
        }

        companion object {
            @JvmStatic
            fun upgrade(element: Element): SourceGroup {
                Preconditions.checkArgument("ssrc-group" == element.getName())
                Preconditions.checkArgument(
                    Namespace.JINGLE_RTP_SOURCE_SPECIFIC_MEDIA_ATTRIBUTES ==
                        element.getNamespace()
                )
                val group = SourceGroup()
                group.bindTo(element)
                return group
            }
        }
    }

    companion object {
        @JvmStatic
        fun stub(media: Media): RtpDescription = RtpDescription(media.toString())

        @JvmStatic
        fun upgrade(element: Element): RtpDescription {
            Preconditions.checkArgument(
                "description" == element.getName(),
                "Name of provided element is not description",
            )
            Preconditions.checkArgument(
                Namespace.JINGLE_APPS_RTP == element.getNamespace(),
                "Element does not match the jingle rtp namespace",
            )
            val description = RtpDescription()
            description.bindTo(element)
            return description
        }

        @JvmStatic
        fun of(
            sessionDescription: SessionDescription,
            media: SessionDescription.Media,
        ): RtpDescription {
            val rtpDescription = RtpDescription(media.media)
            val parameterMap = HashMap<String, List<Parameter>>()
            val feedbackNegotiationMap = ArrayListMultimap.create<String, Element>()
            val sourceParameterMap = ArrayListMultimap.create<String, Source.Parameter>()
            val attributes =
                Sets.newHashSet(
                    Iterables.concat(
                        sessionDescription.attributes.keySet(),
                        media.attributes.keySet(),
                    )
                )
            for (rtcpFb in media.attributes.get("rtcp-fb")) {
                val parts = rtcpFb.split(" ")
                if (parts.size >= 2) {
                    val id = parts[0]
                    val type = parts[1]
                    val subType = if (parts.size >= 3) parts[2] else null
                    if ("trr-int" == type) {
                        if (subType != null) {
                            feedbackNegotiationMap.put(
                                id,
                                FeedbackNegotiationTrrInt(
                                    SessionDescription.ignorantIntParser(subType)
                                ),
                            )
                        }
                    } else {
                        feedbackNegotiationMap.put(id, FeedbackNegotiation(type, subType))
                    }
                }
            }
            for (ssrc in media.attributes.get("ssrc")) {
                val parts = ssrc.split(" ", limit = 2)
                if (parts.size == 2) {
                    val id = parts[0]
                    val subParts = parts[1].split(":", limit = 2)
                    val attribute = subParts[0]
                    val value = if (subParts.size == 2) subParts[1] else null
                    sourceParameterMap.put(id, Source.Parameter(attribute, value))
                }
            }
            for (fmtp in media.attributes.get("fmtp")) {
                val pair = Parameter.ofSdpString(fmtp)
                if (pair != null) {
                    parameterMap.put(pair.first, pair.second)
                }
            }
            rtpDescription.addChildren(feedbackNegotiationMap.get("*"))
            for (rtpmap in media.attributes.get("rtpmap")) {
                val payloadType = PayloadType.ofSdpString(rtpmap)
                if (payloadType != null) {
                    val payloadTypeId = payloadType.getId() ?: throw NullPointerException()
                    payloadType.addParameters(parameterMap.get(payloadTypeId))
                    payloadType.addChildren(feedbackNegotiationMap.get(payloadTypeId))
                    rtpDescription.addChild(payloadType)
                }
            }
            for (extmap in media.attributes.get("extmap")) {
                val extension = RtpHeaderExtension.ofSdpString(extmap)
                if (extension != null) {
                    rtpDescription.addChild(extension)
                }
            }
            if (attributes.contains("extmap-allow-mixed")) {
                rtpDescription.addChild(
                    "extmap-allow-mixed",
                    Namespace.JINGLE_RTP_HEADER_EXTENSIONS,
                )
            }
            for (ssrcGroup in media.attributes.get("ssrc-group")) {
                val parts = ssrcGroup.split(" ")
                if (parts.size >= 2) {
                    val builder = ImmutableList.builder<String>()
                    val semantics = parts[0]
                    for (i in 1 until parts.size) {
                        builder.add(parts[i])
                    }
                    rtpDescription.addChild(SourceGroup(semantics, builder.build()))
                }
            }
            for (source in sourceParameterMap.asMap().entries) {
                rtpDescription.addChild(Source(source.key, source.value))
            }
            if (media.attributes.containsKey("rtcp-mux")) {
                rtpDescription.addChild("rtcp-mux")
            }
            return rtpDescription
        }
    }
}
