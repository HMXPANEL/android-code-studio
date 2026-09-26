package com.tom.rv2ide.artificial.agent

import android.content.ContextWrapper
import android.app.Application
import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolExecutor
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.builtins.AgentTools
import com.tom.rv2ide.artificial.tools.builtins.DeleteFileTool
import com.tom.rv2ide.artificial.tools.builtins.EditFileTool
import com.tom.rv2ide.artificial.tools.builtins.WriteFileTool
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModeDiskUnchangedTest {

    private fun testCtx(root: File, planMode: Boolean = true): ToolContext = ToolContext(
        projectRoot = root,
        planMode = planMode,
        runId = "t",
        job = Job(),
        stepIndex = 0
    )

    private fun newProject(): File {
        val root = Files.createTempDirectory("agent-plan-test").toFile()
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() {\n  println(\"hello\")\n}")
        return root
    }

    private fun testContext(dir: File): ContextWrapper = ContextWrapper(Application()).apply {
        // Override filesDir to use our test directory
    }

    @Test
    fun `PLAN mode refuses write_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = true)
            val tool = WriteFileTool(testContext(root))
            val result = tool.execute(
                mapOf("path" to "src/Main.kt", "content" to "new content"),
                ctx
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("PLAN mode"))
            // File should be unchanged
            assertEquals("fun main() {\n  println(\"hello\")\n}", File(root, "src/Main.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `PLAN mode refuses edit_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = true)
            val tool = EditFileTool(testContext(root))
            val original = "fun main() {\n  println(\"hello\")\n}"
            val result = tool.execute(
                mapOf(
                    "path" to "src/Main.kt",
                    "search" to "hello",
                    "replace" to "world",
                    "expectedHash" to EditFileTool::class.java.getMethod("sha256Hex", String::class.java).invoke(null, original) as String
                ),
                ctx
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("PLAN mode"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `PLAN mode refuses delete_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = true)
            val tool = DeleteFileTool(testContext(root))
            val result = tool.execute(
                mapOf("path" to "src/Main.kt"),
                ctx
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("PLAN mode"))
            assertTrue(File(root, "src/Main.kt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `BUILD mode allows write_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = false)
            val tool = WriteFileTool(testContext(root))
            val result = tool.execute(
                mapOf("path" to "src/NewFile.kt", "content" to "new file content"),
                ctx
            )
            assertTrue(result.ok)
            assertTrue(File(root, "src/NewFile.kt").exists())
            assertEquals("new file content", File(root, "src/NewFile.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `BUILD mode allows edit_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = false)
            val tool = EditFileTool(testContext(root))
            val original = File(root, "src/Main.kt").readText()
            // Use reflection to get sha256Hex since it's private
            val sha256HexMethod = EditFileTool::class.java.getDeclaredMethod("sha256Hex", String::class.java)
            sha256HexMethod.isAccessible = true
            val hash = sha256HexMethod.invoke(null, original) as String
            val result = tool.execute(
                mapOf(
                    "path" to "src/Main.kt",
                    "search" to "hello",
                    "replace" to "world",
                    "expectedHash" to hash
                ),
                ctx
            )
            assertTrue(result.ok)
            assertEquals("fun main() {\n  println(\"world\")\n}", File(root, "src/Main.kt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `PLAN mode allows read_file`() = runBlocking {
        val root = newProject()
        try {
            val ctx = testCtx(root, planMode = true)
            val tool = com.tom.rv2ide.artificial.tools.builtins.ReadFileTool(testContext(root))
            val result = tool.execute(
                mapOf("path" to "src/Main.kt"),
                ctx
            )
            assertTrue(result.ok)
            assertTrue(result.text.contains("hello"))
        } finally {
            root.deleteRecursively()
        }
    }
}