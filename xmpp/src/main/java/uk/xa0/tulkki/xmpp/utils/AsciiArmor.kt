package uk.xa0.tulkki.xmpp.utils

import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.Iterables
import com.google.common.io.BaseEncoding

/**
 * The ASCII-armoured PGP signature reader behind `PgpEngine`.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`input` is `String?`**, which is Java's own contract: the first statement is
 *    `Strings.nullToEmpty(input)`, so a null signature armoured nothing rather than crashing. The
 *    one caller (`PgpEngine.java:162`) passes the API's `RESULT_DETACHED_SIGNATURE`.
 * 2. **The `.trim()` is Java's `trim()`**, not Kotlin's: Java drops every char `<= ' '` and Kotlin's
 *    argument-less `trim()` drops every `Char.isWhitespace()`, a wider set. The predicate is
 *    written out for that reason.
 * 3. **`Splitter.on('\n')`, `Joiner`, `Iterables.getLast` and `BaseEncoding` stay the Guava calls**,
 *    since the dependency is already on `:xmpp`'s compile classpath and the Java line is the
 *    conversion.
 */
class AsciiArmor {

    companion object {

        @JvmStatic
        fun decode(input: String?): ByteArray {
            val lines: List<String> =
                Splitter.on('\n')
                    .splitToList(Strings.nullToEmpty(input).trim { it <= ' ' })
            if (lines.size == 1) {
                val line = lines[0]
                if (line.length > 1) {
                    val end = line.lastIndexOf('=')
                    if (end >= 1) {
                        val cleaned = line.substring(0, end)
                        return BaseEncoding.base64().decode(cleaned)
                    }
                }
            }
            val withoutChecksum: String
            if (Iterables.getLast(lines)[0] == '=') {
                withoutChecksum = Joiner.on("").join(lines.subList(0, lines.size - 1))
            } else {
                withoutChecksum = Joiner.on("").join(lines)
            }
            return BaseEncoding.base64().decode(withoutChecksum)
        }
    }
}
