package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PayTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private suspend fun intent(): Intent {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"s"}"""))
        val i = YP.createIntent(CreateIntentRequest("t1", 1, "RUB", "SMS"), YpConfig(server.url("/").toString()))
        server.takeRequest(); return i
    }

    @Test fun pay_with_cryptogram_authorized() = runTest {
        val i = intent()
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))
        val r = i.pay(PayInput.Cryptogram("CRYPTO"))
        assertTrue(r is PayResult.Authorized)
        // Тело pay обязано содержать paymentMethod (бэкенд: "paymentMethod must be present");
        // encodeDefaults=false не должен выкидывать его → поле без дефолта.
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"paymentMethod\":\"Card\""))
    }

    @Test fun pay_returns_3ds_required() = runTest {
        val i = intent()
        server.enqueue(MockResponse().setBody("""{"threeDsUrl":"https://acs.example/3ds"}"""))
        val r = i.pay(PayInput.Cryptogram("CRYPTO"))
        assertEquals(PayResult.ThreeDsRequired("https://acs.example/3ds"), r)
    }
}
