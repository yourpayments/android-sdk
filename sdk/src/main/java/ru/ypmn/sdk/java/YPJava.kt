package ru.ypmn.sdk.java
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.launch
import ru.ypmn.sdk.AltLinkOpts
import ru.ypmn.sdk.AltPayFlow
import ru.ypmn.sdk.AltPayMethod
import ru.ypmn.sdk.Intent
import ru.ypmn.sdk.IntentStatus
import ru.ypmn.sdk.PayInput
import ru.ypmn.sdk.PayResult
import ru.ypmn.sdk.UpdateChanges
import ru.ypmn.sdk.WaitForResultOpts
import ru.ypmn.sdk.YP
import ru.ypmn.sdk.YpConfig
import ru.ypmn.sdk.CreateIntentRequest

private fun <T> dispatchCallback(cb: YpCallback<T>, block: suspend () -> T): Cancellable {
    val job = facadeScope.launch {
        try { cb.onSuccess(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { cb.onError(e) }
    }
    return Cancellable { job.cancel() }
}

// CompletableFuture вместо Guava ListenableFuture: minSdk 24 позволяет, а
// kotlinx-coroutines-guava тянул весь Guava в дерево зависимостей потребителей.
private fun <T> dispatchFuture(block: suspend () -> T): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    val job = facadeScope.launch {
        try { future.complete(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { future.cancel(false); throw e }
        catch (e: Throwable) { future.completeExceptionally(e) }
    }
    future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
    return future
}

object YPJava {
    @JvmStatic
    fun createIntent(request: CreateIntentRequest, config: YpConfig, cb: YpCallback<Intent>): Cancellable =
        dispatchCallback(cb) { YP.createIntent(request, config) }

    @JvmStatic
    fun createIntentFuture(request: CreateIntentRequest, config: YpConfig): CompletableFuture<Intent> =
        dispatchFuture { YP.createIntent(request, config) }

    /** Восстановление интента по id из Java. */
    @JvmStatic
    fun getIntent(id: String, config: YpConfig, cb: YpCallback<Intent>): Cancellable =
        dispatchCallback(cb) { YP.getIntent(id, config) }

    @JvmStatic
    fun getIntentFuture(id: String, config: YpConfig): CompletableFuture<Intent> =
        dispatchFuture { YP.getIntent(id, config) }
}

fun Intent.payAsync(input: PayInput, cb: YpCallback<PayResult>): Cancellable = dispatchCallback(cb) { pay(input) }
fun Intent.updateAsync(changes: UpdateChanges, cb: YpCallback<Unit>): Cancellable = dispatchCallback(cb) { update(changes) }
fun Intent.getStatusAsync(cb: YpCallback<IntentStatus>): Cancellable = dispatchCallback(cb) { getStatus() }
fun Intent.getStatusDetailsAsync(cb: YpCallback<ru.ypmn.sdk.IntentStatusResponse>): Cancellable = dispatchCallback(cb) { getStatusDetails() }
fun Intent.waitForResultAsync(opts: WaitForResultOpts, cb: YpCallback<ru.ypmn.sdk.WaitForResultStatus>): Cancellable = dispatchCallback(cb) { waitForResult(opts) }

// Альт-флоу (СБП / SberPay / прочие) — Java-обёртки над suspend-API ядра.
fun Intent.getPaymentMethodAsync(method: AltPayMethod, cb: YpCallback<AltPayFlow>): Cancellable = dispatchCallback(cb) { getPaymentMethod(method) }
// Перегрузки-сужения для Java: литерал метода → колбэк с конкретным флоу без каста.
fun Intent.getPaymentMethodAsync(method: AltPayMethod.FasterPayments, cb: YpCallback<AltPayFlow.FasterPaymentsFlow>): Cancellable = dispatchCallback(cb) { getPaymentMethod(method) }
fun Intent.getPaymentMethodAsync(method: AltPayMethod.SberPay, cb: YpCallback<AltPayFlow.SberPayFlow>): Cancellable = dispatchCallback(cb) { getPaymentMethod(method) }
fun AltPayFlow.getLinkAsync(opts: AltLinkOpts, cb: YpCallback<String>): Cancellable = dispatchCallback(cb) { getLink(opts) }
fun AltPayFlow.SberPayFlow.sendSmsAsync(phone: String, cb: YpCallback<Unit>): Cancellable = dispatchCallback(cb) { sendSms(phone) }
