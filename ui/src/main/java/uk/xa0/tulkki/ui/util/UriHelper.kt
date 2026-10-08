package uk.xa0.tulkki.ui.util

import com.google.common.collect.ImmutableMap

/**
 * Helper methods for parsing URI's.
 */
object UriHelper {

    /**
     * Parses a query string into a hashmap.
     *
     * @param q The query string to split.
     * @return A hashmap containing the key-value pairs from the query string.
     */
    @JvmStatic
    fun parseQueryString(q: String?): Map<String, String> {
        if (q == null || q.isEmpty()) {
            return ImmutableMap.of()
        }
        val queryMapBuilder = ImmutableMap.Builder<String, String>()

        // Regex.split keeps java.lang.String.split's semantics, trailing empty strings included.
        val query = q.split(Regex("&"))
        for (param in query) {
            val pair = param.split(Regex("="))
            val value = if (pair.size == 2 && !pair[1].isEmpty()) pair[1] else null
            // Java put the null into Guava's Builder.put and the NPE came from there; Builder.put is
            // non-null to Kotlin, so the same NPE is raised here.
            queryMapBuilder.put(pair[0], value ?: throw NullPointerException("null value in entry"))
        }

        return queryMapBuilder.build()
    }
}
