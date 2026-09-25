package com.tom.rv2ide.artificial.tools

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun tool(
    id: String,
    kind: ToolKind = ToolKind.READ,
    confirm: ConfirmPolicy = ConfirmPolicy.NEVER,
    timeout: Long = 30L,
    body: suspend (Map<String, Any?>, ToolContext) -> ToolResult = { args, _ ->
      ToolResult.success("ok")
    }
): Tool = object : Tool {
  override val id: String = id
  override val namespace: String = id.substringBefore(':')
  override val description: String = "t"
  override val schema: ToolSchema = ToolSchema(
      listOf(ToolInputField("path", ToolInputType.STRING, "p", true))
  )
  override val kind: ToolKind = kind
  override val readOnlyHint: Boolean = kind == ToolKind.READ
  override val timeoutSec: Long = timeout
  override val confirmPolicy: ConfirmPolicy = confirm
  override val visible: Boolean = true
  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    return body(args, ctx)
  }
}

private fun ctx(planMode: Boolean = false): ToolContext = ToolContext(
    projectRoot = File("/proj"),
    planMode = planMode,
    runId = "r",
    job = Job(),
    stepIndex = 0
)

class ToolExecutorTest {

  @Before
  fun setUp() {
    ToolRegistry.clear()
  }

  @After
  fun tearDown() {
    ToolRegistry.clear()
  }

  private val exec = ToolExecutor()

  @Test
  fun `unknown tool names available tools`() = runBlocking {
    val result = exec.execute(
        ToolCall("local:nope", emptyMap(), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("Unknown tool"))
  }

  @Test
  fun `missing required arg fails with schema hint`() = runBlocking {
    ToolRegistry.register(tool("local:read_file"))
    val result = exec.execute(
        ToolCall("local:read_file", emptyMap(), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("path"))
  }

  @Test
  fun `wrong arg type fails`() = runBlocking {
    ToolRegistry.register(tool("local:read_file"))
    val result = exec.execute(
        ToolCall("local:read_file", mapOf("path" to 42), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("string"))
  }

  @Test
  fun `plan mode refuses write tools`() = runBlocking {
    ToolRegistry.register(tool("local:write_file", kind = ToolKind.WRITE))
    val result = exec.execute(
        ToolCall("local:write_file", mapOf("path" to "a"), "c", "r"),
        ctx(planMode = true), ToolPermission.buildDefault(true)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("PLAN mode"))
  }

  @Test
  fun `denied tool is refused`() = runBlocking {
    ToolRegistry.register(tool("local:run_command", kind = ToolKind.POWER))
    val perm = ToolPermission(
        rules = listOf(PermissionRule("local:run_command", ToolDecision.DENY)),
        planMode = false
    )
    val result = exec.execute(
        ToolCall("local:run_command", mapOf("path" to "x"), "c", "r"),
        ctx(), perm
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("not permitted"))
  }

  @Test
  fun `user denial becomes result`() = runBlocking {
    ToolRegistry.register(
        tool("local:delete_file", kind = ToolKind.DESTRUCTIVE, confirm = ConfirmPolicy.ALWAYS)
    )
    val result = exec.execute(
        ToolCall("local:delete_file", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { false }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("denied"))
  }

  @Test
  fun `granted confirmation executes`() = runBlocking {
    ToolRegistry.register(
        tool("local:delete_file", kind = ToolKind.DESTRUCTIVE, confirm = ConfirmPolicy.ALWAYS)
    ) 
    val result = exec.execute(
        ToolCall("local:delete_file", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertTrue(result.ok)
  }

  @Test
  fun `timeout becomes result`() = runBlocking {
    // Zero timeout fires immediately; the tool itself would block for seconds.
    ToolRegistry.register(
        tool("local:instant-timeout", timeout = 0) { _, _ ->
          delay(5_000L)
          ToolResult.success("never")
        }
    )
    val result = exec.execute(
        ToolCall("local:instant-timeout", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("timed out"))
  }

  @Test
  fun `tool exception becomes result not crash`() = runBlocking {
    ToolRegistry.register(
        tool("local:boom") { _, _ -> throw IllegalStateException("kaboom") }
    )
    val result = exec.execute(
        ToolCall("local:boom", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("kaboom"))
  }

  @Test
  fun `long output truncated with signal`() = runBlocking {
    ToolRegistry.register(
        tool("local:big") { _, _ -> ToolResult.success("y".repeat(9000)) }
    )
    val small = ToolExecutor(outputCharCap = 100)
    val result = small.execute(
        ToolCall("local:big", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertTrue(result.ok)
    assertTrue(result.truncated)
    assertTrue(result.text.contains("truncated"))
  }

  @Test
  fun `genuine timeout becomes result not cancellation`() = runBlocking {
    ToolRegistry.register(
        tool("local:slow-but-bounded", timeout = 1) { _, _ ->
          delay(30_000L)
          ToolResult.success("never")
        }
    )
    val result = exec.execute(
        ToolCall("local:slow-but-bounded", mapOf("path" to "x"), "c", "r"),
        ctx(), ToolPermission.buildDefault(false)
    ) { true }
    assertFalse(result.ok)
    assertTrue(result.error!!.contains("timed out"))
  }

  @Test
  fun `cancellation propagates`() {
    var sawCancel = false
    runBlocking {
      ToolRegistry.register(
          tool("local:wait") { _, _ ->
            delay(30_000L)
            ToolResult.success("never")
          }
      )
      val job = launch {
        exec.execute(
            ToolCall("local:wait", mapOf("path" to "x"), "c", "r"),
            ctx(), ToolPermission.buildDefault(false)
        ) { true }
      }
      delay(50)
      job.cancel()
      try {
        job.join()
      } catch (e: CancellationException) {
        sawCancel = true
      }
    }
    assertTrue("expected CancellationException from cancelled run", sawCancel)
  }
}
