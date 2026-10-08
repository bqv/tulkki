package uk.xa0.tulkki.app.http

import java.util.regex.Pattern
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object AesGcmURL {

    /** This matches a 48 or 44 byte IV + KEY hex combo, like used in http/aesgcm upload anchors */
    @JvmField
    val IV_KEY: Pattern = Pattern.compile("([A-Fa-f0-9]{2}){48}|([A-Fa-f0-9]{2}){44}")

    const val PROTOCOL_NAME: String = "aesgcm"

    @JvmStatic
    fun toAesGcmUrl(url: HttpUrl): String =
        if (url.isHttps) {
            PROTOCOL_NAME + url.toString().substring(5)
        } else {
            url.toString()
        }

    @JvmStatic
    fun of(url: String): HttpUrl {
        val end = url.indexOf("://")
        if (end < 0) {
            throw IllegalArgumentException("Scheme not found")
        }
        val protocol = url.substring(0, end)
        return if (PROTOCOL_NAME == protocol) {
            ("https" + url.substring(PROTOCOL_NAME.length)).toHttpUrl()
        } else {
            url.toHttpUrl()
        }
    }
}
