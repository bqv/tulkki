package uk.xa0.tulkki.xmpp.models.state

import uk.xa0.tulkki.xmpp.models.Extension

abstract class ChatStateNotification protected constructor(
    clazz: Class<out ChatStateNotification>,
) : Extension(clazz)
