package works.alomair.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderTest {
    private fun store(): ProviderStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("providers-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return ProviderStore(prefs, AesGcmCipher { key })
    }

    @Test fun defaultsIndependentEncryptedPersistenceAndSwitching() {
        val store = store()
        assertEquals(ProviderId.GO, store.selected())
        assertEquals("https://opencode.ai/inference/go/openai/v1/chat/completions", store.load(ProviderId.GO).endpoint)
        assertEquals("glm-5.3-flash", store.load(ProviderId.GO).model)
        store.save(ProviderConfig(ProviderId.GO, ProviderId.GO.endpoint, "glm-5.3-flash", "dummy-finance-go"))
        store.save(ProviderConfig(ProviderId.ROUTER, ProviderId.ROUTER.endpoint, "chosen/model", "dummy-finance-router"))
        store.save(ProviderConfig(ProviderId.CUSTOM, "https://example.invalid/v1/chat/completions", "custom-model", "dummy-finance-custom"))
        store.select(ProviderId.ROUTER)
        assertEquals("dummy-finance-router", store.active().apiKey)
        store.select(ProviderId.GO)
        assertEquals("dummy-finance-go", store.active().apiKey)
        assertEquals("chosen/model", store.load(ProviderId.ROUTER).model)
        assertFalse(store.preferences.all.values.joinToString().contains("dummy-finance"))
        val reopened = ProviderStore(store.preferences, store.cipher)
        assertEquals("dummy-finance-custom", reopened.load(ProviderId.CUSTOM).apiKey)
        assertEquals(store.sessionId(), reopened.sessionId())
        assertFalse(store.active().toString().contains("dummy-finance"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(ProviderConfig(ProviderId.CUSTOM, "http://public.example/completions", "model", "dummy"))
        }
    }

    @Test fun sharedClientRequestShapeConnectionTestAndNoRedirectLeak() {
        val store = store()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            val client = ChatCompletionsClient(OkHttpClient(), store.sessionId())
            val config = ProviderConfig(ProviderId.CUSTOM, server.url("/chat/completions").toString(), "synthetic-model", "dummy-finance-key")
            assertEquals("OK", client.testConnection(config))
            val request = server.takeRequest()
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer dummy-finance-key", request.getHeader("Authorization"))
            assertEquals("AlomairFinance/1.0 (Android)", request.getHeader("User-Agent"))
            assertNull(request.getHeader("x-opencode-session"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("synthetic-model", body.getString("model"))
            assertEquals(1, body.getJSONArray("messages").length())
            assertTrue(client.headers(ProviderId.GO).containsKey("x-opencode-session"))
            assertFalse(client.headers(ProviderId.ROUTER).containsKey("x-opencode-session"))
            assertEquals(store.sessionId(), client.headers(ProviderId.GO)["x-opencode-session"])
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid"))
            assertEquals("HTTP_302", assertThrows(ApiFailure::class.java) { client.testConnection(config) }.code)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun allPresetsUseTheirExactUrlAuthModelAndProviderScopedHeaders() {
        val store = store()
        MockWebServer().use { server ->
            var originalUrl = ""
            // Observe the real target URL, then intercept transport to a loopback mock only.
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                originalUrl = chain.request().url.toString()
                chain.proceed(chain.request().newBuilder().url(server.url("/mock")).build())
            }.build()
            val client = ChatCompletionsClient(transport, store.sessionId())
            for (id in ProviderId.values()) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
                val url = if (id == ProviderId.CUSTOM) "https://example.invalid/chat/completions" else id.endpoint
                val config = ProviderConfig(id, url, if (id == ProviderId.GO) id.defaultModel else "chosen-model", "dummy-provider-key")
                client.testConnection(config)
                val request = server.takeRequest()
                assertEquals(url, originalUrl)
                assertEquals("Bearer dummy-provider-key", request.getHeader("Authorization"))
                assertEquals(config.model, JSONObject(request.body.readUtf8()).getString("model"))
                assertEquals("AlomairFinance/1.0 (Android)", request.getHeader("User-Agent"))
                assertEquals(if (id == ProviderId.GO) store.sessionId() else null, request.getHeader("x-opencode-session"))
            }
        }
    }

    @Test fun encryptionIsRandomizedAndBoundToEachProvider() {
        val store = store()
        val config = ProviderConfig(ProviderId.GO, ProviderId.GO.endpoint, "glm-5.3-flash", "dummy-isolated-key")
        store.save(config)
        val first = store.preferences.getString("GO.key", null)!!
        store.save(config)
        assertNotEquals(first, store.preferences.getString("GO.key", null))
        store.preferences.edit().putString("CUSTOM.key", first).commit()
        assertEquals("KEY_UNAVAILABLE", assertThrows(IllegalStateException::class.java) { store.load(ProviderId.CUSTOM) }.message)
    }

    @Test fun errorClassificationUsesOnlySafeStatusCodes() {
        MockWebServer().use { server ->
            val client = ChatCompletionsClient(sessionId = "synthetic-session")
            val config = ProviderConfig(ProviderId.CUSTOM, server.url("/chat").toString(), "synthetic", "dummy-test-key")
            for (code in listOf(400, 401, 403, 404, 408, 429, 500, 503)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("Untrusted sensitive error: do not persist"))
                val failure = assertThrows(ApiFailure::class.java) { client.testConnection(config) }
                assertEquals("HTTP_$code", failure.code)
                assertEquals(code in setOf(408, 429, 500, 503), failure.retryable)
                assertFalse(failure.toString().contains("Untrusted"))
            }
        }
    }
}
