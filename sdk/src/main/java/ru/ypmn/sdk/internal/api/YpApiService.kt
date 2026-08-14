package ru.ypmn.sdk.internal.api
import retrofit2.http.*
import ru.ypmn.sdk.CreateIntentRequest
import ru.ypmn.sdk.IntentResponse
import ru.ypmn.sdk.IntentStatusResponse

internal interface YpApiService {
    @POST("api/intent/")
    suspend fun createIntent(@Body body: CreateIntentRequest): IntentResponse

    @GET("api/intent/{id}/")
    suspend fun getIntent(@Path("id") id: String): IntentResponse

    // Секрет-заголовок НЕ отправляется: контракт с бэкендом не зафиксирован
    // (X-Intent-Secret vs secret), убран целиком, пока бэкенд не начнёт требовать.
    @PATCH("api/intent/{id}/")
    suspend fun patchIntent(
        @Path("id") id: String,
        @Body ops: List<PatchOp>,
    ): IntentResponse

    @GET("api/intent/{id}/status/")
    suspend fun getStatus(@Path("id") id: String): IntentStatusResponse

    @GET("api/intent/pay/key/")
    suspend fun getPublicKey(): PublicKeyResponse

    // Оплата картой: 200 → IntentStatusResponse | ответ с threeDsUrl. Разбираем как JsonObject.
    // Спека v1.0.0: uuid интента в path, в теле только paymentMethod+cryptogram.
    @POST("api/intent/{id}/pay/")
    suspend fun pay(@Path("id") id: String, @Body body: CardPaymentRequest): kotlinx.serialization.json.JsonObject

    // Альт-линк: GET /api/intent/alt/{id}/{view}/{method}/?webview=&puid=&schema= (web-sdk client.ts payWithFasterPayments)
    @GET("api/intent/alt/{id}/{view}/{method}/")
    suspend fun altLink(
        @Path("id") id: String,
        @Path("view") view: String,
        @Path("method") method: String,
        @Query("webview") webview: String? = null,
        @Query("puid") puid: String? = null,
        @Query("schema") schema: String? = null,
    ): String

    @POST("api/intent/{id}/send-sms/")
    suspend fun sendSms(@Path("id") id: String, @Body body: SendSmsRequest)
}
