package com.tom.rv2ide.artificial.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun fakeTool(
    id: String,
    kind: ToolKind = ToolKind.READ,
    confirm: ConfirmPolicy = ConfirmPolicy.NEVER,
    timeout: Long = 30L,
    visible: Boolean = true,
    desc: String = "Fake tool"
): Tool = object : Tool {
  override val id: String = id
  override val namespace: String = id.substringBefore(':')
  override val description: String = desc
  override val schema: ToolSchema = ToolSchema(
      listOf(ToolInputField("path", ToolInputType.STRING, "p", true))
  )
  override val kind: ToolKind = kind
  override val readOnlyHint: Boolean = kind == ToolKind.READ
  override val timeoutSec: Long = timeout
  override val confirmPolicy: ConfirmPolicy = confirm
  override val visible: Boolean = visible
  override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
    return ToolResult.success("ok:${args["path"]}")
  }
}

class ToolRegistryTest {

  @Before
  fun setUp() {
    ToolRegistry.clear()
  }

  @After
  fun tearDown() {
    ToolRegistry.clear()
  }

  @Test
  fun `register lookup replace unregister`() {
    ToolRegistry.register(fakeTool("local:a"))
    assertEquals(1, ToolRegistry.count())
    assertEquals("local:a", ToolRegistry.lookup("local:a")?.id)
    ToolRegistry.register(fakeTool("local:a", desc = "v2"))
    assertEquals(1, ToolRegistry.count())
    assertEquals("v2", ToolRegistry.lookup("local:a")?.description)
    assertTrue(ToolRegistry.unregister("local:a"))
    assertNull(ToolRegistry.lookup("local:a"))
    assertFalse(ToolRegistry.unregister("local:a"))
  }

  @Test
  fun `describe lists tools with schema and hides hidden ones`() {
    ToolRegistry.register(fakeTool("local:read_file"))
    ToolRegistry.register(
        fakeTool("local:secret", visible = false, desc = "Hidden")
    )
    val text = ToolRegistry.describeForPrompt(planMode = false)
    assertTrue(text.contains("local:read_file"))
    assertTrue(text.contains("path(string,required)"))
    assertFalse(text.contains("local:secret"))
  }

  @Test
  fun `plan prompt only describes read tools and notes restriction`() {
    ToolRegistry.register(fakeTool("local:read_file", kind = ToolKind.READ))
    ToolRegistry.register(fakeTool("local:write_file", kind = ToolKind.WRITE))
    val text = ToolRegistry.describeForPrompt(planMode = true)
    assertTrue(text.contains("local:read_file"))
    assertFalse(text.contains("local:write_file"))
    assertTrue(text.contains("PLAN mode"))
  }

  @Test
  fun `ordering is deterministic`() {
    ToolRegistry.register(fakeTool("local:b"))
    ToolRegistry.register(fakeTool("local:a"))
    assertEquals(listOf("local:b", "local:a"), ToolRegistry.availableIds())
  }
}

class ToolPermissionTest {

  private val read = fakeTool("local:read_file", kind = ToolKind.READ)
  private val write = fakeTool("local:write_file", kind = ToolKind.WRITE)
  private val delete = fakeTool("local:delete_file", kind = ToolKind.DESTRUCTIVE)
  private val cmd = fakeTool("local:run_command", kind = ToolKind.POWER)

  @Test
  fun `plan mode allows only reads`() {
    val perm = ToolPermission.buildDefault(planMode = true)
    assertEquals(ToolDecision.ALLOW, perm.decide(read))
    assertEquals(ToolDecision.DENY, perm.decide(write))
    assertEquals(ToolDecision.DENY, perm.decide(delete))
    assertEquals(ToolDecision.DENY, perm.decide(cmd))
  }

  @Test
  fun `build defaults ask for destructive and power`() {
    val perm = ToolPermission.buildDefault(planMode = false)
    assertEquals(ToolDecision.ALLOW, perm.decide(read))
    assertEquals(ToolDecision.ALLOW, perm.decide(write))
    assertEquals(ToolDecision.ASK, perm.decide(delete))
    assertEquals(ToolDecision.ASK, perm.decide(cmd))
  }

  @Test
  fun `deny wins over ask and allow`() {
    val perm = ToolPermission(
        rules = listOf(
            PermissionRule("local:run_command", ToolDecision.ALLOW),
            PermissionRule("*", ToolDecision.ASK),
            PermissionRule("local:run_command", ToolDecision.DENY)
        ),
        planMode = false
    )
    assertEquals(ToolDecision.DENY, perm.decide(cmd))
    assertEquals(ToolDecision.ASK, perm.decide(delete))
  }

  @Test
  fun `wildcard star matches everything`() {
    val perm = ToolPermission(
        rules = listOf(PermissionRule("*", ToolDecision.DENY)),
        planMode = false
    )
    assertEquals(ToolDecision.DENY, perm.decide(read))
  }
}
