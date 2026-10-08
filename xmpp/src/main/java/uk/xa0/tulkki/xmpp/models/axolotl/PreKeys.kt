package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the pre-key container. The (`prekeys`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "prekeys", namespace = Namespace.AXOLOTL)
class PreKeys : Extension(PreKeys::class.java)
