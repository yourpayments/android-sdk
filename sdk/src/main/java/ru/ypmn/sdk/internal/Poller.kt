package ru.ypmn.sdk.internal
import kotlinx.coroutines.delay
import ru.ypmn.sdk.YpException

internal suspend fun <T> pollUntil(
    fn: suspend () -> T,
    isDone: (T) -> Boolean,
    intervalMs: Long = 3000,
    timeoutMs: Long = 600_000,
): T {
    var elapsed = 0L
    var value = fn()
    while (!isDone(value)) {
        if (elapsed >= timeoutMs) throw YpException("pollUntil: timeout")
        delay(intervalMs)
        elapsed += if (intervalMs <= 0L) 1L else intervalMs
        value = fn()
    }
    return value
}
