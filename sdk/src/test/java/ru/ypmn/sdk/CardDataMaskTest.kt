package ru.ypmn.sdk
import org.junit.Assert.*
import org.junit.Test
class CardDataMaskTest {
    @Test fun toString_masks_pan_and_cvv() {
        val s = CardData("4111111111111111", "12", "25", "123").toString()
        assertFalse(s.contains("4111111111111111"))
        assertFalse(s.contains("123"))
        assertTrue(s.contains("1111"))   // last 4 shown
    }
}
