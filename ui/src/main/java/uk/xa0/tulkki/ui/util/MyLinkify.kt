package uk.xa0.tulkki.ui.util

import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import java.util.Locale
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.utils.Patterns
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.text.FixedURLSpan
import uk.xa0.tulkki.ui.utils.GeoHelper
import uk.xa0.tulkki.ui.utils.StylingHelper
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.XmppUri

object MyLinkify {

    private val WEBURL_TRANSFORM_FILTER = Linkify.TransformFilter { _, url ->
        if (url == null) {
            null
        } else {
            val lcUrl = url.lowercase(Locale.US)
            if (lcUrl.startsWith("http://") || lcUrl.startsWith("https://")) {
                removeTrailingBracket(url)
            } else {
                "http://" + removeTrailingBracket(url)
            }
        }
    }

    private fun removeTrailingBracket(url: String): String {
        var numOpenBrackets = 0
        for (c in url.toCharArray()) {
            if (c == '(') {
                ++numOpenBrackets
            } else if (c == ')') {
                --numOpenBrackets
            }
        }
        return if (numOpenBrackets != 0 && url[url.length - 1] == ')') {
            url.substring(0, url.length - 1)
        } else {
            url
        }
    }

    private val WEBURL_MATCH_FILTER = Linkify.MatchFilter { cs, start, end ->
        if (start > 0 &&
            (
                cs[start - 1] == '@' ||
                    cs[start - 1] == '.' ||
                    cs.subSequence(Math.max(0, start - 3), start) == "://"
                )
        ) {
            false
        } else if (end < cs.length) {
            // Reject strings that were probably matched only because they contain a dot followed by
            // by some known TLD (see also comment for WORD_BOUNDARY in Patterns.java)
            !isAlphabetic(cs[end - 1].code) || !isAlphabetic(cs[end].code)
        } else {
            true
        }
    }

    private val XMPPURI_MATCH_FILTER = Linkify.MatchFilter { s, start, end ->
        XmppUri(s.subSequence(start, end).toString()).isValidJid()
    }

    private fun isAlphabetic(code: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return Character.isAlphabetic(code)
        }

        return when (Character.getType(code)) {
            Character.UPPERCASE_LETTER.toInt(),
            Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            Character.LETTER_NUMBER.toInt() -> true
            else -> false
        }
    }

    @JvmStatic
    fun addLinks(body: Editable, includeGeo: Boolean) {
        Linkify.addLinks(body, Patterns.XMPP_PATTERN, "xmpp", XMPPURI_MATCH_FILTER, null)
        Linkify.addLinks(body, Patterns.TEL_URI, "tel")
        Linkify.addLinks(body, Patterns.SMS_URI, "sms")
        Linkify.addLinks(body, Patterns.BITCOIN_URI, "bitcoin")
        Linkify.addLinks(body, Patterns.BITCOINCASH_URI, "bitcoincash")
        Linkify.addLinks(body, Patterns.ETHEREUM_URI, "ethereum")
        Linkify.addLinks(body, Patterns.MONERO_URI, "monero")
        Linkify.addLinks(body, Patterns.WOWNERO_URI, "wownero")
        Linkify.addLinks(body, Patterns.URI_TALER, "taler")
        Linkify.addLinks(body, Patterns.AUTOLINK_WEB_URL, "http", WEBURL_MATCH_FILTER, WEBURL_TRANSFORM_FILTER)
        if (includeGeo) {
            Linkify.addLinks(body, GeoHelper.GEO_URI, "geo")
        }
        FixedURLSpan.fix(body)
    }

    @JvmStatic
    fun addLinks(body: Editable, account: Account, context: Jid, service: XmppConnectionService) {
        addLinks(body, true)
        val roster = account.getRoster()
        urlspan@ for (urlspan in body.getSpans(0, body.length - 1, URLSpan::class.java)) {
            val start = body.getSpanStart(urlspan)
            if (start < 0) continue
            for (span in body.getSpans(start, start, Any::class.java)) {
                // instanceof TypefaceSpan is to block in XHTML code blocks. Probably a bit heavy-handed but works for now
                if (((body.getSpanFlags(span) and Spanned.SPAN_USER) shr Spanned.SPAN_USER_SHIFT) == StylingHelper.NOLINKIFY ||
                    span is TypefaceSpan
                ) {
                    body.removeSpan(urlspan)
                    continue@urlspan
                }
            }
            val uri = Uri.parse(urlspan.getURL())
            if (uri.getScheme() == "xmpp") {
                try {
                    if (!body.subSequence(body.getSpanStart(urlspan), body.getSpanEnd(urlspan)).toString().startsWith("xmpp:")) {
                        // Already customized
                        continue
                    }

                    val xmppUri = XmppUri(uri)
                    val jid = xmppUri.getJid() ?: throw NullPointerException()
                    var display: String? = xmppUri.toString()

                    if (service.getBooleanPreference("plain_text_links", R.bool.plain_text_links)) {
                        display = xmppUri.toString()
                    } else if (jid.asBareJid() == context && xmppUri.isAction("message") && xmppUri.getBody() != null) {
                        display = xmppUri.getBody()
                    } else if (jid.asBareJid() == context && xmppUri.parameterString().length > 0) {
                        display = xmppUri.parameterString()
                    } else {
                        val item = account.getBookmark(jid) ?: roster.getContact(jid)
                        display = item.getDisplayName() + xmppUri.displayParameterString()
                    }
                    body.replace(
                        body.getSpanStart(urlspan),
                        body.getSpanEnd(urlspan),
                        display ?: throw NullPointerException(),
                    )
                } catch (e: IllegalArgumentException) {
                    /* bad JID or span gone */
                } catch (e: IndexOutOfBoundsException) {
                    /* bad JID or span gone */
                }
            }
        }
    }

    @JvmStatic
    fun extractLinks(body: Editable): List<String> {
        addLinks(body, false)
        val urlWrappers = body.getSpans(0, body.length - 1, URLSpan::class.java)
            .mapNotNull { s -> s?.let { UrlWrapper(body.getSpanStart(it), it.getURL()) } }
        return urlWrappers.sortedBy { it.position }.map { it.url }
    }

    private class UrlWrapper(val position: Int, val url: String)
}
