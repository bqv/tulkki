package uk.xa0.tulkki.xmpp.models.rsm

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

@XmlElement(namespace = Namespace.RESULT_SET_MANAGEMENT)
class Max : Extension(Max::class.java) {

    fun setMax(max: Int) {
        setContent(max.toString())
    }
}
