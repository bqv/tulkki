package uk.xa0.tulkki.xmpp.utils

import com.google.common.base.Joiner
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import java.util.Collections
import uk.xa0.tulkki.xml.Element

/**
 * The five entity helpers the XML writer uses.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`encodeEntities`' `content` is non-null** (the first statement dereferences it) and its
 *    control-character sweep stays `java.util.regex.Pattern` through Kotlin's `Regex`, which is the
 *    same engine. The `\p{Cntrl}` class and its `&&` intersection are unchanged.
 * 2. **`appendEncodedEntities` keeps both loops and the same short-circuit**: the scan tests `<`,
 *    `&`, `"` and `isInvalidXmlChar` and the write loop tests all five markup chars, exactly as
 *    upstream `76d1bbc304` wrote it.
 * 3. **`printElementNames` takes `Element?` and answers `String`** - Java tested `element == null`
 *    and built an empty list, and `Joiner` on an empty list answers `""`, never null.
 * 4. **`print` takes `Collection<Element>?` and answers `String?`**: Java returned `null` for a null
 *    collection, which is the contract `Tag`/`TextNode` callers read.
 * 5. **All five are `@JvmStatic`**: the island's `Tag.java:95` and `TextNode.java:21` are Java
 *    callers, and so is `:app`'s `XmlHardeningTest`.
 */
class XmlHelper {

    companion object {

        @JvmStatic
        fun encodeEntities(content: String): String {
            var result = content.replace("&", "&amp;")
            result = result.replace("<", "&lt;")
            result = result.replace(">", "&gt;")
            result = result.replace("\"", "&quot;")
            result = result.replace("'", "&apos;")
            result = result.replace(Regex("[\\p{Cntrl}&&[^\\n\\t\\r]]"), "")
            return result
        }

        @JvmStatic
        fun appendEncodedEntities(content: String, sb: StringBuilder) {
            val length = content.length
            // Tulkki: port-11, upstream `76d1bbc304` - the scan and the write loop both have to know
            // about control characters, not only the markup: a body a peer sent could carry one
            // straight into the document we serialize, which is invalid XML.
            var needsWork = false
            for (i in 0 until length) {
                val c = content[i]
                if (c == '<' || c == '&' || c == '"' || isInvalidXmlChar(c)) {
                    needsWork = true
                    break
                }
            }
            if (needsWork) {
                for (i in 0 until length) {
                    when (val c = content[i]) {
                        '&' -> sb.append("&amp;")
                        '<' -> sb.append("&lt;")
                        '>' -> sb.append("&gt;")
                        '"' -> sb.append("&quot;")
                        '\'' -> sb.append("&apos;")
                        else -> if (!isInvalidXmlChar(c)) sb.append(c)
                    }
                }
            } else {
                sb.append(content)
            }
        }

        /**
         * Tulkki: upstream `76d1bbc304`'s rule - the characters XML 1.0 forbids outright. Tab,
         * newline and carriage return are legal and stay; everything else at or below `0x1F`, and
         * `0x7F`, does not.
         */
        private fun isInvalidXmlChar(c: Char): Boolean =
            (c <= '\u001F' || c == '\u007F') && c != '\t' && c != '\n' && c != '\r'

        @JvmStatic
        fun printElementNames(element: Element?): String {
            val features: List<String?> =
                if (element == null) {
                    Collections.emptyList()
                } else {
                    Lists.transform(element.children) { child -> child?.getName() }
                }
            return Joiner.on(", ").join(features)
        }

        @JvmStatic
        fun print(children: Collection<Element>?): String? {
            if (children == null) {
                return null
            }
            return Joiner.on("").join(Iterables.transform(children) { it.toString() })
        }
    }
}
