package com.tom.rv2ide.artificial.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateTest {

  @Test
  fun `forward chain is legal`() {
    val chain = listOf(
        AgentState.IDLE to AgentState.THINKING,
        AgentState.THINKING to AgentState.PROPOSING_TOOLS,
        AgentState.PROPOSING_TOOLS to AgentState.EXECUTING,
        AgentState.EXECUTING to AgentState.AWAITING_USER,
        AgentState.AWAITING_USER to AgentState.EXECUTING,
        AgentState.EXECUTING to AgentState.OBSERVING,
        AgentState.OBSERVING to AgentState.THINKING,
        AgentState.OBSERVING to AgentState.FINALIZING,
        AgentState.PROPOSING_TOOLS to AgentState.FINALIZING,
        AgentState.FINALIZING to AgentState.DONE
    )
    chain.forEach { (from, to) ->
      assertTrue("$from -> $to", AgentState.canTransition(from, to))
    }
  }

  @Test
  fun `cancel reachable from live states only`() {
    listOf(
        AgentState.IDLE, AgentState.THINKING, AgentState.PROPOSING_TOOLS,
        AgentState.AWAITING_USER, AgentState.EXECUTING, AgentState.OBSERVING,
        AgentState.FINALIZING
    ).forEach {
      assertTrue("$it -> CANCELLED", AgentState.canTransition(it, AgentState.CANCELLED))
    }
    assertFalse(AgentState.canTransition(AgentState.DONE, AgentState.CANCELLED))
    assertFalse(AgentState.canTransition(AgentState.FAILED, AgentState.CANCELLED))
  }

  @Test
  fun `terminal states have no outgoing transitions`() {
    AgentState.values().forEach { next ->
      assertFalse(AgentState.canTransition(AgentState.DONE, next))
      assertFalse(AgentState.canTransition(AgentState.FAILED, next))
      assertFalse(AgentState.canTransition(AgentState.CANCELLED, next))
    }
  }

  @Test
  fun `illegal jumps rejected`() {
    assertFalse(AgentState.canTransition(AgentState.IDLE, AgentState.EXECUTING))
    assertFalse(AgentState.canTransition(AgentState.DONE, AgentState.THINKING))
    assertFalse(AgentState.canTransition(AgentState.THINKING, AgentState.DONE))
  }
}

class AgentRunTest {

  private fun run() = AgentRun(
      runId = "test",
      mode = com.tom.rv2ide.artificial.tools.RunMode.BUILD,
      budget = RunBudget(maxSteps = 3, maxRetries = 2, doomRepeat = 3)
  )

  @Test
  fun `budget exhausts after max steps`() {
    val run = run()
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("a"))
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("b"))
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("c"))
    assertEquals(AgentRun.StepVerdict.BUDGET_EXHAUSTED, run.registerStep("d"))
    assertEquals(4, run.stepCount)
  }

  @Test
  fun `same call three times is doom loop`() {
    val run = run()
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
    assertEquals(AgentRun.StepVerdict.DOOM_LOOP, run.registerStep("local:read_file{p=\"x\"}"))
  }

  @Test
  fun `varying calls never trip doom loop`() {
    val run = run()
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("a"))
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("a"))
    assertEquals(AgentRun.StepVerdict.OK, run.registerStep("b"))
  }

  @Test
  fun `two consecutive failures request stop, success resets`() {
    val run = run()
    assertFalse(run.registerFailure())
    assertTrue(run.registerFailure())
    run.registerSuccess()
    assertFalse(run.registerFailure())
  }

  @Test
  fun `transcript trims with tombstone`() {
    val run = run()
    repeat(45) { run.addEntry(EntryRole.TOOL_RESULT, "r$it", maxEntries = 10) }
    assertEquals(11, run.transcript.size)
    assertEquals(EntryRole.SYSTEM, run.transcript.first().role)
  }

  @Test
  fun `transition and cancel`() {
    val run = run()
    assertTrue(run.transitionTo(AgentState.THINKING))
    assertFalse(run.transitionTo(AgentState.DONE))
    run.cancel()
    assertEquals(AgentState.CANCELLED, run.state)
    assertTrue(run.job.isCancelled)
  }
}
