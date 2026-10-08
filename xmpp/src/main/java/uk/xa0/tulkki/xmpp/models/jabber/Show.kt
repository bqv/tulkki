package uk.xa0.tulkki.xmpp.models.jabber

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * `jabber:client` presence show value. The package namespace moves onto the class, because Kotlin
 * cannot annotate a package; registered in the compiled extension index.
 */
@XmlElement(namespace = Namespace.JABBER_CLIENT)
class Show : Extension(Show::class.java)
