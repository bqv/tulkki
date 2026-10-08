package uk.xa0.tulkki.xmpp.models.addressing

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0033 extended stanza addressing: the container. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.ADDRESSING)
class Addresses : Extension(Addresses::class.java)
