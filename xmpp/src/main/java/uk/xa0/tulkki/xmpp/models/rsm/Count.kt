package uk.xa0.tulkki.xmpp.models.rsm

import com.google.common.primitives.Ints
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

@XmlElement(namespace = Namespace.RESULT_SET_MANAGEMENT)
class Count : Extension(Count::class.java) {

    fun getCount(): Int? {
        val content = getContent()
        if (content.isNullOrEmpty()) {
            return null
        }
        return Ints.tryParse(content)
    }
}
