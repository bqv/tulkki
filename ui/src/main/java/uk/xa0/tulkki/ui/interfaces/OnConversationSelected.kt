package uk.xa0.tulkki.ui.interfaces

import uk.xa0.tulkki.data.model.Conversation

interface OnConversationSelected {
    fun onConversationSelected(conversation: Conversation)
}
