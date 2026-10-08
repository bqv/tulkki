package uk.xa0.tulkki.xmpp.models.muc.user

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0045 `muc#user`: one status code. The package namespace moves onto the class, because
 * Kotlin cannot annotate a package; the name is derived, and the
 * (`status`, `http://jabber.org/protocol/muc#user`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.MUC_USER)
class Status : Extension(Status::class.java) {

    fun getCode(): Int? = getOptionalIntAttribute("code").orNull()
}
