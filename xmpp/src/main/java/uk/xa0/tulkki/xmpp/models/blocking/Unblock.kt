package uk.xa0.tulkki.xmpp.models.blocking

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0191 blocking: an unblock command. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.BLOCKING)
class Unblock : Extension(Unblock::class.java)
