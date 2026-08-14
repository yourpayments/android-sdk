package ru.ypmn.sdk.internal.crypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import ru.ypmn.sdk.CardData
import ru.ypmn.sdk.YpException
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal object Cryptogram {
    private val json = Json { encodeDefaults = true }

    @Serializable private data class CardInfoDto(
        @SerialName("FirstSixDigits") val firstSix: String,
        @SerialName("LastFourDigits") val lastFour: String,
        @SerialName("ExpDateYear") val expYear: String,
        @SerialName("ExpDateMonth") val expMonth: String,
    )
    @Serializable private data class Envelope(
        @SerialName("Type") val type: String = "Card",
        @SerialName("BrowserInfoBase64") val browserInfoBase64: String = "",
        @SerialName("CardInfo") val cardInfo: CardInfoDto,
        @SerialName("KeyVersion") val keyVersion: Int,
        @SerialName("Value") val value: String,
    )

    fun build(publicKeyPem: String, card: CardData, browserInfoBase64: String = "", keyVersion: Int = 1): String {
        val pan = card.pan.filter(Char::isDigit)
        val cvv = card.cvv.filter(Char::isDigit)
        val month = card.expMonth.filter(Char::isDigit)
        val year = card.expYear.filter(Char::isDigit)
        if (pan.length < 13) throw YpException("cryptogram: invalid PAN")

        val value = encryptOaep(publicKeyPem, """{"PAN":"$pan","cvv":"$cvv"}""")
        val env = Envelope(
            browserInfoBase64 = browserInfoBase64,
            cardInfo = CardInfoDto(pan.take(6), pan.takeLast(4), year, month),
            keyVersion = keyVersion,
            value = value,
        )
        val envJson = json.encodeToString(Envelope.serializer(), env)
        return envJson.encodeUtf8().base64()
    }

    private fun encryptOaep(pem: String, plaintext: String): String {
        val clean = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val keyBytes = clean.decodeBase64()?.toByteArray() ?: throw YpException("cryptogram: bad public key")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, oaep)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).toByteString().base64()
    }
}
