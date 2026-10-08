package uk.xa0.tulkki.data.utils

import uk.xa0.tulkki.data.model.Conversation

object QuickLoader {

    private var conversationUuid: String? = null
    private val LOCK = Any()

    @JvmStatic
    fun set(uuid: String?) {
        synchronized(LOCK) {
            conversationUuid = uuid
        }
    }

    @JvmStatic
    fun get(haystack: List<Conversation>): Conversation? {
        synchronized(LOCK) {
            val uuid = conversationUuid ?: return null
            for (conversation in haystack) {
                if (conversation.getUuid() == uuid) {
                    return conversation
                }
            }
        }
        return null
    }
}
