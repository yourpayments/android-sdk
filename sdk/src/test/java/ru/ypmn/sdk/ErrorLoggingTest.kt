package ru.ypmn.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.ypmn.sdk.internal.YpLogger
import ru.ypmn.sdk.internal.api.ApiClient
import ru.ypmn.sdk.internal.api.apiCall

/**
 * Ошибки обязаны быть видны в logcat: HTTP-лог показывает ответ, но не то, во что SDK
 * его превратил, а неразобранный ответ по HTTP выглядит как обычная удачная 200.
 */
class ErrorLoggingTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun api(lines: MutableList<String>) =
        ApiClient.create(YpConfig(baseUrl = server.url("/").toString())) to YpLogger(true) { _, m -> lines.add(m) }

    @Test fun api_error_is_logged_with_code_and_server_message() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Request body must be valid JSON"}"""))
        val lines = mutableListOf<String>()
        val (service, log) = api(lines)
        val thrown = runCatching {
            apiCall(log) { service.createIntent(CreateIntentRequest("t1", 100000, "RUB", "SMS")) }
        }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is YpApiException)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("YpApiException code=400"))
        assertTrue(lines[0], lines[0].contains("Request body must be valid JSON"))
    }

    @Test fun unparsable_response_is_logged() = runTest {
        // HTTP 200 с телом не той формы: по HTTP-логу это успех, провал виден только здесь.
        server.enqueue(MockResponse().setBody("""{"unexpected":true}"""))
        val lines = mutableListOf<String>()
        val (service, log) = api(lines)
        val thrown = runCatching {
            apiCall(log) { service.createIntent(CreateIntentRequest("t1", 100000, "RUB", "SMS")) }
        }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is YpException)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("apiCall: ответ не разобран"))
    }

    @Test fun network_error_is_logged() = runTest {
        val lines = mutableListOf<String>()
        val (service, log) = api(lines)
        server.shutdown()  // порт закрыт
        val thrown = runCatching { apiCall(log) { service.getIntent("i1") } }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is YpNetworkException)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("apiCall: YpNetworkException —"))
    }

    @Test fun disabled_logging_stays_silent_on_errors() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val lines = mutableListOf<String>()
        val service = ApiClient.create(YpConfig(baseUrl = server.url("/").toString()))
        runCatching {
            apiCall(YpLogger(false) { _, m -> lines.add(m) }) { service.getIntent("i1") }
        }
        assertEquals(emptyList<String>(), lines)
    }
}
