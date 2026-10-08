package uk.xa0.tulkki.xmpp.models.axolotl

import com.google.common.primitives.Ints
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: a signed public pre-key. The (`signedPreKeyPublic`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "signedPreKeyPublic", namespace = Namespace.AXOLOTL)
class SignedPreKey : Extension(SignedPreKey::class.java), ECPublicKeyContent {

    fun getId(): Int = Ints.saturatedCast(getLongAttribute("signedPreKeyId"))

    fun setId(id: Int) {
        setAttribute("signedPreKeyId", id)
    }
}
