package uk.xa0.tulkki.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import android.widget.Toast
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.ShareText
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.xmpp.utils.XmppUri

object ShareUtil {

    private fun displayedBody(context: Context, message: Message): DisplayedBody {
        // Tulkki: the interpreter is the last input and the first thing read. Off, what is shared
        // and what is copied is the message as it arrived - the plain client's answer - and the
        // cover below can never be reached.
        val interpreter = TranslationSettings.get(context).interpreter()
        return DisplayedBody.of(
            message.getBody(),
            message.getTranslatedBody(),
            message.getTranslationState(),
            DisplayedBody.needsTranslation(
                message.getStatus(),
                message.getBody(),
                ConversationName.of(message.getConversation()),
                interpreter,
            ),
            interpreter,
        )
    }

    @JvmStatic
    fun share(activity: XmppActivity, message: Message) {
        val shareIntent = Intent()
        shareIntent.setAction(Intent.ACTION_SEND)
        if (message.isGeoUri()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, message.getRawBody())
            shareIntent.setType("text/plain")
        } else if (!message.isFileOrImage()) {
            // Tulkki: the shared text is what the interface shows - the translation when there is
            // one - and a message whose translation is missing stays covered here too, because the
            // clipboard and the share sheet are readable outside the app. ShareText is the one
            // decision, and it takes the body with the reply fallback already removed: the quote
            // inside a reply is somebody else's message in their language, and it must not ride out
            // of the app inside a body that needed no translation.
            val text = ShareText.of(displayedBody(activity, message), message.getBody(true))
            if (ShareText.refused(text)) {
                Toast.makeText(
                    activity,
                    R.string.tulkki_untranslated_reveal,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            shareIntent.putExtra(Intent.EXTRA_TEXT, text)
            shareIntent.setType("text/plain")
            shareIntent.putExtra(
                ConversationListActivity.EXTRA_AS_QUOTE,
                message.getStatus() == Message.STATUS_RECEIVED,
            )
        } else {
            val file = FileBackends.get().getFile(message)
            val fp = message.getFileParams()
            val name = fp.getName()
            val displayName = name ?: file.getName()
            try {
                shareIntent.putExtra(
                    Intent.EXTRA_STREAM,
                    FileBackend.getUriForFile(activity, file, displayName),
                )
            } catch (e: SecurityException) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.no_permission_to_access_x, file.getAbsolutePath()),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            shareIntent.setType(message.getMimeType() ?: "*/*")
        }
        try {
            activity.startActivity(
                Intent.createChooser(shareIntent, activity.getText(R.string.share_with)),
            )
        } catch (e: ActivityNotFoundException) {
            // This should happen only on faulty androids because normally chooser is always
            // available
            Toast.makeText(activity, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun copyToClipboard(activity: XmppActivity, message: Message) {
        // Tulkki: copy what the interface shows, through the one decision that also serves the share
        // sheet and the search result's quote (ShareText): a translated message must not copy the
        // original, a message that is still covered must not copy the raw text at all, and a body
        // that needed no translation is copied without the reply fallback it carries. The clipboard
        // is readable outside the app, so this is a display surface like any other.
        val text = ShareText.of(displayedBody(activity, message), message.getBody(true))
        if (ShareText.refused(text)) {
            Toast.makeText(activity, R.string.tulkki_untranslated_reveal, Toast.LENGTH_SHORT).show()
            return
        }
        // Tulkki: and the clip is marked sensitive, because API 33 and later render it in the
        // clipboard preview and keep it in the clipboard history - a second copy of the
        // conversation, outside the app, that the owner never asked for.
        if (activity.copyTextToClipboard(text, uk.xa0.tulkki.data.R.string.message, true)) {
            Toast.makeText(activity, uk.xa0.tulkki.data.R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun copyLinkToClipboard(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.getScheme() == "xmpp") {
            try {
                val jid = XmppUri(uri).getJid() ?: throw NullPointerException()
                if (copyTextToClipboard(context, jid.asBareJid().toString(), R.string.account_settings_jabber_id)) {
                    Toast.makeText(context, R.string.jabber_id_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
            }
        } else {
            if (copyTextToClipboard(context, url, R.string.web_address)) {
                Toast.makeText(context, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Tulkki: whether this row's body is covered - "needed a translation and did not get one". For a
     * caller that must not offer the body at all: the menu hides a link it cannot copy, and anything
     * that hands the body out of the app asks here first.
     */
    @JvmStatic
    fun concealed(context: Context, message: Message): Boolean {
        return displayedBody(context, message).isBlurred()
    }

    @JvmStatic
    fun copyLinkToClipboard(activity: XmppActivity, message: Message) {
        // Tulkki: a link is part of the body, so a covered body has no link to copy. Upstream read
        // the raw spannable here, which lifted a URL straight out of a contact's original - a piece
        // of text the interface refuses to show, and proof of what it contains. The menu row is
        // hidden for the same reason (ConversationFragment), and this refusal is the one that holds
        // if some other caller ever reaches the action.
        if (concealed(activity, message)) {
            Toast.makeText(activity, R.string.tulkki_untranslated_reveal, Toast.LENGTH_SHORT).show()
            return
        }
        val body = message.getSpannableBody()
        MyLinkify.addLinks(body, true)
        for (urlspan in body.getSpans(0, body.length - 1, URLSpan::class.java)) {
            copyLinkToClipboard(activity, urlspan.getURL())
            return
        }
    }

    @JvmStatic
    fun copyTextToClipboard(context: Context, text: String, labelResId: Int): Boolean {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val label = context.resources.getString(labelResId)
        if (clipboardManager != null) {
            val clipData = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clipData)
            return true
        }
        return false
    }

    @JvmStatic
    fun getLinkScheme(body: SpannableStringBuilder): String? {
        MyLinkify.addLinks(body, false)
        for (url in MyLinkify.extractLinks(body)) {
            val uri = Uri.parse(url)
            if (uri.getScheme() == "xmpp") {
                return uri.getScheme()
            } else {
                return "http"
            }
        }
        return null
    }
}
