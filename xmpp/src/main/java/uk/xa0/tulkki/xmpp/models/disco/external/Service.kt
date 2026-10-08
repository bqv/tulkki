package uk.xa0.tulkki.xmpp.models.disco.external

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0215 external service discovery: one `<service/>`. The package namespace moves onto the
 * class, because Kotlin cannot annotate a package; the derived (`service`, `urn:xmpp:extdisco:2`)
 * pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.EXTERNAL_SERVICE_DISCOVERY)
class Service : Extension(Service::class.java)
