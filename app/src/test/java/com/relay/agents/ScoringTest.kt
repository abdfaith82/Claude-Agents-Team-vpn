package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    @Test fun speedIsMonotonic() {
        assertEquals(50, Scoring.speed(0.0))
        assertEquals(100, Scoring.speed(250.0))
        assertEquals(0, Scoring.speed(9000.0))
        assertTrue(Scoring.speed(1000.0) > Scoring.speed(3000.0))
    }

    @Test fun biggerModelsScoreHigher() {
        assertTrue(Scoring.intelligence("llama-3.1-8b") < Scoring.intelligence("llama-3.3-70b"))
        assertTrue(Scoring.intelligence("gemini-2.5-flash") < Scoring.intelligence("gemini-2.5-pro"))
        assertEquals(100, Scoring.intelligence("qwen3-235b-a22b"))
    }

    @Test fun smartestStrategyPicksStrongerModel() {
        val r = AgentRouter(now = { 1_000_000L })
        r.load(listOf(
            AgentConfig("a", "a", "k", "llama-3.1-8b", true, Provider.GROQ),
            AgentConfig("b", "b", "k", "gpt-oss-120b", true, Provider.GROQ)
        ))
        val p = r.pick(strat = AgentRouter.Strategy.SMARTEST) as AgentRouter.Pick.Ready
        assertEquals("b", p.health.agent.id)
    }
}
