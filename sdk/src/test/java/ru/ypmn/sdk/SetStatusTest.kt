package ru.ypmn.sdk
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.ypmn.sdk.internal.IntentSession
import ru.ypmn.sdk.internal.setStatus

class SetStatusTest {
    private fun TestScopeSession(status: String = "RequiresPaymentData"): IntentSession =
        IntentSession(
            IntentResponse(id = "i1", status = status, secret = "s"),
            YpConfig("http://x"), ThreeDsPollingTestApi(),
            context = StandardTestDispatcher() + Job(),
        )

    @Test fun terminal_status_is_not_overwritten_by_stale_nonterminal() = runTest {
        val s = TestScopeSession()
        s.setStatus("Success")
        // Запоздалый ответ /status/ со старым статусом не должен откатить терминальный.
        s.setStatus("RequiresPaymentData")
        assertEquals("Success", s.data.status)
    }

    @Test fun nonterminal_transitions_still_apply() = runTest {
        val s = TestScopeSession()
        s.setStatus("RequiresPaymentMethod")
        assertEquals("RequiresPaymentMethod", s.data.status)
        s.setStatus("Expired")
        assertEquals("Expired", s.data.status)
        s.setStatus("RequiresPaymentData")   // из Expired тоже не откатываемся
        assertEquals("Expired", s.data.status)
    }
}

/** Минимальная заглушка API — setStatus в сеть не ходит. */
private class ThreeDsPollingTestApi : ru.ypmn.sdk.internal.api.YpApiService {
    override suspend fun createIntent(body: CreateIntentRequest): IntentResponse = error("unused")
    override suspend fun getIntent(id: String): IntentResponse = error("unused")
    override suspend fun patchIntent(id: String, ops: List<ru.ypmn.sdk.internal.api.PatchOp>): IntentResponse = error("unused")
    override suspend fun getStatus(id: String): IntentStatusResponse = error("unused")
    override suspend fun getPublicKey(): ru.ypmn.sdk.internal.api.PublicKeyResponse = error("unused")
    override suspend fun pay(id: String, body: ru.ypmn.sdk.internal.api.CardPaymentRequest): kotlinx.serialization.json.JsonObject = error("unused")
    override suspend fun altLink(id: String, view: String, method: String, webview: String?, puid: String?, schema: String?): String = error("unused")
    override suspend fun sendSms(id: String, body: ru.ypmn.sdk.internal.api.SendSmsRequest) = error("unused")
}
