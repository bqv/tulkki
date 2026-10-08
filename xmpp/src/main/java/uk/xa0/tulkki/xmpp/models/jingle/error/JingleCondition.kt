package uk.xa0.tulkki.xmpp.models.jingle.error

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.error.Error

/**
 * XEP-0166 Jingle error conditions: the abstract base whose four nested classes are the registry
 * entries. The namespace is named by each nested class rather than a package, because Kotlin cannot
 * annotate a package; the four (`out-of-order`/`tie-break`/`unknown-session`/`unsupported-info`,
 * `urn:xmpp:jingle:errors:1`) pairs come from the `@XmlElement` annotation. The base's private
 * constructor stays private, as in the Java.
 */
abstract class JingleCondition private constructor(
    clazz: Class<out JingleCondition>,
) : Error.Extension(clazz) {

    @XmlElement(namespace = Namespace.JINGLE_ERRORS)
    class OutOfOrder : JingleCondition(OutOfOrder::class.java)

    @XmlElement(namespace = Namespace.JINGLE_ERRORS)
    class TieBreak : JingleCondition(TieBreak::class.java)

    @XmlElement(namespace = Namespace.JINGLE_ERRORS)
    class UnknownSession : JingleCondition(UnknownSession::class.java)

    @XmlElement(namespace = Namespace.JINGLE_ERRORS)
    class UnsupportedInfo : JingleCondition(UnsupportedInfo::class.java)
}
