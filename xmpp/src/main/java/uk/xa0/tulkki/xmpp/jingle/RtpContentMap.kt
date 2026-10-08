package uk.xa0.tulkki.xmpp.jingle

import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.collect.Maps
import com.google.common.collect.Sets
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription
import uk.xa0.tulkki.xmpp.models.jingle.Jingle

/** Java's `private static RtpContentMap.checkSenderModification`. */
private fun checkSenderModification(
    isInitiator: Boolean,
    current: Content.Senders,
    target: Content.Senders,
) {
    if (isInitiator) {
        // we were both sending and now other party only wants to receive
        if (current == Content.Senders.BOTH && target == Content.Senders.INITIATOR) {
            return
        }
        // only we were sending but now other party wants to send too
        if (current == Content.Senders.INITIATOR && target == Content.Senders.BOTH) {
            return
        }
    } else {
        // we were both sending and now other party only wants to receive
        if (current == Content.Senders.BOTH && target == Content.Senders.RESPONDER) {
            return
        }
        // only we were sending but now other party wants to send too
        if (current == Content.Senders.RESPONDER && target == Content.Senders.BOTH) {
            return
        }
    }
    throw IllegalArgumentException(
        String.format("Unsupported senders modification %s -> %s", current, target),
    )
}

/** Java's `private static RtpContentMap.merge`, kept file-private for the companion's callers. */
private fun merge(
    a: Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
    b: Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
): Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>> {
    val combined =
        LinkedHashMap<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
    combined.putAll(a)
    combined.putAll(b)
    return ImmutableMap.copyOf(combined)
}

/**
 * A Jingle RTP session's contents: the peer's or our own description/transport pairs plus the group.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The class is `open`**: `OmemoVerifiedRtpContentMap` extends it.
 * 2. **`transportInfo()`, `transportInfo(String, Candidate)`, `requireDTLSFingerprint()` and
 *    `withCandidates(...)` are public**, widened from Java's package-private because Kotlin has no
 *    package-private and `internal` would mangle the JVM names `JingleRtpConnection` calls.
 * 3. **`getMedia()`/`getCredentials()`/`getCombinedIceOptions()` answer `MutableSet`/`Set`**, the
 *    module's spelling for a Java `Set` return; the values are still the `ImmutableSet`s Java built.
 * 4. **`Diff` and `DTLS` keep Java's private constructors as `internal`.** Kotlin will not let the
 *    outer class (here its own methods) reach a nested class's private member — the
 *    `IceUdpTransportInfo` precedent.
 * 5. **`DTLS`'s three fields answer nullable.** Java declared them non-null but built them from
 *    `Fingerprint.getHash()`/`getSetup()`/`getContent()`, all of which answer null; the two used to
 *    feed `IceUdpTransportInfo.of` are hoisted with `?: throw NullPointerException()`, which is the
 *    failure the Kotlin parameter checks would have raised anyway.
 * 6. **`merge` and `checkSenderModification` are file-private top-level functions**, because the
 *    companion's `@JvmStatic` factories and the instance methods both reach them and Kotlin will not
 *    let the outer class read a companion's private member (the `IceUdpTransportInfo` precedent).
 * 7. **`Maps.transformValues`/`filterKeys`/`filterValues` become Kotlin's `mapValues`/`filterKeys`/
 *    `filterValues`**, which produce the same values over the immutable inputs; `getMedia`,
 *    `getSenders` and the rest keep the Guava immutable collection they return.
 * 8. `of(Content)`/`of(SessionDescription, boolean)` and the private `of(Map)`/`of(…, media)` stay
 *    the four Java overloads; nothing is collapsed into default arguments.
 */
open class RtpContentMap(
    group: Group?,
    contents: Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
) : AbstractContentMap<RtpDescription, IceUdpTransportInfo>(group, contents) {

    fun getMedia(): MutableSet<Media> =
        Sets.newHashSet(
            contents.values.map { input ->
                val rtpDescription = input.description
                if (rtpDescription == null) Media.UNKNOWN else rtpDescription.getMedia()
            },
        )

    fun requireDTLSFingerprint() {
        requireDTLSFingerprint(false)
    }

    fun requireDTLSFingerprint(requireActPass: Boolean) {
        if (this.contents.isEmpty()) {
            throw IllegalStateException("No contents available")
        }
        for (entry in this.contents.entries) {
            val transport = entry.value.transport
            val fingerprint = transport.getFingerprint()
            if (fingerprint == null ||
                fingerprint.getContent().isNullOrEmpty() ||
                fingerprint.getHash().isNullOrEmpty()
            ) {
                throw SecurityException(
                    String.format(
                        "Use of DTLS-SRTP (XEP-0320) is required for content %s",
                        entry.key,
                    ),
                )
            }
            val setup = fingerprint.getSetup()
            if (setup == null) {
                throw SecurityException(
                    String.format(
                        "Use of DTLS-SRTP (XEP-0320) is required for content %s but missing" +
                            " setup attribute",
                        entry.key,
                    ),
                )
            }
            if (requireActPass && setup != IceUdpTransportInfo.Setup.ACTPASS) {
                throw SecurityException(
                    "Initiator needs to offer ACTPASS as setup for DTLS-SRTP (XEP-0320)",
                )
            }
        }
    }

    fun transportInfo(
        contentName: String,
        candidate: IceUdpTransportInfo.Candidate,
    ): RtpContentMap {
        val descriptionTransport =
            contents[contentName]
                ?: throw IllegalArgumentException(
                    "Unable to find transport info for content name $contentName",
                )
        val transportInfo = descriptionTransport.transport
        val newTransportInfo = transportInfo.cloneWrapper()
        newTransportInfo.addChild(candidate)
        return RtpContentMap(
            null,
            ImmutableMap.of(
                contentName,
                DescriptionTransport<RtpDescription, IceUdpTransportInfo>(
                    descriptionTransport.senders,
                    null,
                    newTransportInfo,
                ),
            ),
        )
    }

    fun transportInfo(): RtpContentMap =
        RtpContentMap(
            null,
            contents.mapValues { (_, dt) ->
                DescriptionTransport<RtpDescription, IceUdpTransportInfo>(
                    dt.senders,
                    null,
                    dt.transport.cloneWrapper(),
                )
            },
        )

    fun withCandidates(
        candidates: ImmutableMultimap<String, IceUdpTransportInfo.Candidate>,
    ): RtpContentMap {
        val contentBuilder =
            ImmutableMap.builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
        for (entry in this.contents.entries) {
            val name = entry.key
            val descriptionTransport = entry.value
            val transport = descriptionTransport.transport
            contentBuilder.put(
                name,
                DescriptionTransport(
                    descriptionTransport.senders,
                    descriptionTransport.description,
                    transport.withCandidates(candidates.get(name)),
                ),
            )
        }
        return RtpContentMap(group, contentBuilder.build())
    }

    fun getDistinctCredentials(): IceUdpTransportInfo.Credentials {
        val allCredentials = getCredentials()
        val credentials = Iterables.getFirst(allCredentials, null)
        if (allCredentials.size == 1 && credentials != null) {
            if (credentials.password.isNullOrEmpty() || credentials.ufrag.isNullOrEmpty()) {
                throw IllegalStateException("Credentials are missing password or ufrag")
            }
            return credentials
        }
        throw IllegalStateException("Content map does not have distinct credentials")
    }

    private fun getCombinedIceOptions(): MutableSet<String> =
        ImmutableSet.copyOf(contents.values.flatMap { it.transport.getIceOptions() })

    fun getCredentials(): MutableSet<IceUdpTransportInfo.Credentials> {
        val credentials = ImmutableSet.copyOf(contents.values.map { it.transport.getCredentials() })
        if (credentials.isEmpty()) {
            throw IllegalStateException("Content map does not have any credentials")
        }
        return credentials
    }

    fun getCredentials(contentName: String): IceUdpTransportInfo.Credentials {
        val descriptionTransport =
            this.contents[contentName]
                ?: throw IllegalArgumentException(
                    String.format("Unable to find transport info for content name %s", contentName),
                )
        return descriptionTransport.transport.getCredentials()
    }

    fun getDtlsSetup(): IceUdpTransportInfo.Setup {
        val setups =
            ImmutableSet.copyOf(
                contents.values.map { dt ->
                    val fingerprint = dt.transport.getFingerprint() ?: throw NullPointerException()
                    fingerprint.getSetup() ?: throw NullPointerException()
                },
            )
        val setup = Iterables.getFirst(setups, null)
        if (setups.size == 1 && setup != null) {
            return setup
        }
        throw IllegalStateException("Content map doesn't have distinct DTLS setup")
    }

    private fun getDistinctDtls(): DTLS {
        val dtlsSet =
            ImmutableSet.copyOf(
                contents.values.map { dt ->
                    val fingerprint = dt.transport.getFingerprint() ?: throw NullPointerException()
                    DTLS(fingerprint.getHash(), fingerprint.getSetup(), fingerprint.getContent())
                },
            )
        val dtls = Iterables.getFirst(dtlsSet, null)
        if (dtlsSet.size == 1 && dtls != null) {
            return dtls
        }
        throw IllegalStateException("Content map doesn't have distinct DTLS setup")
    }

    fun emptyCandidates(): Boolean {
        var count = 0
        for (descriptionTransport in contents.values) {
            count += descriptionTransport.transport.getCandidates().size
        }
        return count == 0
    }

    fun hasFullTransportInfo(): Boolean = contents.values.any { !it.transport.isStub() }

    fun modifiedCredentials(
        credentials: IceUdpTransportInfo.Credentials,
        setup: IceUdpTransportInfo.Setup,
    ): RtpContentMap {
        val contentMapBuilder =
            ImmutableMap.builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
        for (content in contents.entries) {
            val descriptionTransport = content.value
            val rtpDescription = descriptionTransport.description
            val transportInfo = descriptionTransport.transport
            val modifiedTransportInfo = transportInfo.modifyCredentials(credentials, setup)
            contentMapBuilder.put(
                content.key,
                DescriptionTransport(descriptionTransport.senders, rtpDescription, modifiedTransportInfo),
            )
        }
        return RtpContentMap(this.group, contentMapBuilder.build())
    }

    fun modifiedSenders(senders: Content.Senders): RtpContentMap =
        RtpContentMap(
            this.group,
            contents.mapValues { (_, dt) ->
                DescriptionTransport(senders, dt.description, dt.transport)
            },
        )

    fun modifiedSendersChecked(
        isInitiator: Boolean,
        modification: Map<String, Content.Senders>,
    ): RtpContentMap {
        val contentMapBuilder =
            ImmutableMap.builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
        for (content in contents.entries) {
            val id = content.key
            val descriptionTransport = content.value
            val currentSenders = descriptionTransport.senders
            val targetSenders = modification[id]
            if (targetSenders == null || currentSenders == targetSenders) {
                contentMapBuilder.put(id, descriptionTransport)
            } else {
                checkSenderModification(isInitiator, currentSenders, targetSenders)
                contentMapBuilder.put(
                    id,
                    DescriptionTransport(
                        targetSenders,
                        descriptionTransport.description,
                        descriptionTransport.transport,
                    ),
                )
            }
        }
        return RtpContentMap(this.group, contentMapBuilder.build())
    }

    fun toContentModification(modifications: Collection<String>): RtpContentMap =
        RtpContentMap(this.group, Maps.filterKeys(this.contents) { it in modifications })

    fun toStub(): RtpContentMap =
        RtpContentMap(
            null,
            contents.mapValues { (_, dt) ->
                val description = dt.description ?: throw NullPointerException()
                DescriptionTransport(
                    dt.senders,
                    RtpDescription.stub(description.getMedia()),
                    IceUdpTransportInfo.STUB,
                )
            },
        )

    fun activeContents(): RtpContentMap =
        RtpContentMap(group, contents.filterValues { it.senders != Content.Senders.NONE })

    fun diff(rtpContentMap: RtpContentMap): Diff {
        val existingContentIds = this.contents.keys
        val newContentIds = rtpContentMap.contents.keys
        return Diff(
            ImmutableSet.copyOf(Sets.difference(newContentIds, existingContentIds)),
            ImmutableSet.copyOf(Sets.difference(existingContentIds, newContentIds)),
        )
    }

    fun iceRestart(rtpContentMap: RtpContentMap): Boolean =
        try {
            getDistinctCredentials() != rtpContentMap.getDistinctCredentials()
        } catch (e: IllegalStateException) {
            false
        }

    fun addContent(
        modification: RtpContentMap,
        setupOverwrite: IceUdpTransportInfo.Setup,
    ): RtpContentMap {
        val combined = merge(contents, modification.contents)
        val combinedFixedTransport =
            combined.mapValues { (_, dt) ->
                val iceUdpTransportInfo: IceUdpTransportInfo
                if (dt.transport.isStub()) {
                    val credentials = getDistinctCredentials()
                    val iceOptions = getCombinedIceOptions()
                    val dtls = getDistinctDtls()
                    iceUdpTransportInfo =
                        IceUdpTransportInfo.of(
                            credentials,
                            iceOptions,
                            setupOverwrite,
                            dtls.hash ?: throw NullPointerException(),
                            dtls.fingerprint ?: throw NullPointerException(),
                        )
                } else {
                    val fingerprint = dt.transport.getFingerprint() ?: throw NullPointerException()
                    val setup = fingerprint.getSetup()
                    iceUdpTransportInfo =
                        IceUdpTransportInfo.of(
                            dt.transport.getCredentials(),
                            dt.transport.getIceOptions(),
                            (if (setup == IceUdpTransportInfo.Setup.ACTPASS) {
                                setupOverwrite
                            } else {
                                setup
                            }) ?: throw NullPointerException(),
                            fingerprint.getHash() ?: throw NullPointerException(),
                            fingerprint.getContent() ?: throw NullPointerException(),
                        )
                }
                DescriptionTransport(dt.senders, dt.description, iceUdpTransportInfo)
            }
        return RtpContentMap(modification.group, ImmutableMap.copyOf(combinedFixedTransport))
    }

    class Diff internal constructor(
        @JvmField val added: MutableSet<String>,
        @JvmField val removed: MutableSet<String>,
    ) {
        fun hasModifications(): Boolean = !this.added.isEmpty() || !this.removed.isEmpty()

        fun isEmpty(): Boolean = this.added.isEmpty() && this.removed.isEmpty()

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("added", added)
                .add("removed", removed)
                .toString()
    }

    class DTLS internal constructor(
        @JvmField val hash: String?,
        @JvmField val setup: IceUdpTransportInfo.Setup?,
        @JvmField val fingerprint: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val dtls = other as DTLS
            return Objects.equal(hash, dtls.hash) &&
                setup == dtls.setup &&
                Objects.equal(fingerprint, dtls.fingerprint)
        }

        override fun hashCode(): Int = Objects.hashCode(hash, setup, fingerprint)
    }

    companion object {
        @JvmStatic
        fun of(jinglePacket: Jingle): RtpContentMap {
            val contents = of(jinglePacket.getJingleContents())
            return if (isOmemoVerified(contents)) {
                OmemoVerifiedRtpContentMap(jinglePacket.getGroup(), contents)
            } else {
                RtpContentMap(jinglePacket.getGroup(), contents)
            }
        }

        private fun isOmemoVerified(
            contents: Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
        ): Boolean {
            val values = contents.values
            if (values.isEmpty()) {
                return false
            }
            for (descriptionTransport in values) {
                if (descriptionTransport.transport is OmemoVerifiedIceUdpTransportInfo) {
                    continue
                }
                return false
            }
            return true
        }

        @JvmStatic
        fun of(sessionDescription: SessionDescription, isInitiator: Boolean): RtpContentMap {
            val contentMapBuilder =
                ImmutableMap.builder<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>()
            for (media in sessionDescription.media) {
                val id =
                    Iterables.getFirst(media.attributes.get("mid"), null)
                        ?: throw NullPointerException("media has no mid")
                contentMapBuilder.put(id, of(sessionDescription, isInitiator, media))
            }
            val groupAttribute = Iterables.getFirst(sessionDescription.attributes.get("group"), null)
            val group = if (groupAttribute == null) null else Group.ofSdpString(groupAttribute)
            return RtpContentMap(group, contentMapBuilder.build())
        }

        @JvmStatic
        fun of(content: Content): DescriptionTransport<RtpDescription, IceUdpTransportInfo> {
            val description = content.getDescription()
            val transportInfo = content.getTransport()
            val senders = content.getSenders()
            val rtpDescription: RtpDescription?
            val iceUdpTransportInfo: IceUdpTransportInfo
            if (description == null) {
                rtpDescription = null
            } else if (description is RtpDescription) {
                rtpDescription = description
            } else {
                throw AbstractContentMap.UnsupportedApplicationException(
                    "Content does not contain rtp description",
                )
            }
            if (transportInfo is IceUdpTransportInfo) {
                iceUdpTransportInfo = transportInfo
            } else {
                throw AbstractContentMap.UnsupportedTransportException(
                    "Content does not contain ICE-UDP transport",
                )
            }
            return DescriptionTransport(
                senders,
                rtpDescription,
                OmemoVerifiedIceUdpTransportInfo.upgrade(iceUdpTransportInfo),
            )
        }

        private fun of(
            sessionDescription: SessionDescription,
            isInitiator: Boolean,
            media: SessionDescription.Media,
        ): DescriptionTransport<RtpDescription, IceUdpTransportInfo> {
            val senders = Content.Senders.of(media, isInitiator)
            val rtpDescription = RtpDescription.of(sessionDescription, media)
            val transportInfo = IceUdpTransportInfo.of(sessionDescription, media)
            return DescriptionTransport(senders, rtpDescription, transportInfo)
        }

        private fun of(
            contents: Map<String, Content>,
        ): Map<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>> =
            ImmutableMap.copyOf(contents.mapValues { (_, content) -> of(content) })
    }
}
