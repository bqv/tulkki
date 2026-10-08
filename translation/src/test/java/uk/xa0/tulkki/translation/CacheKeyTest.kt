package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/** The cache key is what stops a retry from being a second purchase. */
class CacheKeyTest {

    @Test
    fun sameTextAndLanguageIsTheSameKey() {
        Assert.assertEquals(
                CacheKey.of("Hello there", "fi"), CacheKey.of("Hello there", "fi"))
    }

    @Test
    fun languageIsPartOfTheKey() {
        Assert.assertNotEquals(
                CacheKey.of("Hello there", "fi"), CacheKey.of("Hello there", "de"))
    }

    @Test
    fun textIsPartOfTheKey() {
        Assert.assertNotEquals(CacheKey.of("Hello", "fi"), CacheKey.of("Hallo", "fi"))
    }

    @Test
    fun whitespaceAndCaseMatter() {
        // The key has to describe the exact text that was bought, not a tidied version of it.
        Assert.assertNotEquals(CacheKey.of("Hello", "fi"), CacheKey.of("Hello ", "fi"))
        Assert.assertNotEquals(CacheKey.of("Hello", "fi"), CacheKey.of("hello", "fi"))
    }

    @Test
    fun targetLanguageCaseDoesNotMatter() {
        Assert.assertEquals(CacheKey.of("Hello", "FI"), CacheKey.of("Hello", "fi"))
    }

    @Test
    fun nullsAreStableAndDoNotThrow() {
        Assert.assertEquals(CacheKey.of(null, null), CacheKey.of(null, null))
        Assert.assertEquals(CacheKey.of(null, "fi"), CacheKey.of("", "fi"))
    }

    @Test
    fun unicodeTextIsHashedConsistently() {
        Assert.assertEquals(CacheKey.of("Moi! 😀 ä ö", "fi"), CacheKey.of("Moi! 😀 ä ö", "fi"))
        Assert.assertNotEquals(CacheKey.of("Moi! 😀 ä ö", "fi"), CacheKey.of("Moi! 😀 ä ö", "sv"))
    }

    @Test
    fun isSixtyFourLowercaseHexCharacters() {
        val key = CacheKey.of("anything", "fi")
        Assert.assertEquals(64, key.length)
        Assert.assertTrue("not hex: " + key, key.matches(Regex("[0-9a-f]{64}")))
    }
}
