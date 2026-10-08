package uk.xa0.tulkki.xmpp.jingle

import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericTransportInfo

/**
 * A Jingle content's three parts: who may send, the description and the transport.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The three public fields stay fields** (`@JvmField`), because Java reads them directly
 *    throughout the three big connections, the transports and `:crypto`'s `AxolotlService`.
 * 2. **`description` is `D?`**, because Java constructed transport-info-only entries with a null
 *    description (`FileTransferContentMap.transportInfo()` and friends). `transport` stays non-null:
 *    every construction, in both Java and Kotlin, passes one.
 */
class DescriptionTransport<D : GenericDescription, T : GenericTransportInfo>(
    @JvmField val senders: Content.Senders,
    @JvmField val description: D?,
    @JvmField val transport: T,
)
