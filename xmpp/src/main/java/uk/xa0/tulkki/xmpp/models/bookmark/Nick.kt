package uk.xa0.tulkki.xmpp.models.bookmark

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/** XEP-0402 bookmarks 2: the per-conference nick. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.BOOKMARKS2)
class Nick : Extension(Nick::class.java)
