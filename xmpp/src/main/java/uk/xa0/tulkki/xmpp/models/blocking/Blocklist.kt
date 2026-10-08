package uk.xa0.tulkki.xmpp.models.blocking

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0191 blocking: the blocklist container. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.BLOCKING)
class Blocklist : Extension(Blocklist::class.java)
