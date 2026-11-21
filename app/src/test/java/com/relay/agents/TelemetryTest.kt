package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {
    @Test
    fun statsAggregatePerAgent() {
        val now = 10_000_000L
        val recs = listOf(
            Telemetry.Rec(now, "a", "A", "GROQ", true, 100, 400, 400, 200),
            Telemetry.Rec(now, "a", "A", "GROQ", false, 50, 400, 0, 429),
            Telemetry.Rec(now, "b", "B", "GEMINI", true, 300, 40, 40, 200),
            Telemetry.Rec(now - 999_999_999, "a", "A", "GROQ", true, 1, 1, 1, 200)
        )
        val s = Telemetry.statsOf(recs, now - 1000)
        assertEquals(2, s.size)
        val a = s.first { it.agentId == "a" }
        assertEquals(2, a.requests); assertEquals(1, a.ok); assertEquals(50, a.successPct); assertEquals(100, a.avgMs)
        assertEquals((400 + 400 + 400) / 4L, a.tokens)
    }

    @Test
    fun cacheHonorsTtl() {
        val k = ResponseCache.key("sys", "question")
        ResponseCache.put(k, "answer", "Groq", now = 0)
        assertEquals("answer" to "Groq", ResponseCache.get(k, now = 10 * 60_000L))
        assertNull(ResponseCache.get(k, now = 31 * 60_000L))
        assertEquals(ResponseCache.key("a", "b"), ResponseCache.key("a", "b"))
        assertTrue(ResponseCache.key("a", "b") != ResponseCache.key("ab", ""))
    }

    @Test
    fun compressorCollapsesNoise() {
        val out = PromptCompressor.compress("hello   \n\n\n\nhello\nhello\nworld\n")
        assertEquals("hello\n\nhello\nworld", out)
        val long = PromptCompressor.compress("x".repeat(5000))
        assertTrue(long.length < 2100 && long.contains("[trimmed]"))
    }
}
