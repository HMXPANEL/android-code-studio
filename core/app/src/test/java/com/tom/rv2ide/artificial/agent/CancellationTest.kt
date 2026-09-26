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
import com.tom.rv2ide.artificial.tools.builtins.RunCommandTool
import com.tom.rv2ide.artificial.tools.builtins.BuildProjectTool
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CancellationTest {

    private fun testCtx(root: File): ToolContext = ToolContext(
        projectRoot = root,
        planMode = false,
        runId = "t",
        job = Job(),
        stepIndex = 0
    )

    private fun testContext(dir: File): ContextWrapper = ContextWrapper(Application())

    private fun newProject(): File {
        return Files.createTempDirectory("agent-cancel-test").toFile()
    }

    @Test
    fun `cancellation during command execution stops process`() = runBlocking {
        val root = newProject()
        val job = Job()
        val ctx = ToolContext(root, false, "t", job, 0)

        val tool = RunCommandTool(testContext(root))
        val command = "sleep 10"

        val deferred = async {
            tool.execute(mapOf("command" to command), ctx)
        }

        // Give it a moment to start
        delay(100)
        job.cancel()

        val result = deferred.await()
        // Should be cancelled
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("cancelled") || result.error!!.contains("Cancelled"))
    }

    @Test
    fun `cancellation propagates through ToolExecutor`() = runBlocking {
        val root = newProject()
        val job = Job()
        val ctx = ToolContext(root, false, "t", job, 0)

        val deferred = async {
            ToolExecutor().execute(
                com.tom.rv2ide.artificial.tools.ToolCall(
                    name = "local:run_command",
                    args = mapOf("command" to "sleep 10"),
                    callId = "test",
                    runId = "t"
                ),
                ctx,
                ToolPermission.buildDefault(false),
                { true } // onConfirm
            )
        }

        delay(100)
        job.cancel()

        val result = deferred.await()
        assertFalse(result.ok)
    }

    @Test
    fun `doom loop protection triggers on repeated identical calls`() = runBlocking {
        val run = AgentRun(
            runId = "test",
            mode = RunMode.BUILD,
            budget = RunBudget(maxSteps = 10, maxRetries = 2, doomRepeat = 3)
        )

        // First two calls OK
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
        // Third identical call triggers doom loop
        assertEquals(AgentRun.StepVerdict.DOOM_LOOP, run.registerStep("local:read_file{p=\"x\"}"))
    }

    @Test
    fun `doom loop not triggered by varying calls`() = runBlocking {
        val run = AgentRun(
            runId = "test",
            mode = RunMode.BUILD,
            budget = RunBudget(maxSteps = 10, maxRetries = 2, doomRepeat = 3)
        )

        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"a\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"a\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"b\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"b\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"b\"}"))
    }
}