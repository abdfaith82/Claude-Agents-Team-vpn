package com.relay.agents

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GatewayTest {
    private fun agent(id: String, name: String, model: String, p: Provider = Provider.GROQ) =
        AgentConfig(id, name, "k", model, true, p)

    @Test
    fun httpReadParsesBodyAndExpectContinue() {
        val raw = "POST /v1/chat/completions?x=1 HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\nhello"
        val out = ByteArrayOutputStream()
        val r = Http.read(ByteArrayInputStream(raw.toByteArray()), out)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/chat/completions?x=1", r.path)
        assertEquals("hello", String(r.body))
        assertTrue(String(out.toByteArray()).contains("100 Continue"))
    }

    @Test
    fun httpReadRejectsHugeBody() {
        val raw = "POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n"
        assertNull(Http.read(ByteArrayInputStream(raw.toByteArray()), null))
    }

    @Test
    fun openAiParsingFlattensConversation() {
        val b = JSONObject("""{"model":"auto","stream":true,"max_tokens":100000,"messages":[
            {"role":"system","content":"Be brief"},
            {"role":"user","content":[{"type":"text","text":"Hi"}]},
            {"role":"assistant","content":"Hello"},
            {"role":"user","content":"Bye"}]}""")
        val r = GatewayLogic.parseOpenAi(b)
        assertEquals("Be brief", r.system)
        assertTrue(r.user.startsWith("User: Hi"))
        assertTrue(r.user.contains("Assistant: Hello"))
        assertTrue(r.user.endsWith("Reply to the last User message."))
        assertEquals(4096, r.maxTokens)
        assertTrue(r.stream)
    }

    @Test
    fun singleUserMessageStaysPlain() {
        val r = GatewayLogic.parseOpenAi(JSONObject("""{"messages":[{"role":"user","content":"ping"}]}"""))
        assertEquals("ping", r.user)
    }

    @Test
    fun anthropicParsingHandlesSystemBlocks() {
        val b = JSONObject("""{"model":"claude","max_tokens":50,"system":[{"type":"text","text":"S1"}],
            "messages":[{"role":"user","content":[{"type":"text","text":"Q"}]}]}""")
        val r = GatewayLogic.parseAnthropic(b)
        assertEquals("S1", r.system)
        assertEquals("Q", r.user)
        assertEquals(50, r.maxTokens)
    }

    @Test
    fun routeByModelName() {
        val list = listOf(agent("1", "Groq1", "llama-x"), agent("2", "Gem", "gemini-x", Provider.GEMINI))
        val (byName, s1) = GatewayLogic.route("groq1", list)
        assertTrue(byName(list[0])); assertFalse(byName(list[1])); assertNull(s1)
        val (unknown, _) = GatewayLogic.route("gpt-4o", list)
        assertTrue(unknown(list[0]) && unknown(list[1]))
        val (_, fast) = GatewayLogic.route("auto:fast", list)
        assertEquals(AgentRouter.Strategy.FASTEST, fast)
        val (vision, _) = GatewayLogic.route("auto:vision", list)
        assertFalse(vision(list[0])); assertTrue(vision(list[1]))
    }

    @Test
    fun streamingFormats() {
        val oa = GatewayLogic.sseOpenAi("id", "m", "hello")
        assertTrue(oa.contains("\"content\":\"hello\""))
        assertTrue(oa.trimEnd().endsWith("data: [DONE]"))
        val an = GatewayLogic.sseAnthropic("id", "m", "hello", 3, 2)
        assertTrue(an.contains("event: message_start"))
        assertTrue(an.contains("\"text\":\"hello\""))
        assertTrue(an.trimEnd().endsWith("\"type\":\"message_stop\"}"))
    }

    @Test
    fun responsesCarryUsage() {
        val j = GatewayLogic.openAiResponse("id", "m", "hi", 4, 1)
        assertEquals(5, j.getJSONObject("usage").getInt("total_tokens"))
        val a = GatewayLogic.anthropicResponse("id", "m", "hi", 4, 1)
        assertEquals("hi", a.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun keysAndAuth() {
        val k = GatewayLogic.newKey()
        assertTrue(k.startsWith("cat-") && k.length == 36)
        assertTrue(GatewayLogic.keyMatches(k, k))
        assertFalse(GatewayLogic.keyMatches("x", k))
        assertFalse(GatewayLogic.keyMatches(null, k))
        assertFalse(GatewayLogic.keyMatches("", ""))
    }

    @Test
    fun customEndpointNormalization() {
        val a = AgentConfig("1", "L", "", "m", true, Provider.CUSTOM, "http://h:1/v1/")
        assertEquals("http://h:1/v1/chat/completions", a.endpoint())
        assertEquals("http://h:1/v1/chat/completions", a.copy(baseUrl = "http://h:1/v1/chat/completions").endpoint())
        assertEquals("http://h:1/v1", AgentConfig.fromJson(JSONObject(a.toJson().toString())).baseUrl.trimEnd('/'))
        assertTrue(Provider.NVIDIA.openAiCompat && !Provider.CLAUDE.openAiCompat && !Provider.WEB.openAiCompat)
        assertEquals(JSONArray().length(), 0)
    }
}
