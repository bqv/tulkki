package uk.xa0.tulkki.translation

/**
 * Answers we already paid for.
 *
 * <p>Keyed by [CacheKey], which is the text plus the target language, so the same text is never
 * bought twice - not by a retry after a crash, not by two identical messages, not by the queue being
 * pumped twice. The key is the whole point of the class; the storage is somebody else's problem.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests. `Entry`'s four values are
 * `@JvmField`s because Java reads them as fields (`TranslationCacheTest`'s assertions), while
 * `TranslationStore.kt` reads the same names as Kotlin properties - both work on a Java-visible
 * field. `Store`'s members stay `get`/`put` calls. `cacheKey`/`detectedLanguage`/`translatedBody`
 * stay nullable because that is what the callers hold - `TranslationService` hands `store` a
 * nullable cache key and body, and the Java this replaces read platform types - and
 * `TranslationStore.put` is the one place that needs the first and third non-null for the row's own
 * columns, so it names them there rather than guessing.
 */
class TranslationCache(private val storeValue: Store) {

    /** Where cached answers live between runs. */
    interface Store {
        fun get(cacheKey: String): Entry?

        fun put(entry: Entry)
    }

    /** A bought answer. */
    class Entry(
            @JvmField val cacheKey: String?,
            /** The language the model saw in the input, or `null` if it did not say. */
            @JvmField val detectedLanguage: String?,
            /** The text in the target language. */
            @JvmField val translatedBody: String?,
            @JvmField val totalTokens: Int,
            @JvmField val createdAt: Long
    )

    fun get(cacheKey: String?): Entry? = if (cacheKey == null) null else storeValue.get(cacheKey)

    /** Records an answer that was just bought. */
    fun store(
            cacheKey: String?,
            detectedLanguage: String?,
            translatedBody: String?,
            totalTokens: Int,
            now: Long
    ): Entry {
        val entry = Entry(cacheKey, detectedLanguage, translatedBody, totalTokens, now)
        storeValue.put(entry)
        return entry
    }
}
