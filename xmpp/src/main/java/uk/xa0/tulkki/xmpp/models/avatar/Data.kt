package uk.xa0.tulkki.xmpp.models.avatar

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0084 user avatar: the image data. `ByteContent`'s members are inherited from [Extension]'s
 * `Element` superclass, exactly as the Java class inherited them. Registered in the compiled
 * extension index.
 */
@XmlElement(namespace = Namespace.AVATAR_DATA)
class Data : Extension(Data::class.java), ByteContent
