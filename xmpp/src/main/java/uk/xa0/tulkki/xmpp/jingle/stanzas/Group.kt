package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The Jingle `group` element (XEP-0338).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The two constructors stay two**: the private no-arg one and the public
 *    `(semantics, identificationTags)` one, which delegates to the private one exactly as Java's
 *    `this()` did.
 * 2. **`ofSdpString` answers `Group?`** because Java returned null when the SDP string split into
 *    fewer than two parts, and **`getSemantics` answers `String?`** for the same reason.
 * 3. **`ofSdpString` and `upgrade` are `@JvmStatic`**: `WebRTCDataChannelTransportInfo`,
 *    `SessionDescription`, `RtpContentMap` and `AbstractContentMap` call them as statics.
 * 4. Kotlin's `String.split` answers a `List`, so `parts.size`/`parts[i]` replace Java's array
 *    length and indexing; the split itself is the same plain `" "`.
 */
class Group : Element {

    private constructor() : super("group", Namespace.JINGLE_APPS_GROUPING)

    constructor(semantics: String, identificationTags: Collection<String>) : this() {
        this.setAttribute("semantics", semantics)
        for (tag in identificationTags) {
            this.addChild(Element("content").setAttribute("name", tag))
        }
    }

    fun getSemantics(): String? = this.getAttribute("semantics")

    fun getIdentificationTags(): List<String> {
        val builder = ImmutableList.builder<String>()
        for (child in getChildren()) {
            if ("content" == child.getName()) {
                val name = child.getAttribute("name")
                if (name != null) {
                    builder.add(name)
                }
            }
        }
        return builder.build()
    }

    companion object {
        @JvmStatic
        fun ofSdpString(input: String): Group? {
            val tagBuilder = ImmutableList.builder<String>()
            val parts = input.split(" ")
            if (parts.size >= 2) {
                val semantics = parts[0]
                for (i in 1 until parts.size) {
                    tagBuilder.add(parts[i])
                }
                return Group(semantics, tagBuilder.build())
            }
            return null
        }

        @JvmStatic
        fun upgrade(element: Element): Group {
            Preconditions.checkArgument("group" == element.getName())
            Preconditions.checkArgument(Namespace.JINGLE_APPS_GROUPING == element.getNamespace())
            val group = Group()
            group.bindTo(element)
            return group
        }
    }
}
