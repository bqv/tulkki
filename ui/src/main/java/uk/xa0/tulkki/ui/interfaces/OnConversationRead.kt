package uk.xa0.tulkki.ui.interfaces

import uk.xa0.tulkki.data.model.Conversation

interface OnConversationRead {
    fun onConversationRead(conversation: Conversation, upToUuid: String)
}
