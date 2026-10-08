package uk.xa0.tulkki.xmpp.jingle

import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableSet
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription

/**
 * The summary of a content-add/content-accept: a direction and one [Summary] per content.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The two public fields stay fields** (`@JvmField`), because Java reads
 *    `pending.direction`/`ca.direction` directly and the `summary` set is carried between the big
 *    connections.
 * 2. **`summary` is `MutableSet`**, the module's spelling for a Java `Set` field; the value is still
 *    the `ImmutableSet` Java built.
 * 3. **`summary(rtpContentMap)` hoists the description with `?: throw NullPointerException()`.**
 *    Java's `dt.description.getMedia()` would throw a message-less `NullPointerException` on a
 *    transport-info-only entry; Kotlin's `description` is a declared `D?`, so the same failure is
 *    spelled out at the same place.
 * 4. `Summary`'s constructor is `internal` where Java's was private: Kotlin will not let the outer
 *    class (here its companion) reach a nested class's private member — the `IceUdpTransportInfo`
 *    precedent. `equals`/`hashCode` keep Guava's `Objects.equal`/`Objects.hashCode`, the
 *    null-tolerant comparison Java used.
 */
class ContentAddition private constructor(
    @JvmField val direction: Direction,
    @JvmField val summary: MutableSet<Summary>,
) {

    fun media(): MutableSet<Media> =
        ImmutableSet.copyOf(Collections2.transform(summary) { s -> s.media })

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("direction", direction)
            .add("summary", summary)
            .toString()

    enum class Direction {
        OUTGOING,
        INCOMING,
    }

    class Summary internal constructor(
        @JvmField val name: String,
        @JvmField val media: Media,
        @JvmField val senders: Content.Senders,
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val summary = other as Summary
            return Objects.equal(name, summary.name) &&
                media == summary.media &&
                senders == summary.senders
        }

        override fun hashCode(): Int = Objects.hashCode(name, media, senders)

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("media", media)
                .add("senders", senders)
                .toString()
    }

    companion object {
        @JvmStatic
        fun of(direction: Direction, rtpContentMap: RtpContentMap): ContentAddition =
            ContentAddition(direction, summary(rtpContentMap))

        @JvmStatic
        fun summary(rtpContentMap: RtpContentMap): MutableSet<Summary> =
            ImmutableSet.copyOf(
                Collections2.transform(rtpContentMap.contents.entries) { e ->
                    val descriptionTransport = e.value
                    val description = descriptionTransport.description ?: throw NullPointerException()
                    Summary(e.key, description.getMedia(), descriptionTransport.senders)
                },
            )
    }
}
