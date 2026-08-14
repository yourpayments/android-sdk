package ru.ypmn.sdk

/** Базовый тип всех ошибок SDK. */
open class YpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * HTTP-ошибка API (4xx/5xx). [status] — код ответа, [body] — сырое тело (если было).
 * message — `message` из тела `{"message":…}`, иначе `"HTTP <code>"`.
 */
class YpApiException(
    val status: Int,
    val body: String?,
    message: String,
    cause: Throwable? = null,
) : YpException(message, cause)

/** Сетевая (I/O) ошибка: запрос не отправлен или ответ не получен. */
class YpNetworkException(message: String, cause: Throwable? = null) : YpException(message, cause)
