package ru.ypmn.sdk.internal.crypto

import android.content.res.Resources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import java.util.Locale
import java.util.TimeZone

/**
 * Собирает BrowserInfo для криптограммы. Бэкенд требует валидную структуру —
 * пустой BrowserInfoBase64 отвергается ("BrowserInfoBase64 contains invalid browser info structure").
 * Зеркало web-sdk collectBrowserInfo с device-эквивалентами; без Context (Resources.getSystem).
 */
internal object BrowserInfo {
    private val json = Json { encodeDefaults = true }

    fun collectBase64(): String {
        val dm = runCatching { Resources.getSystem().displayMetrics }.getOrNull()
        val tzMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val info: JsonObject = buildJsonObject {
            put("AcceptHeader", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("ColorDepth", 24)
            put("IpAddress", "0.0.0.0")
            put("Language", ietfLanguageTag())
            put("ScreenHeight", dm?.heightPixels ?: 0)
            put("ScreenWidth", dm?.widthPixels ?: 0)
            put("TimeZone", tzMinutes)
            put("UserAgent", System.getProperty("http.agent") ?: "Android")
            put("JavaEnabled", false)
            put("JavaScriptEnabled", true)
        }
        return json.encodeToString(JsonObject.serializer(), info).encodeUtf8().base64()
    }

    /**
     * Language в форме language[-Script][-REGION] — как navigator.language в web-sdk.
     * Locale.getDefault().toLanguageTag() нельзя: Android 14+ дописывает «региональные
     * настройки» пользователя Unicode-расширениями ("ru-RU-u-fw-mon-mu-celsius"), и бэкенд
     * отвергает криптограмму ("Language must be a valid IETF language tag"). Собираем тег
     * только из language/script/region; пустой или "und" → "en".
     */
    internal fun ietfLanguageTag(locale: Locale = Locale.getDefault()): String {
        val tag = runCatching {
            Locale.Builder()
                .setLanguage(locale.language)
                .setScript(locale.script)
                .setRegion(locale.country)
                .build()
                .toLanguageTag()
        }.getOrDefault("")
        return if (tag.isBlank() || tag == "und") "en" else tag
    }
}
