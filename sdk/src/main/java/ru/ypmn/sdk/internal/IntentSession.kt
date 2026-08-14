package ru.ypmn.sdk.internal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import ru.ypmn.sdk.Intent
import ru.ypmn.sdk.IntentEvent
import ru.ypmn.sdk.IntentStatus
import ru.ypmn.sdk.UpdateChanges
import ru.ypmn.sdk.YpConfig
import ru.ypmn.sdk.IntentResponse
import ru.ypmn.sdk.internal.api.YpApiService
import kotlin.coroutines.CoroutineContext

internal class PendingUpdate(
    var changes: UpdateChanges,
    val waiters: MutableList<kotlinx.coroutines.CompletableDeferred<Unit>> = mutableListOf(),
)

internal class IntentSession(
    @Volatile var data: IntentResponse,
    val config: YpConfig,
    val api: YpApiService,
    context: CoroutineContext = Dispatchers.IO + SupervisorJob(),
) {
    val id: String get() = data.id
    val log = YpLogger(config.debugLogging)
    val events = MutableSharedFlow<IntentEvent>(extraBufferCapacity = 32)
    val queue = OperationQueue()
    val scope = CoroutineScope(context)
    val pendingMutex = Mutex()
    @Volatile var pendingUpdate: PendingUpdate? = null
    @Volatile var publicKey: String? = null
    /** Версия ключа из /api/intent/pay/key/ — уходит в KeyVersion криптограммы. */
    @Volatile var publicKeyVersion: Int? = null
    @Volatile var handle: Intent? = null
}

/**
 * Mutates session state and emits StatusChange. NOT atomic under concurrent callers —
 * must be invoked from within [OperationQueue.enqueue] (single-writer), as all operations do.
 */
internal fun IntentSession.setStatus(raw: String) {
    if (data.status == raw) return
    // Из терминального статуса легальных переходов нет (v1.0.0): запоздалый ответ
    // /status/ не должен перетирать Success/Expired от параллельного pay/3DS.
    if (IntentStatus.from(data.status).isTerminal) {
        log.d { "status($id): ${data.status} терминален, переход в $raw отброшен" }
        return
    }
    log.d { "status($id): ${data.status} → $raw" }
    data = data.copy(status = raw)
    events.tryEmit(IntentEvent.StatusChange(IntentStatus.from(raw)))
}
