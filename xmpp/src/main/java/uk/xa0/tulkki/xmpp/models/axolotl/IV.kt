package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the initialization vector. The (`iv`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "iv", namespace = Namespace.AXOLOTL)
class IV : Extension(IV::class.java), ByteContent
