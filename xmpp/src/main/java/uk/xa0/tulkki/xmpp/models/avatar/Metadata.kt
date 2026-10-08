package uk.xa0.tulkki.xmpp.models.avatar

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0084 user avatar: the metadata container. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.AVATAR_METADATA)
class Metadata : Extension(Metadata::class.java)
