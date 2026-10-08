package uk.xa0.tulkki.xmpp.models.register

import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0077 in-band registration: the `<instructions/>` child. It carries no `@XmlElement` in the Java
 * tree either, so it is not a registry entry and stays out of the extension index.
 */
class Instructions : Extension(Instructions::class.java)
