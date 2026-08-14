package ru.ypmn.sdk
import ru.ypmn.sdk.internal.IntentSession
import ru.ypmn.sdk.internal.YpLogger
import ru.ypmn.sdk.internal.api.ApiClient
import ru.ypmn.sdk.internal.api.apiCall
import ru.ypmn.sdk.internal.newHandle

object YP {
    suspend fun createIntent(request: CreateIntentRequest, config: YpConfig): Intent {
        val log = YpLogger(config.debugLogging)
        val api = ApiClient.create(config)
        // SDK-канал по умолчанию; явный request.scenario имеет приоритет.
        val req = if (request.scenario == null) request.copy(scenario = "AndroidSDK") else request
        log.d { "createIntent: baseUrl=${config.baseUrl}, scenario=${req.scenario}, amount=${req.amount} ${req.currency}" }
        val data = apiCall(log) { api.createIntent(req) }
        log.d { "createIntent: id=${data.id}, status=${data.status}" }
        return newHandle(IntentSession(data, config, api))
    }
    suspend fun getIntent(id: String, config: YpConfig): Intent {
        val log = YpLogger(config.debugLogging)
        val api = ApiClient.create(config)
        log.d { "getIntent: id=$id, baseUrl=${config.baseUrl}" }
        val data = apiCall(log) { api.getIntent(id) }
        log.d { "getIntent: id=${data.id}, status=${data.status}" }
        return newHandle(IntentSession(data, config, api))
    }
    fun wrapIntent(data: IntentResponse, config: YpConfig): Intent {
        val api = ApiClient.create(config)
        YpLogger(config.debugLogging).d { "wrapIntent: id=${data.id}, status=${data.status}" }
        return newHandle(IntentSession(data, config, api))
    }
}
