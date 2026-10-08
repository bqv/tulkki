package uk.xa0.tulkki.xmpp.models

import com.google.common.base.CaseFormat
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace

/**
 * XEP-0300 use of cryptographic hash functions: the `hash` element. The annotation stays on the
 * class with its namespace, because Kotlin cannot annotate a package; the (`hash`,
 * `urn:xmpp:hashes:2`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.HASHES)
class Hash : Extension(Hash::class.java) {

    fun getAlgorithm(): Algorithm? = Algorithm.tryParse(getAttribute("algo"))

    fun setAlgorithm(algorithm: Algorithm) {
        setAttribute("algo", algorithm.toString())
    }

    enum class Algorithm {
        SHA_1,
        SHA_256,
        SHA_512;

        override fun toString(): String =
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name)

        companion object {
            @JvmStatic
            fun tryParse(name: String?): Algorithm? =
                    try {
                        Algorithm.valueOf(
                                CaseFormat.LOWER_HYPHEN.to(
                                        CaseFormat.UPPER_UNDERSCORE, name.orEmpty()))
                    } catch (e: IllegalArgumentException) {
                        null
                    }
        }
    }
}
