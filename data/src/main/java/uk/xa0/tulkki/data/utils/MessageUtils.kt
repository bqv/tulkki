package uk.xa0.tulkki.data.utils

import com.google.common.base.Strings
import uk.xa0.tulkki.app.http.AesGcmURL
import uk.xa0.tulkki.app.http.URL
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.view.ViewPorts
import uk.xa0.tulkki.xmpp.Config
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

object MessageUtils {

    private val LTR_RTL = Pattern.compile("(\\u200E[^\\u200F]*\\u200F){3,}")

    @JvmField
    val EMPTY_STRING = ""

    @JvmStatic
    fun prepareQuote(message: Message): String = prepareQuote(message, Config.QUOTING_MAX_DEPTH, -1)

    @JvmStatic
    fun prepareQuote(message: Message, maxDepth: Int, maxLines: Int): String {
        val builder = StringBuilder()
        val body: String
        if (message.hasMeCommand()) {
            val nick: String?
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                val conversation = message.getConversation() ?: throw NullPointerException()
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    nick =
                        Strings.nullToEmpty(
                            (message.getCounterpart() ?: throw NullPointerException()).getResource(),
                        )
                } else {
                    nick = (message.getContact() ?: throw NullPointerException()).getPublicDisplayName()
                }
            } else {
                nick = DisplayNames.getMessageDisplayName(message)
            }
            body =
                nick + " " +
                    (message.getQuoteableBody() ?: throw NullPointerException())
                        .substring(Message.ME_COMMAND.length)
        } else {
            body = message.getQuoteableBody() ?: throw NullPointerException()
        }
        var lines = 0
        for (line in body.split("\n")) {
            if (line.isNotEmpty() && QuoteHelper.isNestedTooDeeply(line)) {
                continue
            }
            if (maxLines > 0 && maxLines <= lines) break
            if (builder.length != 0) {
                builder.append('\n')
            }
            builder.append(line.trim { it <= ' ' })
            lines++
        }
        return builder.toString()
    }

    @JvmStatic
    fun treatAsDownloadable(body: String?, oob: Boolean): Boolean {
        if (oob) return true

        val text = body ?: throw NullPointerException()
        val lines = text.split("\n")
        if (lines.isEmpty()) {
            return false
        }
        for (line in lines) {
            if (line.contains("\\s+")) {
                return false
            }
        }
        val uri: URI =
            try {
                URI(lines[0])
            } catch (e: URISyntaxException) {
                return false
            }
        if (!URL.WELL_KNOWN_SCHEMES.contains(uri.scheme)) {
            return false
        }
        val ref = uri.fragment
        val protocol = uri.scheme
        val encrypted = ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()
        val followedByDataUri = lines.size == 2 && lines[1].startsWith("data:")
        val validAesGcm =
            AesGcmURL.PROTOCOL_NAME.equals(protocol, ignoreCase = true) &&
                encrypted &&
                (lines.size == 1 || followedByDataUri)
        val validProtocol =
            "http".equals(protocol, ignoreCase = true) || "https".equals(protocol, ignoreCase = true)
        return if (ViewPorts.uiSettings().loadImageFromAnyLink()) {
            val path = uri.path
            val validOob =
                validProtocol &&
                    (oob ||
                        encrypted ||
                        (path != null &&
                            (path.endsWith(".xdc") ||
                                path.endsWith(".webp") ||
                                path.endsWith(".gif") ||
                                path.endsWith(".png") ||
                                path.endsWith(".jpg") ||
                                path.endsWith(".jpeg") ||
                                path.endsWith(".bmp")))) &&
                    lines.size == 1
            validAesGcm || validOob
        } else {
            val validOob = validProtocol && (oob || encrypted) && lines.size == 1
            validAesGcm || validOob
        }
    }

    @JvmStatic
    fun aesgcmDownloadable(body: String): String? {
        val lines = body.split("\n")
        if (lines.isEmpty()) {
            return null
        }
        for (line in lines) {
            if (line.contains("\\s+")) {
                return null
            }
        }
        val uri: URI =
            try {
                URI(lines[0])
            } catch (e: URISyntaxException) {
                return null
            }
        if (!URL.WELL_KNOWN_SCHEMES.contains(uri.scheme)) {
            return null
        }
        val ref = uri.fragment
        val protocol = uri.scheme
        val encrypted = ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()
        val followedByDataUri = lines.size == 2 && lines[1].startsWith("data:")
        val validAesGcm =
            AesGcmURL.PROTOCOL_NAME.equals(protocol, ignoreCase = true) &&
                encrypted &&
                (lines.size == 1 || followedByDataUri)
        return if (validAesGcm) lines[0] else null
    }

    @JvmStatic
    fun filterLtrRtl(body: String): String = LTR_RTL.matcher(body).replaceFirst(EMPTY_STRING)

    @JvmStatic
    fun unInitiatedButKnownSize(message: Message): Boolean {
        val fileParams = message.getFileParams()
        val oob = message.getOob()
        return message.getType() == Message.TYPE_TEXT &&
            message.getTransferable() == null &&
            message.isOOb() &&
            (fileParams.size != null ||
                (oob != null && oob.scheme != null && oob.scheme.equals("cid", ignoreCase = true))) &&
            fileParams.url != null
    }
}
