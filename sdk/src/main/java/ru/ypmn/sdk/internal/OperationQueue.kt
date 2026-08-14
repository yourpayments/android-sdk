package ru.ypmn.sdk.internal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OperationQueue {
    private val mutex = Mutex()
    suspend fun <T> enqueue(block: suspend () -> T): T = mutex.withLock { block() }
}
