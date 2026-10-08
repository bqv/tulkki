package uk.xa0.tulkki.xmpp.models.pgp

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0027 / OpenPGP for XMPP: the `<x xmlns='jabber:x:encrypted'/>` element. Its namespace and
 * name are carried by the class already, because there is no `package-info` to inherit them from;
 * the (`x`, `jabber:x:encrypted`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(name = "x", namespace = Namespace.PGP_ENCRYPTED)
class Encrypted : Extension(Encrypted::class.java)
