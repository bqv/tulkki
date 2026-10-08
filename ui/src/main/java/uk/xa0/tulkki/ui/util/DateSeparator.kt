package uk.xa0.tulkki.ui.util

import uk.xa0.tulkki.data.model.IndividualMessage
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.utils.UIHelper

object DateSeparator {

    @JvmStatic
    fun addAll(messages: MutableList<Message>) {
        var i = 0
        while (i < messages.size) {
            val current = messages[i]
            if (i == 0 || !UIHelper.sameDay(
                    messages[i - 1].getTimeSent(), current.getTimeSent())) {
                messages.add(i, IndividualMessage.createDateSeparator(current))
                i++
            }
            i++
        }
    }
}
