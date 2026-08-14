package ru.ypmn.sdk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class CreateIntentRequest(
    val merchantCode: String,
    val amount: Long,
    val currency: String,
    val messageScheme: String,            // "SMS" | "DMS"
    val culture: String? = null,          // "ru-RU" | "en-US"
    val description: String? = null,
    val merchantPaymentReference: String? = null,
    val receiptEmail: String? = null,
    val accountId: String? = null,
    val autoClose: Int? = null,
    val orderTimeout: Int? = null,
    val retryPayment: Boolean? = null,
    val tokenize: Boolean? = null,
    /** Сценарий оплаты ("Widget" | "WebSDK" | "AndroidSDK" | "IoSSDK"). null → YP.createIntent подставит "AndroidSDK". */
    val scenario: String? = null,
    // Поля хостед-страниц (widget/checkout): бэкенд их принимает из любого канала,
    // нативному SDK обычно не нужны — оставлены для полного паритета контракта.
    val skin: String? = null,             // "Classic" | "Modern"
    val emailBehavior: String? = null,    // "Required" | "Hidden" | "Optional"
    val paymentUrl: String? = null,
    val successRedirectUrl: String? = null,
    val failRedirectUrl: String? = null,
    val restrictedPaymentMethods: List<String>? = null,
    val items: List<ProductItem>? = null,
    val receipts: JsonObject? = null,
    val userInfo: UserInfo? = null,
    val metaData: JsonObject? = null,
) {
    /** Java-эргономика: из Kotlin используйте именованные аргументы конструктора. */
    class Builder(
        private val merchantCode: String,
        private val amount: Long,
        private val currency: String,
        private val messageScheme: String,
    ) {
        private var culture: String? = null
        private var description: String? = null
        private var merchantPaymentReference: String? = null
        private var receiptEmail: String? = null
        private var accountId: String? = null
        private var autoClose: Int? = null
        private var orderTimeout: Int? = null
        private var retryPayment: Boolean? = null
        private var tokenize: Boolean? = null
        private var scenario: String? = null
        private var skin: String? = null
        private var emailBehavior: String? = null
        private var paymentUrl: String? = null
        private var successRedirectUrl: String? = null
        private var failRedirectUrl: String? = null
        private var restrictedPaymentMethods: List<String>? = null
        private var items: List<ProductItem>? = null
        private var receipts: JsonObject? = null
        private var userInfo: UserInfo? = null
        private var metaData: JsonObject? = null

        fun culture(v: String?) = apply { culture = v }
        fun description(v: String?) = apply { description = v }
        fun merchantPaymentReference(v: String?) = apply { merchantPaymentReference = v }
        fun receiptEmail(v: String?) = apply { receiptEmail = v }
        fun accountId(v: String?) = apply { accountId = v }
        fun autoClose(v: Int?) = apply { autoClose = v }
        fun orderTimeout(v: Int?) = apply { orderTimeout = v }
        fun retryPayment(v: Boolean?) = apply { retryPayment = v }
        fun tokenize(v: Boolean?) = apply { tokenize = v }
        fun scenario(v: String?) = apply { scenario = v }
        fun skin(v: String?) = apply { skin = v }
        fun emailBehavior(v: String?) = apply { emailBehavior = v }
        fun paymentUrl(v: String?) = apply { paymentUrl = v }
        fun successRedirectUrl(v: String?) = apply { successRedirectUrl = v }
        fun failRedirectUrl(v: String?) = apply { failRedirectUrl = v }
        fun restrictedPaymentMethods(v: List<String>?) = apply { restrictedPaymentMethods = v }
        fun items(v: List<ProductItem>?) = apply { items = v }
        fun receipts(v: JsonObject?) = apply { receipts = v }
        fun userInfo(v: UserInfo?) = apply { userInfo = v }
        fun metaData(v: JsonObject?) = apply { metaData = v }

        fun build() = CreateIntentRequest(
            merchantCode = merchantCode, amount = amount, currency = currency,
            messageScheme = messageScheme, culture = culture, description = description,
            merchantPaymentReference = merchantPaymentReference, receiptEmail = receiptEmail, accountId = accountId,
            autoClose = autoClose, orderTimeout = orderTimeout, retryPayment = retryPayment,
            tokenize = tokenize, scenario = scenario, skin = skin, emailBehavior = emailBehavior,
            paymentUrl = paymentUrl, successRedirectUrl = successRedirectUrl,
            failRedirectUrl = failRedirectUrl, restrictedPaymentMethods = restrictedPaymentMethods,
            items = items, receipts = receipts, userInfo = userInfo, metaData = metaData,
        )
    }

    companion object {
        @JvmStatic
        fun builder(merchantCode: String, amount: Long, currency: String, messageScheme: String) =
            Builder(merchantCode, amount, currency, messageScheme)
    }
}

@Serializable
data class ProductItem(
    val name: String,
    val sku: String,
    val unitPrice: String,
    val quantity: String,
    val additionalDetails: String? = null,
    val vat: String? = null,              // "0" | "5" | "7" | "10" | "22"
    val marketplace: Marketplace? = null,
)

@Serializable data class Marketplace(val merchantCode: String)

@Serializable data class UserInfo(val billing: Billing? = null)

@Serializable
data class Billing(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val countryCode: String? = null,
    val phone: String? = null,
    val city: String? = null,
    val state: String? = null,
    val companyName: String? = null,
    val taxId: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val zipCode: String? = null,
)

@Serializable
data class IntentResponse(
    val id: String,
    val status: String,
    val secret: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val expiredAt: Long = 0,
    val merchantCode: String = "",
    val amount: Long = 0,
    val currency: String = "",
    val messageScheme: String = "",
    val description: String? = null,
    val receiptEmail: String? = null,
    val tokenize: Boolean? = null,
    val paymentMethods: List<PaymentMethodDto>? = null,
    val transactions: List<Transaction>? = null,
)

// Спека v1.0.0: CardPaymentMethod | QrPaymentMethod | SberPayMethod; MirPay отдаёт link
// (deeplink убран), SberPay — обязательные deepLinks (схемы нативных приложений).
@Serializable
data class PaymentMethodDto(
    val type: String,
    val group: String? = null,            // "Card" | "FastPayment" | "Installments"
    val link: String? = null,
    val image: String? = null,
    val networks: List<String>? = null,   // Card: "Visa" | "MasterCard" | "Mir"
    val capabilities: List<String>? = null,
    val primary: Boolean? = null,
    val deepLinks: List<String>? = null,
    val banks: List<SbpBank>? = null,
    /** Данные рассрочек: Podeli (options), TcsInstallment (periods/shopId/showCaseId). */
    val data: PaymentMethodDataDto? = null,
)

@Serializable
data class PaymentMethodDataDto(
    val options: PodeliOptions? = null,
    val periods: List<String>? = null,
    val shopId: String? = null,
    val showCaseId: String? = null,
)

@Serializable data class PodeliOptions(val minLimit: Double, val maxLimit: Double)

@Serializable
data class Transaction(
    val id: Long,
    // "PENDING" | "AUTHORIZED" | "DECLINED". DECLINED отсутствует в openapi v1.0.0
    // (спека отстаёт), но подтверждён бэкендом — по нему waitForResult(puid) детектит
    // неуспех попытки.
    val status: String,
    val puid: String? = null,
)

// Ключи банка расходятся между openapi и боевым ответом, поэтому принимаем оба
// написания: спека обещает logoUrl/package_name и строковый isWebClientActive,
// а sandbox отдаёт logoURL/packageName и JSON-булево. Строгий разбор ронял весь
// createIntent («invalid response») на любом интенте с СБП.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SbpBank(
    val bankName: String,
    @SerialName("logoURL") @JsonNames("logoUrl") val logoUrl: String? = null,
    val schema: String,
    val webClientUrl: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val isWebClientActive: Boolean? = null,
    @JsonNames("package_name") val packageName: String? = null,
)

/** Флаг, который бэкенд шлёт то JSON-булевым, то строкой "true"/"false". */
internal object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("ru.ypmn.sdk.LenientBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: primitive.content.equals("true", ignoreCase = true)
    }
}

@Serializable data class IntentStatusData(val status: String)
@Serializable data class IntentStatusResponse(val intent: IntentStatusData, val transactions: List<Transaction> = emptyList())
