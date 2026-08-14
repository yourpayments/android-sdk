package ru.ypmn.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ypmn.sdk.internal.crypto.BrowserInfo
import java.util.Locale

class BrowserInfoTest {
    @Test fun collects_valid_non_empty_structure() {
        val b64 = BrowserInfo.collectBase64()
        assertTrue("base64 пуст", b64.isNotEmpty())
        val obj = Json.parseToJsonElement(b64.decodeBase64()!!.utf8()).jsonObject
        // Структура зеркалит web-sdk DecodedBrowserInfo — все поля должны присутствовать.
        for (k in listOf(
            "AcceptHeader", "ColorDepth", "IpAddress", "Language", "ScreenHeight",
            "ScreenWidth", "TimeZone", "UserAgent", "JavaEnabled", "JavaScriptEnabled",
        )) {
            assertTrue("отсутствует поле $k", obj.containsKey(k))
        }
        assertEquals(true, obj["JavaScriptEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(false, obj["JavaEnabled"]!!.jsonPrimitive.boolean)
    }

    // Android 14+ дописывает «региональные настройки» (первый день недели, единицы
    // температуры) в default-локаль как Unicode-расширения: toLanguageTag() даёт
    // "ru-RU-u-fw-mon-mu-celsius", и бэкенд отвергает такой Language
    // ("Language must be a valid IETF language tag"). SDK обязан слать чистый
    // language[-Script][-REGION] — как navigator.language в web-sdk.
    @Test fun language_strips_unicode_extensions() {
        val withExtensions = Locale.Builder()
            .setLanguage("ru").setRegion("RU")
            .setUnicodeLocaleKeyword("fw", "mon")
            .setUnicodeLocaleKeyword("mu", "celsius")
            .build()
        assertEquals("ru-RU-u-fw-mon-mu-celsius", withExtensions.toLanguageTag()) // предусловие
        assertEquals("ru-RU", BrowserInfo.ietfLanguageTag(withExtensions))
    }

    @Test fun language_keeps_script_and_region() {
        val zh = Locale.Builder().setLanguage("zh").setScript("Hans").setRegion("CN").build()
        assertEquals("zh-Hans-CN", BrowserInfo.ietfLanguageTag(zh))
        assertEquals("en-US", BrowserInfo.ietfLanguageTag(Locale.US))
        assertEquals("ru", BrowserInfo.ietfLanguageTag(Locale.Builder().setLanguage("ru").build()))
    }

    @Test fun language_falls_back_to_en_for_undetermined_locale() {
        assertEquals("en", BrowserInfo.ietfLanguageTag(Locale.ROOT))
    }
}
