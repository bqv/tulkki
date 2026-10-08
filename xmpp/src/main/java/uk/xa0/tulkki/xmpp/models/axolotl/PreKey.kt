package uk.xa0.tulkki.xmpp.models.axolotl

import com.google.common.primitives.Ints
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: a public pre-key. The (`preKeyPublic`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "preKeyPublic", namespace = Namespace.AXOLOTL)
class PreKey : Extension(PreKey::class.java), ECPublicKeyContent {

    fun getId(): Int = Ints.saturatedCast(getLongAttribute("preKeyId"))

    fun setId(id: Int) {
        setAttribute("preKeyId", id)
    }
}
