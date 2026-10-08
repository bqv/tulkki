package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/** The cache: the same text and target language is never bought twice. */
class TranslationCacheTest {

    private val NOW = 1_800_000_000_000L

    private val store = TranslationDoubles.MemoryCacheStore()
    private val cache = TranslationCache(store)

    @Test
    fun aMissIsNull() {
        Assert.assertNull(cache.get(CacheKey.of("Hello", "fi")))
    }

    @Test
    fun aStoredAnswerComesBackWithItsLanguageAndCost() {
        val key = CacheKey.of("Hello", "fi")
        cache.store(key, "en", "Hei", 150, NOW)
        val entry = cache.get(key)
        Assert.assertNotNull(entry)
        Assert.assertEquals("Hei", entry!!.translatedBody)
        Assert.assertEquals("en", entry.detectedLanguage)
        Assert.assertEquals(150, entry.totalTokens)
        Assert.assertEquals(NOW, entry.createdAt)
    }

    @Test
    fun theSameTextInAnotherLanguageIsAnotherEntry() {
        cache.store(CacheKey.of("Hello", "fi"), "en", "Hei", 150, NOW)
        Assert.assertNull(cache.get(CacheKey.of("Hello", "de")))
    }

    @Test
    fun aSecondAnswerForTheSameKeyWins() {
        val key = CacheKey.of("Hello", "fi")
        cache.store(key, "en", "Hei", 150, NOW)
        cache.store(key, "en", "Hei siellä", 160, NOW + 1)
        Assert.assertEquals("Hei siellä", cache.get(key)!!.translatedBody)
        Assert.assertEquals(1, store.entries.size)
    }

    @Test
    fun aNullKeyIsAMissAndDoesNotReachTheStore() {
        Assert.assertNull(cache.get(null))
    }
}
