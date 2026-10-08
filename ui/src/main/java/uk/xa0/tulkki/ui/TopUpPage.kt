package uk.xa0.tulkki.ui

import java.util.Locale

/**
 * The top-up page: where it lives, who may render it, and what it is called when this app fetches it.
 *
 * <p>This is everything about the top-up screen that can be decided off a device, and it is a pure
 * function of the address on purpose - no Android type appears anywhere in this file. A WebView cannot
 * be exercised in the JVM: its rendering, its cookie jar and its dark mode are only observable on a
 * phone, and a payment cannot be driven at all. What is left is the decision, and `TopUpPageTest` pins
 * it.
 *
 * <p>The screen around this class is deliberately a browser and nothing more: the page is the
 * platform's, the session is the owner's, and nothing here reads, keeps or forwards a credential.
 */
object TopUpPage {

    /**
     * The platform's own top-up page. Signed out, the platform's single-page app sends this to its
     * sign-in screen by itself, so the owner signs in once inside the WebView and the WebView's own
     * cookie jar remembers it - which is why nothing here pre-fills or handles a login.
     */
    const val URL = "https://platform.deepseek.com/top_up"

    /**
     * Where an address goes.
     *
     * <p>There is no third answer on purpose: "nobody can open it" is not a destination, it is a
     * failure the screen has to say out loud, and the caller learns it by the system refusing.
     */
    enum class Destination {
        /** The WebView is the only thing that can - or may - render it, so it stays. */
        WEBVIEW,
        /** A payment app, the dialer, the mail client, the store: hand it to the system. */
        SYSTEM
    }

    /**
     * The schemes this screen keeps for itself even though they are not http(s).
     *
     * <p>None of them is a page another app could show: they are the WebView's own vocabulary
     * (about:, javascript:, data:, blob:) and the two local sources this screen has switched off
     * (file:, content:). Handing a file: address to another app would be a local-file leak, and handing
     * an unknown-but-harmless javascript: address out would only produce an error where the page meant
     * to run a script.
     */
    private val WEBVIEW_ONLY_SCHEMES =
            arrayOf("about", "javascript", "data", "blob", "file", "content", "chrome")

    /**
     * Does this address stay in the WebView, or does it belong to whatever app can open it?
     *
     * <p>http and https stay: navigation must not leave the screen. Everything else that names a
     * scheme goes out, and that includes the schemes named in the brief - `alipay:`, `weixin:`,
     * `intent:`, `market:`, `tel:`, `mailto:` - an app link with a scheme of its own, and a scheme
     * nobody has ever heard of. The last one is not a special case here: the caller tries the system
     * and reports it if nothing answers, which is the only honest thing to do with an address whose
     * handler is unknown.
     */
    @JvmStatic
    fun destinationOf(url: String?): Destination {
        val scheme = schemeOf(url) ?: return Destination.WEBVIEW
        if (scheme == "http" || scheme == "https") {
            return Destination.WEBVIEW
        }
        for (kept in WEBVIEW_ONLY_SCHEMES) {
            if (kept == scheme) {
                return Destination.WEBVIEW
            }
        }
        return Destination.SYSTEM
    }

    /**
     * The default WebView user agent, minus the two tokens that say "this is an embedded WebView":
     * the `; wv` marker in the platform comment and the `Version/4.0` product token.
     *
     * <p>Both are rewritten rather than replaced with a canned string, so what is sent is still the
     * truth about this device and this engine - only the "do not treat me as a browser" part is gone.
     * That matters here: the platform is behind bot protection, and an unknown share of it is aimed at
     * clients that announce themselves as WebViews. Nothing else is changed, and no header is invented.
     *
     * <p>`null` in means `null` out, which the caller reads as "keep the default", rather than an empty
     * user agent that would be sent as one.
     */
    @JvmStatic
    fun browserUserAgent(defaultUserAgent: String?): String? {
        if (defaultUserAgent == null) {
            return null
        }
        val withoutWebViewMarkers =
                defaultUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
        return collapseSpaces(withoutWebViewMarkers)
    }

    /**
     * The scheme of an absolute address, lowercased, or `null` when there is not one.
     *
     * <p>Written by hand rather than with a URL parser because the input is whatever a payment page
     * hands a WebView: relative references, deeply nested `intent://` addresses, and garbage. It never
     * throws and it never guesses - a colon that is not preceded by a valid scheme
     * (`example.com/a:b`) is not a scheme, and an address the WebView will resolve itself stays with
     * the WebView.
     */
    private fun schemeOf(url: String?): String? {
        if (url == null) {
            return null
        }
        val colon = url.indexOf(':')
        if (colon <= 0) {
            return null
        }
        val candidate = url.substring(0, colon)
        for (i in candidate.indices) {
            val c = candidate[i]
            val letter = (c in 'a'..'z') || (c in 'A'..'Z')
            val rest = (c in '0'..'9') || c == '+' || c == '-' || c == '.'
            if (!letter && !(i > 0 && rest)) {
                return null
            }
        }
        return candidate.lowercase(Locale.ROOT)
    }

    /** One space where there were several, and none at the ends: a user agent is a single line. */
    private fun collapseSpaces(value: String): String {
        val collapsed = StringBuilder(value.length)
        var previousWasSpace = false
        for (element in value) {
            if (element == ' ') {
                if (previousWasSpace) {
                    continue
                }
                previousWasSpace = true
            } else {
                previousWasSpace = false
            }
            collapsed.append(element)
        }
        val length = collapsed.length
        if (length > 0 && collapsed[length - 1] == ' ') {
            collapsed.setLength(length - 1)
        }
        return collapsed.toString()
    }
}
