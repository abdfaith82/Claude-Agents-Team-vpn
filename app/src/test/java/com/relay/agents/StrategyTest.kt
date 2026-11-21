package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrategyTest {
    private var t = 1_000_000L
    private lateinit var router: AgentRouter

    private fun agent(id: String) = AgentConfig(id, id, "k", "m", true, Provider.GROQ)
    private fun ready(p: AgentRouter.Pick): String = (p as AgentRouter.Pick.Ready).health.agent.id

    @Before
    fun setUp() {
        t = 1_000_000L
        router = AgentRouter(now = { t }, jitter = { 1.0 })
        router.load(listOf(agent("A"), agent("B")))
    }

    @Test
    fun priorityUsesListOrderThenFallsBack() {
        router.strategy = AgentRouter.Strategy.PRIORITY
        assertEquals("A", ready(router.pick()))
        router.markStart("A")
        assertEquals("B", ready(router.pick()))
    }

    @Test
    fun fastestPrefersLowerLatency() {
        router.onSuccess("A", 900)
        router.onSuccess("B", 200)
        assertEquals("B", ready(router.pick(strat = AgentRouter.Strategy.FASTEST)))
    }

    @Test
    fun reliablePrefersHigherSuccessRate() {
        router.onSuccess("A")
        router.onFailure("A", 429, null, false, "rate")
        router.onSuccess("B"); router.onSuccess("B")
        t += 100_000
        assertEquals("B", ready(router.pick(strat = AgentRouter.Strategy.RELIABLE)))
    }

    @Test
    fun dailyQuotaWaitsUntilUtcMidnight() {
        router.load(listOf(agent("A")))
        val cool = router.onFailure("A", 429, null, false, "Rate limit reached: requests per day exceeded")
        assertEquals(86_400_000L - t, cool)
        assertTrue(router.pick() is AgentRouter.Pick.Wait)
    }

    @Test
    fun ewmaSmoothsLatency() {
        router.onSuccess("A", 1000)
        router.onSuccess("A", 2000)
        val l = router.stats()["A"]!!.first
        assertEquals(1300.0, l, 0.001)
    }
}
