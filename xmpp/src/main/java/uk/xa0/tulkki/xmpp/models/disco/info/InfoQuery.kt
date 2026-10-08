package uk.xa0.tulkki.xmpp.models.disco.info

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0030 service discovery: the `<query/>`. Its name and namespace are carried by the class
 * already, because there is no `package-info` to inherit them from; the (`query`,
 * `http://jabber.org/protocol/disco#info`) pair comes from the `@XmlElement` annotation.
 *
 * The Guava `Iterables.any` tests become `any`, with the same null-safe `String.equals` comparison.
 */
@XmlElement(name = "query", namespace = Namespace.DISCO_INFO)
class InfoQuery : Extension(InfoQuery::class.java) {

    fun setNode(node: String) {
        setAttribute("node", node)
    }

    fun getNode(): String? = getAttribute("node")

    fun getFeatures(): Collection<Feature> = getExtensions(Feature::class.java)

    fun hasFeature(feature: String): Boolean = getFeatures().any { feature == it.getVar() }

    fun getIdentities(): Collection<Identity> = getExtensions(Identity::class.java)

    fun hasIdentityWithCategory(category: String): Boolean =
        getIdentities().any { category == it.getCategory() }
}
