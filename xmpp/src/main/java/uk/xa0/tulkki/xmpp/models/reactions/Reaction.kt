package uk.xa0.tulkki.xmpp.models.reactions

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0444 message reactions: one `<reaction/>`. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the derived (`reaction`, `urn:xmpp:reactions:0`) pair comes from the
 * `@XmlElement` annotation. The one-argument constructor stays a public secondary
 * constructor, because `MessageGenerator` names it.
 */
@XmlElement(namespace = Namespace.REACTIONS)
class Reaction() : Extension(Reaction::class.java) {

    constructor(reaction: String) : this() {
        setContent(reaction)
    }
}
