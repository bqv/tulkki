package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The Jingle message `propose` element (XEP-0353).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decision taken rather than inherited:
 *
 * 1. **`upgrade` is `@JvmStatic`**: `JingleConnectionManager` and `JingleRtpConnection` call it
 *    statically. The descriptions still branch on the child's namespace, answering
 *    `FileTransferDescription`, `RtpDescription` or the generic description.
 */
class Propose private constructor() : Element("propose", Namespace.JINGLE_MESSAGE) {

    fun getDescriptions(): List<GenericDescription> {
        val builder = ImmutableList.builder<GenericDescription>()
        for (child in getChildren()) {
            if ("description" == child.getName()) {
                val namespace = child.getNamespace()
                if (Namespace.JINGLE_APPS_FILE_TRANSFER == namespace) {
                    builder.add(FileTransferDescription.upgrade(child))
                } else if (Namespace.JINGLE_APPS_RTP == namespace) {
                    builder.add(RtpDescription.upgrade(child))
                } else {
                    builder.add(GenericDescription.upgrade(child))
                }
            }
        }
        return builder.build()
    }

    companion object {
        @JvmStatic
        fun upgrade(element: Element): Propose {
            Preconditions.checkArgument("propose" == element.getName())
            Preconditions.checkArgument(Namespace.JINGLE_MESSAGE == element.getNamespace())
            val propose = Propose()
            propose.bindTo(element)
            return propose
        }
    }
}
