package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.FileTransferDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.IbbTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.SocksByteStreamsTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.WebRTCDataChannelTransportInfo
import uk.xa0.tulkki.xmpp.jingle.transports.Transport
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.jingle.Jingle

/**
 * A Jingle file-transfer session's contents.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The four package-private members are public** — `transportInfo()`,
 *    `transportInfo(String, Candidate)`, and the constructor is `internal`. Kotlin has no
 *    package-private, and `internal` would mangle the JVM names `JingleFileTransferConnection`
 *    calls; the constructor is reached only from the class's own companion, which Kotlin permits
 *    even at `private`.
 * 2. **`of(Content)` hoists the transport with `?: throw NullPointerException()`** after Java's
 *    description check, preserving Java's order (a wrong description throws
 *    `UnsupportedApplicationException` first; a missing transport was Java's `getClass()` NPE).
 * 3. **`of(…, initialTransportInfo)` returns Kotlin's `mapOf`** for Java's `Map.of`, with the same
 *    single non-null entry.
 * 4. **`requireOnlyFileTransferDescription` keeps Java's declared non-null return** by throwing
 *    where Java would have returned the `description` its callers only discard; every other
 *    "requireOnly" reaches its value the way Java did.
 * 5. The two `Maps.transformValues` sites become Kotlin's `mapValues`; the results are still the
 *    immutable maps the constructor stores.
 */
class FileTransferContentMap internal constructor(
    group: Group?,
    contents: Map<String, DescriptionTransport<FileTransferDescription, GenericTransportInfo>>,
) : AbstractContentMap<FileTransferDescription, GenericTransportInfo>(group, contents) {

    fun requireOnlyFile(): FileTransferDescription.File {
        if (this.contents.size != 1) {
            throw IllegalStateException("Only one file at a time is supported")
        }
        val descriptionTransport = Iterables.getOnlyElement(this.contents.values)
        val description = descriptionTransport.description ?: throw NullPointerException()
        return description.getFile()
    }

    fun requireOnlyFileTransferDescription(): FileTransferDescription {
        if (this.contents.size != 1) {
            throw IllegalStateException("Only one file at a time is supported")
        }
        val descriptionTransport = Iterables.getOnlyElement(this.contents.values)
        return descriptionTransport.description ?: throw NullPointerException()
    }

    fun requireOnlyTransportInfo(): GenericTransportInfo {
        if (this.contents.size != 1) {
            throw IllegalStateException("We expect exactly one content with one transport info")
        }
        val descriptionTransport = Iterables.getOnlyElement(this.contents.values)
        return descriptionTransport.transport
    }

    fun withTransport(transportWrapper: Transport.TransportInfo): FileTransferContentMap {
        val transportInfo = transportWrapper.transportInfo
        return FileTransferContentMap(
            transportWrapper.group,
            ImmutableMap.copyOf(
                contents.mapValues { (_, content) ->
                    DescriptionTransport(content.senders, content.description, transportInfo)
                },
            ),
        )
    }

    fun candidateUsed(streamId: String, cid: String): FileTransferContentMap =
        FileTransferContentMap(
            null,
            ImmutableMap.copyOf(
                contents.mapValues { (_, content) ->
                    val transportInfo = SocksByteStreamsTransportInfo(streamId, emptyList())
                    val candidateUsed =
                        transportInfo.addChild(
                            "candidate-used",
                            Namespace.JINGLE_TRANSPORTS_S5B,
                        )
                    candidateUsed.setAttribute("cid", cid)
                    DescriptionTransport(content.senders, null, transportInfo)
                },
            ),
        )

    fun candidateError(streamId: String): FileTransferContentMap =
        FileTransferContentMap(
            null,
            ImmutableMap.copyOf(
                contents.mapValues { (_, content) ->
                    val transportInfo = SocksByteStreamsTransportInfo(streamId, emptyList())
                    transportInfo.addChild("candidate-error", Namespace.JINGLE_TRANSPORTS_S5B)
                    DescriptionTransport(content.senders, null, transportInfo)
                },
            ),
        )

    fun proxyActivated(streamId: String, cid: String): FileTransferContentMap =
        FileTransferContentMap(
            null,
            ImmutableMap.copyOf(
                contents.mapValues { (_, content) ->
                    val transportInfo = SocksByteStreamsTransportInfo(streamId, emptyList())
                    val candidateUsed =
                        transportInfo.addChild("activated", Namespace.JINGLE_TRANSPORTS_S5B)
                    candidateUsed.setAttribute("cid", cid)
                    DescriptionTransport(content.senders, null, transportInfo)
                },
            ),
        )

    fun transportInfo(): FileTransferContentMap =
        FileTransferContentMap(
            this.group,
            contents.mapValues { (_, dt) ->
                DescriptionTransport<FileTransferDescription, GenericTransportInfo>(
                    dt.senders,
                    null,
                    dt.transport,
                )
            },
        )

    fun transportInfo(
        contentName: String,
        candidate: IceUdpTransportInfo.Candidate,
    ): FileTransferContentMap {
        val descriptionTransport =
            contents[contentName]
                ?: throw IllegalArgumentException(
                    "Unable to find transport info for content name $contentName",
                )
        val transport = descriptionTransport.transport
        if (transport !is WebRTCDataChannelTransportInfo) {
            throw IllegalStateException("TransportInfo is not WebRTCDataChannel")
        }
        val newTransportInfo = transport.cloneWrapper()
        newTransportInfo.addCandidate(candidate)
        return FileTransferContentMap(
            null,
            ImmutableMap.of(
                contentName,
                DescriptionTransport<FileTransferDescription, GenericTransportInfo>(
                    descriptionTransport.senders,
                    null,
                    newTransportInfo,
                ),
            ),
        )
    }

    companion object {
        private val SUPPORTED_TRANSPORTS: List<Class<out GenericTransportInfo>> =
            listOf(
                SocksByteStreamsTransportInfo::class.java,
                IbbTransportInfo::class.java,
                WebRTCDataChannelTransportInfo::class.java,
            )

        @JvmStatic
        fun of(jinglePacket: Jingle): FileTransferContentMap {
            val contents = of(jinglePacket.getJingleContents())
            return FileTransferContentMap(jinglePacket.getGroup(), contents)
        }

        @JvmStatic
        fun of(content: Content): DescriptionTransport<FileTransferDescription, GenericTransportInfo> {
            val description = content.getDescription()
            val transportInfo = content.getTransport()
            val senders = content.getSenders()
            val fileTransferDescription: FileTransferDescription?
            if (description == null) {
                fileTransferDescription = null
            } else if (description is FileTransferDescription) {
                fileTransferDescription = description
            } else {
                throw AbstractContentMap.UnsupportedApplicationException(
                    "Content does not contain file transfer description",
                )
            }
            val nonNullTransportInfo = transportInfo ?: throw NullPointerException()
            if (!SUPPORTED_TRANSPORTS.contains(nonNullTransportInfo.javaClass)) {
                throw AbstractContentMap.UnsupportedTransportException(
                    "Content does not have supported transport",
                )
            }
            return DescriptionTransport(senders, fileTransferDescription, nonNullTransportInfo)
        }

        private fun of(
            contents: Map<String, Content>,
        ): Map<String, DescriptionTransport<FileTransferDescription, GenericTransportInfo>> =
            ImmutableMap.copyOf(contents.mapValues { (_, content) -> of(content) })

        @JvmStatic
        fun of(
            file: FileTransferDescription.File,
            initialTransportInfo: Transport.InitialTransportInfo,
        ): FileTransferContentMap {
            // TODO copy groups
            val transportInfo = initialTransportInfo.transportInfo
            return FileTransferContentMap(
                initialTransportInfo.group,
                mapOf(
                    initialTransportInfo.contentName to
                        DescriptionTransport(
                            Content.Senders.INITIATOR,
                            FileTransferDescription.of(file),
                            transportInfo,
                        ),
                ),
            )
        }
    }
}
