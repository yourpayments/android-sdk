package ru.ypmn.sdk

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ypmn.sdk.internal.YpLogger
import ru.ypmn.sdk.internal.logChunks

class YpLogTest {
    @Test fun disabled_logger_writes_nothing_and_never_builds_message() {
        val lines = mutableListOf<String>()
        var built = 0
        val log = YpLogger(enabled = false) { _, m -> lines.add(m) }
        log.d { built++; "должно быть пропущено" }
        log.w { built++; "должно быть пропущено" }
        assertEquals(emptyList<String>(), lines)
        // Сообщение не должно вычисляться при выключенном логе: сборка строки с телом
        // ответа не бесплатна, а на проде лог выключен.
        assertEquals(0, built)
    }

    @Test fun enabled_logger_writes_with_priority() {
        val entries = mutableListOf<Pair<Int, String>>()
        val log = YpLogger(enabled = true) { p, m -> entries.add(p to m) }
        log.d { "debug-строка" }
        log.w { "warn-строка" }
        assertEquals(listOf(Log.DEBUG to "debug-строка", Log.WARN to "warn-строка"), entries)
    }

    @Test fun short_message_is_not_chunked() {
        assertEquals(listOf("коротко"), logChunks("коротко"))
    }

    @Test fun long_message_is_chunked_without_loss() {
        val body = "x".repeat(9000)
        val chunks = logChunks(body)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].startsWith("[1/3] "))
        assertTrue(chunks[2].startsWith("[3/3] "))
        assertEquals(body, chunks.joinToString("") { it.substringAfter("] ") })
    }
}
