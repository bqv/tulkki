package uk.xa0.tulkki.translation

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the page DeepSeek publishes its prices on, so a price table can be refreshed instead of
 * released.
 *
 * <p>Kept apart from [DeepSeekClient] on purpose: that class talks to the API with the owner's
 * key, and this one fetches a public documentation page with no key at all. Nothing about the two
 * belongs in the same client - not the endpoint, not the authorization, and not the reason each can
 * fail - and keeping them apart is what makes "the price page was unreachable" impossible to confuse
 * with "DeepSeek refused the key".
 *
 * <p><strong>The User-Agent is load-bearing.</strong> The site in front of the documentation answers
 * `400` to some clients - a lowercase custom product name, or a bare token with no version,
 * drew one while `curl`, `python-urllib`, `okhttp` and this string are served - so
 * the value is not decoration to be tidied away. It names the app rather than lying about a browser,
 * and `tools/refresh-price-fixture` sends the same string so that a refusal can be reproduced
 * in a terminal where the status code is visible.
 *
 * <p>Nothing here decides anything. A body is handed to [TariffPage.parse], and a page that
 * cannot be read raises the same [DeepSeekClient.TranslationException] the API raises, whose
 * message goes on screen: an unreachable price page must leave the prices in force and say why rather
 * than emptying the Ledger.
 */
class TariffPageClient
@JvmOverloads
constructor(
        private val http: OkHttpClient,
        private val url: String = TariffPage.CNY_URL
) {

    /** The page's bytes as text. */
    @Throws(DeepSeekClient.TranslationException::class)
    fun fetch(): String {
        val request =
                Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html")
                        .get()
                        .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body
                val document = if (body == null) "" else body.string()
                if (!response.isSuccessful) {
                    // A wrong path does not 404 here: the site answers 200 with its shell for routes it
                    // does not have, which is why a fetch that succeeded still has to be parsed before it
                    // is believed - and why the status is reported rather than interpreted.
                    throw DeepSeekClient.TranslationException(
                            "the pricing page answered HTTP " + response.code,
                            response.code == 429 || response.code >= 500)
                }
                if (document.length > MAX_CHARS) {
                    throw DeepSeekClient.TranslationException(
                            "the pricing page sent more than " + (MAX_CHARS / 1024) + " KB", false)
                }
                return document
            }
        } catch (e: IOException) {
            throw DeepSeekClient.TranslationException(
                    "the pricing page is unreachable: " + e.message, true, e)
        }
    }

    companion object {

        /**
         * What this app calls itself to the documentation site. See the class comment - the value matters.
         */
        const val USER_AGENT = "Tulkki/1.0"

        /**
         * The largest page worth parsing. The real one is about 24 KB; anything far past this is not the
         * price table, and reading it into a phone's heap to find that out is the one cost worth refusing
         * before paying it.
         */
        private const val MAX_CHARS = 2 * 1024 * 1024
    }
}
