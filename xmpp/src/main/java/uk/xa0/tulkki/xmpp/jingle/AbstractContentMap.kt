package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * The shared half of a Jingle content map: the group, the per-content description/transport pairs
 * and the packet they turn into.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The two public fields stay fields** (`@JvmField`); `contents` is a read-only `Map` because no
 *    caller mutates the field, and the descriptor is still `java.util.Map`.
 * 2. **`getSenders` and `getNames` answer `MutableSet`/`MutableList`**, the module's spelling for a
 *    Java `Set`/`List` return; both still hold the `ImmutableSet`/`ImmutableList` Java built.
 * 3. **`toJinglePacket` and `requireContentDescriptions` are public**, widened from Java's
 *    package-private: Kotlin has no package-private visibility, and `internal` would mangle the JVM
 *    name the Java subclasses call. That is the module's recorded cost, not a design choice.
 * 4. **The two nested exceptions keep package-private reach through `internal` constructors**;
 *    `Reason.of` only `is`-tests them, and no Java caller constructs one.
 * 5. **`toJinglePacket` reads the description through a local**, so Kotlin's smart cast replaces
 *    Java's null test without `!!`.
 */
abstract class AbstractContentMap<D : GenericDescription, T : GenericTransportInfo> protected constructor(
    @JvmField val group: Group?,
    @JvmField val contents: Map<String, DescriptionTransport<D, T>>,
) {

    class UnsupportedApplicationException internal constructor(message: String) :
        IllegalArgumentException(message)

    class UnsupportedTransportException internal constructor(message: String) :
        IllegalArgumentException(message)

    fun getSenders(): MutableSet<Content.Senders> =
        ImmutableSet.copyOf(contents.values.map { it.senders })

    fun getNames(): MutableList<String> = ImmutableList.copyOf(contents.keys)

    fun toJinglePacket(action: Jingle.Action, sessionId: String): Iq {
        val iq = Iq(Iq.Type.SET)
        val jinglePacket = iq.addExtension(Jingle(action, sessionId))
        for (entry in this.contents.entries) {
            val descriptionTransport = entry.value
            val content =
                Content(Content.Creator.INITIATOR, descriptionTransport.senders, entry.key)
            val description = descriptionTransport.description
            if (description != null) {
                content.addChild(description)
            }
            content.addChild(descriptionTransport.transport)
            jinglePacket.addJingleContent(content)
        }
        val group = this.group
        if (group != null) {
            jinglePacket.addGroup(group)
        }
        return iq
    }

    fun requireContentDescriptions() {
        if (this.contents.isEmpty()) {
            throw IllegalStateException("No contents available")
        }
        for (entry in this.contents.entries) {
            if (entry.value.description == null) {
                throw IllegalStateException(
                    String.format("%s is lacking content description", entry.key),
                )
            }
        }
    }
}
