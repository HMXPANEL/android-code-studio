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
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Acceptance tests for context budgeting, compaction and tool-call
 * consistency. Uses a tiny budget so compaction is deterministic.
 */
class ContextCompactionTest {

    private fun engineWithBudget(store: SessionStore, maxTokens: Int): ContextEngine {
        val indexer = ProjectIndexer()
        return ContextEngine(
            store,
            indexer,
            RelevanceEngine(indexer),
            ContextConfig(
                maxContextTokens = maxTokens,
                reservedOutputTokens = 100,
                reservedToolTokens = 100,
                maxRecentMessages = 5,
                maxFileExcerpts = 3,
                summaryTokenBudget = 100
            )
        )
    }

    private fun bigTool() = object : Tool {
        override val id = "local:read_file"
        override val namespace = "local"
        override val description = "Read a file. " + "detail ".repeat(50)
        override val schema = ToolSchema(
            listOf(ToolInputField("path", ToolInputType.STRING, "File path to read. ".repeat(10), true))
        )
        override val kind = ToolKind.READ
        override val readOnlyHint = true
        override val timeoutSec = 10L
        override val confirmPolicy = ConfirmPolicy.NEVER
        override val visible = true
        override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult =
            ToolResult.success("ok")
    }

    private fun longMessage(role: MessageRole, i: Int): PersistedMessage {
        val pad = "content-$i ".repeat(60)
        return if (i % 2 == 0) {
            PersistedMessage(role = role, content = "User turn $i: $pad")
        } else {
            PersistedMessage(
                role = MessageRole.ASSISTANT,
                content = "Assistant turn $i: $pad",
                toolCalls = listOf(
                    PersistedToolCall(callId = "call-$i", name = "local:read_file", arguments = mapOf("path" to "f$i.kt"))
                ),
                toolResults = listOf(
                    PersistedToolResult(callId = "call-$i", success = true, output = "result-$i $pad", error = null)
                )
            )
        }
    }

    @Test
    fun testOverBudgetContextIsCompactedButMandatorySurvives() = runBlocking {
        val dir = Files.createTempDirectory("ctx_compact_").toFile()
        try {
            val store = InMemorySessionStore()
            val session = AgentSession(
                projectRoot = dir.absolutePath, mode = RunMode.BUILD, providerId = "p", modelId = "m"
            )
            store.create(session)
            store.update(session.copyWith(messages = (0 until 12).map { longMessage(MessageRole.USER, it) }))

            val engine = engineWithBudget(store, maxTokens = 1500)
            val run = AgentRun(mode = RunMode.BUILD, sessionId = session.id)
            val compiled = engine.compileContext(run, dir, "current request pins down the task", listOf(bigTool()))

            assertTrue(compiled.wasCompacted)
            assertTrue(compiled.conversationHistory.size < 12)
            // Mandatory content intact.
            assertTrue(compiled.systemInstructions.isNotBlank())
            assertEquals("current request pins down the task", compiled.userRequest)
            assertEquals(1, compiled.toolDefinitions.size)
            assertEquals("local:read_file", compiled.toolDefinitions[0].name)
            // Canonical persisted history untouched by compaction.
            assertEquals(12, store.getWithHistory(session.id)!!.messages.size)
            // Every surviving result still has its call.
            val callIds = compiled.conversationHistory.flatMap { it.toolCalls.map { c -> c.callId } }.toSet()
            for (msg in compiled.conversationHistory) {
                for (res in msg.toolResults) {
                    assertTrue("orphaned result ${res.callId}", callIds.contains(res.callId))
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testShortConversationIsNotCompacted() = runBlocking {
        val dir = Files.createTempDirectory("ctx_short_").toFile()
        try {
            val store = InMemorySessionStore()
            val session = AgentSession(
                projectRoot = dir.absolutePath, mode = RunMode.BUILD, providerId = "p", modelId = "m"
            )
            store.create(session)
            store.update(
                session.copyWith(
                    messages = listOf(PersistedMessage(role = MessageRole.USER, content = "hi"))
                )
            )

            val engine = engineWithBudget(store, maxTokens = 32000)
            val run = AgentRun(mode = RunMode.BUILD, sessionId = session.id)
            val compiled = engine.compileContext(run, dir, "hello", listOf(bigTool()))

            assertFalse(compiled.wasCompacted)
            assertEquals(1, compiled.conversationHistory.size)
            assertNull(compiled.compactionSummary)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testOrphanedResultRegainsItsCall() = runBlocking {
        val dir = Files.createTempDirectory("ctx_orphan_").toFile()
        try {
            val store = InMemorySessionStore()
            val session = AgentSession(
                projectRoot = dir.absolutePath, mode = RunMode.BUILD, providerId = "p", modelId = "m"
            )
            store.create(session)
            store.update(
                session.copyWith(
                    messages = listOf(
                        PersistedMessage(
                            role = MessageRole.ASSISTANT,
                            content = "old turn",
                            toolCalls = listOf(
                                PersistedToolCall(callId = "c1", name = "local:read_file", arguments = mapOf("path" to "x.kt"))
                            )
                        ),
                        PersistedMessage(
                            role = MessageRole.TOOL,
                            content = "c1: OK",
                            toolResults = listOf(
                                PersistedToolResult(callId = "c1", success = true, output = "data", error = null)
                            )
                        )
                    )
                )
            )

            // Only the newest message fits; the result's call must be re-attached.
            val indexer = ProjectIndexer()
            val engine = ContextEngine(
                store, indexer, RelevanceEngine(indexer),
                ContextConfig(maxContextTokens = 32000, maxRecentMessages = 1)
            )
            val run = AgentRun(mode = RunMode.BUILD, sessionId = session.id)
            val compiled = engine.compileContext(run, dir, "what did the tool return?", emptyList())

            val callIds = compiled.conversationHistory.flatMap { it.toolCalls.map { c -> c.callId } }
            assertTrue(callIds.contains("c1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testSevereBudgetPressureKeepsMandatoryAndLastMessages() = runBlocking {
        val dir = Files.createTempDirectory("ctx_severe_").toFile()
        try {
            val store = InMemorySessionStore()
            val session = AgentSession(
                projectRoot = dir.absolutePath, mode = RunMode.BUILD, providerId = "p", modelId = "m"
            )
            store.create(session)
            store.update(session.copyWith(messages = (0 until 8).map { longMessage(MessageRole.USER, it) }))

            // Budget smaller than the mandatory block itself.
            val engine = engineWithBudget(store, maxTokens = 50)
            val run = AgentRun(mode = RunMode.BUILD, sessionId = session.id)
            val compiled = engine.compileContext(run, dir, "must survive", listOf(bigTool()))

            assertTrue(compiled.wasCompacted)
            assertTrue(compiled.systemInstructions.isNotBlank())
            assertEquals("must survive", compiled.userRequest)
            assertEquals(1, compiled.toolDefinitions.size)
            assertTrue(compiled.conversationHistory.size <= 3)
        } finally {
            dir.deleteRecursively()
        }
    }
}
