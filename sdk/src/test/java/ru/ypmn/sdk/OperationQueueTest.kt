package ru.ypmn.sdk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ypmn.sdk.internal.OperationQueue

class OperationQueueTest {
    @Test fun runs_serially_in_order() = runTest {
        val q = OperationQueue()
        val log = mutableListOf<Int>()
        val jobs = (1..5).map { n -> launch { q.enqueue { log.add(n) } } }
        jobs.joinAll()
        assertEquals(listOf(1, 2, 3, 4, 5), log)
    }

    @Test fun serializes_overlapping_operations() = runTest {
        val q = OperationQueue()
        val events = mutableListOf<String>()
        val jobs = (1..3).map { n ->
            launch { q.enqueue { events.add("start$n"); delay(10); events.add("end$n") } }
        }
        jobs.joinAll()
        // Each op holds the lock across its delay, so the next can't start until the prior ends.
        // Without the Mutex this would interleave (start1,start2,start3,end1,...).
        assertEquals(listOf("start1","end1","start2","end2","start3","end3"), events)
    }
}
