package uk.xa0.tulkki.xmpp.models

import com.google.common.base.CharMatcher
import com.google.common.base.Strings
import com.google.common.io.BaseEncoding
import uk.xa0.tulkki.xml.Element

/**
 * The base64 payload contract. Converted from the Java interface: `getContent`/`setContent` are
 * satisfied by [Element]'s members, which every implementor already inherits, and the two defaults
 * keep the Java's exact guards and Guava base64 codec (whitespace stripped, `canDecode` before
 * `decode`, `IllegalStateException` on either an empty or an invalid payload).
 */
interface ByteContent {

    fun getContent(): String

    fun asBytes(): ByteArray {
        val content = getContent()
        if (Strings.isNullOrEmpty(content)) {
            throw IllegalStateException("${javaClass.name} element is lacking content")
        }
        val contentCleaned = CharMatcher.whitespace().removeFrom(content)
        if (BaseEncoding.base64().canDecode(contentCleaned)) {
            return BaseEncoding.base64().decode(contentCleaned)
        }
        throw IllegalStateException("${javaClass.name} element contains invalid base64")
    }

    fun setContent(bytes: ByteArray) {
        setContent(BaseEncoding.base64().encode(bytes))
    }

    fun setContent(content: String?): Element
}
