package ru.ypmn.sdk

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.ypmn.sdk.internal.IntentSession
import ru.ypmn.sdk.internal.ThreeDsViewImpl
import ru.ypmn.sdk.internal.api.CardPaymentRequest
import ru.ypmn.sdk.internal.api.PatchOp
import ru.ypmn.sdk.internal.api.PublicKeyResponse
import ru.ypmn.sdk.internal.api.SendSmsRequest
import ru.ypmn.sdk.internal.api.YpApiService

class ThreeDsPollingTest {
    private class StatusApi(private val statuses: List<String>) : YpApiService {
        private var i = 0
        override suspend fun getStatus(id: String): IntentStatusResponse {
            val s = statuses[minOf(i, statuses.lastIndex)]; i++
            return IntentStatusResponse(IntentStatusData(s), listOf(Transaction(id = 9, status = "AUTHORIZED")))
        }
        override suspend fun createIntent(body: CreateIntentRequest): IntentResponse = error("unused")
        override suspend fun getIntent(id: String): IntentResponse = error("unused")
        override suspend fun patchIntent(id: String, ops: List<PatchOp>): IntentResponse = error("unused")
        override suspend fun getPublicKey(): PublicKeyResponse = error("unused")
        override suspend fun pay(id: String, body: CardPaymentRequest): kotlinx.serialization.json.JsonObject = error("unused")
        override suspend fun altLink(id: String, view: String, method: String, webview: String?, puid: String?, schema: String?): String = error("unused")
        override suspend fun sendSms(id: String, body: SendSmsRequest) = error("unused")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun polling_settles_success_and_emits_session_success_event() = runTest {
        val base = IntentResponse(id = "i1", status = "RequiresPaymentData", secret = "s")
        val session = IntentSession(
            base, YpConfig("http://x"), StatusApi(listOf("RequiresPaymentData", "Success")),
            context = StandardTestDispatcher(testScheduler) + Job(),
        )
        val view = ThreeDsViewImpl(session, "https://acs", intervalMs = 0)
        val events = mutableListOf<IntentEvent>()
        val collectJob = launch { session.events.collect { events.add(it) } }

        view.beginPolling()
        advanceUntilIdle()

        assertEquals(ThreeDsResult.Status.SUCCESS, view.results.replayCache.firstOrNull()?.status)
        val success = events.filterIsInstance<IntentEvent.Success>().firstOrNull()
        assertNotNull("должно эмититься Success-событие", success)
        // Транзакции — из последнего ответа /status/, не из устаревшего кэша сессии.
        val data = (success!!.result as PayResult.Authorized).data
        assertEquals(listOf(9L), data.transactions.map { it.id })
        collectJob.cancel()
    }
}
