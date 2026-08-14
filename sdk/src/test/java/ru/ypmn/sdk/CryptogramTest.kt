package ru.ypmn.sdk
import kotlinx.serialization.json.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import ru.ypmn.sdk.internal.crypto.Cryptogram

class CryptogramTest {
    // Тестовый RSA-публичный ключ (PEM, SPKI). Сгенерировать: openssl genrsa 2048 → openssl rsa -pubout.
    private val pem = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArBZ1NNjvszen6BNWsgyD
UJvDUZDtvR4jKNQtEwW1iW7hqJr0TdD8hgTxw3DfH+Hi/7ZjSNdH5EfChvgVW9wtTxr
vUXCOyJndReq7qNMo94lHpoSIVW82dp4rcDB4kU+q+ekh5rj9Oj6EReCTuXr3foLLBV
pH0/z1vtgcCfQzsLlGkSTwgLqASTUsuzfI8viVUbxE1a+600hN0uBh/CYKoMnCp/Ehx
V8g7eUmNsWjZyiUrV8AA/5DgZUCB+jqGQT/Dhc8e21tAkQ3qan/jQ5i/QYocA/4jW3W
QAldMLj0PA36kINEbuDKq8qRh25v+k4qyjb7Xp4W2DywmNtG3Q20MQIDAQAB
-----END PUBLIC KEY-----"""

    @Test fun envelope_has_web_sdk_structure() {
        val card = CardData(pan = "4652 0354 4066 7037", expMonth = "12", expYear = "25", cvv = "123")
        val packet = Cryptogram.build(pem, card)
        // Внешняя обёртка — base64(JSON(конверт))
        val envJson = packet.decodeBase64()!!.utf8()
        val env = Json.parseToJsonElement(envJson).jsonObject
        assertEquals("Card", env["Type"]!!.jsonPrimitive.content)
        assertEquals("", env["BrowserInfoBase64"]!!.jsonPrimitive.content)     // как CloudPayments
        assertEquals(1, env["KeyVersion"]!!.jsonPrimitive.int)
        val ci = env["CardInfo"]!!.jsonObject
        assertEquals("465203", ci["FirstSixDigits"]!!.jsonPrimitive.content)   // пробелы вычищены
        assertEquals("7037", ci["LastFourDigits"]!!.jsonPrimitive.content)
        assertEquals("25", ci["ExpDateYear"]!!.jsonPrimitive.content)
        assertEquals("12", ci["ExpDateMonth"]!!.jsonPrimitive.content)
        // Value — непустой base64 (OAEP-шифртекст)
        assertNotNull(env["Value"]!!.jsonPrimitive.content.decodeBase64())
        assertTrue(env["Value"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test fun value_decrypts_to_pan_cvv_json() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubPem = "-----BEGIN PUBLIC KEY-----\n" +
            kp.public.encoded.toByteString().base64() + "\n-----END PUBLIC KEY-----"
        val card = CardData("4652 0354 4066 7037", "12", "25", "123")
        val packet = Cryptogram.build(pubPem, card)
        val env = Json.parseToJsonElement(packet.decodeBase64()!!.utf8()).jsonObject
        val cipherText = env["Value"]!!.jsonPrimitive.content.decodeBase64()!!.toByteArray()
        val oaep = javax.crypto.spec.OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT
        )
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp.private, oaep)
        val decrypted = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        assertEquals("""{"PAN":"4652035440667037","cvv":"123"}""", decrypted)
    }
}
