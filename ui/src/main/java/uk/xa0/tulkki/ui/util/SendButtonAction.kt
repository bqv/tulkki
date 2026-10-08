package uk.xa0.tulkki.ui.util

import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_CHOOSE_IMAGE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_LOCATION
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_RECORD_VIDEO
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_RECORD_VOICE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_TAKE_PHOTO

enum class SendButtonAction {
    TEXT,
    TAKE_PHOTO,
    SEND_LOCATION,
    RECORD_VOICE,
    CANCEL,
    CHOOSE_PICTURE,
    RECORD_VIDEO;

    fun toChoice(): Int {
        return when (this) {
            TAKE_PHOTO -> ATTACHMENT_CHOICE_TAKE_PHOTO
            SEND_LOCATION -> ATTACHMENT_CHOICE_LOCATION
            RECORD_VOICE -> ATTACHMENT_CHOICE_RECORD_VOICE
            CHOOSE_PICTURE -> ATTACHMENT_CHOICE_CHOOSE_IMAGE
            RECORD_VIDEO -> ATTACHMENT_CHOICE_RECORD_VIDEO
            else -> 0
        }
    }

    companion object {
        @JvmStatic
        fun valueOfOrDefault(setting: String?): SendButtonAction {
            if (setting == null) {
                return TEXT
            }
            return try {
                valueOf(setting)
            } catch (e: IllegalArgumentException) {
                TEXT
            }
        }

        @JvmStatic
        fun of(attachmentChoice: Int): SendButtonAction {
            return when (attachmentChoice) {
                ATTACHMENT_CHOICE_LOCATION -> SEND_LOCATION
                ATTACHMENT_CHOICE_RECORD_VOICE -> RECORD_VOICE
                ATTACHMENT_CHOICE_RECORD_VIDEO -> RECORD_VIDEO
                ATTACHMENT_CHOICE_TAKE_PHOTO -> TAKE_PHOTO
                ATTACHMENT_CHOICE_CHOOSE_IMAGE -> CHOOSE_PICTURE
                else -> throw IllegalArgumentException("Not a known attachment choice")
            }
        }
    }
}
