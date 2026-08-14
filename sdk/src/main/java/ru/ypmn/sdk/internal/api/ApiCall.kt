package ru.ypmn.sdk.internal.api
import ru.ypmn.sdk.YpApiException
import ru.ypmn.sdk.YpException
import ru.ypmn.sdk.YpNetworkException
import ru.ypmn.sdk.internal.YpLogger

/**
 * Единственная воронка, где ошибки транспорта превращаются в [ru.ypmn.sdk.YpException].
 * Здесь же они и логируются: HTTP-лог показывает ответ, но не то, во что SDK его
 * превратил, а неразобранный ответ (SerializationException) иначе не виден вообще.
 */
internal suspend fun <T> apiCall(log: YpLogger? = null, block: suspend () -> T): T = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: retrofit2.HttpException) {
    val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val serverMsg = raw?.let {
        runCatching { ApiClient.sharedJson.decodeFromString(ErrorResponseDto.serializer(), it).message }.getOrNull()
    }?.takeIf { it.isNotBlank() }
    log?.w { "apiCall: YpApiException code=${e.code()}, message=${serverMsg ?: "HTTP ${e.code()}"}, body=${raw ?: "-"}" }
    throw YpApiException(e.code(), raw, serverMsg ?: "HTTP ${e.code()}", e)
} catch (e: java.io.IOException) {
    log?.w { "apiCall: YpNetworkException — ${e.javaClass.simpleName}: ${e.message}" }
    throw YpNetworkException("network error", e)
} catch (e: kotlinx.serialization.SerializationException) {
    log?.w { "apiCall: ответ не разобран — ${e.javaClass.simpleName}: ${e.message}" }
    throw YpException("invalid response", e)
}
