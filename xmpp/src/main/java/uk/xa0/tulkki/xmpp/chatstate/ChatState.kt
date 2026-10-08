package uk.xa0.tulkki.xmpp.chatstate

import uk.xa0.tulkki.xml.Element

enum class ChatState {
    ACTIVE,
    INACTIVE,
    GONE,
    COMPOSING,
    PAUSED;

    companion object {
        private const val NAMESPACE = "http://jabber.org/protocol/chatstates"

        @JvmStatic
        fun parse(element: Element): ChatState? =
            when {
                element.hasChild("active", NAMESPACE) -> ACTIVE
                element.hasChild("inactive", NAMESPACE) -> INACTIVE
                element.hasChild("composing", NAMESPACE) -> COMPOSING
                element.hasChild("gone", NAMESPACE) -> GONE
                element.hasChild("paused", NAMESPACE) -> PAUSED
                else -> null
            }

        @JvmStatic
        fun toElement(state: ChatState): Element {
            val element = Element(state.name.lowercase())
            element.setAttribute("xmlns", NAMESPACE)
            return element
        }
    }
}
