package ru.ypmn.sdk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test fun createIntentRequest_serializes_required_fields() {
        val req = CreateIntentRequest(merchantCode = "t1", amount = 100000, currency = "RUB", messageScheme = "SMS")
        val s = json.encodeToString(CreateIntentRequest.serializer(), req)
        assertEquals("""{"merchantCode":"t1","amount":100000,"currency":"RUB","messageScheme":"SMS"}""", s)
    }

    @Test fun createIntentRequest_serializes_fiscal_and_meta_fields() {
        val req = CreateIntentRequest(
            merchantCode = "t1", amount = 100000, currency = "RUB", messageScheme = "SMS",
            retryPayment = true, orderTimeout = 900,
            items = listOf(ProductItem(
                name = "Товар", sku = "sku-1", unitPrice = "1000.00", quantity = "1",
                vat = "22", marketplace = Marketplace("mc-1"),
            )),
            userInfo = UserInfo(billing = Billing(firstName = "Иван", email = "a@b.c")),
            metaData = kotlinx.serialization.json.buildJsonObject {
                put("orderId", kotlinx.serialization.json.JsonPrimitive("o-1"))
            },
        )
        val s = json.encodeToString(CreateIntentRequest.serializer(), req)
        assertTrue(s, s.contains(""""retryPayment":true"""))
        assertTrue(s, s.contains(""""orderTimeout":900"""))
        assertTrue(s, s.contains(""""sku":"sku-1""""))
        assertTrue(s, s.contains(""""marketplace":{"merchantCode":"mc-1"}"""))
        assertTrue(s, s.contains(""""billing":{"firstName":"Иван","email":"a@b.c"}"""))
        assertTrue(s, s.contains(""""metaData":{"orderId":"o-1"}"""))
    }

    @Test fun paymentMethodDto_parses_networks_and_installment_data() {
        val raw = """[
            {"type":"Card","group":"Card","networks":["Visa","Mir"],"capabilities":["domestic"]},
            {"type":"Podeli","group":"Installments","link":"https://p","data":{"options":{"minLimit":3000,"maxLimit":60000}}},
            {"type":"TcsInstallment","group":"Installments","data":{"periods":["3","6"],"shopId":"sh-1","showCaseId":"sc-1"}}
        ]"""
        val methods = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PaymentMethodDto.serializer()), raw)
        assertEquals(listOf("Visa", "Mir"), methods[0].networks)
        assertEquals(3000.0, methods[1].data!!.options!!.minLimit, 0.0)
        assertEquals(60000.0, methods[1].data!!.options!!.maxLimit, 0.0)
        assertEquals(listOf("3", "6"), methods[2].data!!.periods)
        assertEquals("sh-1", methods[2].data!!.shopId)
        assertEquals("sc-1", methods[2].data!!.showCaseId)
    }

    // Фикстура снята с боевого sandbox.ypmn.ru: isWebClientActive приходит JSON-булевым
    // (не строкой), имя пакета — camelCase "packageName". Расхождение с DTO валит разбор
    // всего интента ещё в createIntent.
    @Test fun sbpBank_parses_real_backend_shape() {
        val raw = """{"bankName":"Сбербанк","isWebClientActive":false,""" +
            """"logoURL":"https://qr.nspk.ru/proxyapp/logo/bank100000000111.png",""" +
            """"packageName":"ru.sberbankmobile","schema":"bank100000000111","webClientUrl":null}"""
        val bank = json.decodeFromString(SbpBank.serializer(), raw)
        assertEquals("Сбербанк", bank.bankName)
        assertEquals("https://qr.nspk.ru/proxyapp/logo/bank100000000111.png", bank.logoUrl)
        assertEquals("ru.sberbankmobile", bank.packageName)
        assertEquals(false, bank.isWebClientActive)
    }

    @Test fun intentResponse_parses_minimal() {
        val raw = """{"id":"i1","status":"RequiresPaymentData","secret":"s","createdAt":1,"updatedAt":1,"expiredAt":1,"merchantCode":"t1","amount":100000,"currency":"RUB","messageScheme":"SMS"}"""
        val r = json.decodeFromString(IntentResponse.serializer(), raw)
        assertEquals("i1", r.id)
        assertEquals("RequiresPaymentData", r.status)
    }
}
