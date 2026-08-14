package ru.ypmn.sdk.internal

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.ypmn.sdk.IntentEvent
import ru.ypmn.sdk.IntentStatus
import ru.ypmn.sdk.IntentStatusData
import ru.ypmn.sdk.IntentStatusResponse
import ru.ypmn.sdk.PayResult
import ru.ypmn.sdk.ThreeDsResult
import ru.ypmn.sdk.ThreeDsView
import ru.ypmn.sdk.YpException
import ru.ypmn.sdk.internal.operations.getStatusDetails

private val threeDsJson = Json { ignoreUnknownKeys = true }

/**
 * Origin (scheme://host[:port]) для allowedOriginRules WebMessageListener: сообщения
 * принимаем только от страниц baseUrl — return-страница 3DS живёт там. Web-sdk
 * принимает postMessage с любого origin; здесь ужесточено осознанно — сторонняя
 * ACS-страница не должна уметь подделать {"code":"0"}. Завершение с чужих origin'ов
 * ловит поллинг статуса. Непарсибельный baseUrl → "*" (деградация до web-поведения).
 */
internal fun threeDsAllowedOrigin(baseUrl: String): String {
    val url = baseUrl.toHttpUrlOrNull() ?: return "*"
    val port = if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
    return "${url.scheme}://${url.host}$port"
}

/** postMessage от return-страницы 3DS: {"code":"0"}→SUCCESS, иной string-code→FAILURE, нет code/мусор→null. Зеркало web-sdk onMessage. */
internal fun parseThreeDsMessage(raw: String?): ThreeDsResult? {
    if (raw.isNullOrBlank()) return null
    val code = runCatching {
        threeDsJson.parseToJsonElement(raw).jsonObject["code"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: return null
    return if (code == "0") {
        ThreeDsResult(ThreeDsResult.Status.SUCCESS, code, IntentStatus.Success)
    } else {
        // Спека v1.0.0: статуса Failed нет — после неуспешного 3DS интент возвращается
        // в RequiresPayment*, терминального intentStatus у неуспеха не бывает.
        ThreeDsResult(ThreeDsResult.Status.FAILURE, code, intentStatus = null)
    }
}

internal class ThreeDsViewImpl(
    private val session: IntentSession,
    private val url: String,
    private val intervalMs: Long = 3000,
) : ThreeDsView {
    override var webView: WebView? = null
        private set
    private val _results = MutableSharedFlow<ThreeDsResult>(replay = 1, extraBufferCapacity = 1)
    override val results = _results

    @Volatile private var settled = false
    private var pollJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled") // 3DS ACS-страницы требуют JavaScript — обязательно
    override fun mount(container: ViewGroup): ThreeDsView {
        unmount()
        webView?.destroy()
        webView = null

        val wv = WebView(container.context)
        wv.settings.javaScriptEnabled = true
        wv.webViewClient = WebViewClient() // навигация остаётся внутри WebView

        // postMessage-мост (как web-sdk): return-страница шлёт {code}; ловим через androidx.webkit.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            val origins = setOf(threeDsAllowedOrigin(session.config.baseUrl))
            session.log.d { "3ds(${session.id}): мост YpBridge, разрешённый origin=${origins.first()}" }
            WebViewCompat.addWebMessageListener(wv, "YpBridge", origins) { _, message, _, _, _ ->
                val parsed = parseThreeDsMessage(message.data)
                session.log.d { "3ds(${session.id}): postMessage ${message.data} → ${parsed?.status ?: "проигнорировано"}" }
                parsed?.let { settle(it.status, it.code, it.intentStatus) }
            }
            WebViewCompat.addDocumentStartJavaScript(
                wv,
                "window.addEventListener('message',function(e){try{YpBridge.postMessage(typeof e.data==='string'?e.data:JSON.stringify(e.data));}catch(_){}})",
                origins,
            )
        }
        // Если WEB_MESSAGE_LISTENER недоступен — завершение ловит поллинг статуса (ниже).
        else session.log.d { "3ds(${session.id}): WEB_MESSAGE_LISTENER недоступен, полагаемся на поллинг статуса" }

        session.log.d { "3ds(${session.id}): mount $url" }
        wv.loadUrl(url)
        webView = wv
        container.addView(wv)
        if (!settled) beginPolling()
        return this
    }

    /** Поллинг статуса — надёжный fallback-сигнал завершения 3DS. Отдельно от mount для тестируемости. */
    internal fun beginPolling() {
        if (settled || pollJob?.isActive == true) return
        pollJob = session.scope.launch {
            while (!settled) {
                delay(intervalMs)
                if (settled) break
                // Сбой тика намеренно не прерывает поллинг (ACS-страница ещё открыта,
                // сеть может вернуться), но молчать о нём нельзя — иначе «3DS висит»
                // выглядит в логе как полное отсутствие активности.
                val res = runCatching { getStatusDetails(session) }
                    .onFailure { e -> session.log.w { "3ds(${session.id}): тик поллинга упал: ${e.javaClass.simpleName}: ${e.message}" } }
                    .getOrNull()
                val status = res?.let { IntentStatus.from(it.intent.status) }
                if (status != null && status.isTerminal) {
                    settle(
                        if (status == IntentStatus.Success) ThreeDsResult.Status.SUCCESS else ThreeDsResult.Status.FAILURE,
                        intentStatus = status,
                        transactions = res.transactions,
                    )
                    break
                }
            }
        }
    }

    override fun unmount() {
        pollJob?.cancel(); pollJob = null
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    override fun destroy() {
        settled = true
        unmount()
        webView?.destroy(); webView = null
    }

    private fun settle(
        status: ThreeDsResult.Status,
        code: String? = null,
        intentStatus: IntentStatus? = null,
        transactions: List<ru.ypmn.sdk.Transaction>? = null,
    ) {
        if (settled) return
        settled = true
        pollJob?.cancel(); pollJob = null
        session.log.d { "3ds(${session.id}): settle status=$status code=${code ?: "-"} intentStatus=${intentStatus ?: "-"}" }
        _results.tryEmit(ThreeDsResult(status, code, intentStatus))
        session.scope.launch {
            if (status == ThreeDsResult.Status.SUCCESS) {
                session.queue.enqueue { session.setStatus("Success") }
                // Транзакции — из последнего ответа /status/ (поллинг); у postMessage-пути их нет —
                // тогда кэш сессии.
                session.events.tryEmit(
                    IntentEvent.Success(
                        PayResult.Authorized(
                            IntentStatusResponse(
                                IntentStatusData("Success"),
                                transactions ?: session.data.transactions ?: emptyList(),
                            ),
                        ),
                    ),
                )
            } else {
                session.events.tryEmit(IntentEvent.Error(YpException("3DS failed")))
            }
        }
    }

}
