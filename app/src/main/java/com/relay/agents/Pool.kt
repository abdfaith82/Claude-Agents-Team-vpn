package com.relay.agents

import kotlinx.coroutines.delay

/** رد من وكيل مع بياناته. */
class Ans(val text: String, val id: String, val name: String, val provider: Provider, val ms: Long)

/**
 * مجمّع الوكلاء المشترك بين المحادثة والبوابة المحلية: موجّه واحد يعرف حالة كل وكيل (راحة/تعطيل/زمن/موثوقية)،
 * فلا يُرهَق وكيل في مكان وهو متعثر في آخر.
 */
/** محاولة واحدة ضمن مسار طلب. */
class Attempt(val name: String, val ok: Boolean, val detail: String)

class AgentPool {
    /** مسار آخر طلب: من جُرّب وماذا حدث. */
    @Volatile var lastTrace: List<Attempt> = emptyList()

    val router = AgentRouter()
    private var sig = ""

    /** false = لا تبديل بين الوكلاء: يُجرَّب وكيل واحد فقط في كل طلب. */
    var failover = true
    var lastError = ""

    var strategy: AgentRouter.Strategy
        get() = router.strategy
        set(v) { router.strategy = v }

    fun sync(agents: List<AgentConfig>) {
        val s = agents.joinToString("|") { "${it.id}:${it.model}:${it.enabled}:${it.baseUrl}:${it.apiKey.hashCode()}" }
        if (s != sig) { sig = s; router.load(agents) }
    }

    suspend fun ask(
        system: String, user: String, tokens: Int,
        eligible: (AgentConfig) -> Boolean, status: (String) -> Unit,
        images: List<ImageData> = emptyList(), maxWaitMs: Long = 45_000L,
        strat: AgentRouter.Strategy? = null
    ): Ans? {
        var waited = 0L
        var tries = 0
        val trace = mutableListOf<Attempt>()
        lastTrace = emptyList()
        while (tries < 20) {
            if (!failover && tries >= 1) return null
            when (val p = router.pick(eligible, strat ?: router.strategy)) {
                AgentRouter.Pick.None -> {
                    if (lastError.isEmpty()) lastError = "No usable agent"
                    return null
                }
                is AgentRouter.Pick.Wait -> {
                    if (waited > maxWaitMs) { lastError = "All agents are cooling down"; return null }
                    val d = minOf(p.ms, 5_000L)
                    status(trf("⏳ كل الوكلاء في راحة… %s ثانية", p.ms / 1000))
                    delay(d); waited += d
                }
                is AgentRouter.Pick.Ready -> {
                    tries++
                    val a = p.health.agent
                    router.markStart(a.id)
                    status(trf("⏳ %s يجيب…", a.name))
                    when (val r = ClaudeClient.send(a, system, user, tokens, images)) {
                        is ApiResult.Ok -> { router.onSuccess(a.id, r.ms); trace += Attempt(a.name, true, "200 · ${r.ms} ms"); lastTrace = trace.toList(); return Ans(r.text.trim(), a.id, a.name, a.provider, r.ms) }
                        is ApiResult.Fail -> {
                            val cd = router.onFailure(a.id, r.code, r.retryAfterMs, r.fatal, r.message)
                            trace += Attempt(a.name, false, if (cd < 0) "${r.code} → disabled" else "${r.code} → skipped, cooled ${cd / 1000} s")
                            lastTrace = trace.toList()
                            lastError = "${a.name}: ${r.code} ${r.message.take(120)}"
                            status(trf("⚠️ %s توقف، ينتقل للتالي", a.name))
                        }
                    }
                }
            }
        }
        return null
    }
}
