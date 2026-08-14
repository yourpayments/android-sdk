package ru.ypmn.sdk
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CreateAndUpdateTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun create_then_getStatus() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"s"}"""))
        server.enqueue(MockResponse().setBody("""{"intent":{"status":"Success"},"transactions":[]}"""))
        val intent = YP.createIntent(CreateIntentReq(), YpConfig(server.url("/").toString()))
        assertEquals("i1", intent.id)
        assertEquals(IntentStatus.Success, intent.getStatus())
    }

    @Test fun update_sends_json_patch_without_secret_header() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"sec"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"sec","receiptEmail":"a@b.c"}"""))
        val intent = YP.createIntent(CreateIntentReq(), YpConfig(server.url("/").toString()))
        server.takeRequest() // POST create
        intent.update(UpdateChanges(receiptEmail = "a@b.c"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/intent/i1/", patch.path)
        // Контракт секрета с бэкендом не зафиксирован (X-Intent-Secret vs secret) —
        // заголовок убран целиком, пока бэкенд его не начнёт требовать.
        assertNull(patch.getHeader("X-Intent-Secret"))
        assertNull(patch.getHeader("secret"))
        assertTrue(patch.body.readUtf8().contains("\"/receiptEmail\""))
    }

    @Test fun update_clearReceiptEmail_sends_null_value() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"sec"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"i1","status":"RequiresPaymentData","secret":"sec"}"""))
        val intent = YP.createIntent(CreateIntentReq(), YpConfig(server.url("/").toString()))
        server.takeRequest() // POST create
        intent.update(UpdateChanges(clearReceiptEmail = true))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains(""""path":"/receiptEmail","value":null"""))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun coalesces_concurrent_updates_into_one_patch() = runTest {
        val base = IntentResponse(id = "i1", status = "RequiresPaymentData", secret = "sec")
        val api = RecordingApi(base)
        val session = ru.ypmn.sdk.internal.IntentSession(
            base, YpConfig("http://test"), api,
            context = StandardTestDispatcher(testScheduler) + Job(),
        )
        val intent = ru.ypmn.sdk.internal.newHandle(session)
        val j1 = launch { intent.update(UpdateChanges(receiptEmail = "a@b.c")) }
        val j2 = launch { intent.update(UpdateChanges(tokenize = true)) }
        advanceUntilIdle()
        j1.join(); j2.join()
        assertEquals(1, api.patchCount)                                  // coalesced into ONE PATCH
        assertTrue(api.ops.any { it.path == "/receiptEmail" })
        assertTrue(api.ops.any { it.path == "/tokenize" })
    }
}
private fun CreateIntentReq() = CreateIntentRequest("t1", 100000, "RUB", "SMS")

private class RecordingApi(private val base: IntentResponse) : ru.ypmn.sdk.internal.api.YpApiService {
    var patchCount = 0
    val ops = mutableListOf<ru.ypmn.sdk.internal.api.PatchOp>()
    override suspend fun createIntent(body: CreateIntentRequest) = base
    override suspend fun getIntent(id: String) = base
    override suspend fun patchIntent(id: String, ops: List<ru.ypmn.sdk.internal.api.PatchOp>): IntentResponse {
        patchCount++; this.ops.addAll(ops); return base
    }
    override suspend fun getStatus(id: String) =
        IntentStatusResponse(IntentStatusData("RequiresPaymentData"))
    override suspend fun getPublicKey() = ru.ypmn.sdk.internal.api.PublicKeyResponse("")
    override suspend fun pay(id: String, body: ru.ypmn.sdk.internal.api.CardPaymentRequest) = kotlinx.serialization.json.JsonObject(emptyMap())
    override suspend fun altLink(id: String, view: String, method: String, webview: String?, puid: String?, schema: String?) = ""
    override suspend fun sendSms(id: String, body: ru.ypmn.sdk.internal.api.SendSmsRequest) {}
}
