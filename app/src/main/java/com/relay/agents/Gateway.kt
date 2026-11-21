package com.relay.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** تحليل HTTP/1.1 بسيط وآمن الحدود (للخادم المحلي فقط). */
object Http {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private const val MAX_HEADERS = 16 * 1024
    const val MAX_BODY = 4 * 1024 * 1024

    /** يقرأ الطلب. out يُستخدم للرد بـ 100-continue عند الطلب. يرجع null إن كان الطلب فاسداً أو كبيراً. */
    fun read(input: InputStream, out: OutputStream?): Req? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val pattern = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || head.size() > MAX_HEADERS) return null
            head.write(b)
            matched = if (b.toByte() == pattern[matched]) matched + 1 else if (b.toByte() == pattern[0]) 1 else 0
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        if (len > 0 && headers["expect"]?.contains("100-continue", true) == true) {
            out?.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out?.flush()
        }
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) return null
            read += n
        }
        return Req(first[0].uppercase(), first[1], headers, body)
    }

    fun response(code: Int, status: String, extra: Map<String, String>, body: ByteArray, type: String = "application/json"): ByteArray {
        val h = StringBuilder("HTTP/1.1 $code $status\r\n")
        h.append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        h.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
        h.append("\r\n")
        return h.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    fun sseHead(extra: Map<String, String>): ByteArray {
        val h = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n")
        h.append("Access-Control-Allow-Origin: *\r\n")
        extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
        h.append("\r\n")
        return h.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

/** تحويل صيغ OpenAI وAnthropic إلى طلب موحّد وبناء الردود (بلا أي اعتماد على أندرويد). */
object GatewayLogic {
    class ChatReq(val model: String, val system: String, val user: String, val maxTokens: Int, val stream: Boolean)

    private fun textOf(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> buildString {
            for (i in 0 until c.length()) {
                val p = c.optJSONObject(i) ?: continue
                val t = p.optString("text")
                if (t.isNotEmpty() && (p.optString("type").let { it == "text" || it == "input_text" || it.isEmpty() })) {
                    if (isNotEmpty()) append('\n')
                    append(t)
                }
            }
        }
        else -> ""
    }

    private fun flatten(msgs: List<Pair<String, String>>, compress: Boolean): String {
        if (msgs.size == 1 && msgs[0].first == "user") return msgs[0].second
        return buildString {
            msgs.forEachIndexed { i, (role, text) ->
                append(if (role == "assistant") "Assistant: " else "User: ")
                append(if (compress && i < msgs.size - 2) PromptCompressor.compress(text) else text).append("\n\n")
            }
            append("Reply to the last User message.")
        }
    }

    private fun cap(v: Int) = v.coerceIn(16, 4096)

    fun parseOpenAi(b: JSONObject, compress: Boolean = false): ChatReq {
        val arr = b.optJSONArray("messages") ?: JSONArray()
        val sys = StringBuilder()
        val rest = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val text = textOf(m.opt("content"))
            if (text.isBlank()) continue
            if (role == "system" || role == "developer") { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(text) }
            else rest.add(role to text)
        }
        val max = if (b.has("max_completion_tokens")) b.optInt("max_completion_tokens", 2048) else b.optInt("max_tokens", 2048)
        return ChatReq(b.optString("model", "auto"), sys.toString(), flatten(rest, compress), cap(max), b.optBoolean("stream", false))
    }

    fun parseAnthropic(b: JSONObject, compress: Boolean = false): ChatReq {
        val arr = b.optJSONArray("messages") ?: JSONArray()
        val rest = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val text = textOf(m.opt("content"))
            if (text.isNotBlank()) rest.add(m.optString("role") to text)
        }
        return ChatReq(
            b.optString("model", "auto"), textOf(b.opt("system")), flatten(rest, compress),
            cap(b.optInt("max_tokens", 2048)), b.optBoolean("stream", false)
        )
    }

    fun tokens(s: String): Int = (s.length / 4).coerceAtLeast(1)

    fun openAiResponse(id: String, model: String, text: String, inTok: Int, outTok: Int): JSONObject = JSONObject()
        .put("id", id).put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000).put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", text)).put("finish_reason", "stop")))
        .put("usage", JSONObject().put("prompt_tokens", inTok).put("completion_tokens", outTok).put("total_tokens", inTok + outTok))

    fun anthropicResponse(id: String, model: String, text: String, inTok: Int, outTok: Int): JSONObject = JSONObject()
        .put("id", id).put("type", "message").put("role", "assistant").put("model", model)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", inTok).put("output_tokens", outTok))

    private fun chunks(text: String): List<String> = if (text.isEmpty()) listOf("") else text.chunked(300)

    fun sseOpenAi(id: String, model: String, text: String): String = buildString {
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: JSONObject, finish: Any?) = JSONObject().put("id", id).put("object", "chat.completion.chunk")
            .put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))
        append("data: ").append(chunk(JSONObject().put("role", "assistant").put("content", ""), null)).append("\n\n")
        chunks(text).forEach { append("data: ").append(chunk(JSONObject().put("content", it), null)).append("\n\n") }
        append("data: ").append(chunk(JSONObject(), "stop")).append("\n\n")
        append("data: [DONE]\n\n")
    }

    fun sseAnthropic(id: String, model: String, text: String, inTok: Int, outTok: Int): String = buildString {
        fun ev(name: String, data: JSONObject) { append("event: ").append(name).append("\ndata: ").append(data).append("\n\n") }
        ev("message_start", JSONObject().put("type", "message_start").put("message", JSONObject()
            .put("id", id).put("type", "message").put("role", "assistant").put("model", model)
            .put("content", JSONArray()).put("stop_reason", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", inTok).put("output_tokens", 1))))
        ev("content_block_start", JSONObject().put("type", "content_block_start").put("index", 0)
            .put("content_block", JSONObject().put("type", "text").put("text", "")))
        chunks(text).forEach {
            ev("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", it)))
        }
        ev("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", 0))
        ev("message_delta", JSONObject().put("type", "message_delta")
            .put("delta", JSONObject().put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL))
            .put("usage", JSONObject().put("output_tokens", outTok)))
        ev("message_stop", JSONObject().put("type", "message_stop"))
    }

    fun error(msg: String, type: String): String =
        JSONObject().put("error", JSONObject().put("message", msg).put("type", type)).toString()

    /** يحدّد الوكلاء المؤهلين واستراتيجية الطلب من اسم النموذج: auto, auto:fast, auto:reliable, auto:priority, auto:vision أو اسم وكيل. */
    fun route(model: String, agents: List<AgentConfig>): Pair<(AgentConfig) -> Boolean, AgentRouter.Strategy?> {
        val base = { a: AgentConfig -> a.enabled && !a.provider.manual }
        val m = model.trim().lowercase()
        return when (m) {
            "auto:fast" -> base to AgentRouter.Strategy.FASTEST
            "auto:reliable" -> base to AgentRouter.Strategy.RELIABLE
            "auto:priority" -> base to AgentRouter.Strategy.PRIORITY
            "auto:smart" -> base to AgentRouter.Strategy.SMARTEST
            "auto:vision" -> ({ a: AgentConfig -> base(a) && a.provider.vision }) to null
            else -> {
                val ids = agents.filter { it.name.lowercase() == m || it.model.lowercase() == m }.map { it.id }.toSet()
                // اسم نموذج غير معروف (مثل gpt-4o) يُعامل كـ auto بدل الفشل.
                if (ids.isEmpty()) base to null else ({ a: AgentConfig -> base(a) && a.id in ids }) to null
            }
        }
    }

    fun keyMatches(provided: String?, expected: String): Boolean =
        provided != null && expected.isNotEmpty() &&
            MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())

    fun newKey(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "cat-" + b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * بوابة محلية متوافقة مع OpenAI وAnthropic: تعرض مجمّع وكلائك كنقطة نهاية واحدة بمفتاح موحّد،
 * فيستطيع أي تطبيق (Termux، Claude Code، عملاء OpenAI) استخدام التبديل التلقائي دون مفاتيح المزوّدين.
 * تعمل داخل التطبيق؛ الربط على 127.0.0.1 افتراضياً، وعلى الشبكة المحلية بطلبك فقط.
 */
class GatewayServer(
    private val pool: AgentPool,
    private val agents: () -> List<AgentConfig>,
    private val apiKey: () -> String,
    private val cacheOn: () -> Boolean,
    private val compressOn: () -> Boolean
) {
    var running by mutableStateOf(false)
        private set
    var requests by mutableIntStateOf(0)
        private set
    var lastError by mutableStateOf("")
        private set

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private val permits = Semaphore(4)

    fun start(port: Int, lan: Boolean): Result<Unit> = runCatching {
        if (running) return@runCatching
        val ss = ServerSocket(port, 20, if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1"))
        server = ss
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        running = true
        lastError = ""
        sc.launch {
            while (isActive) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                launch { permits.withPermit { runCatching { handle(s) }.onFailure { lastError = it.message ?: "error" } } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        scope?.cancel()
        server = null; scope = null
        running = false
    }

    private fun json(out: OutputStream, code: Int, status: String, body: String, extra: Map<String, String> = emptyMap()) {
        out.write(Http.response(code, status, extra, body.toByteArray(Charsets.UTF_8))); out.flush()
    }

    private suspend fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = Http.read(input, out) ?: return
            val path = req.path.substringBefore('?')
            if (req.method == "OPTIONS") { json(out, 204, "No Content", ""); return }
            if (path == "/" || path == "/health") {
                json(out, 200, "OK", JSONObject().put("status", "ok").put("agents", agents().count { it.enabled }).toString()); return
            }
            val bearer = req.headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            val provided = bearer ?: req.headers["x-api-key"]
            if (!GatewayLogic.keyMatches(provided, apiKey())) {
                json(out, 401, "Unauthorized", GatewayLogic.error("Invalid API key", "authentication_error")); return
            }
            when {
                req.method == "GET" && path == "/v1/models" -> {
                    val data = JSONArray()
                    listOf("auto", "auto:fast", "auto:reliable", "auto:priority", "auto:vision").forEach {
                        data.put(JSONObject().put("id", it).put("object", "model").put("owned_by", "claude-agents-team"))
                    }
                    agents().filter { it.enabled && !it.provider.manual }.forEach {
                        data.put(JSONObject().put("id", it.name).put("object", "model").put("owned_by", it.provider.label))
                    }
                    json(out, 200, "OK", JSONObject().put("object", "list").put("data", data).toString())
                }
                req.method == "POST" && (path == "/v1/chat/completions" || path == "/v1/messages") ->
                    chat(req, out, anthropic = path == "/v1/messages")
                else -> json(out, 404, "Not Found", GatewayLogic.error("Unknown endpoint $path", "not_found_error"))
            }
        }
    }

    private suspend fun chat(req: Http.Req, out: OutputStream, anthropic: Boolean) {
        val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
        if (body == null) { json(out, 400, "Bad Request", GatewayLogic.error("Invalid JSON body", "invalid_request_error")); return }
        val compress = compressOn()
        val cr = if (anthropic) GatewayLogic.parseAnthropic(body, compress) else GatewayLogic.parseOpenAi(body, compress)
        if (cr.user.isBlank()) { json(out, 400, "Bad Request", GatewayLogic.error("No messages", "invalid_request_error")); return }
        val list = agents()
        pool.sync(list)
        requests++
        val (eligible, strat) = GatewayLogic.route(cr.model, list)
        val system = cr.system.ifBlank { "You are a helpful assistant." }

        val ck = if (cacheOn()) ResponseCache.key(system + cr.model, cr.user) else null
        var text: String; var by: String; var routed: String
        val hit = ck?.let { ResponseCache.get(it) }
        if (hit != null) {
            text = hit.first; by = hit.second; routed = hit.second + " (cache)"
        } else {
            pool.lastError = ""
            val a = pool.ask(system, cr.user, cr.maxTokens, eligible, {}, maxWaitMs = 30_000L, strat = strat)
            if (a == null) {
                json(out, 503, "Service Unavailable",
                    GatewayLogic.error("All agents are unavailable: " + pool.lastError.ifEmpty { "no agents configured" }, "overloaded_error"))
                return
            }
            text = a.text; by = a.name; routed = a.provider.label + "/" + a.name
            if (ck != null) ResponseCache.put(ck, text, by)
        }
        val inTok = GatewayLogic.tokens(system + cr.user)
        val outTok = GatewayLogic.tokens(text)
        val id = (if (anthropic) "msg_" else "chatcmpl-") + UUID.randomUUID().toString().replace("-", "").take(24)
        val model = by
        val extra = mapOf("X-Routed-Via" to routed.replace('\n', ' '))
        if (cr.stream) {
            out.write(Http.sseHead(extra))
            val sse = if (anthropic) GatewayLogic.sseAnthropic(id, model, text, inTok, outTok) else GatewayLogic.sseOpenAi(id, model, text)
            out.write(sse.toByteArray(Charsets.UTF_8)); out.flush()
        } else {
            val j = if (anthropic) GatewayLogic.anthropicResponse(id, model, text, inTok, outTok)
            else GatewayLogic.openAiResponse(id, model, text, inTok, outTok)
            json(out, 200, "OK", j.toString(), extra)
        }
    }
}
