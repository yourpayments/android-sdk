package ru.ypmn.sdk

import org.junit.Assert.*
import org.junit.Test
import ru.ypmn.sdk.internal.parseThreeDsMessage

class ThreeDsMessageTest {
    @Test fun code_0_is_success() {
        val r = parseThreeDsMessage("""{"code":"0"}""")
        assertEquals(ThreeDsResult.Status.SUCCESS, r?.status)
        assertEquals(IntentStatus.Success, r?.intentStatus)
        assertEquals("0", r?.code)
    }
    @Test fun nonzero_code_is_failure() {
        val r = parseThreeDsMessage("""{"code":"5"}""")
        assertEquals(ThreeDsResult.Status.FAILURE, r?.status)
        // Спека v1.0.0: терминального Failed нет — неуспех 3DS не несёт intentStatus.
        assertNull(r?.intentStatus)
    }
    @Test fun missing_or_garbage_is_ignored() {
        assertNull(parseThreeDsMessage("""{"foo":"bar"}"""))
        assertNull(parseThreeDsMessage("not json"))
        assertNull(parseThreeDsMessage(null))
        assertNull(parseThreeDsMessage(""))
    }
}
