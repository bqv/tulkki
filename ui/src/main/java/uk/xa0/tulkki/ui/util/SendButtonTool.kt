package uk.xa0.tulkki.ui.util

import android.app.Activity
import android.preference.PreferenceManager
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.ui.Activities
import uk.xa0.tulkki.ui.ConversationRequests
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.utils.UIHelper

object SendButtonTool {

    @JvmStatic
    fun getAction(activity: Activity?, c: Conversation, text: String, subject: String): SendButtonAction {
        if (activity == null) {
            return SendButtonAction.TEXT
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val empty = text.isEmpty()
        val conference = c.getMode() == Conversational.MODE_MULTI
        val correcting = c.getCorrectingMessage()
        if (correcting != null &&
            (
                empty || (
                    text == correcting.getBody() &&
                        subject == correcting.getSubject() &&
                        (
                            c.getThread() === correcting.getThread() ||
                                (c.getThread() != null && c.getThread() == correcting.getThread())
                            )
                    )
                )
        ) {
            return SendButtonAction.CANCEL
        } else if (conference && !(c.getAccount() ?: throw NullPointerException()).httpUploadAvailable()) {
            if (empty && c.getNextCounterpart() != null) {
                return SendButtonAction.CANCEL
            } else {
                return SendButtonAction.TEXT
            }
        } else {
            if (empty && (c.getThread() == null || subject.length == 0)) {
                if (conference && c.getNextCounterpart() != null) {
                    return SendButtonAction.CANCEL
                } else {
                    var setting: String? = preferences.getString(
                        "quick_action",
                        activity.resources.getString(R.string.quick_action),
                    )
                    if (setting != "none" && UIHelper.receivedLocationQuestion(c.getLatestMessage())) {
                        return SendButtonAction.SEND_LOCATION
                    } else {
                        if (setting == "recent") {
                            setting = preferences.getString(
                                ConversationRequests.RECENTLY_USED_QUICK_ACTION,
                                SendButtonAction.TEXT.toString(),
                            )
                            return SendButtonAction.valueOfOrDefault(setting)
                        } else {
                            return SendButtonAction.valueOfOrDefault(setting)
                        }
                    }
                }
            } else {
                return SendButtonAction.TEXT
            }
        }
    }

    @JvmStatic
    @DrawableRes
    fun getSendButtonImageResource(action: SendButtonAction, canSend: Boolean): Int {
        return when (action) {
            SendButtonAction.TEXT -> if (canSend) R.drawable.ic_send_24dp else R.drawable.ic_attach_file_24dp
            SendButtonAction.TAKE_PHOTO -> R.drawable.ic_camera_alt_24dp
            SendButtonAction.SEND_LOCATION -> R.drawable.ic_location_pin_24dp
            SendButtonAction.CHOOSE_PICTURE -> R.drawable.ic_image_24dp
            SendButtonAction.RECORD_VIDEO -> R.drawable.ic_videocam_24dp
            SendButtonAction.RECORD_VOICE -> R.drawable.ic_mic_24dp
            SendButtonAction.CANCEL -> R.drawable.ic_cancel_24dp
        }
    }

    @JvmStatic
    @ColorInt
    fun getSendButtonColor(view: View, status: Presence.Status): Int {
        val nightMode = Activities.isNightMode(view.getContext())
        return when (status) {
            Presence.Status.OFFLINE -> MaterialColors.getColor(
                view,
                com.google.android.material.R.attr.colorOnSurface,
            )
            Presence.Status.ONLINE, Presence.Status.CHAT -> MaterialColors.harmonizeWithPrimary(
                view.getContext(),
                ContextCompat.getColor(
                    view.getContext(),
                    if (nightMode) R.color.green_300 else R.color.green_800,
                ),
            )
            Presence.Status.AWAY -> MaterialColors.harmonizeWithPrimary(
                view.getContext(),
                ContextCompat.getColor(
                    view.getContext(),
                    if (nightMode) R.color.amber_300 else R.color.amber_800,
                ),
            )
            Presence.Status.XA -> MaterialColors.harmonizeWithPrimary(
                view.getContext(),
                ContextCompat.getColor(
                    view.getContext(),
                    if (nightMode) R.color.orange_300 else R.color.orange_800,
                ),
            )
            Presence.Status.DND -> MaterialColors.harmonizeWithPrimary(
                view.getContext(),
                ContextCompat.getColor(
                    view.getContext(),
                    if (nightMode) R.color.red_300 else R.color.red_800,
                ),
            )
        }
    }
}
