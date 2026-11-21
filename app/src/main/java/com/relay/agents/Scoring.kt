package com.relay.agents

import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/** تقييم الوكلاء بثلاثة محاور (موثوقية، سرعة، ذكاء) لعرض الترتيب والتوجيه «الأذكى». */
object Scoring {
    data class Weights(val reliability: Double, val speed: Double, val intelligence: Double)

    fun weights(s: AgentRouter.Strategy): Weights = when (s) {
        AgentRouter.Strategy.SMARTEST -> Weights(0.2, 0.1, 0.7)
        AgentRouter.Strategy.FASTEST -> Weights(0.2, 0.6, 0.2)
        AgentRouter.Strategy.RELIABLE -> Weights(0.7, 0.15, 0.15)
        AgentRouter.Strategy.BALANCED, AgentRouter.Strategy.PRIORITY -> Weights(0.5, 0.25, 0.25)
    }

    /** 0..100؛ غير مقاس بعد = 50. أقل من 300ms = 100 وأكثر من 8 ثوانٍ = 0 بتدرّج لوغاريتمي. */
    fun speed(latencyMs: Double): Int = when {
        latencyMs <= 0.0 -> 50
        latencyMs <= 300.0 -> 100
        latencyMs >= 8000.0 -> 0
        else -> (100 * (1 - ln(latencyMs / 300.0) / ln(8000.0 / 300.0))).roundToInt()
    }

    private val PARAMS = Regex("""(\d+(?:\.\d+)?)\s*b\b""")

    /** تقدير تقريبي للذكاء من حجم النموذج المذكور في اسمه أو من عائلته؛ مجرد ترتيب إرشادي لا اختبار أداء. */
    fun intelligence(model: String): Int {
        val m = model.lowercase()
        val params = PARAMS.findAll(m).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
        val tokens = m.split(Regex("[^a-z0-9]+")).toSet()
        val base = when {
            params != null && params > 0 -> 25 + 11 * log2(params)
            listOf("haiku", "flash", "mini", "lite", "small", "nano", "instant").any { it in tokens } -> 65.0
            ("gpt-5" in m || listOf("opus", "ultra", "pro").any { it in tokens }) -> 92.0
            ("gpt-4" in m || "gemini" in m || listOf("sonnet", "large").any { it in tokens }) -> 85.0
            else -> 60.0
        }
        return base.coerceIn(10.0, 100.0).roundToInt()
    }

    /** النتيجة 0..1. */
    fun score(w: Weights, reliability: Double, speed: Int, intelligence: Int): Double =
        (w.reliability * reliability * 100 + w.speed * speed + w.intelligence * intelligence) / 100.0
}
