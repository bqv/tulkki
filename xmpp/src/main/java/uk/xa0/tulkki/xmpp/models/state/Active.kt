package uk.xa0.tulkki.xmpp.models.state

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace

@XmlElement(namespace = Namespace.CHAT_STATES)
class Active : ChatStateNotification(Active::class.java)
