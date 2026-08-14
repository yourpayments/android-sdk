package ru.ypmn.sdk
import kotlinx.coroutines.flow.Flow

interface Intent {
    val id: String
    val status: IntentStatus
    val data: IntentResponse
    val events: Flow<IntentEvent>

    suspend fun update(changes: UpdateChanges)
    suspend fun getStatus(): IntentStatus
    /** Полный ответ /status/ (статус + транзакции) с синхронизацией статуса сессии. */
    suspend fun getStatusDetails(): IntentStatusResponse
    suspend fun pay(input: PayInput): PayResult
    fun create3dsView(result: PayResult.ThreeDsRequired): ThreeDsView
    suspend fun getPaymentMethod(method: AltPayMethod, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow
    // Перегрузки-сужения (как string-literal overloads в web-sdk): литерал метода → конкретный флоу без каста.
    suspend fun getPaymentMethod(method: AltPayMethod.FasterPayments, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.FasterPaymentsFlow
    suspend fun getPaymentMethod(method: AltPayMethod.SberPay, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.SberPayFlow
    suspend fun waitForResult(opts: WaitForResultOpts = WaitForResultOpts()): WaitForResultStatus

    fun addEventListener(listener: ru.ypmn.sdk.java.IntentEventListener): ru.ypmn.sdk.java.Cancellable
    fun removeEventListener(listener: ru.ypmn.sdk.java.IntentEventListener)
}
