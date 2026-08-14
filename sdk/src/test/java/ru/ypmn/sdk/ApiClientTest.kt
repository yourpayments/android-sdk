package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.ypmn.sdk.internal.api.ApiClient

class ApiClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun createIntent_posts_to_api_intent() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"sec"}"""))
        val api = ApiClient.create(YpConfig(baseUrl = server.url("/").toString()))
        val res = api.createIntent(CreateIntentRequest("t1", 100000, "RUB", "SMS"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/intent/", recorded.path)
        // Бэкенд принимает только голый application/json (без charset) — см. ApiClient contentTypeFix.
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("i1", res.id)
    }
}
