package ru.ypmn.sdk.internal
import kotlinx.coroutines.flow.Flow
import ru.ypmn.sdk.Intent
import ru.ypmn.sdk.IntentEvent
import ru.ypmn.sdk.IntentStatus
import ru.ypmn.sdk.UpdateChanges
import ru.ypmn.sdk.IntentResponse

internal class IntentHandle(private val s: IntentSession) : Intent {
    override val id: String get() = s.data.id
    override val status: IntentStatus get() = IntentStatus.from(s.data.status)
    override val data: IntentResponse get() = s.data
    override val events: Flow<IntentEvent> get() = s.events

    override suspend fun update(changes: UpdateChanges) = ru.ypmn.sdk.internal.operations.update(s, changes)
    override suspend fun getStatus(): IntentStatus = ru.ypmn.sdk.internal.operations.getStatus(s)
    override suspend fun getStatusDetails(): ru.ypmn.sdk.IntentStatusResponse =
        ru.ypmn.sdk.internal.operations.getStatusDetails(s)
    override suspend fun pay(input: ru.ypmn.sdk.PayInput): ru.ypmn.sdk.PayResult =
        ru.ypmn.sdk.internal.operations.pay(s, input)
    override fun create3dsView(result: ru.ypmn.sdk.PayResult.ThreeDsRequired): ru.ypmn.sdk.ThreeDsView =
        ThreeDsViewImpl(s, result.threeDsUrl)
    override suspend fun getPaymentMethod(method: ru.ypmn.sdk.AltPayMethod, opts: ru.ypmn.sdk.AltRequestOpts): ru.ypmn.sdk.AltPayFlow =
        ru.ypmn.sdk.internal.operations.getPaymentMethod(s, method, opts)
    // Перегрузки-сужения: ядро по этому методу гарантированно строит соответствующий подтип.
    override suspend fun getPaymentMethod(method: ru.ypmn.sdk.AltPayMethod.FasterPayments, opts: ru.ypmn.sdk.AltRequestOpts): ru.ypmn.sdk.AltPayFlow.FasterPaymentsFlow =
        ru.ypmn.sdk.internal.operations.getPaymentMethod(s, method, opts) as ru.ypmn.sdk.AltPayFlow.FasterPaymentsFlow
    override suspend fun getPaymentMethod(method: ru.ypmn.sdk.AltPayMethod.SberPay, opts: ru.ypmn.sdk.AltRequestOpts): ru.ypmn.sdk.AltPayFlow.SberPayFlow =
        ru.ypmn.sdk.internal.operations.getPaymentMethod(s, method, opts) as ru.ypmn.sdk.AltPayFlow.SberPayFlow
    override suspend fun waitForResult(opts: ru.ypmn.sdk.WaitForResultOpts): ru.ypmn.sdk.WaitForResultStatus =
        ru.ypmn.sdk.internal.operations.waitForResult(s, opts)
    override fun addEventListener(listener: ru.ypmn.sdk.java.IntentEventListener): ru.ypmn.sdk.java.Cancellable =
        ru.ypmn.sdk.java.bridgeListener(s, listener)
    override fun removeEventListener(listener: ru.ypmn.sdk.java.IntentEventListener) =
        ru.ypmn.sdk.java.unbridgeListener(listener)
}

internal fun newHandle(s: IntentSession): Intent {
    val h = IntentHandle(s)
    s.handle = h
    return h
}
