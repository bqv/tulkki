package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the symmetric ciphertext. The (`payload`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(namespace = Namespace.AXOLOTL)
class Payload : Extension(Payload::class.java), ByteContent
