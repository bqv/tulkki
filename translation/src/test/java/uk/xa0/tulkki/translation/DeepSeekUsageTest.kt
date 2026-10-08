package uk.xa0.tulkki.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two account documents beside the chat call: the {@code usage} object every completion carries,
 * and the model list {@code GET /models} answers with.
 *
 * <p>No server: the parsers are handed recorded documents, which is what makes the fallbacks - the
 * ones that decide whether the app's own estimate over- or under-reports - pinnable here.
 */
class DeepSeekUsageTest {

    private fun json(document: String): JsonObject {
        return JsonParser.parseString(document).getAsJsonObject()
    }

    // -- usage ------------------------------------------------------------------------------------

    @Test
    fun theWholeUsageSplitIsRead() {
        val usage =
                DeepSeekClient.usage(
                        json(
                                "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200,"
                                        + "\"prompt_cache_hit_tokens\":900,"
                                        + "\"prompt_cache_miss_tokens\":100,"
                                        + "\"total_tokens\":1200}}"))

        assertEquals(1000, usage.promptTokens)
        assertEquals(200, usage.completionTokens)
        assertEquals(900, usage.cacheHitTokens)
        assertEquals(100, usage.cacheMissTokens)
        assertEquals("the cap still counts the response's own total", 1200, usage.totalTokens)
    }

    @Test
    fun aMissingCacheSplitCountsTheWholePromptAsAMiss() {
        // The honest direction: a miss is the expensive price, so this over-estimates the spend rather
        // than making the app's own report cheaper than the bill.
        val usage =
                DeepSeekClient.usage(
                        json(
                                "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200,"
                                        + "\"total_tokens\":1200}}"))

        assertEquals(0, usage.cacheHitTokens)
        assertEquals(1000, usage.cacheMissTokens)
        assertTrue(
                "an unreadable split must not price the prompt as cached",
                usage.cacheMissTokens > usage.cacheHitTokens)
    }

    @Test
    fun halfAReportedSplitIsTreatedAsNoSplit() {
        // The two counts are used together: one present and one absent cannot be made to add up to
        // the prompt, so it takes the same fallback as neither being there.
        val usage =
                DeepSeekClient.usage(
                        json(
                                "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":200,"
                                        + "\"prompt_cache_hit_tokens\":900,"
                                        + "\"total_tokens\":1200}}"))

        assertEquals(0, usage.cacheHitTokens)
        assertEquals(1000, usage.cacheMissTokens)
    }

    @Test
    fun aResponseWithNoUsageIsNothing() {
        assertTrue(DeepSeekClient.usage(json("{\"choices\":[]}")).isEmpty())
        assertTrue(DeepSeekClient.usage(json("{\"usage\":{}}")).isEmpty())
        assertTrue(DeepSeekClient.usage(null).isEmpty())
        assertEquals(0, DeepSeekClient.usage(json("{}")).totalTokens)
    }

    @Test
    fun theTotalIsUnchangedByTheNewFields() {
        // The old parser read only total_tokens and the cap's behaviour must not move: a body with a
        // total and nothing else still reports exactly that total.
        val usage =
                DeepSeekClient.usage(json("{\"usage\":{\"total_tokens\":142}}"))
        assertEquals(142, usage.totalTokens)
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
    }

    @Test
    fun aStringNumberIsReadLikeTheOldParserReadIt() {
        val usage =
                DeepSeekClient.usage(json("{\"usage\":{\"total_tokens\":\"142\"}}"))
        assertEquals(142, usage.totalTokens)
    }

    @Test
    fun aNegativeCountIsClampedAndNeverSubtracts() {
        val usage =
                DeepSeekClient.usage(
                        json("{\"usage\":{\"prompt_tokens\":-5,\"total_tokens\":-5}}"))
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.totalTokens)
    }

    // -- models -----------------------------------------------------------------------------------

    @Test
    fun theModelListIsReadInOrderWithBlanksAndDuplicatesDropped() {
        val models =
                DeepSeekClient.parseModels(
                        "{\"object\":\"list\",\"data\":["
                                + "{\"id\":\"deepseek-flash\"},"
                                + "{\"id\":\"\"},"
                                + "{\"id\":\"deepseek-v4-pro\"},"
                                + "{\"id\":\"deepseek-flash\"},"
                                + "\"not an object\""
                                + "]}")

        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.ids)
        assertTrue(models.contains("DEEPSEEK-FLASH"))
        assertTrue(models.contains(" deepseek-v4-pro "))
        assertTrue(!models.contains("deepseek-chat"))
    }

    @Test
    fun anEmptyListIsAnAnswerAndNotAFailure() {
        val models = DeepSeekClient.parseModels("{\"object\":\"list\",\"data\":[]}")
        assertTrue(models.isEmpty())
    }

    @Test
    fun aListWithoutDataIsAFailureToReport() {
        try {
            DeepSeekClient.parseModels("{\"object\":\"list\"}")
            fail("a document with no data array is not the model list")
        } catch (expected: DeepSeekClient.TranslationException) {
            assertTrue(expected.message!!.contains("data"))
        }
    }

    @Test
    fun aModelListThatIsNotJsonIsAFailureToReport() {
        try {
            DeepSeekClient.parseModels("not json at all")
            fail("prose is not a model list")
        } catch (expected: DeepSeekClient.TranslationException) {
            assertTrue(expected.message!!.contains("not JSON"))
        }
    }
}
