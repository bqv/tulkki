package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.ByteContent
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the signed pre-key signature. The (`signedPreKeySignature`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "signedPreKeySignature", namespace = Namespace.AXOLOTL)
class SignedPreKeySignature : Extension(SignedPreKeySignature::class.java), ByteContent
