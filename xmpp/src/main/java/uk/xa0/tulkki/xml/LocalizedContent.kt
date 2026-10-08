package uk.xa0.tulkki.xml

import com.google.common.collect.Iterables
import java.util.HashMap
import java.util.Locale

/**
 * Tulkki: the `<body xml:lang=...>` value the stanza parsers read.
 *
 * Ported from `LocalizedContent.java`. The three
 * public fields stay public `@JvmField`s because Java reads them as fields
 * (`MessageParser.body.content`, `.count`, `.language`, `XmppConnectionService.markMessage`), and
 * `STREAM_LANGUAGE` stays a `const val` and `get` an `@JvmStatic` because Java reads
 * `LocalizedContent.STREAM_LANGUAGE` and calls `LocalizedContent.get(...)`.
 *
 * The nullability is read off the behaviour: `language` is nullable because the map key comes from
 * `xml:lang`, which may be absent, and the `contents.get(null)` fallback hands that null key
 * straight to the constructor; `content` is non-null because the only store is behind a null check
 * and every constructor caller passes a real string.
 */
class LocalizedContent(
    @JvmField val content: String,
    @JvmField val language: String?,
    @JvmField val count: Int,
) {

    companion object {

        const val STREAM_LANGUAGE = "en"

        @JvmStatic
        fun get(element: Element, name: String): LocalizedContent? {
            val contents = HashMap<String?, String>()
            val parentLanguage = element.getAttribute("xml:lang")
            for (child in element.getChildren()) {
                if (name == child.getName()) {
                    val namespace = child.getNamespace()
                    val childLanguage = child.getAttribute("xml:lang")
                    val lang = childLanguage ?: parentLanguage
                    val content = child.getContent()
                    if (content != null && (namespace == null || Namespace.JABBER_CLIENT == namespace)) {
                        if (contents.put(lang, content) != null) {
                            // anything that has multiple contents for the same language is invalid
                            return null
                        }
                    }
                }
            }
            if (contents.isEmpty()) {
                return null
            }
            val userLanguage = Locale.getDefault().language
            val localized = contents[userLanguage]
            if (localized != null) {
                return LocalizedContent(localized, userLanguage, contents.size)
            }
            val defaultLanguageContent = contents[null]
            if (defaultLanguageContent != null) {
                return LocalizedContent(defaultLanguageContent, STREAM_LANGUAGE, contents.size)
            }
            val streamLanguageContent = contents[STREAM_LANGUAGE]
            if (streamLanguageContent != null) {
                return LocalizedContent(streamLanguageContent, STREAM_LANGUAGE, contents.size)
            }
            val first = Iterables.get(contents.entries, 0)
            return LocalizedContent(first.value, first.key, contents.size)
        }
    }
}
