package ru.ypmn.sdk.internal.api
import kotlinx.serialization.Serializable

@Serializable data class PublicKeyResponse(val publicKey: String, val version: Int? = null)
@Serializable data class PatchOp(val op: String, val path: String, val value: kotlinx.serialization.json.JsonElement)
// paymentMethod БЕЗ дефолта: с encodeDefaults=false поле, равное дефолту, выкидывается из JSON,
// а бэкенд требует его ("paymentMethod must be present"). Передаётся явно из Operations.pay.
// id интента ушёл в path (POST /api/intent/{uuid}/pay/).
@Serializable data class CardPaymentRequest(val paymentMethod: String, val cryptogram: String)
@Serializable data class SendSmsRequest(val phone: String)
@Serializable data class ErrorResponseDto(val message: String = "")
