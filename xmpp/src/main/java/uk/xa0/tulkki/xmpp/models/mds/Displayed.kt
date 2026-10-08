package uk.xa0.tulkki.xmpp.models.mds

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0333 displayed markers, the `<displayed/>` of `urn:xmpp:mds:displayed:0`. The namespace is
 * named by the class rather than a package, because Kotlin cannot annotate a package; the
 * (`displayed`, `urn:xmpp:mds:displayed:0`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.MDS_DISPLAYED)
class Displayed : Extension(Displayed::class.java)
