package uk.xa0.tulkki.data.translation

/**
 * One `translation_cache` row as `:data` reads and writes it (S5-6).
 *
 * <p>**Why not `TranslationCache.Entry`.** That type is `:translation`'s, and `:data` may not import
 * it - the dependency runs the other way. So the row is its own value here and `TranslationStore`
 * maps between the two, field for field, in one place. Nothing is decided here: the key is whatever
 * the caller computed, and the row carries the file's columns and no rule about what may be cached.
 *
 * [detectedLanguage] is nullable because the column is: a model that did not say what language it saw
 * leaves it null, and the column has no `NOT NULL`. The body is not nullable, which is the file's own
 * declaration and the reason a row without an answer is not a row.
 */
class TranslationCacheRow(
    val cacheKey: String,
    val detectedLanguage: String?,
    val translatedBody: String,
    val totalTokens: Int,
    val createdAt: Long,
)
