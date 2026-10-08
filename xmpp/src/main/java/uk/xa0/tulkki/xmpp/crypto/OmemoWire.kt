package uk.xa0.tulkki.xmpp.crypto

import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid

/**
 * The wire half of an OMEMO message, in the vocabulary of the XMPP island.
 *
 * Ported from Java by the port-14 `xmppport` lane. Decision taken rather than inherited:
 *
 * 1. **`getIV` stays a function, not a property.** A Kotlin `val iv` would generate `getIv()`, and
 *    Java's method is `getIV()` with capital letters; the implementing `XmppAxolotlMessage` in
 *    `:crypto` and the island's callers both spell it `getIV`.
 * 2. **The two key getters are nullable.** A message built by the sending constructor always has
 *    both, but one parsed from an `<encrypted>` element has no `innerKey` at all and no `iv` when
 *    its header carries no `<iv/>` - the Java getters were platform-typed and hid that. The
 *    implementation's nullable return is the recorded decision, so the interface matches it.
 */
interface OmemoWire {

    /** The `<encrypted>` element this message is carried as. */
    fun toElement(): Element

    /** Whether the message carries a payload rather than only key transport. */
    fun hasPayload(): Boolean

    fun getInnerKey(): ByteArray?

    fun getIV(): ByteArray?

    fun getFrom(): Jid
}
