package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class StepAssigneeTest {
    @Test
    fun assigneeSurvivesJson() {
        val s = Step("t", assignee = "a1")
        assertEquals("a1", Step.fromJson(s.toJson()).assignee)
    }

    @Test
    fun oldCheckpointsWithoutAssigneeStillLoad() {
        val o = org.json.JSONObject().put("title", "x").put("status", "PENDING")
        assertEquals("", Step.fromJson(o).assignee)
    }
}
