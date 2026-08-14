package ru.ypmn.sdk

// Спека v1.0.0: Pending/Failed больше нет — неуспешная попытка возвращает интент в
// RequiresPaymentData/RequiresPaymentMethod; терминальны только Success и Expired.
enum class IntentStatus { RequiresPaymentData, RequiresPaymentMethod, Expired, Success;
    /** Терминальный статус: легальных переходов из него нет (спека v1.0.0). */
    val isTerminal: Boolean get() = this == Success || this == Expired
    companion object { fun from(raw: String): IntentStatus = entries.firstOrNull { it.name == raw } ?: RequiresPaymentData }
}

sealed class AltPayMethod(val type: String) {
    data object FasterPayments : AltPayMethod("FasterPayments")
    data object SberPay : AltPayMethod("SberPay")
    data object AlfaPay : AltPayMethod("AlfaPay")
    data object MirPay : AltPayMethod("MirPay")
    data object TPay : AltPayMethod("TPay")
    data object BNPL : AltPayMethod("BNPL")

    companion object {
        /**
         * Все методы (замена enum-овского entries). Намеренно геттер, а не поле:
         * поле инициализировалось бы во время <clinit> класса и при первом обращении
         * через сам data object словило бы цикл инициализации (companion ↔ object) →
         * null в списке. Геттер вычисляется в момент обращения, когда объекты готовы.
         */
        @JvmStatic
        val entries: List<AltPayMethod> get() = listOf(FasterPayments, SberPay, AlfaPay, MirPay, TPay, BNPL)

        /** Маппинг PaymentMethodDto.type (строка с бэка) → метод; null для неизвестных. */
        @JvmStatic
        fun fromType(type: String): AltPayMethod? = entries.firstOrNull { it.type == type }
    }
}

/**
 * [debugLogging] — отладочный лог в logcat под тегом `YpSdk` (`adb logcat -s YpSdk`):
 * HTTP-запросы/ответы целиком (заголовки и тела, без маскирования) и шаги SDK — pay,
 * смены статуса, тики waitForResult, 3DS. Только для отладки: в проде оставлять
 * выключенным, logcat приложения читают средства диагностики устройства.
 *
 * `@JvmOverloads` — чтобы Java-код с `new YpConfig(url)` и `new YpConfig(url, headers)`
 * продолжал компилироваться после добавления третьего параметра.
 */
data class YpConfig @JvmOverloads constructor(
    val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val debugLogging: Boolean = false,
)

data class CardData(val pan: String, val expMonth: String, val expYear: String, val cvv: String) {
    override fun toString(): String {
        val digits = pan.filter(Char::isDigit)
        val last4 = if (digits.length >= 4) digits.takeLast(4) else "****"
        return "CardData(pan=****$last4, exp=**/**, cvv=***)"
    }
}

/**
 * null-поле = «не менять». Очистка e-mail — через [clearReceiptEmail]
 * (web-sdk шлёт в PATCH `"/receiptEmail": null`; в Kotlin null-поле неотличимо от «не трогать»).
 */
data class UpdateChanges(
    val receiptEmail: String? = null,
    val tokenize: Boolean? = null,
    val clearReceiptEmail: Boolean = false,
)

sealed interface PayInput {
    data class Card(val card: CardData) : PayInput
    data class Cryptogram(val cryptogram: String) : PayInput
}

sealed interface PayResult {
    data class Authorized(val data: IntentStatusResponse) : PayResult
    data class ThreeDsRequired(val threeDsUrl: String) : PayResult
}

data class AltRequestOpts(val webview: Boolean = false, val puid: String? = null)
data class AltLinkOpts(val webview: Boolean = false, val puid: String? = null, val schema: String? = null)

/**
 * [puid] — client-generated UUID текущей попытки оплаты. Неуспех попытки не терминален
 * для интента (спека v1.0.0): он виден только по транзакции DECLINED с этим puid.
 * Тот же puid обязан уходить в getPaymentMethod/getLink/getImage — иначе транзакция
 * попытки придёт с puid=null и не сматчится. Без puid детект отказа выключен.
 */
data class WaitForResultOpts(val intervalMs: Long? = null, val timeoutMs: Long? = null, val puid: String? = null)

/**
 * Исход waitForResult: статусы интента + клиентский синтетический Declined
 * (DECLINED-транзакция с puid попытки; у самого интента статуса неуспеха нет).
 * Зеркало web-sdk WaitForResultStatus = IntentStatus | 'Declined'.
 */
enum class WaitForResultStatus {
    RequiresPaymentData, RequiresPaymentMethod, Expired, Success, Declined;

    companion object {
        @JvmStatic
        fun from(status: IntentStatus): WaitForResultStatus = when (status) {
            IntentStatus.RequiresPaymentData -> RequiresPaymentData
            IntentStatus.RequiresPaymentMethod -> RequiresPaymentMethod
            IntentStatus.Expired -> Expired
            IntentStatus.Success -> Success
        }
    }
}

sealed interface AltPayFlow {
    val method: AltPayMethod
    val link: String?
    fun getImage(): String
    suspend fun getLink(opts: AltLinkOpts = AltLinkOpts()): String
    interface SberPayFlow : AltPayFlow { suspend fun sendSms(phone: String) }
    interface FasterPaymentsFlow : AltPayFlow { val banks: List<SbpBank> }
    interface GenericAltFlow : AltPayFlow
}
