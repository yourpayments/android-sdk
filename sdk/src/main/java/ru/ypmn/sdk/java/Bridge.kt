package ru.ypmn.sdk.java
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.ypmn.sdk.internal.IntentSession
import java.util.concurrent.ConcurrentHashMap

// JVM-безопасный диспетчер (Main недоступен в unit-тестах). Main-thread доставка — будущий рефайн.
internal val facadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val listenerJobs = ConcurrentHashMap<IntentEventListener, Job>()

internal fun bridgeListener(s: IntentSession, l: IntentEventListener): Cancellable {
    listenerJobs.remove(l)?.cancel()                       // replace any prior registration for this listener
    val job = s.events.onEach { l.onEvent(it) }.launchIn(s.scope)
    listenerJobs[l] = job
    return Cancellable {
        job.cancel()                                       // cancel THIS job specifically
        listenerJobs.remove(l, job)                        // remove only if still mapped to this job
    }
}

internal fun unbridgeListener(l: IntentEventListener) { listenerJobs.remove(l)?.cancel() }
