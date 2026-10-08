package uk.xa0.tulkki.ui.text

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.Spanned
import android.text.style.URLSpan
import android.view.SoundEffectConstants
import android.view.View
import android.widget.Toast

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.ShowLocationActivity
import uk.xa0.tulkki.ui.utils.BrowserHelper

@SuppressLint("ParcelCreator")
class FixedURLSpan : URLSpan {

    @JvmField
    protected val account: Account?

    constructor(url: String) : this(url, null)

    constructor(url: String, account: Account?) : super(url) {
        this.account = account
    }

    override fun onClick(widget: View) {
        open(url, account, widget)
    }

    companion object {

        @JvmStatic
        fun fix(editable: Editable) {
            for (urlspan in editable.getSpans(0, editable.length - 1, URLSpan::class.java)) {
                val start = editable.getSpanStart(urlspan)
                val end = editable.getSpanEnd(urlspan)
                editable.removeSpan(urlspan)
                editable.setSpan(
                    FixedURLSpan(urlspan.url),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /**
         * A click's whole body, callable without a span.
         *
         * Pair 8b: `:data`'s command form builds its own links and clicks them straight away, so it
         * reaches this through `uk.xa0.tulkki.data.view.ViewPorts.LinkedText` instead of naming this
         * class. The span's own `onClick` goes through the same method, so there is one body and the
         * two paths cannot drift.
         */
        @JvmStatic
        fun open(url: String, account: Account?, widget: View) {
            val uri = Uri.parse(url)
            val context = widget.context
            val candidateToProcessDirectly = "xmpp" == uri.scheme ||
                ("https" == uri.scheme && "conversations.im" == uri.host &&
                    uri.pathSegments.size > 1 && listOf("j", "i").contains(uri.pathSegments[0]))
            if (candidateToProcessDirectly && context is ConversationListActivity) {
                if (context.onXmppUriClicked(uri)) {
                    widget.playSoundEffect(SoundEffectConstants.CLICK)
                    return
                }
            }

            if (("sms" == uri.scheme || "tel" == uri.scheme) && context is ConversationListActivity) {
                if (context.onTelUriClicked(uri, account)) {
                    widget.playSoundEffect(SoundEffectConstants.CLICK)
                    return
                }
            }

            if ("http" == uri.scheme || "https" == uri.scheme) {
                try {
                    BrowserHelper.launchUri(context, uri)
                    widget.playSoundEffect(SoundEffectConstants.CLICK)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        context, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT).show()
                }
                return
            }

            val intent = Intent(Intent.ACTION_VIEW, uri)
            if ("geo".equals(uri.scheme, ignoreCase = true)) {
                intent.setClass(context, ShowLocationActivity::class.java)
            } else {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            }
            try {
                context.startActivity(intent)
                widget.playSoundEffect(SoundEffectConstants.CLICK)
            } catch (e: ActivityNotFoundException) {
                if ("bitcoin" == uri.scheme || "bitcoincash" == uri.scheme ||
                    "monero" == uri.scheme || "taler" == uri.scheme) {
                    Toast.makeText(
                        context, "No compatible wallet app found", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
