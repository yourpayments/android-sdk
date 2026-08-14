package ru.ypmn.sdk.internal.operations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.ypmn.sdk.AltLinkOpts
import ru.ypmn.sdk.AltPayFlow
import ru.ypmn.sdk.AltPayMethod
import ru.ypmn.sdk.AltRequestOpts
import ru.ypmn.sdk.IntentEvent
import ru.ypmn.sdk.IntentStatus
import ru.ypmn.sdk.IntentStatusData
import ru.ypmn.sdk.IntentStatusResponse
import ru.ypmn.sdk.PayInput
import ru.ypmn.sdk.PayResult
import ru.ypmn.sdk.UpdateChanges
import ru.ypmn.sdk.WaitForResultOpts
import ru.ypmn.sdk.WaitForResultStatus
import ru.ypmn.sdk.YpException
import ru.ypmn.sdk.internal.IntentSession
import ru.ypmn.sdk.internal.PendingUpdate
import ru.ypmn.sdk.internal.pollUntil
import ru.ypmn.sdk.internal.setStatus
import ru.ypmn.sdk.internal.api.ApiClient
import ru.ypmn.sdk.internal.api.PatchOp
import ru.ypmn.sdk.internal.api.SendSmsRequest
import ru.ypmn.sdk.internal.api.apiCall

internal suspend fun pay(s: IntentSession, input: PayInput): PayResult = s.queue.enqueue {
    s.log.d { "pay(${s.id}): input=${if (input is PayInput.Card) "Card" else "Cryptogram"}" }
    try {
        val cryptogram = when (input) {
            is PayInput.Cryptogram -> input.cryptogram
            is PayInput.Card -> {
                val pk = s.publicKey ?: apiCall(s.log) { s.api.getPublicKey() }.let { res ->
                    s.publicKey = res.publicKey
                    s.publicKeyVersion = res.version
                    s.log.d { "pay(${s.id}): получен публичный ключ, version=${res.version}" }
                    res.publicKey
                }
                ru.ypmn.sdk.internal.crypto.Cryptogram.build(
                    pk, input.card,
                    ru.ypmn.sdk.internal.crypto.BrowserInfo.collectBase64(),
                    keyVersion = s.publicKeyVersion ?: 1,
                )
            }
        }
        val raw = apiCall(s.log) { s.api.pay(s.id, ru.ypmn.sdk.internal.api.CardPaymentRequest(paymentMethod = "Card", cryptogram = cryptogram)) }
        val threeDsUrl = (raw["threeDsUrl"] as? JsonPrimitive)?.contentOrNull
        if (threeDsUrl != null) {
            s.log.d { "pay(${s.id}): требуется 3DS → $threeDsUrl" }
            PayResult.ThreeDsRequired(threeDsUrl)
        } else {
            val parsed = ru.ypmn.sdk.internal.api.ApiClient.sharedJson
                .decodeFromJsonElement(IntentStatusResponse.serializer(), raw)
            s.setStatus(parsed.intent.status)
            val result = PayResult.Authorized(parsed)
            s.log.d { "pay(${s.id}): авторизован, status=${parsed.intent.status}, событие Success" }
            s.events.tryEmit(IntentEvent.Success(result))
            result
        }
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        s.log.w { "pay(${s.id}): ошибка ${e.javaClass.simpleName}: ${e.message}, событие Error" }
        s.events.tryEmit(IntentEvent.Error(e))
        throw e
    }
}

/** Полный /status/ (статус + транзакции) с синхронизацией сессии — зеркало web-sdk getStatusDetails. */
internal suspend fun getStatusDetails(s: IntentSession): IntentStatusResponse {
    val res = apiCall(s.log) { s.api.getStatus(s.id) }
    s.queue.enqueue { s.setStatus(res.intent.status) }
    return res
}

internal suspend fun getStatus(s: IntentSession): IntentStatus =
    IntentStatus.from(getStatusDetails(s).intent.status)

private fun buildPatchOps(c: UpdateChanges): List<PatchOp> {
    val ops = mutableListOf<PatchOp>()
    if (c.clearReceiptEmail) {
        ops.add(PatchOp("replace", "/receiptEmail", kotlinx.serialization.json.JsonNull))
    } else {
        c.receiptEmail?.let { ops.add(PatchOp("replace", "/receiptEmail", JsonPrimitive(it))) }
    }
    c.tokenize?.let { ops.add(PatchOp("replace", "/tokenize", JsonPrimitive(it))) }
    if (ops.isEmpty()) throw YpException("update: no editable fields provided")
    return ops
}

private fun merge(a: UpdateChanges, b: UpdateChanges): UpdateChanges {
    // Последнее слово за b: явный clear побеждает, явный новый e-mail отменяет clear.
    val clear = when {
        b.clearReceiptEmail -> true
        b.receiptEmail != null -> false
        else -> a.clearReceiptEmail
    }
    return UpdateChanges(
        receiptEmail = if (clear) null else b.receiptEmail ?: a.receiptEmail,
        tokenize = b.tokenize ?: a.tokenize,
        clearReceiptEmail = clear,
    )
}


/** Синхронный сборщик URL /alt/-эндпоинта (без сети) — зеркало web-sdk altUrl. */
private fun altUrl(s: IntentSession, view: String, method: String, query: Map<String, String?>): String {
    val encode = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
    val qs = query.entries
        .filter { !it.value.isNullOrEmpty() }
        .joinToString("&") { "${it.key}=${encode(it.value!!)}" }
    val base = s.config.baseUrl.trimEnd('/')
    return "$base/api/intent/alt/${encode(s.id)}/$view/${encode(method)}/" + if (qs.isEmpty()) "" else "?$qs"
}

/**
 * Тело /alt/…/link/ — application/json со строковым литералом: `"https://…"`.
 * Retrofit отдаёт его сырым (ScalarsConverter обслуживает String раньше
 * kotlinx-конвертера), поэтому кавычки снимаем здесь: с ними Uri.parse у мерчанта
 * даёт URI без схемы, и startActivity падает в ActivityNotFoundException.
 * Голый текст без кавычек принимаем как есть — зеркало фолбэка web-sdk на res.text().
 */
internal fun unwrapAltLink(raw: String): String {
    val trimmed = raw.trim()
    val link = runCatching {
        ApiClient.sharedJson.decodeFromString(String.serializer(), trimmed)
    }.getOrDefault(trimmed)
    if (link.isBlank()) throw YpException("getLink: бэкенд вернул пустую ссылку")
    return link
}

internal suspend fun getPaymentMethod(s: IntentSession, method: AltPayMethod, opts: AltRequestOpts): AltPayFlow = s.queue.enqueue {
    val pm = s.data.paymentMethods?.firstOrNull { it.type == method.type }
        ?: throw YpException("getPaymentMethod: метод ${method.type} отсутствует в интенте")
    // Встроенный QR сгенерирован бэкендом без puid — оплата по нему даст транзакцию с
    // puid=null, и waitForResult(puid) её не сматчит. При заданном puid строим QR-URL
    // на /alt/-эндпоинт с ?puid= (сеть — на стороне загрузчика картинки).
    val image = opts.puid?.let { altUrl(s, "image", method.type, mapOf("puid" to it)) }
        ?: pm.image ?: ""
    val baseLink = pm.link
    s.log.d {
        "getPaymentMethod(${s.id}): ${method.type}, puid=${opts.puid ?: "-"}, " +
            "image=${if (opts.puid != null) "собран с puid" else "встроенный"}, link=${baseLink ?: "-"}"
    }

    // getLink уходит в сеть и сериализуется очередью; вызывается ПОЗЖЕ getPaymentMethod (без реентранси).
    suspend fun fetchLink(linkOpts: AltLinkOpts): String = s.queue.enqueue {
        s.log.d {
            "getLink(${s.id}): ${method.type}, webview=${linkOpts.webview || opts.webview}, " +
                "puid=${linkOpts.puid ?: opts.puid ?: "-"}, schema=${linkOpts.schema ?: "-"}"
        }
        unwrapAltLink(
            apiCall(s.log) {
                s.api.altLink(
                    id = s.id, view = "link", method = method.type,
                    webview = if (linkOpts.webview || opts.webview) "true" else null,
                    puid = linkOpts.puid ?: opts.puid,
                    schema = linkOpts.schema,
                )
            },
        )
    }

    when (method) {
        AltPayMethod.SberPay -> object : AltPayFlow.SberPayFlow {
            override val method = method
            override val link = baseLink
            override fun getImage() = image
            override suspend fun getLink(opts: AltLinkOpts) = fetchLink(opts)
            override suspend fun sendSms(phone: String) {
                s.queue.enqueue {
                    s.log.d { "sendSms(${s.id}): phone=$phone" }
                    apiCall(s.log) { s.api.sendSms(s.id, SendSmsRequest(phone)) }
                }
            }
        }
        AltPayMethod.FasterPayments -> object : AltPayFlow.FasterPaymentsFlow {
            override val method = method
            override val link = baseLink
            override val banks = pm.banks ?: emptyList()
            override fun getImage() = image
            override suspend fun getLink(opts: AltLinkOpts) = fetchLink(opts)
        }
        else -> object : AltPayFlow.GenericAltFlow {
            override val method = method
            override val link = baseLink
            override fun getImage() = image
            override suspend fun getLink(opts: AltLinkOpts) = fetchLink(opts)
        }
    }
}

internal suspend fun waitForResult(s: IntentSession, opts: WaitForResultOpts): WaitForResultStatus {
    // Неуспех попытки не терминален для интента (v1.0.0, RequiresPayment*) — его видно
    // только по DECLINED-транзакции. Матчим строго по puid текущей попытки: старые
    // DECLINED (retryPayment) с чужим/пустым puid не должны давать ложный отказ.
    var declined: ru.ypmn.sdk.Transaction? = null
    var lastTransactions: List<ru.ypmn.sdk.Transaction> = emptyList()
    s.log.d {
        "waitForResult(${s.id}): старт interval=${opts.intervalMs ?: 3000}ms " +
            "timeout=${opts.timeoutMs ?: 600_000}ms puid=${opts.puid ?: "<нет, детект отказа выключен>"}"
    }
    val status = try {
        pollUntil(
            fn = {
                val res = getStatusDetails(s)      // queues setStatus (single-writer)
                lastTransactions = res.transactions
                if (opts.puid != null && declined == null) {
                    declined = res.transactions.firstOrNull { it.puid == opts.puid && it.status == "DECLINED" }
                }
                s.log.d { "waitForResult(${s.id}): тик status=${res.intent.status}, транзакций=${res.transactions.size}" }
                IntentStatus.from(res.intent.status)
            },
            isDone = { it.isTerminal || declined != null },
            intervalMs = opts.intervalMs ?: 3000,
            timeoutMs = opts.timeoutMs ?: 600_000,
        )
    } catch (e: Throwable) {
        if (e !is kotlinx.coroutines.CancellationException) {
            // Сюда приходит и таймаут поллера — без лога он неотличим от «всё ещё ждём».
            s.log.w { "waitForResult(${s.id}): прервано — ${e.javaClass.simpleName}: ${e.message}" }
        }
        throw e
    }
    declined?.let {
        s.log.d { "waitForResult(${s.id}): отказ по транзакции ${it.id} (puid=${opts.puid}) → Declined" }
        s.events.tryEmit(IntentEvent.Error(YpException("payment declined (transaction ${it.id})")))
        return WaitForResultStatus.Declined
    }
    s.log.d { "waitForResult(${s.id}): финал status=$status" }
    if (status == IntentStatus.Success) {
        // В отличие от web-sdk здесь транзакции из последнего ответа /status/, не из кэша сессии.
        s.events.tryEmit(IntentEvent.Success(PayResult.Authorized(
            IntentStatusResponse(IntentStatusData("Success"), lastTransactions)
        )))
    } else {
        s.events.tryEmit(IntentEvent.Error(YpException("payment ${status.name.lowercase()}")))
    }
    return WaitForResultStatus.from(status)
}

internal suspend fun update(s: IntentSession, changes: UpdateChanges) {
    val deferred = CompletableDeferred<Unit>()
    s.pendingMutex.withLock {
        val pending = s.pendingUpdate
        if (pending != null) {
            pending.changes = merge(pending.changes, changes)
            pending.waiters.add(deferred)
        } else {
            val batch = PendingUpdate(changes)
            batch.waiters.add(deferred)
            s.pendingUpdate = batch
            s.scope.launch {
                s.queue.enqueue {
                    s.pendingMutex.withLock { s.pendingUpdate = null }
                    try {
                        val ops = buildPatchOps(batch.changes)
                        s.log.d {
                            "update(${s.id}): batch из ${batch.waiters.size} вызовов, " +
                                "ops=${ops.joinToString { "${it.op} ${it.path}" }}"
                        }
                        val updated = apiCall(s.log) { s.api.patchIntent(s.id, ops) }
                        s.data = updated
                        s.handle?.let { s.events.tryEmit(IntentEvent.Update(it)) }
                        batch.waiters.forEach { it.complete(Unit) }
                    } catch (e: Throwable) {
                        s.log.w { "update(${s.id}): ошибка ${e.javaClass.simpleName}: ${e.message}" }
                        batch.waiters.forEach { it.completeExceptionally(e) }
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
            }
        }
    }
    deferred.await()
}
