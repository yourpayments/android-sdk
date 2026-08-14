package ru.ypmn.sdk
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ypmn.sdk.internal.threeDsAllowedOrigin

class ThreeDsOriginTest {
    @Test fun origin_is_scheme_host_for_default_port() {
        assertEquals("https://sandbox.ypmn.ru", threeDsAllowedOrigin("https://sandbox.ypmn.ru"))
        assertEquals("https://sandbox.ypmn.ru", threeDsAllowedOrigin("https://sandbox.ypmn.ru/"))
        assertEquals("https://ypmn.ru", threeDsAllowedOrigin("https://ypmn.ru/api/"))
    }
    @Test fun origin_keeps_custom_port() {
        assertEquals("http://10.0.2.2:8080", threeDsAllowedOrigin("http://10.0.2.2:8080/"))
    }
    @Test fun garbage_falls_back_to_wildcard() {
        assertEquals("*", threeDsAllowedOrigin("not a url"))
    }
}
