package ru.ypmn.sdk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ypmn.sdk.internal.pollUntil

class PollerTest {
    @Test fun polls_until_done() = runTest {
        var calls = 0
        val r = pollUntil(fn = { ++calls }, isDone = { it >= 3 }, intervalMs = 0, timeoutMs = 10_000)
        assertEquals(3, r)
    }
    @Test(expected = YpException::class) fun throws_on_timeout() = runTest {
        pollUntil<Int>(fn = { 0 }, isDone = { it == 1 }, intervalMs = 1, timeoutMs = 5)
    }
}
