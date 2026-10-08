package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import org.whispersystems.libsignal.ecc.ECPublicKey
import org.whispersystems.libsignal.state.PreKeyRecord
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * OMEMO (eu.siacs.conversations.axolotl): the key bundle. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the (`bundle`, `eu.siacs.conversations.axolotl`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.AXOLOTL)
class Bundle : Extension(Bundle::class.java) {

    fun getSignedPreKey(): SignedPreKey? = getExtension(SignedPreKey::class.java)

    fun getSignedPreKeySignature(): SignedPreKeySignature? =
            getExtension(SignedPreKeySignature::class.java)

    fun getIdentityKey(): IdentityKey? = getExtension(IdentityKey::class.java)

    fun getRandomPreKey(): PreKey? {
        val preKeys = getExtension(PreKeys::class.java)
        val preKeyList: Collection<PreKey> =
                preKeys?.getExtensions(PreKey::class.java) ?: emptyList()
        return preKeyList.elementAtOrNull((preKeyList.size * Math.random()).toInt())
    }

    fun setIdentityKey(ecPublicKey: ECPublicKey) {
        val identityKey = addExtension(IdentityKey())
        identityKey.setContent(ecPublicKey)
    }

    fun setSignedPreKey(id: Int, ecPublicKey: ECPublicKey, signature: ByteArray) {
        val signedPreKey = addExtension(SignedPreKey())
        signedPreKey.setId(id)
        signedPreKey.setContent(ecPublicKey)
        val signedPreKeySignature = addExtension(SignedPreKeySignature())
        signedPreKeySignature.setContent(signature)
    }

    fun addPreKeys(preKeyRecords: List<PreKeyRecord>) {
        val preKeys = addExtension(PreKeys())
        for (preKeyRecord in preKeyRecords) {
            val preKey = preKeys.addExtension(PreKey())
            preKey.setId(preKeyRecord.id)
            preKey.setContent(preKeyRecord.keyPair.publicKey)
        }
    }
}
