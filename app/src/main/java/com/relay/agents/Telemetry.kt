package com.relay.agents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** سجل مبسّط لكل طلب API لعرض لوحة التحليلات (آخر 2000 طلب محفوظة على الجهاز). */
object Telemetry {
    data class Rec(
        val ts: Long, val agentId: String, val agentName: String, val provider: String,
        val ok: Boolean, val ms: Long, val inChars: Int, val outChars: Int, val code: Int
    )

    data class AgentStat(
        val agentId: String, val name: String, val provider: String,
        val requests: Int, val ok: Int, val avgMs: Long, val tokens: Long
    ) {
        val successPct: Int get() = if (requests == 0) 0 else ok * 100 / requests
    }

    private const val MAX = 2000
    private val recs = ArrayList<Rec>()
    private var file: File? = null
    private var sinceSave = 0

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "telemetry.json")
        runCatching {
            val f = file!!
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    recs.add(Rec(o.getLong("t"), o.getString("i"), o.getString("n"), o.getString("p"),
                        o.getBoolean("k"), o.getLong("m"), o.getInt("a"), o.getInt("b"), o.getInt("c")))
                }
            }
        }
    }

    @Synchronized
    fun record(a: AgentConfig, ok: Boolean, ms: Long, inChars: Int, outChars: Int, code: Int) {
        recs.add(Rec(System.currentTimeMillis(), a.id, a.name, a.provider.name, ok, ms, inChars, outChars, code))
        while (recs.size > MAX) recs.removeAt(0)
        if (++sinceSave >= 10) flush()
    }

    @Synchronized
    fun flush() {
        sinceSave = 0
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            recs.forEach {
                arr.put(JSONObject().put("t", it.ts).put("i", it.agentId).put("n", it.agentName).put("p", it.provider)
                    .put("k", it.ok).put("m", it.ms).put("a", it.inChars).put("b", it.outChars).put("c", it.code))
            }
            f.writeText(arr.toString())
        }
    }

    @Synchronized
    fun clear() { recs.clear(); flush() }

    /** إحصاءات لكل وكيل منذ وقت معيّن (التوكنز تقديرية = أحرف ÷ 4). */
    @Synchronized
    fun stats(sinceMs: Long): List<AgentStat> = statsOf(recs, sinceMs)

    fun statsOf(list: List<Rec>, sinceMs: Long): List<AgentStat> =
        list.filter { it.ts >= sinceMs }.groupBy { it.agentId }.map { (id, rs) ->
            val oks = rs.filter { it.ok }
            AgentStat(
                id, rs.last().agentName, rs.last().provider, rs.size, oks.size,
                if (oks.isEmpty()) 0 else oks.map { it.ms }.average().toLong(),
                rs.sumOf { (it.inChars + it.outChars).toLong() } / 4
            )
        }.sortedByDescending { it.requests }
}

/** ذاكرة مؤقتة للردود (اختيارية): نفس الموجّه خلال 30 دقيقة يُجاب فوراً دون استهلاك حصة. */
object ResponseCache {
    private const val MAX = 50
    private const val TTL = 30 * 60_000L
    private class Entry(val text: String, val by: String, val at: Long)
    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX
    }

    fun key(system: String, user: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(system.toByteArray()); md.update(0); md.update(user.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Synchronized
    fun get(key: String, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        val e = map[key] ?: return null
        if (now - e.at > TTL) { map.remove(key); return null }
        return e.text to e.by
    }

    @Synchronized
    fun put(key: String, text: String, by: String, now: Long = System.currentTimeMillis()) {
        map[key] = Entry(text, by, now)
    }

    @Synchronized
    fun clear() = map.clear()
}

/** ضغط خفيف للموجّهات: يزيل الفراغات والأسطر المكررة ويقصّ النصوص الطويلة القديمة. */
object PromptCompressor {
    fun compress(text: String, maxLen: Int = 2000): String {
        val out = ArrayList<String>()
        var blank = false
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (!blank && out.isNotEmpty()) out.add("")
                blank = true
                continue
            }
            blank = false
            if (out.isNotEmpty() && out.last() == line) continue
            out.add(line)
        }
        val joined = out.joinToString("\n").trim()
        if (joined.length <= maxLen) return joined
        val half = maxLen / 2
        return joined.take(half) + "\n…[trimmed]…\n" + joined.takeLast(half)
    }
}
