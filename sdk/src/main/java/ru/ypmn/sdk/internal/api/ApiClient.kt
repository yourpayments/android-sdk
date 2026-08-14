package ru.ypmn.sdk.internal.api
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import ru.ypmn.sdk.YpConfig
import ru.ypmn.sdk.internal.YpLogger

internal object ApiClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Бэкенд принимает строго "application/json" без charset. OkHttp на JSON-теле дописывает
    // "; charset=utf-8" → 400 "Request body must be valid JSON". Network-интерсептор выполняется
    // ПОСЛЕ BridgeInterceptor (он ставит Content-Type из тела) и перетирает заголовок на голый.
    private val contentTypeFix = Interceptor { chain ->
        val req = chain.request()
        if (req.body?.contentType()?.subtype.equals("json", ignoreCase = true)) {
            chain.proceed(req.newBuilder().header("Content-Type", "application/json").build())
        } else {
            chain.proceed(req)
        }
    }

    // Один dispatcher/connection pool на все интенты: newBuilder() ниже шарит их между клиентами.
    private val baseClient by lazy { OkHttpClient.Builder().addNetworkInterceptor(contentTypeFix).build() }

    fun create(config: YpConfig): YpApiService {
        val headerInterceptor = Interceptor { chain ->
            val b = chain.request().newBuilder()
            config.headers.forEach { (k, v) -> b.header(k, v) }
            chain.proceed(b.build())
        }
        val ok = baseClient.newBuilder()
            .addInterceptor(headerInterceptor)
            // Application-, а не network-уровень: тело ответа здесь уже распаковано
            // (gzip снимает BridgeInterceptor ниже), иначе в лог попали бы бинарные байты.
            // Плата — Content-Type виден до правки contentTypeFix (см. выше).
            .addInterceptor(httpLogInterceptor(YpLogger(config.debugLogging)))
            .build()
        val base = if (config.baseUrl.endsWith("/")) config.baseUrl else config.baseUrl + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(ok)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YpApiService::class.java)
    }

    val sharedJson: Json get() = json
}
