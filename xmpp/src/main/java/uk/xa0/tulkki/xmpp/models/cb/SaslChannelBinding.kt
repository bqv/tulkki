package uk.xa0.tulkki.xmpp.models.cb

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.StreamFeature

/**
 * XEP-0440 SASL channel binding: the `<sasl-channel-binding/>` stream feature. The package
 * namespace moves onto the class, because Kotlin cannot annotate a package; the derived
 * (`sasl-channel-binding`, `urn:xmpp:sasl-cb:0`) pair comes from the `@XmlElement` annotation. The
 * Guava `Collections2.filter(transform(...), notNull())` view becomes `mapNotNull` with the same
 * declared `Collection<String>` signature.
 */
@XmlElement(namespace = Namespace.CHANNEL_BINDING)
class SaslChannelBinding : StreamFeature(SaslChannelBinding::class.java) {

    fun getChannelBindings(): Collection<ChannelBinding> =
        getExtensions(ChannelBinding::class.java)

    fun getChannelBindingTypes(): Collection<String> =
        getChannelBindings().mapNotNull { it.getType() }
}
