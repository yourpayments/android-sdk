package ru.ypmn.sdk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WaitForResultTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private suspend fun intent(): Intent {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"s"}"""))
        val i = YP.createIntent(CreateIntentRequest("t1", 1, "RUB", "SMS"), YpConfig(server.url("/").toString()))
        server.takeRequest(); return i
    }

    @Test fun declined_transaction_with_matching_puid_returns_Declined_and_emits_error() = runTest {
        val i = intent()
        val events = mutableListOf<IntentEvent>()
        val collector = launch { i.events.collect { events.add(it) } }
        server.enqueue(MockResponse().setBody(
            """{"intent":{"status":"RequiresPaymentMethod"},"transactions":[{"id":7,"status":"DECLINED","puid":"p-1"}]}"""
        ))
        val status = i.waitForResult(WaitForResultOpts(intervalMs = 0, timeoutMs = 5_000, puid = "p-1"))
        assertEquals(WaitForResultStatus.Declined, status)
        advanceUntilIdle()
        val err = events.filterIsInstance<IntentEvent.Error>().firstOrNull()
        assertNotNull("должно эмититься Error-событие", err)
        assertEquals("payment declined (transaction 7)", err!!.error.message)
        collector.cancel()
    }

    @Test fun declined_with_foreign_or_null_puid_is_ignored() = runTest {
        val i = intent()
        // Первый ответ: DECLINED чужой попытки (retryPayment) и без puid — матчиться не должны.
        server.enqueue(MockResponse().setBody(
            """{"intent":{"status":"RequiresPaymentMethod"},"transactions":[
                {"id":1,"status":"DECLINED","puid":"other"},
                {"id":2,"status":"DECLINED","puid":null}
            ]}"""
        ))
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))
        val status = i.waitForResult(WaitForResultOpts(intervalMs = 0, timeoutMs = 5_000, puid = "p-1"))
        assertEquals(WaitForResultStatus.Success, status)
    }

    @Test fun without_puid_declined_matching_is_disabled() = runTest {
        val i = intent()
        server.enqueue(MockResponse().setBody(
            """{"intent":{"status":"RequiresPaymentMethod"},"transactions":[{"id":3,"status":"DECLINED","puid":"p-1"}]}"""
        ))
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))
        val status = i.waitForResult(WaitForResultOpts(intervalMs = 0, timeoutMs = 5_000))
        assertEquals(WaitForResultStatus.Success, status)
    }

    @Test fun success_event_carries_fresh_transactions_from_last_poll() = runTest {
        val i = intent()
        val events = mutableListOf<IntentEvent>()
        val collector = launch { i.events.collect { events.add(it) } }
        server.enqueue(MockResponse().setBody(
            """{"intent":{"status":"Success"},"transactions":[{"id":42,"status":"AUTHORIZED","puid":"p-1"}]}"""
        ))
        val status = i.waitForResult(WaitForResultOpts(intervalMs = 0, timeoutMs = 5_000))
        assertEquals(WaitForResultStatus.Success, status)
        advanceUntilIdle()
        val success = events.filterIsInstance<IntentEvent.Success>().firstOrNull()
        assertNotNull(success)
        val data = (success!!.result as PayResult.Authorized).data
        assertEquals(listOf(42L), data.transactions.map { it.id })
        collector.cancel()
    }

    @Test fun getStatusDetails_returns_transactions_and_syncs_status() = runTest {
        val i = intent()
        server.enqueue(MockResponse().setBody(
            """{"intent":{"status":"RequiresPaymentMethod"},"transactions":[{"id":1,"status":"PENDING"}]}"""
        ))
        val details = i.getStatusDetails()
        assertEquals(1, details.transactions.size)
        assertEquals("PENDING", details.transactions[0].status)
        advanceUntilIdle()
        assertEquals(IntentStatus.RequiresPaymentMethod, i.status)
    }
}
