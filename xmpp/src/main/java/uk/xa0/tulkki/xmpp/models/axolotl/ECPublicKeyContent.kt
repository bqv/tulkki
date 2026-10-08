package uk.xa0.tulkki.xmpp.models.axolotl

import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECPublicKey
import uk.xa0.tulkki.xmpp.models.ByteContent

/**
 * A base64 payload that decodes to a libsignal `ECPublicKey`. Converted from the Java interface;
 * `asBytes` and the `String` `setContent` come from [ByteContent], and the two defaults keep the
 * Java's `Curve.decodePoint(asBytes(), 0)` and its `IllegalStateException` on a bad point.
 */
interface ECPublicKeyContent : ByteContent {

    fun asECPublicKey(): ECPublicKey {
        try {
            return Curve.decodePoint(asBytes(), 0)
        } catch (e: InvalidKeyException) {
            throw IllegalStateException(
                "${javaClass.name} does not contain a valid ECPublicKey",
                e,
            )
        }
    }

    fun setContent(ecPublicKey: ECPublicKey) {
        setContent(ecPublicKey.serialize())
    }
}
