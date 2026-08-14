package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AltPayTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private suspend fun intentWith(methods: String): Intent {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"s","paymentMethods":$methods}"""))
        val i = YP.createIntent(CreateIntentRequest("t1", 1, "RUB", "SMS"), YpConfig(server.url("/").toString()))
        server.takeRequest(); return i
    }

    @Test fun fasterPayments_exposes_banks_and_getLink() = runTest {
        // Сервер шлёт ключ логотипа как "logoURL" (заглавные) — фикстура обязана его повторять,
        // иначе регресс @SerialName не поймается.
        val i = intentWith("""[{"type":"FasterPayments","group":"FastPayment","image":"https://qr","banks":[{"bankName":"Sber","logoURL":"https://logo","schema":"bank100000"}]}]""")
        val flow = i.getPaymentMethod(AltPayMethod.FasterPayments)   // перегрузка сужает до FasterPaymentsFlow — без каста
        assertEquals(1, flow.banks.size)
        assertEquals("https://logo", flow.banks[0].logoUrl)
        assertEquals("https://qr", flow.getImage())
        server.enqueue(MockResponse().setBody("https://deeplink"))
        val link = flow.getLink(AltLinkOpts(schema = flow.banks[0].schema))
        val req = server.takeRequest()
        assertEquals("/api/intent/alt/i1/link/FasterPayments/?schema=bank100000", req.path)
        assertEquals("https://deeplink", link)
    }

    // Боевой /alt/.../link/ отдаёт application/json со СТРОКОВЫМ ЛИТЕРАЛОМ — ссылка
    // приезжает в кавычках. Их обязано снимать SDK: с кавычками Uri.parse на стороне
    // приложения даёт URI без схемы, и startActivity падает в ActivityNotFoundException.
    @Test fun getLink_unwraps_json_string_literal() = runTest {
        val i = intentWith("""[{"type":"FasterPayments","banks":[{"bankName":"Sber","schema":"bank100000000111"}]}]""")
        val flow = i.getPaymentMethod(AltPayMethod.FasterPayments)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("\"https://simulators.sandbox.ypmn.ru/faster_payments/pay?mdOrder=686a692d\""),
        )
        val link = flow.getLink(AltLinkOpts(schema = "bank100000000111"))
        assertEquals("https://simulators.sandbox.ypmn.ru/faster_payments/pay?mdOrder=686a692d", link)
    }

    // Диплинк нативного приложения (MirPay) — тот же литерал, схема не http.
    @Test fun getLink_unwraps_native_scheme_deeplink() = runTest {
        val i = intentWith("""[{"type":"MirPay","link":"https://a"}]""")
        val flow = i.getPaymentMethod(AltPayMethod.MirPay)
        server.enqueue(MockResponse().setBody("\"mirpay://pay.mironline.ru/inapp/eyJhbGciOiJQUzI1NiJ9\"\n"))
        assertEquals("mirpay://pay.mironline.ru/inapp/eyJhbGciOiJQUzI1NiJ9", flow.getLink())
    }

    // Пустое тело — отдельная ошибка, а не «ссылка» из пустой строки: иначе приложение
    // уходит открывать пустой URI и получает невнятный ActivityNotFoundException.
    @Test fun getLink_rejects_empty_body() = runTest {
        val i = intentWith("""[{"type":"AlfaPay","link":"https://a"}]""")
        val flow = i.getPaymentMethod(AltPayMethod.AlfaPay)
        server.enqueue(MockResponse().setBody(""))
        try {
            flow.getLink()
            fail("ожидали YpException на пустом теле")
        } catch (e: YpException) {
            assertTrue(e.message, e.message!!.contains("пустую ссылку"))
        }
    }

    @Test fun fromType_maps_known_and_unknown() {
        assertEquals(AltPayMethod.SberPay, AltPayMethod.fromType("SberPay"))
        assertEquals(AltPayMethod.FasterPayments, AltPayMethod.fromType("FasterPayments"))
        assertNull(AltPayMethod.fromType("Nope"))
    }

    @Test fun generic_method_returns_base_flow() = runTest {
        val i = intentWith("""[{"type":"AlfaPay","link":"https://a"}]""")
        // У AlfaPay нет перегрузки-сужения → базовый getPaymentMethod возвращает AltPayFlow (конкретно GenericAltFlow).
        val flow = i.getPaymentMethod(AltPayMethod.AlfaPay)
        assertTrue(flow is AltPayFlow.GenericAltFlow)
    }

    @Test fun getImage_with_puid_builds_alt_url_without_it_returns_embedded() = runTest {
        val i = intentWith("""[{"type":"FasterPayments","image":"https://embedded/qr"}]""")
        val base = server.url("/").toString().trimEnd('/')
        val withPuid = i.getPaymentMethod(AltPayMethod.FasterPayments, AltRequestOpts(puid = "p-9"))
        assertEquals("$base/api/intent/alt/i1/image/FasterPayments/?puid=p-9", withPuid.getImage())
        val plain = i.getPaymentMethod(AltPayMethod.FasterPayments)
        assertEquals("https://embedded/qr", plain.getImage())
    }

    @Test fun waitForResult_polls_until_success() = runTest {
        val i = intentWith("[]")
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"RequiresPaymentData"},"transactions":[]}"""))
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))
        val status = i.waitForResult(WaitForResultOpts(intervalMs = 0, timeoutMs = 10_000))
        assertEquals(WaitForResultStatus.Success, status)
    }
}
