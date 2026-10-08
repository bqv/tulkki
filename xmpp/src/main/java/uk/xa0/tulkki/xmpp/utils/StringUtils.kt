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

/**
 * The three null-tolerant string comparisons the tree uses.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`trim()` is Java's `trim()`, not Kotlin's.** `nullOnEmpty` guards on `input.trim().isEmpty()`;
 *    Java's `String.trim()` drops every char `<= ' '` while Kotlin's argument-less `trim()` drops
 *    every `Char.isWhitespace()`, which is a broader set (NBSP among them) and would have turned some
 *    inputs from empty into non-empty. [javaTrim] is the same predicate the Java line had.
 * 2. **All three parameters that Java tolerated as null stay nullable.** `equals`/`changed` existed
 *    to compare nulls without the caller checking, and `:data`'s `Bookmark.kt:300` and
 *    `Message.kt:1888` call `nullOnEmpty` with a value that may be null. `input` on `nullOnEmpty` is
 *    read before the null test, so it must be `String?` or Kotlin throws where Java answered `null`.
 * 3. **The methods are `@JvmStatic`, on the Java-visible companion.** Java callers exist and are
 *    static imports (`import static ...StringUtils.changed` in `ConferenceDetailsActivity.java:5`),
 *    which a plain companion function would not satisfy. The class keeps a public constructor, as
 *    Java's implicit one was.
 */
class StringUtils {

    companion object {

        /** Java's `String.trim()`: every char `<= ' '` goes, NBSP included in the survivors. */
        private fun String.javaTrim(): String = trim { it <= ' ' }

        @JvmStatic
        fun equals(one: String?, two: String?): Boolean = blankOnNull(one) == blankOnNull(two)

        @JvmStatic
        fun changed(one: String?, two: String?): Boolean = !equals(one, two)

        @JvmStatic
        fun nullOnEmpty(input: String?): String? =
            if (input == null || input.javaTrim().isEmpty()) null else input

        private fun blankOnNull(input: String?): String = input ?: ""
    }
}
