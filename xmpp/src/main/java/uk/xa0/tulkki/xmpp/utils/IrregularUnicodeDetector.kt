/*
 * Copyright (c) 2018-2019, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
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

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.regex.Pattern
import uk.xa0.tulkki.libs.Jid

/**
 * Colours the parts of a JID that use a character from more than one script, so a spoofed name is
 * visible before it is trusted.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`split("\\.")` is `Pattern.compile("\\.").split(domain, 0)`.** The Java argument is a *regex*;
 *    Kotlin's `split(String)` is a literal split and would have looked for a backslash-dot, so the
 *    `Pattern` overload is the faithful call. It is used for the domain labels both in [style] and in
 *    the pattern builder.
 * 2. **`normalize` answers `Character.UnicodeBlock?` and tests `inBlock == null` first.** Java's
 *    `NORMALIZATION_MAP.containsKey(in)` tolerated a null (`UnicodeBlock.of` answers null for an
 *    unassigned code point) and returned the same null; Kotlin's `Map.containsKey` will not take a
 *    nullable key, and the early return is exactly that branch.
 * 3. **The two per-script maps are `HashMap<…, MutableList<String>>` and are filled with `getOrPut`.**
 *    Java fetched the existing list and appended to it, then removed the largest script from the map
 *    (`eliminateFirstAndGetCodePoints` mutates it); the Kotlin parameter is a `MutableMap` for that
 *    reason.
 * 4. **`String.copyValueOf(Character.toChars(codePoint))` is `String(Character.toChars(codePoint))`**,
 *    the same default-charset construction.
 * 5. **`PatternTuple.of` becomes a private companion factory `patternTupleOf`.** The class and its
 *    factory were private, the factory's only caller is [find], and this keeps the nested
 *    `PatternTuple`'s own scope simple; no visible surface changes.
 * 6. **`PatternTuple`'s two fields are `val`s of a private nested class**, read by [style] from the
 *    outer class - Kotlin, unlike Java, would not let the outer class read another class's `private`
 *    members.
 * 7. **`style(Context, Jid)` is `@JvmStatic`** for its Java callers in `:ui`.
 */
class IrregularUnicodeDetector {

    companion object {

        private val NORMALIZATION_MAP: Map<Character.UnicodeBlock, Character.UnicodeBlock> =
            Collections.unmodifiableMap(
                HashMap<Character.UnicodeBlock, Character.UnicodeBlock>().apply {
                    put(
                        Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                        Character.UnicodeBlock.BASIC_LATIN,
                    )
                },
            )

        private val CACHE: LruCache<Jid, PatternTuple> = LruCache(4096)

        private val AMBIGUOUS_CYRILLIC: List<String> =
            Arrays.asList("а", "г", "е", "ѕ", "і", "ј", "ķ", "ԛ", "о", "р", "с", "у", "х")

        private fun normalize(inBlock: Character.UnicodeBlock?): Character.UnicodeBlock? {
            if (inBlock == null) {
                return null
            }
            return if (NORMALIZATION_MAP.containsKey(inBlock)) {
                NORMALIZATION_MAP[inBlock]
            } else {
                inBlock
            }
        }

        @JvmStatic
        fun style(context: Context, jid: Jid): Spannable =
            style(
                jid,
                MaterialColors.getColor(
                    context,
                    androidx.appcompat.R.attr.colorError,
                    "colorError not found",
                ),
            )

        private fun style(jid: Jid, @ColorInt color: Int): Spannable {
            val patternTuple = find(jid)
            val builder = SpannableStringBuilder()
            if (jid.getLocal() != null && patternTuple.local != null) {
                val local = SpannableString(jid.getLocal())
                colorize(local, patternTuple.local, color)
                builder.append(local)
                builder.append('@')
            }
            if (jid.getDomain() != null) {
                val labels = Pattern.compile("\\.").split(jid.getDomain().toString(), 0)
                for (i in labels.indices) {
                    val spannableString = SpannableString(labels[i])
                    if (patternTuple.domain.size > i) {
                        colorize(spannableString, patternTuple.domain[i], color)
                    }
                    if (i != 0) {
                        builder.append('.')
                    }
                    builder.append(spannableString)
                }
            }
            if (builder.length != 0 && jid.getResource() != null) {
                builder.append('/')
                builder.append(jid.getResource())
            }
            return builder
        }

        private fun colorize(
            spannableString: SpannableString,
            pattern: Pattern,
            @ColorInt color: Int,
        ) {
            val matcher = pattern.matcher(spannableString)
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    spannableString.setSpan(
                        ForegroundColorSpan(color),
                        matcher.start(),
                        matcher.end(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

        private fun mapCompat(word: String): HashMap<Character.UnicodeBlock?, MutableList<String>> {
            val map = HashMap<Character.UnicodeBlock?, MutableList<String>>()
            val length = word.length
            var offset = 0
            while (offset < length) {
                val codePoint = word.codePointAt(offset)
                offset += Character.charCount(codePoint)
                if (!Character.isLetter(codePoint)) {
                    continue
                }
                val block = normalize(Character.UnicodeBlock.of(codePoint))
                map.getOrPut(block) { ArrayList() }
                    .add(String(Character.toChars(codePoint)))
            }
            return map
        }

        @TargetApi(Build.VERSION_CODES.N)
        private fun map(word: String): HashMap<Character.UnicodeScript, MutableList<String>> {
            val map = HashMap<Character.UnicodeScript, MutableList<String>>()
            val length = word.length
            var offset = 0
            while (offset < length) {
                val codePoint = word.codePointAt(offset)
                val script = Character.UnicodeScript.of(codePoint)
                if (script != Character.UnicodeScript.COMMON) {
                    map.getOrPut(script) { ArrayList() }
                        .add(String(Character.toChars(codePoint)))
                }
                offset += Character.charCount(codePoint)
            }
            return map
        }

        private fun eliminateFirstAndGetCodePointsCompat(
            map: MutableMap<Character.UnicodeBlock?, MutableList<String>>
        ): MutableSet<String> =
            eliminateFirstAndGetCodePoints(map, Character.UnicodeBlock.BASIC_LATIN)

        @TargetApi(Build.VERSION_CODES.N)
        private fun eliminateFirstAndGetCodePoints(
            map: MutableMap<Character.UnicodeScript, MutableList<String>>
        ): MutableSet<String> = eliminateFirstAndGetCodePoints(map, Character.UnicodeScript.COMMON)

        private fun <T> eliminateFirstAndGetCodePoints(
            map: MutableMap<T, MutableList<String>>,
            defaultPick: T,
        ): MutableSet<String> {
            var pick = defaultPick
            var size = 0
            for ((key, value) in map.entries) {
                if (value.size > size) {
                    size = value.size
                    pick = key
                }
            }
            map.remove(pick)
            val all = HashSet<String>()
            for (codePoints in map.values) {
                all.addAll(codePoints)
            }
            return all
        }

        private fun findIrregularCodePoints(word: String): Set<String> {
            val codePoints: Set<String>
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val map = mapCompat(word)
                val set = asSet(map)
                if (containsOnlyAmbiguousCyrillic(set)) {
                    return set
                }
                codePoints = eliminateFirstAndGetCodePointsCompat(map)
            } else {
                val map = map(word)
                val set = asSet(map)
                if (containsOnlyAmbiguousCyrillic(set)) {
                    return set
                }
                codePoints = eliminateFirstAndGetCodePoints(map)
            }
            return codePoints
        }

        private fun asSet(map: Map<*, List<String>>): Set<String> {
            val flat = HashSet<String>()
            for (value in map.values) {
                flat.addAll(value)
            }
            return flat
        }

        private fun containsOnlyAmbiguousCyrillic(codePoints: Collection<String>): Boolean {
            for (codePoint in codePoints) {
                if (!AMBIGUOUS_CYRILLIC.contains(codePoint)) {
                    return false
                }
            }
            return true
        }

        private fun find(jid: Jid): PatternTuple {
            synchronized(CACHE) {
                var pattern = CACHE.get(jid)
                if (pattern != null) {
                    return pattern
                }
                pattern = patternTupleOf(jid)
                CACHE.put(jid, pattern)
                return pattern
            }
        }

        private fun create(codePoints: Set<String>): Pattern {
            val pattern = StringBuilder()
            for (codePoint in codePoints) {
                if (pattern.length != 0) {
                    pattern.append('|')
                }
                pattern.append(Pattern.quote(codePoint))
            }
            return Pattern.compile(pattern.toString())
        }

        private fun patternTupleOf(jid: Jid): PatternTuple {
            val local = jid.getLocal()
            val localPattern: Pattern? =
                if (local != null) {
                    create(findIrregularCodePoints(local))
                } else {
                    null
                }
            val domain = jid.getDomain().toString()
            val domainPatterns = ArrayList<Pattern>()
            for (label in Pattern.compile("\\.").split(domain, 0)) {
                domainPatterns.add(create(findIrregularCodePoints(label)))
            }
            return PatternTuple(localPattern, domainPatterns)
        }
    }

    private class PatternTuple(val local: Pattern?, val domain: List<Pattern>)
}
