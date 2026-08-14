package ru.ypmn.sdk
sealed interface IntentEvent {
    data class Update(val intent: Intent) : IntentEvent
    data class StatusChange(val status: IntentStatus) : IntentEvent
    data class Success(val result: PayResult) : IntentEvent
    data class Error(val error: Throwable) : IntentEvent
}
