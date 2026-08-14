package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ErrorMappingTest {

    @Test fun http_error_maps_to_YpApiException_with_status_and_server_message() = runTest {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"intent not found"}"""))
        try {
            YP.getIntent("nope", YpConfig(server.url("/").toString()))
            fail("должно бросить")
        } catch (e: YpApiException) {
            assertEquals(404, e.status)
            assertEquals("intent not found", e.message)
            assertTrue(e.body!!.contains("intent not found"))
        } finally { server.shutdown() }
    }

    @Test fun http_error_without_message_keeps_code_and_raw_body() = runTest {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try {
            YP.getIntent("i1", YpConfig(server.url("/").toString()))
            fail("должно бросить")
        } catch (e: YpApiException) {
            assertEquals(500, e.status)
            assertEquals("HTTP 500", e.message)
            assertEquals("oops", e.body)
        } finally { server.shutdown() }
    }

    @Test fun io_error_maps_to_YpNetworkException() = runTest {
        val server = MockWebServer(); server.start()
        val deadUrl = server.url("/").toString()
        server.shutdown()   // порт закрыт — соединение упадёт
        try {
            YP.getIntent("i1", YpConfig(deadUrl))
            fail("должно бросить")
        } catch (e: YpNetworkException) {
            assertEquals("network error", e.message)
        }
    }

    @Test fun api_and_network_exceptions_are_YpException_subtypes() {
        // Компиляция присваиваний фиксирует иерархию; runtime-достаточно равенства сообщений.
        val api: YpException = YpApiException(400, null, "api")
        val net: YpException = YpNetworkException("net")
        assertEquals("api", api.message)
        assertEquals("net", net.message)
    }
}
