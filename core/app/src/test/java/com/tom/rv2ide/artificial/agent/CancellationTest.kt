package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolExecutor
import com.tom.rv2ide.artificial.tools.ToolPermission
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.builtins.RunCommandTool
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * JVM-only cancellation and loop-guard tests.
 *
 * [RunCommandTool] is Android-free (pure ProcessBuilder), so command
 * cancellation can be tested on the JVM without a device.
 */
class CancellationTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        ToolRegistry.clear()
        root = Files.createTempDirectory("agent-cancel-test").toFile()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
        root.deleteRecursively()
    }

    @Test
    fun `cancellation during command execution stops process`() = runBlocking {
        val job = Job()
        val ctx = ToolContext(root, false, "t", job, 0)
        val deferred = async {
            RunCommandTool().execute(mapOf("command" to "sleep 30"), ctx)
        }
        delay(500)
        job.cancel()
        // By executor/tool contract, run cancellation propagates as
        // CancellationException rather than a failure result.
        var outcome = "returned normally"
        try {
            deferred.await()
        } catch (e: CancellationException) {
            outcome = "threw CancellationException"
        }
        assertEquals("threw CancellationException", outcome)
    }

    @Test
    fun `cancellation propagates through ToolExecutor`() = runBlocking {
        ToolRegistry.register(RunCommandTool())
        val job = Job()
        val ctx = ToolContext(root, false, "t", job, 0)
        val deferred = async {
            ToolExecutor().execute(
                ToolCall(
                    name = "local:run_command",
                    args = mapOf("command" to "sleep 30"),
                    callId = "test",
                    runId = "t"
                ),
                ctx,
                ToolPermission.buildDefault(false),
                { true }
            )
        }
        delay(500)
        job.cancel()
        var outcome = "returned normally"
        try {
            deferred.await()
        } catch (e: CancellationException) {
            outcome = "threw CancellationException"
        }
        assertEquals("threw CancellationException", outcome)
    }

    @Test
    fun `doom loop protection triggers on repeated identical calls`() {
        val run = AgentRun(
            runId = "test",
            mode = RunMode.BUILD,
            budget = RunBudget(maxSteps = 10, maxRetries = 2, doomRepeat = 3)
        )
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"x\"}"))
        assertEquals(AgentRun.StepVerdict.DOOM_LOOP, run.registerStep("local:read_file{p=\"x\"}"))
    }

    @Test
    fun `doom loop not triggered by varying calls`() {
        val run = AgentRun(
            runId = "test",
            mode = RunMode.BUILD,
            budget = RunBudget(maxSteps = 10, maxRetries = 2, doomRepeat = 3)
        )
        // Alternating fingerprints never fill the window with identical entries.
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"a\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"b\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"a\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"b\"}"))
        assertEquals(AgentRun.StepVerdict.OK, run.registerStep("local:read_file{p=\"a\"}"))
    }
}
