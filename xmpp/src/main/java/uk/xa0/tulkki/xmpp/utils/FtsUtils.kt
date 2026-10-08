/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.xa0.tulkki.xmpp.utils

import java.util.Arrays
import java.util.Locale
import java.util.regex.Pattern

/**
 * The search-box term splitter: each part cleaned of surrounding `*`, `OR`/`AND` recognised, and the
 * cleaned terms turned back into an FTS match string.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`split` is `Pattern.compile("\\s+").split(input, 0)`, not Kotlin's `split(Regex)` or its
 *    literal `split(String)`.** Java's `String.split(regex)` drops trailing empty strings and Kotlin
 *    keeps them (the same trap `Contact.match` records), so the `Pattern` overload with limit `0` is
 *    the byte-faithful call; the `part.isEmpty()` guard then skips the leading empty a run of
 *    whitespace produces, exactly as the Java loop did.
 * 2. **Every public method is `@JvmStatic`.** The callers are Java - `:ui`'s
 *    `SearchActivity:125/:229/:316`, `StylingHelper:125/:134` and `:data`'s `DatabaseBackend:1197`
 *    - and `:data`'s tests call `parse` directly.
 * 3. **The `charAt` walks are indexed reads** (`term[index]`), which is the same call, and the
 *    `getStartIndex`/`getEndIndex` bounds checks stay as Java wrote them (an empty `term` was never
 *    passed: the loop skips empty parts before `clean`).
 * 4. **`toUpperCase(Locale.ENGLISH)` is `uppercase(Locale.ENGLISH)`**, the same
 *    `String.toUpperCase(Locale)` operation.
 */
class FtsUtils {

    companion object {

        private val KEYWORDS: List<String> = Arrays.asList("OR", "AND")

        @JvmStatic
        fun parse(input: String): List<String> {
            val term = ArrayList<String>()
            for (part in Pattern.compile("\\s+").split(input.replace('"', ' '), 0)) {
                if (part.isEmpty()) {
                    continue
                }
                val cleaned = clean(part)
                if (isKeyword(cleaned) || cleaned.contains("*")) {
                    term.add(part)
                } else if (cleaned.isNotEmpty()) {
                    term.add(cleaned)
                }
            }
            return term
        }

        @JvmStatic
        fun toMatchString(terms: List<String>): String {
            val builder = StringBuilder()
            for (term in terms) {
                if (builder.length != 0) {
                    builder.append(' ')
                }
                if (isKeyword(term)) {
                    builder.append(term.uppercase(Locale.ENGLISH))
                } else if (term.contains("*") || term.startsWith("-")) {
                    builder.append(term)
                } else {
                    builder.append(term).append('*')
                }
            }
            return builder.toString()
        }

        @JvmStatic
        fun isKeyword(term: String): Boolean =
            KEYWORDS.contains(term.uppercase(Locale.ENGLISH))

        private fun getStartIndex(term: String): Int {
            val length = term.length
            var index = 0
            while (term[index] == '*') {
                ++index
                if (index >= length) {
                    break
                }
            }
            return index
        }

        private fun getEndIndex(term: String): Int {
            var index = term.length - 1
            while (term[index] == '*') {
                --index
                if (index < 0) {
                    break
                }
            }
            return index
        }

        private fun clean(input: String): String {
            val begin = getStartIndex(input)
            val end = getEndIndex(input)
            return if (begin > end) {
                ""
            } else {
                input.substring(begin, end + 1)
            }
        }

        @JvmStatic
        fun toUserEnteredString(term: List<String>): String {
            val builder = StringBuilder()
            for (part in term) {
                if (builder.length != 0) {
                    builder.append(' ')
                }
                builder.append(part)
            }
            return builder.toString()
        }
    }
}
