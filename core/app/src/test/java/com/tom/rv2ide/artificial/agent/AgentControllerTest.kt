package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.PermissionRule
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolDecision
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Minimal probe tool: no Android dependencies, no I/O, deterministic.
 *
 * Everything a test needs from a tool is expressed through [body], so these
 * tests exercise the *loop* (proposal, execution, result accounting, stop
 * rules) rather than tool internals, which already have their own suites.
 */
private fun probe(
    id: String,
    kind: ToolKind = ToolKind.READ,
    confirm: ConfirmPolicy = ConfirmPolicy.NEVER,
    withSchema: Boolean = false,
    body: suspend (Map<String, Any?>, ToolContext) -> ToolResult = { _, _ ->
      ToolResult.success("ok")
    }
): Tool = object : Tool {
  override val id: String = id
  override val namespace: String = id.substringBefore(':')
  override val description: String = "test probe"
  override val schema: ToolSchema = if (withSchema) {
    ToolSchema(
        listOf(
            ToolInputField("path", ToolInputType.STRING, "p", true),
            ToolInputField("content", ToolInputType.STRING, "c", true)
        )
    )
  } else {
    ToolSchema(emptyList())
  }
  override val kind: ToolKind = kind
  override val readOnlyHint: Boolean = kind == ToolKind.READ
  override val timeoutSec: Long = 10L
  override val confirmPolicy: ConfirmPolicy = confirm
  override val visible: Boolean = true
  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult =
      body(args, ctx)
}

/**
 * STEP 1 characterization suite for [AgentController].
 *
 * These tests describe what the controller does *today*, defects included.
 * Where a test documents a known contract gap it is marked
 * CHARACTERIZATION (STEP 1) together with the step meant to flip it.
 * Production behavior may not be changed just to make one of these pass.
 */
class AgentControllerTest {

  private lateinit var root: File
  private val eventLog = mutableListOf<AgentEvents>()
  private var controller: AgentController? = null

  @Before
  fun setUp() {
    ToolRegistry.clear()
    eventLog.clear()
    controller = null
    root = Files.createTempDirectory("agent-controller-test").toFile()
  }

  @After
  fun tearDown() {
    ToolRegistry.clear()
    root.deleteRecursively()
  }

  /** Runs one full agent turn and returns the run, failing the test if none. */
  private fun executeRun(
      replies: List<String>,
      mode: RunMode = RunMode.BUILD,
      permissionFor: (RunMode) -> ToolPermission = {
        ToolPermission.buildDefault(planMode = it == RunMode.PLAN)
      },
      onConfirm: suspend (ToolCall) -> Boolean = { true },
      onEvent: (AgentEvents) -> Unit = {},
      request: String = "do the work"
  ): AgentRun {
    val c = AgentController(
        providerCall = FakeProviderCall(replies),
        permissionFor = permissionFor
    )
    controller = c
    var captured: AgentRun? = null
    try {
      runBlocking {
        c.runAgent(
            userRequest = request,
            mode = mode,
            projectRoot = root,
            events = { event ->
              eventLog.add(event)
              if (event is AgentEvents.RunStarted) {
                captured = c.activeRun
              }
              onEvent(event)
            },
            onConfirm = onConfirm
        ).also { captured = it }
      }
    } catch (t: Throwable) {
      // A cancelled run rethrows by contract; anything else is a real failure.
      if (t !is CancellationException) throw t
    }
    return requireNotNull(captured) { "runAgent never reported RunStarted" }
  }

  private fun call(id: String, tag: String): String = "TOOL_CALL: $id {\"tag\":\"$tag\"}"

  private fun proposed(): List<AgentEvents.ToolsProposed> =
      eventLog.filterIsInstance<AgentEvents.ToolsProposed>()

  private fun started(): List<AgentEvents.ToolStarted> =
      eventLog.filterIsInstance<AgentEvents.ToolStarted>()

  private fun finished(): List<AgentEvents.ToolFinished> =
      eventLog.filterIsInstance<AgentEvents.ToolFinished>()

  private fun finalAnswer(): AgentEvents.FinalAnswer =
      eventLog.filterIsInstance<AgentEvents.FinalAnswer>().single()

  private fun batch(vararg tags: String): String =
      tags.joinToString("\n") { call("test:noop", it) }

  // ---------------------------------------------------------------- basic ---

  @Test
  fun `one tool call executes then a final answer ends the run`() {
    ToolRegistry.register(probe("test:noop"))
    val run = executeRun(listOf(call("test:noop", "a"), "All done."))
    assertEquals(AgentState.DONE, run.state)
    assertEquals(1, started().size)
    assertEquals(1, finished().size)
    assertTrue(finished()[0].result.ok)
    assertEquals("test:noop", finished()[0].call.name)
    assertEquals("All done.", finalAnswer().text)
    assertFalse(finalAnswer().legacyModifications)
  }

  @Test
  fun `plain reply with no tool calls ends the run with a final answer`() {
    val run = executeRun(listOf("Just an answer."))
    // CHARACTERIZATION (STEP 1): THINKING has no edge to FINALIZING, so a run
    // that answers before ever proposing a tool never reaches DONE even though
    // FinalAnswer fires. STEP 5 owns accurate termination state.
    assertEquals(AgentState.THINKING, run.state)
    assertTrue(started().isEmpty())
    assertEquals("Just an answer.", finalAnswer().text)
    assertNull(run.checkpoint)
  }

  @Test
  fun `multiple tool calls in one response all execute and all commit results`() {
    val order = mutableListOf<String>()
    ToolRegistry.register(probe("test:noop") { args, _ ->
      order.add(args["tag"].toString())
      ToolResult.success("ok")
    })
    val run = executeRun(listOf(batch("a", "b", "c"), "All done."))
    assertEquals(AgentState.DONE, run.state)
    assertEquals(3, started().size)
    assertEquals(3, finished().size)
    assertTrue(finished().all { it.result.ok })
    assertEquals(listOf("a", "b", "c"), order)
    assertEquals(3, proposed()[0].calls.size)
    assertEquals("All done.", finalAnswer().text)
  }

  @Test
  fun `calls in one batch run sequentially, never concurrently`() {
    ToolRegistry.register(probe("test:noop"))
    executeRun(listOf(batch("a", "b", "c"), "All done."))
    val observed = eventLog
        .filter { it is AgentEvents.ToolStarted || it is AgentEvents.ToolFinished }
        .map {
          when (it) {
            is AgentEvents.ToolStarted -> "start:" + it.call.args["tag"]
            is AgentEvents.ToolFinished -> "finish:" + it.call.args["tag"]
            else -> error("unexpected event $it")
          }
        }
    assertEquals(
        listOf("start:a", "finish:a", "start:b", "finish:b", "start:c", "finish:c"),
        observed
    )
  }

  // ------------------------------------------------------------- failure ---

  @Test
  fun `tool failure is reported and the run continues to a final answer`() {
    ToolRegistry.register(probe("test:fail") { _, _ -> ToolResult.failure("boom") })
    val run = executeRun(listOf(call("test:fail", "a"), "Recovered."))
    assertEquals(AgentState.DONE, run.state)
    assertEquals(1, finished().size)
    assertFalse(finished()[0].result.ok)
    assertTrue(finished()[0].result.error!!.contains("boom"))
    assertEquals(1, eventLog.filterIsInstance<AgentEvents.Retrying>().size)
    assertEquals("Recovered.", finalAnswer().text)
  }

  @Test
  fun `denied tool never reaches the tool body`() {
    var executed = false
    ToolRegistry.register(probe("test:denied") { _, _ ->
      executed = true
      ToolResult.success("must not run")
    })
    val run = executeRun(
        replies = listOf(call("test:denied", "a"), "Stopped."),
        permissionFor = {
          ToolPermission(
              rules = listOf(PermissionRule("test:denied", ToolDecision.DENY)),
              planMode = false
          )
        }
    )
    assertEquals(AgentState.DONE, run.state)
    assertFalse(executed)
    assertEquals(1, finished().size)
    assertTrue(finished()[0].result.error!!.contains("not permitted"))
    assertTrue(finished()[0].result.error!!.contains("test:denied"))
  }

  @Test
  fun `confirm-gated tool denied by the user fails without executing`() {
    var executed = false
    ToolRegistry.register(probe("test:danger", kind = ToolKind.DESTRUCTIVE) { _, _ ->
      executed = true
      ToolResult.success("must not run")
    })
    val run = executeRun(
        replies = listOf(call("test:danger", "a"), "All done."),
        onConfirm = { false }
    )
    assertEquals(AgentState.DONE, run.state)
    assertFalse(executed)
    assertTrue(eventLog.filterIsInstance<AgentEvents.ApprovalRequired>().isNotEmpty())
    assertEquals(1, finished().size)
    assertTrue(finished()[0].result.error!!.contains("User denied"))
  }

  @Test
  fun `malformed tool call becomes a parse error and a guided retry`() {
    ToolRegistry.register(probe("test:noop"))
    val run = executeRun(listOf("TOOL_CALL: nope {\"tag\":\"a\"}", "All done."))
    // CHARACTERIZATION (STEP 1): the parse-error retry never leaves THINKING,
    // so the same missing THINKING -> FINALIZING edge leaves the run in
    // THINKING after the retry answer. STEP 5 owns this.
    assertEquals(AgentState.THINKING, run.state)
    assertTrue(started().isEmpty())
    assertEquals(1, eventLog.filterIsInstance<AgentEvents.Retrying>().size)
    assertTrue(run.transcript.any {
      it.role == EntryRole.SYSTEM && it.text.contains("Parse error")
    })
    assertEquals("All done.", finalAnswer().text)
  }

  @Test
  fun `unknown tool name fails and lists what is available`() {
    val run = executeRun(listOf(call("test:nope", "a"), "All done."))
    assertEquals(AgentState.DONE, run.state)
    assertEquals(1, finished().size)
    assertTrue(finished()[0].result.error!!.contains("Unknown tool"))
    assertEquals(1, eventLog.filterIsInstance<AgentEvents.Retrying>().size)
    assertEquals("All done.", finalAnswer().text)
  }

  @Test
  fun `repeated tool failures nudge once and then stop the run`() {
    ToolRegistry.register(probe("test:fail") { _, _ -> ToolResult.failure("boom") })
    val run = executeRun(listOf(
        call("test:fail", "f1"),
        call("test:fail", "f2"),
        call("test:fail", "f3"),
        "never reached"
    ))
    assertEquals(3, finished().size)
    assertTrue(finished().none { it.result.ok })
    assertEquals(2, eventLog.filterIsInstance<AgentEvents.Retrying>().size)
    assertTrue(finalAnswer().text.contains("Stopping after repeated failures"))
    assertTrue(run.transcript.any {
      it.role == EntryRole.SYSTEM && it.text.contains("Re-plan")
    })
    assertEquals(AgentState.DONE, run.state)
  }

  @Test
  fun `provider failure ends the run as FAILED with a reason`() {
    val run = executeRun(emptyList())
    assertEquals(AgentState.FAILED, run.state)
    val failed = eventLog.filterIsInstance<AgentEvents.Failed>().single()
    assertTrue(failed.reason.contains("AI request failed"))
    assertTrue(eventLog.filterIsInstance<AgentEvents.FinalAnswer>().isEmpty())
    assertTrue(started().isEmpty())
  }

  // -------------------------------------------------------------- budget ---

  @Test
  fun `step budget stops the run at 15 tool calls`() {
    ToolRegistry.register(probe("test:noop"))
    val reply = (0 until 18).joinToString("\n") { call("test:noop", "b$it") }
    val run = executeRun(listOf(reply))
    assertEquals(15, started().size)
    assertEquals(15, finished().size)
    assertTrue(finished().all { it.result.ok })
    // All 18 calls are proposed, but only 15 ever start.
    assertEquals(18, proposed()[0].calls.size)
    assertTrue(finalAnswer().text.contains("Stopped after 15 steps"))
    assertEquals(AgentState.DONE, run.state)
  }

  @Test
  fun `identical repeated call trips the doom loop guard`() {
    ToolRegistry.register(probe("test:noop"))
    val same = call("test:noop", "d")
    val run = executeRun(listOf(same, same, same))
    assertEquals(2, started().size)
    assertEquals(2, finished().size)
    assertTrue(finalAnswer().text.contains("was repeated with identical arguments"))
    assertEquals(AgentState.DONE, run.state)
  }

  @Test
  fun `three tool calls in one response consume three steps today`() {
    ToolRegistry.register(probe("test:noop"))
    val run = executeRun(listOf(batch("a", "b", "c"), "All done."))
    // CHARACTERIZATION (STEP 1): one tool call = one step today.
    // STEP 5 moves step accounting to one model turn = one step.
    assertEquals(3, run.stepCount)
  }

  // -------------------------------------------------------- cancellation ---

  @Test
  fun `cancelling after the first result stops the run as CANCELLED`() {
    ToolRegistry.register(probe("test:noop"))
    val run = executeRun(
        replies = listOf(batch("a", "b", "c"), "All done."),
        onEvent = { event ->
          if (event is AgentEvents.ToolFinished) {
            controller?.cancelActiveRun()
          }
        }
    )
    assertEquals(AgentState.CANCELLED, run.state)
    assertEquals(1, started().size)
    assertEquals(1, finished().size)
    assertTrue(eventLog.last() is AgentEvents.Cancelled)
    assertTrue(eventLog.filterIsInstance<AgentEvents.FinalAnswer>().isEmpty())
  }

  // -------------------------------------------------------------- legacy ---

  @Test
  fun `legacy FILE_TO_MODIFY reply converts into a write_file tool call`() {
    var capturedPath: Any? = null
    var capturedContent: Any? = null
    ToolRegistry.register(probe("local:write_file", withSchema = true) { args, _ ->
      capturedPath = args["path"]
      capturedContent = args["content"]
      ToolResult.success("written")
    })
    val reply = "FILE_TO_MODIFY: app/src/Main.kt\npackage com.example\nfun main() {}\n"
    val run = executeRun(listOf(reply, "All done."))
    assertEquals(AgentState.DONE, run.state)
    assertEquals("app/src/Main.kt", capturedPath)
    assertNotNull(capturedContent, "legacy conversion must always supply content")
    assertEquals(1, finished().size)
    assertEquals("local:write_file", finished()[0].call.name)
    assertTrue(finished()[0].call.legacy)
    assertTrue(finished()[0].result.ok)
  }

  // ------------------------------------------------------------- contract ---

  @Test
  fun `prompt still advertises a one-call-per-reply contract today`() {
    ToolRegistry.register(probe("test:noop"))
    val provider = FakeProviderCall(listOf("All done."))
    val c = AgentController(providerCall = provider)
    runBlocking {
      c.runAgent(
          userRequest = "do the work",
          mode = RunMode.BUILD,
          projectRoot = root,
          events = {},
          onConfirm = { true }
      )
    }
    val prompt = provider.prompts.single()
    // CHARACTERIZATION (STEP 1): both strings contradict executeCallsBatch(),
    // which runs every parsed call. STEP 3 rewrites them.
    assertTrue(prompt.contains("exactly one tool call per reply"))
    assertTrue(prompt.contains("call exactly one per turn"))
    // The menu itself must be present alongside the rule.
    assertTrue(prompt.contains("Available tools"))
    assertTrue(prompt.contains("- test:noop:"))
  }

  @Test
  fun `one batch emits a duplicate per-call proposal event today`() {
    ToolRegistry.register(probe("test:noop"))
    executeRun(listOf(batch("a", "b"), "All done."))
    // CHARACTERIZATION (STEP 1): one batch proposal plus one per call.
    // STEP 7 removes the per-call duplicates.
    assertEquals(3, proposed().size)
    assertEquals(2, proposed()[0].calls.size)
    assertEquals(1, proposed()[1].calls.size)
    assertEquals(1, proposed()[2].calls.size)
  }

  // ----------------------------------------------------------------- plan ---

  @Test
  fun `PLAN mode refuses write tools and still reaches a final answer`() {
    ToolRegistry.register(probe("test:write", kind = ToolKind.WRITE))
    val run = executeRun(
        replies = listOf(call("test:write", "a"), "Here is the plan."),
        mode = RunMode.PLAN
    )
    assertEquals(AgentState.DONE, run.state)
    assertEquals(1, finished().size)
    assertTrue(finished()[0].result.error!!.contains("disabled in PLAN mode"))
    assertEquals("Here is the plan.", finalAnswer().text)
  }
}
