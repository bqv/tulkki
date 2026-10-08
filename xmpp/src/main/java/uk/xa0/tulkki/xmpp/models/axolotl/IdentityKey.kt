package uk.xa0.tulkki.xmpp.models.axolotl

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** OMEMO: the identity key. The (`identityKey`, `eu.siacs.conversations.axolotl`) pair is in the compiled extension index. */
@XmlElement(name = "identityKey", namespace = Namespace.AXOLOTL)
class IdentityKey : Extension(IdentityKey::class.java), ECPublicKeyContent
