package ru.ypmn.sdk.internal

import android.util.Log

internal const val YP_LOG_TAG = "YpSdk"

/**
 * Максимум полезной нагрузки в одном сообщении logcat: буфер записи — 4096 байт на
 * запись целиком (тег, приоритет и служебные байты входят в него), длинную строку
 * ядро молча обрезает. Режем сами, иначе хвост тела ответа теряется.
 */
private const val CHUNK = 3500

/** Разбивка длинного сообщения на куски, переживающие лимит записи logcat. */
internal fun logChunks(message: String): List<String> {
    if (message.length <= CHUNK) return listOf(message)
    val total = (message.length + CHUNK - 1) / CHUNK
    return (0 until total).map { i ->
        val part = message.substring(i * CHUNK, minOf((i + 1) * CHUNK, message.length))
        "[${i + 1}/$total] $part"
    }
}

/**
 * Отладочный лог SDK под тегом [YP_LOG_TAG] — `adb logcat -s YpSdk`.
 *
 * Экземпляр на сессию/клиент, включается через [ru.ypmn.sdk.YpConfig.debugLogging];
 * глобального переключателя намеренно нет (иначе состояние течёт между интентами и
 * тестами). Выключенный логгер не вычисляет сообщение: аргумент — лямбда.
 *
 * [sink] подменяется в юнит-тестах: на JVM `android.util.Log` не пишет никуда.
 */
internal class YpLogger(
    private val enabled: Boolean,
    private val sink: (Int, String) -> Unit = { priority, msg ->
        logChunks(msg).forEach { Log.println(priority, YP_LOG_TAG, it) }
    },
) {
    fun d(message: () -> String) {
        if (enabled) sink(Log.DEBUG, message())
    }

    fun w(message: () -> String) {
        if (enabled) sink(Log.WARN, message())
    }
}
