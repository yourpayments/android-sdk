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
import ru.ypmn.sdk.internal.api.httpLogInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class HttpLogTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun clientWith(lines: MutableList<String>, enabled: Boolean = true) = OkHttpClient.Builder()
        .addInterceptor(httpLogInterceptor(YpLogger(enabled) { _, m -> lines.add(m) }))
        .build()

    @Test fun logs_request_and_response_with_bodies() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"i1"}""").setHeader("Content-Type", "application/json"))
        val lines = mutableListOf<String>()
        val req = Request.Builder()
            .url(server.url("/api/intent/?x=1"))
            .header("X-Api-Key", "secret-value")
            .post("""{"amount":100000}""".toRequestBody("application/json".toMediaType()))
            .build()
        val body = clientWith(lines).newCall(req).execute().use { it.body?.string() }

        // Тело ответа должно остаться читаемым для вызывающего: интерцептор смотрит его через peekBody.
        assertEquals("""{"id":"i1"}""", body)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("→ POST /api/intent/?x=1"))
        assertTrue(lines[0], lines[0].contains("""{"amount":100000}"""))
        // Заголовки пишутся как есть — по решению об отладочном логе без маскирования.
        assertTrue(lines[0], lines[0].contains("X-Api-Key: secret-value"))
        assertTrue(lines[1], lines[1].startsWith("← 200 POST /api/intent/?x=1 ("))
        assertTrue(lines[1], lines[1].contains("""{"id":"i1"}"""))
    }

    @Test fun binary_response_body_is_described_not_dumped() = runTest {
        server.enqueue(MockResponse().setBody("PNG-байты").setHeader("Content-Type", "image/png"))
        val lines = mutableListOf<String>()
        clientWith(lines).newCall(Request.Builder().url(server.url("/qr.png")).build()).execute().close()
        assertTrue(lines[1], lines[1].contains("<image/png,"))
        assertTrue(lines[1], !lines[1].contains("PNG-байты"))
    }

    @Test fun network_failure_is_logged_as_warning() = runTest {
        val lines = mutableListOf<String>()
        val url = server.url("/api/intent/")
        server.shutdown()  // порт закрыт — connect упадёт
        runCatching {
            clientWith(lines).newCall(Request.Builder().url(url).build()).execute().close()
        }
        // Строка запроса пишется до отправки, поэтому у неудачи их две: "→" и "✗".
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("→ GET /api/intent/"))
        assertTrue(lines[1], lines[1].startsWith("✗ GET /api/intent/ failed after "))
    }

    @Test fun disabled_logging_writes_nothing() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val lines = mutableListOf<String>()
        clientWith(lines, enabled = false)
            .newCall(Request.Builder().url(server.url("/api/intent/")).build()).execute().close()
        assertEquals(emptyList<String>(), lines)
    }
}
