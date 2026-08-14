package ru.ypmn.sdk.internal.api

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okio.Buffer
import ru.ypmn.sdk.internal.YpLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Потолок вычитываемого в память тела ответа. Не маскирование и не «обрезка ради
 * приличия» — страховка от OOM на неожиданно большом ответе; платёжный JSON на
 * порядки меньше.
 */
private const val MAX_BODY_BYTES = 1L * 1024 * 1024

/** Тела логируем как текст только для заведомо текстовых типов — иначе в лог полезут байты картинки. */
private fun MediaType?.isTextual(): Boolean {
    if (this == null) return false
    if (type == "text") return true
    val sub = subtype.lowercase()
    return sub == "json" || sub == "xml" || sub.endsWith("+json") || sub.endsWith("+xml")
}

private fun StringBuilder.appendHeaders(headers: Headers) {
    for (i in 0 until headers.size) appendLine("  ${headers.name(i)}: ${headers.value(i)}")
}

/**
 * Полный лог HTTP-обмена SDK при `YpConfig.debugLogging = true`.
 *
 * Пишет заголовки и тела как есть, без маскирования: контракт с бэкендом отлаживается
 * по фактическим байтам. Открытых карточных данных в трафике нет — PAN/CVV уходят
 * только внутри RSA-криптограммы, — но заголовки конфига (ключи, секреты) попадают в
 * logcat в открытом виде, поэтому флаг предназначен для отладочных сборок.
 */
internal fun httpLogInterceptor(log: YpLogger) = Interceptor { chain ->
    val req = chain.request()
    val path = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")

    log.d {
        buildString {
            appendLine("→ ${req.method} $path")
            appendHeaders(req.headers)
            val body = req.body
            when {
                body == null -> {}
                body.isDuplex() || body.isOneShot() -> appendLine("  <тело не читается повторно>")
                !body.contentType().isTextual() -> appendLine("  <${body.contentType()}, ${body.contentLength()} bytes>")
                else -> appendLine("  " + Buffer().also { body.writeTo(it) }.readUtf8())
            }
        }.trimEnd()
    }

    val startedNs = System.nanoTime()
    val res: Response = try {
        chain.proceed(req)
    } catch (e: IOException) {
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        log.w { "✗ ${req.method} $path failed after $tookMs ms: ${e.javaClass.simpleName}: ${e.message}" }
        throw e
    }
    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

    log.d {
        buildString {
            appendLine("← ${res.code} ${req.method} $path ($tookMs ms)")
            appendHeaders(res.headers)
            val contentType = res.body?.contentType()
            if (contentType.isTextual()) {
                // peekBody, а не body.string(): исходный поток обязан остаться нетронутым
                // для Retrofit-конвертера ниже по стеку.
                appendLine("  " + res.peekBody(MAX_BODY_BYTES).string())
            } else if (res.body != null) {
                appendLine("  <$contentType, ${res.body?.contentLength() ?: -1} bytes>")
            }
        }.trimEnd()
    }
    res
}
