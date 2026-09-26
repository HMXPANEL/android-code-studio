package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolExecutor
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM-only tests proving PLAN mode is read-only at the executor chokepoint.
 *
 * Fake tools record whether their body ran by touching a marker file, so the
 * tests prove both the refusal result AND that no disk mutation happened.
 */
class PlanModeDiskUnchangedTest {

    private lateinit var root: File

    private fun fakeTool(id: String, kind: ToolKind, marker: File): Tool = object : Tool {
        override val id: String = id
        override val namespace: String = "local"
        override val description: String = "test double"
        override val schema: ToolSchema = ToolSchema(emptyList())
        override val kind: ToolKind = kind
        override val readOnlyHint: Boolean = kind == ToolKind.READ
        override val timeoutSec: Long = 30L
        override val confirmPolicy: ConfirmPolicy = ConfirmPolicy.NEVER
        override val visible: Boolean = true
        override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
            marker.writeText("executed")
            return ToolResult.success("ok")
        }
    }

    private fun ctx(planMode: Boolean): ToolContext = ToolContext(
        projectRoot = root,
        planMode = planMode,
        runId = "t",
        job = Job(),
        stepIndex = 0
    )

    private fun call(id: String) = ToolCall(name = id, args = emptyMap(), callId = "c", runId = "t")

    @Before
    fun setUp() {
        ToolRegistry.clear()
        root = Files.createTempDirectory("agent-plan-test").toFile()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
        root.deleteRecursively()
    }

    @Test
    fun `PLAN refuses WRITE tool and body never runs`() = runBlocking {
        val marker = File(root, "write-marker.txt")
        ToolRegistry.register(fakeTool("local:write_file", ToolKind.WRITE, marker))
        val result = ToolExecutor().execute(
            call("local:write_file"), ctx(planMode = true),
            ToolPermission.buildDefault(planMode = true), { true }
        )
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("PLAN mode"))
        assertFalse(marker.exists())
    }

    @Test
    fun `PLAN refuses DESTRUCTIVE tool and body never runs`() = runBlocking {
        val marker = File(root, "delete-marker.txt")
        ToolRegistry.register(fakeTool("local:delete_file", ToolKind.DESTRUCTIVE, marker))
        val result = ToolExecutor().execute(
            call("local:delete_file"), ctx(planMode = true),
            ToolPermission.buildDefault(planMode = true), { true }
        )
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("PLAN mode"))
        assertFalse(marker.exists())
    }

    @Test
    fun `PLAN refuses POWER tool and body never runs`() = runBlocking {
        val marker = File(root, "cmd-marker.txt")
        ToolRegistry.register(fakeTool("local:run_command", ToolKind.POWER, marker))
        val result = ToolExecutor().execute(
            call("local:run_command"), ctx(planMode = true),
            ToolPermission.buildDefault(planMode = true), { true }
        )
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("PLAN mode"))
        assertFalse(marker.exists())
    }

    @Test
    fun `PLAN allows READ tool`() = runBlocking {
        val marker = File(root, "read-marker.txt")
        ToolRegistry.register(fakeTool("local:read_file", ToolKind.READ, marker))
        val result = ToolExecutor().execute(
            call("local:read_file"), ctx(planMode = true),
            ToolPermission.buildDefault(planMode = true), { true }
        )
        assertTrue(result.ok)
        assertTrue(marker.exists())
    }

    @Test
    fun `BUILD allows WRITE tool`() = runBlocking {
        val marker = File(root, "write-marker.txt")
        ToolRegistry.register(fakeTool("local:write_file", ToolKind.WRITE, marker))
        val result = ToolExecutor().execute(
            call("local:write_file"), ctx(planMode = false),
            ToolPermission.buildDefault(planMode = false), { true }
        )
        assertTrue(result.ok)
        assertTrue(marker.exists())
    }

    @Test
    fun `PLAN leaves existing project bytes untouched`() = runBlocking {
        val victim = File(root, "Main.kt").apply { writeText("original") }
        val marker = File(root, "write-marker.txt")
        ToolRegistry.register(fakeTool("local:write_file", ToolKind.WRITE, marker))
        ToolExecutor().execute(
            call("local:write_file"), ctx(planMode = true),
            ToolPermission.buildDefault(planMode = true), { true }
        )
        assertEquals("original", victim.readText())
        assertFalse(marker.exists())
    }
}
