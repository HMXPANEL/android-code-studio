package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.agent.AgentRun
import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContextEngineTest {

    private lateinit var tempDir: File
    private lateinit var store: InMemorySessionStore
    private lateinit var indexer: ProjectIndexer
    private lateinit var relevance: RelevanceEngine
    private lateinit var engine: ContextEngine

    private fun readTool() = object : Tool {
        override val id = "local:read_file"
        override val namespace = "local"
        override val description = "Read a file"
        override val schema = ToolSchema(
            listOf(ToolInputField("path", ToolInputType.STRING, "File path", true))
        )
        override val kind = ToolKind.READ
        override val readOnlyHint = true
        override val timeoutSec = 10L
        override val confirmPolicy = ConfirmPolicy.NEVER
        override val visible = true
        override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult =
            ToolResult.success("ok")
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("context_test_").toFile()
        store = InMemorySessionStore()
        indexer = ProjectIndexer()
        relevance = RelevanceEngine(indexer)
        engine = ContextEngine(store, indexer, relevance)
    }

    @Test
    fun testCompileContextForNewSession() = runBlocking {
        val run = AgentRun(mode = RunMode.BUILD, sessionId = "missing-session")
        val context = engine.compileContext(run, tempDir, "Read the main file", listOf(readTool()))

        assertNotNull(context)
        assertTrue(context.systemInstructions.isNotBlank())
        assertEquals("Read the main file", context.userRequest)
        assertEquals(1, context.toolDefinitions.size)
        assertEquals("local:read_file", context.toolDefinitions[0].name)
        assertFalse(context.wasCompacted)
        assertTrue(context.conversationHistory.isEmpty())
    }

    @Test
    fun testCompileContextWithSessionHistory() = runBlocking {
        store.create(
            AgentSession(
                id = "test-session",
                projectRoot = tempDir.absolutePath,
                mode = RunMode.BUILD,
                providerId = "p", modelId = "m"
            )
        )
        val session = store.getWithHistory("test-session")!!
        store.update(
            session.copyWith(
                messages = listOf(
                    PersistedMessage(role = MessageRole.USER, content = "Previous request"),
                    PersistedMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Previous response",
                        toolCalls = listOf(
                            PersistedToolCall(callId = "call-1", name = "read_file", arguments = mapOf("path" to "test.kt"))
                        ),
                        toolResults = listOf(
                            PersistedToolResult(callId = "call-1", success = true, output = "file content", error = null)
                        )
                    )
                )
            )
        )

        val run = AgentRun(mode = RunMode.BUILD, sessionId = "test-session")
        val context = engine.compileContext(run, tempDir, "New request", listOf(readTool()))

        assertEquals(2, context.conversationHistory.size)
        assertEquals("user", context.conversationHistory[0].role)
        assertEquals("assistant", context.conversationHistory[1].role)
        assertEquals(1, context.conversationHistory[1].toolCalls.size)
        assertEquals(1, context.conversationHistory[1].toolResults.size)
    }

    @Test
    fun testTokenEstimation() {
        val estimator = TokenEstimator()
        assertTrue(estimator.estimate("Hello") > 0)
        assertTrue(estimator.estimate("x".repeat(1000)) > estimator.estimate("Hello"))
        assertEquals(0, estimator.estimate(""))
        assertEquals(0, estimator.estimate("   "))
    }
}
