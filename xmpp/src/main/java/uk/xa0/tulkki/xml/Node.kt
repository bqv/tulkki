package uk.xa0.tulkki.xml

import com.google.common.collect.ImmutableMap

/**
 * Tulkki: the XML tree's node contract.
 *
 * Ported from `Node.java`. It is an island interface,
 * so the conversion is faithful: `getContent` answers a non-null `String` (both implementations
 * answer one, and `Element.getContent`'s old null filter was a redundant safety), `toString`
 * keeps Guava's `ImmutableMap` parameter and the descriptor the Java callers resolve against, and
 * `appendToBuilder` takes Java's `java.util.Map` - Kotlin's read-only `Map`, which is what the
 * implementations only ever read before copying.
 */
interface Node {
    fun getContent(): String

    fun toString(ns: ImmutableMap<String, String>): String

    fun appendToBuilder(ns: Map<String, String>, elementOutput: StringBuilder, skipEnd: Int)
}
