package uk.xa0.tulkki.xmpp.models.csi

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamFeature

/**
 * XEP-0352 client state indication: the `<csi/>` stream feature. The package namespace moves onto
 * the class, because Kotlin cannot annotate a package; the (`csi`, `urn:xmpp:csi:0`) pair comes from the
 * `@XmlElement` annotation.
 */
@XmlElement(name = "csi", namespace = Namespace.CSI)
class ClientStateIndication : StreamFeature(ClientStateIndication::class.java)
