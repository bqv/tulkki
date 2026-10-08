package uk.xa0.tulkki.xmpp.models.register

import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0077 in-band registration: the `<remove/>` child. It carries no `@XmlElement` in the Java tree
 * either, so it is not a registry entry and stays out of the extension index; `Register` builds it
 * directly.
 */
class Remove : Extension(Remove::class.java)
