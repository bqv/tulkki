package uk.xa0.tulkki.ui.interfaces

import uk.xa0.tulkki.data.model.Conversation

interface OnConversationArchived {

    fun onConversationArchived(conversation: Conversation?)
}
