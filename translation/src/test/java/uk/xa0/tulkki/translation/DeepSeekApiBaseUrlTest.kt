package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The API base URL: the setting, the endpoints derived from it, and the proof that a client built
 * for it asks *that* server about both the translation and the balance.
 *
 * <p>The balance lives at a different path on the chat endpoint's host, so a base URL that lost its
 * port - or a balance URL that quietly fell back to the real API - would send the app's one balance
 * request to DeepSeek while the translations went to the test server. Both calls are therefore made
 * against a local server here, and both are checked for the port they arrived on.
 */
class DeepSeekApiBaseUrlTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String {
        return server.url("/").toString()
    }

    private fun chatAnswer(lang: String, text: String, totalTokens: Int): String {
        val contract = JsonObject()
        contract.addProperty("lang", lang)
        contract.addProperty("text", text)

        val message = JsonObject()
        message.addProperty("role", "assistant")
        message.addProperty("content", contract.toString())

        val choice = JsonObject()
        choice.addProperty("index", 0)
        choice.add("message", message)

        val choices = JsonArray()
        choices.add(choice)

        val usage = JsonObject()
        usage.addProperty("total_tokens", totalTokens)

        val root = JsonObject()
        root.add("choices", choices)
        root.add("usage", usage)
        return root.toString()
    }

    private fun balanceDocument(): String {
        return "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"USD\"," +
                "\"total_balance\":\"-0.00\",\"granted_balance\":\"0.00\"," +
                "\"topped_up_balance\":\"-0.00\"},{\"currency\":\"CNY\"," +
                "\"total_balance\":\"15.51\",\"granted_balance\":\"0.00\"," +
                "\"topped_up_balance\":\"15.51\"}]}"
    }

    // ---- the setting, and the endpoints that follow from it ----

    @Test
    fun anUntouchedSettingKeepsTheRealApi() {
        val settings = TranslationSettings.inMemory()

        assertEquals(DeepSeekClient.DEFAULT_BASE_URL, settings.apiBaseUrl())
        assertEquals(DeepSeekClient.DEFAULT_ENDPOINT, DeepSeekClient.endpointFor(settings.apiBaseUrl()))
    }

    @Test
    fun aTrailingSlashIsDroppedFromTheEndpointNotDoubledUp() {
        val settings = TranslationSettings.inMemory()

        settings.setApiBaseUrl("  http://10.0.2.2:18080/  ")

        // The setting keeps what was written, minus the spaces; the endpoint is where it matters.
        assertEquals("http://10.0.2.2:18080/", settings.apiBaseUrl())
        assertEquals(
                "http://10.0.2.2:18080/chat/completions",
                DeepSeekClient.endpointFor(settings.apiBaseUrl()))
    }

    @Test
    fun aProxyPrefixIsKeptRatherThanReplaced() {
        assertEquals(
                "https://example.test/api/v1/chat/completions",
                DeepSeekClient.endpointFor("https://example.test/api/v1"))
    }

    @Test
    fun anEmptyOrUnusableBaseUrlIsTheRealApi() {
        for (unusable in arrayOf<String?>(null, "", "   ", "not a url", "://")) {
            assertEquals(
                    "a base URL of " + unusable + " must not point anywhere new",
                    DeepSeekClient.DEFAULT_ENDPOINT,
                    DeepSeekClient.endpointFor(unusable))
        }
    }

    // ---- both calls, against a base URL with a port of its own ----

    @Test
    fun bothTheChatCallAndTheBalanceAskTheConfiguredBaseUrl() {
        val settings = TranslationSettings.inMemory()
        settings.setApiBaseUrl(baseUrl())
        val client =
                DeepSeekClient(
                        OkHttpClient(),
                        "test-key",
                        DeepSeekClient.endpointFor(settings.apiBaseUrl()),
                        DeepSeekClient.DEFAULT_MODEL)

        server.enqueue(MockResponse().setBody(chatAnswer("en", "[fi] Hei", 42)))
        server.enqueue(MockResponse().setBody(balanceDocument()))

        val result = client.translate("Hello", "fi")
        assertEquals("[fi] Hei", result.text)

        val balance = client.fetchBalance()
        assertEquals(2, balance.infos.size)
        assertEquals("CNY", balance.infos[1].currency)
        assertEquals("15.51", balance.infos[1].totalBalance)

        val chat: RecordedRequest = server.takeRequest()
        val fetch: RecordedRequest = server.takeRequest()
        assertEquals("/chat/completions", chat.path)
        assertEquals("/user/balance", fetch.path)
        // The port is the point: a base URL that lost it would send the balance to the real API.
        assertNotNull(chat.requestUrl)
        assertNotNull(fetch.requestUrl)
        assertEquals(server.port, chat.requestUrl!!.port)
        assertEquals(server.port, fetch.requestUrl!!.port)
        assertTrue(chat.requestUrl!!.host.equals(fetch.requestUrl!!.host))
    }

    /**
     * The client the usage screen builds - key alone, no endpoint passed - has to follow the
     * setting too, or the balance figure on screen would keep coming from the real account while
     * everything else went to the test server.
     */
    @Test
    fun aClientBuiltFromTheKeyAloneAsksTheConfiguredBaseUrl() {
        val settings = TranslationSettings.inMemory()
        settings.setApiBaseUrl(baseUrl())

        server.enqueue(MockResponse().setBody(balanceDocument()))

        val balance = DeepSeekClient("test-key").fetchBalance()

        assertEquals("CNY", balance.infos[1].currency)
        val fetch: RecordedRequest = server.takeRequest()
        assertEquals("/user/balance", fetch.path)
        assertEquals(server.port, fetch.requestUrl!!.port)
    }
}
