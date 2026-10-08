package uk.xa0.tulkki.xmpp.models.sasl2

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.fast.Fast
import uk.xa0.tulkki.xmpp.models.fast.Mechanism

/** XEP-0388 SASL2: the `inline` request for a fast token. The (`inline`, `urn:xmpp:sasl:2`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.SASL_2)
class Inline : Extension(Inline::class.java) {

    fun getFast(): Fast? = getExtension(Fast::class.java)

    fun getFastMechanisms(): Collection<String> {
        val mechanisms = getFast()?.getExtensions(Mechanism::class.java) ?: emptyList()
        return mechanisms.map { it.getContent() }
    }
}
