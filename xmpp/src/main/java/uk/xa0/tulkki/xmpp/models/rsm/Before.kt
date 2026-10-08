package uk.xa0.tulkki.xmpp.models.rsm

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

@XmlElement(namespace = Namespace.RESULT_SET_MANAGEMENT)
class Before : Extension(Before::class.java)
