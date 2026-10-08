package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the OpenPGP decryption queue, in island vocabulary. Converted from the Java.
 *
 * Every parameter is **non-null**: the only implementation, `:crypto`'s `PgpDecryptionService`, is
 * already Kotlin and declares `discardMessage(message: MessageRef)` and
 * `decryptMessage(message: MessageRef, notify: Boolean): Boolean` with no `?`, and its
 * `continueDecryption(resetPending: Boolean)` takes a Java `boolean`. The two `MessageRef` members
 * are the 3.7 pair 9, part 12 additions, and the `discardMessage` name (rather than an overload
 * `discard`) is kept exactly as the Java explains: an overload would make every call holding a
 * `MessageRef` ambiguous, because the ref implements both `OmemoMessage` and `MessageRef`.
 */
interface PgpDecryptionPort {

    fun continueDecryption(resetPending: Boolean)

    fun discardMessage(message: MessageRef)

    fun decryptMessage(message: MessageRef, notify: Boolean): Boolean
}
