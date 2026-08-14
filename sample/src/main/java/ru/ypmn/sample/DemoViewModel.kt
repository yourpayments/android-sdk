package ru.ypmn.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.ypmn.sdk.AltLinkOpts
import ru.ypmn.sdk.AltPayFlow
import ru.ypmn.sdk.AltPayMethod
import ru.ypmn.sdk.CardData
import ru.ypmn.sdk.CreateIntentRequest
import ru.ypmn.sdk.Intent as SdkIntent
import ru.ypmn.sdk.IntentEvent
import ru.ypmn.sdk.PayInput
import ru.ypmn.sdk.PayResult
import ru.ypmn.sdk.YP
import ru.ypmn.sdk.YpConfig

enum class Lang { Kotlin, Java }

sealed class Screen {
    object Config : Screen()
    object Methods : Screen()
}

class DemoViewModel : ViewModel() {
    var baseUrl by mutableStateOf("https://sandbox.ypmn.ru")
    var json by mutableStateOf(EXAMPLE_JSON)
    var screen by mutableStateOf<Screen>(Screen.Config)
    var intent: SdkIntent? = null
    var lastResult by mutableStateOf("")
    var busy by mutableStateOf(false)
    var threeDsRequired by mutableStateOf<PayResult.ThreeDsRequired?>(null)
    var fasterPaymentsFlow by mutableStateOf<AltPayFlow.FasterPaymentsFlow?>(null)

    /**
     * Последняя полученная ссылка альт-оплаты. Показываем её отдельной карточкой:
     * приложения банка на устройстве может не быть (на эмуляторе его нет никогда),
     * и тогда единственный способ довести оплату — скопировать ссылку.
     */
    var lastLink by mutableStateOf<String?>(null)

    /** Глобальный язык код-сниппетов. */
    var lang by mutableStateOf(Lang.Kotlin)

    /** Живая лента событий из intent.events (новейшие сверху). */
    var events by mutableStateOf<List<String>>(emptyList())

    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    fun createIntent() {
        viewModelScope.launch {
            busy = true
            lastResult = ""
            events = emptyList()
            try {
                val req = jsonDecoder.decodeFromString(CreateIntentRequest.serializer(), json)
                // debugLogging — весь обмен с бэкендом в logcat: adb logcat -s YpSdk
                val result = YP.createIntent(req, YpConfig(baseUrl, debugLogging = true))
                intent = result
                // Новое API событий: подписываемся на жизненный цикл интента
                result.events
                    .onEach { ev -> events = (listOf(formatEvent(ev)) + events).take(8) }
                    .launchIn(viewModelScope)
                screen = Screen.Methods
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun formatEvent(ev: IntentEvent): String = when (ev) {
        is IntentEvent.StatusChange -> "StatusChange → ${ev.status}"
        is IntentEvent.Success -> "Success ✓"
        is IntentEvent.Error -> "Error — ${ev.error.message}"
        is IntentEvent.Update -> "Update"
    }

    fun payCard(pan: String, expMonth: String, expYear: String, cvv: String) {
        viewModelScope.launch {
            busy = true
            lastResult = ""
            threeDsRequired = null
            try {
                val result = intent!!.pay(PayInput.Card(CardData(pan, expMonth, expYear, cvv)))
                when (result) {
                    is PayResult.Authorized -> lastResult = "Authorized: ${result.data.intent.status}"
                    is PayResult.ThreeDsRequired -> threeDsRequired = result
                }
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun fetchSbp() {
        viewModelScope.launch {
            busy = true
            lastResult = ""
            lastLink = null
            fasterPaymentsFlow = null
            try {
                val flow = intent!!.getPaymentMethod(AltPayMethod.FasterPayments)   // перегрузка сужает до FasterPaymentsFlow
                fasterPaymentsFlow = flow
                lastResult = "СБП: ${flow.banks.size} банков"
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Диплинк выбранного банка СБП: кладём в [lastLink] и отдаём в [onReady] для открытия. */
    fun fetchBankLink(schema: String, onReady: (String) -> Unit) {
        val flow = fasterPaymentsFlow ?: return
        viewModelScope.launch {
            busy = true
            lastResult = ""
            lastLink = null
            try {
                val link = flow.getLink(AltLinkOpts(schema = schema))
                lastLink = link
                onReady(link)
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun sendSms(phone: String) {
        viewModelScope.launch {
            busy = true
            lastResult = ""
            try {
                val sber = intent!!.getPaymentMethod(AltPayMethod.SberPay)   // перегрузка сужает до SberPayFlow
                sber.sendSms(phone)
                lastResult = "SMS отправлен на $phone"
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun fetchGenericAlt(type: String) {
        val method = AltPayMethod.fromType(type) ?: run {
            lastResult = "Неизвестный тип: $type"
            return
        }
        viewModelScope.launch {
            busy = true
            lastResult = ""
            lastLink = null
            try {
                val flow = intent!!.getPaymentMethod(method)
                lastLink = flow.getLink(AltLinkOpts())
            } catch (e: Throwable) {
                lastResult = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun resetForNewIntent() {
        intent = null
        threeDsRequired = null
        fasterPaymentsFlow = null
        lastResult = ""
        lastLink = null
        events = emptyList()
        screen = Screen.Config
    }
}
