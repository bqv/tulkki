package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import uk.xa0.tulkki.xml.Element

/**
 * The `description` element of a Jingle application the tree does not model (XEP-0166).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The constructor is `internal`.** Java's was package-private, and only
 *    `FileTransferDescription` and `RtpDescription`, both in this package, call it. Kotlin has no
 *    package-private visibility; `internal` is the module-scoped equivalent this tree uses, and it
 *    keeps the constructor off the Kotlin-visible surface of other modules.
 * 2. **The class is `open`**, as Java's was: the two subclasses above extend it.
 * 3. **`upgrade` is `@JvmStatic`** because Java callers construct through it (`Content`, `Propose`,
 *    `FileTransferContentMap`), which needs a real static on the class.
 */
open class GenericDescription internal constructor(name: String, namespace: String?) :
    Element(name, namespace) {

    init {
        Preconditions.checkArgument("description" == name)
    }

    companion object {
        @JvmStatic
        fun upgrade(element: Element): GenericDescription {
            Preconditions.checkArgument("description" == element.getName())
            val description = GenericDescription("description", element.getNamespace())
            description.bindTo(element)
            return description
        }
    }
}
