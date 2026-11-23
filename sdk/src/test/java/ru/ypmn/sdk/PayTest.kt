package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
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

    @Test fun pay_with_card_puts_intent_id_in_cryptogram() = runTest {
        val i = intent()
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            kp.public.encoded.toByteString().base64() + "\n-----END PUBLIC KEY-----"
        server.enqueue(MockResponse().setBody("""{"publicKey":"$pem","version":2}"""))
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))

        i.pay(PayInput.Card(CardData("4652 0354 4066 7037", "12", "25", "123")))
        server.takeRequest() // GET public key
        val payBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val cryptogram = payBody["cryptogram"]!!.jsonPrimitive.content
        val envelope = Json.parseToJsonElement(cryptogram.decodeBase64()!!.utf8()).jsonObject
        assertEquals("i1", envelope["uuid"]!!.jsonPrimitive.content)
    }
}
