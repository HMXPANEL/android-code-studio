package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.session.ContextEngine
import com.tom.rv2ide.artificial.session.InMemorySessionStore
import com.tom.rv2ide.artificial.session.MessageRole
import com.tom.rv2ide.artificial.session.ProjectIndexer
import com.tom.rv2ide.artificial.session.RelevanceEngine
import com.tom.rv2ide.artificial.session.SessionManager
import com.tom.rv2ide.artificial.tools.ConfirmPolicy
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolContext
import com.tom.rv2ide.artificial.tools.ToolInputField
import com.tom.rv2ide.artificial.tools.ToolInputType
import com.tom.rv2ide.artificial.tools.ToolKind
import com.tom.rv2ide.artificial.tools.ToolRegistry
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchema
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Acceptance test for the real controller ↔ session wiring (Test C at
 * controller level): persistence across turns and across controller
 * recreation, using the same store a restarted app would reload from disk.
 */
class AgentControllerSessionTest {

    private lateinit var root: File
    private lateinit var store: InMemorySessionStore
    private lateinit var manager: SessionManager
    private lateinit var engine: ContextEngine

    private fun echoTool() = object : Tool {
        override val id = "test:sess_echo"
        override val namespace = "test"
        override val description = "echo probe"
        override val schema = ToolSchema(
            listOf(ToolInputField("tag", ToolInputType.STRING, "t", true))
        )
        override val kind = ToolKind.READ
        override val readOnlyHint = true
        override val timeoutSec = 10L
        override val confirmPolicy = ConfirmPolicy.NEVER
        override val visible = true
        override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult =
            ToolResult.success("echo:${args["tag"]}")
    }

    @Before
    fun setUp() {
        ToolRegistry.clear()
        ToolRegistry.register(echoTool())
        root = Files.createTempDirectory("agent-session-test").toFile()
        store = InMemorySessionStore()
        val indexer = ProjectIndexer()
        engine = ContextEngine(store, indexer, RelevanceEngine(indexer))
        manager = SessionManager(
            store, engine, indexer, RelevanceEngine(indexer),
            CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
        root.deleteRecursively()
    }

    private fun runWith(
        replies: List<String>,
        request: String,
        sessionId: String?
    ): Pair<AgentRun, FakeProviderCall> {
        val fake = FakeProviderCall(replies)
        val c = AgentController(
            providerCall = fake,
            sessionManager = manager,
            contextEngine = engine
        )
        var captured: AgentRun? = null
        try {
            runBlocking {
                try {
                    c.runAgent(
                        userRequest = request,
                        mode = RunMode.BUILD,
                        projectRoot = root,
                        events = {},
                        onConfirm = { true },
                        sessionId = sessionId
                    ).also { captured = it }
                } finally {
                    (captured ?: c.activeRun)?.job?.cancel()
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) throw t
        }
        return requireNotNull(captured) to fake
    }

    @Test
    fun testSessionPersistsUserToolResultAndFinalAcrossTurn() {
        val session = runBlocking {
            manager.createSession(root, RunMode.BUILD, "p", "m", "accept")
        }
        val (run, _) = runWith(
            replies = listOf("TOOL_CALL: test:sess_echo {\"tag\":\"a\"}", "All done."),
            request = "echo it",
            sessionId = session.id
        )
        assertEquals(AgentState.DONE, run.state)

        val restored = runBlocking { manager.getSession(session.id) }!!
        val roles = restored.messages.map { it.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT), roles)
        assertEquals("echo it", restored.messages[0].content)
        assertEquals("test:sess_echo", restored.messages[1].toolCalls.single().name)
        assertEquals("a", restored.messages[1].toolCalls.single().arguments["tag"])
        assertEquals("echo:a", restored.messages[1].toolResults.single().output)
        assertEquals(
            restored.messages[1].toolCalls.single().callId,
            restored.messages[1].toolResults.single().callId
        )
        assertEquals("All done.", restored.messages[2].content)
    }

    @Test
    fun testFollowUpWithNewControllerSeesPriorContext() {
        val session = runBlocking {
            manager.createSession(root, RunMode.BUILD, "p", "m", "followup")
        }
        runWith(
            replies = listOf("First final answer marker-xyz."),
            request = "first question",
            sessionId = session.id
        )

        // Simulate app restart: brand-new controller, same session store.
        val (_, fake2) = runWith(
            replies = listOf("Follow-up done."),
            request = "refer back to earlier work",
            sessionId = session.id
        )
        val followUpPrompt = fake2.prompts.single()
        assertTrue(followUpPrompt.contains("marker-xyz"))
        assertTrue(followUpPrompt.contains("first question"))
    }

    @Test
    fun testRunWithoutSessionIdLeavesStoreUntouched() {
        val (run, _) = runWith(
            replies = listOf("TOOL_CALL: test:sess_echo {\"tag\":\"z\"}", "Done."),
            request = "no session",
            sessionId = null
        )
        assertEquals(AgentState.DONE, run.state)
        assertTrue(runBlocking { manager.listSessions(root) }.isEmpty())
    }

    // TEMPORARY CI-HANG BISECTION (revert): ignored to determine whether the
    // stall originates in the new tests. Re-enable with
    // AgentSessionCoordinatorTest after diagnosis.
    @Ignore("CI hang bisection — revert after diagnosis")
    @Test
    fun testSameControllerTwoSessionsStayIsolated() {
        val sessionA = runBlocking {
            manager.createSession(root, RunMode.BUILD, "p", "m", "A")
        }
        val sessionB = runBlocking {
            manager.createSession(root, RunMode.BUILD, "p", "m", "B")
        }
        // One shared controller, sequential runs: the second run must not see
        // the first run's conversation, and each store history stays separate.
        val fake = FakeProviderCall(
            listOf(
                "TOOL_CALL: test:sess_echo {\"tag\":\"a\"}",
                "Answer Alpha marker-alpha.",
                "TOOL_CALL: test:sess_echo {\"tag\":\"b\"}",
                "Answer Beta."
            )
        )
        val c = AgentController(
            providerCall = fake,
            sessionManager = manager,
            contextEngine = engine
        )
        var run1: AgentRun? = null
        var run2: AgentRun? = null
        try {
            runBlocking {
                try {
                    run1 = c.runAgent(
                        userRequest = "alpha question",
                        mode = RunMode.BUILD,
                        projectRoot = root,
                        events = {},
                        onConfirm = { true },
                        sessionId = sessionA.id
                    )
                    run2 = c.runAgent(
                        userRequest = "beta question",
                        mode = RunMode.BUILD,
                        projectRoot = root,
                        events = {},
                        onConfirm = { true },
                        sessionId = sessionB.id
                    )
                } finally {
                    (run2 ?: run1 ?: c.activeRun)?.job?.cancel()
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) throw t
        }
        assertEquals(AgentState.DONE, run1!!.state)
        assertEquals(AgentState.DONE, run2!!.state)

        // The beta prompt (3rd provider call) must not contain alpha history.
        assertEquals(4, fake.prompts.size)
        assertFalse(fake.prompts[2].contains("marker-alpha"))
        assertFalse(fake.prompts[2].contains("alpha question"))
        assertTrue(fake.prompts[2].contains("beta question"))

        val historyA = runBlocking { manager.getSession(sessionA.id) }!!
        val historyB = runBlocking { manager.getSession(sessionB.id) }!!
        assertTrue(historyA.messages.any { it.content.contains("alpha question") })
        assertFalse(historyA.messages.any { it.content.contains("beta question") })
        assertTrue(historyB.messages.any { it.content.contains("beta question") })
        assertFalse(historyB.messages.any { it.content.contains("marker-alpha") })
    }

    @Test
    fun testSessionIdWithoutManagerFailsFast() {
        val c = AgentController(providerCall = FakeProviderCall(listOf("x")))
        try {
            runBlocking {
                c.runAgent(
                    userRequest = "x",
                    mode = RunMode.BUILD,
                    projectRoot = root,
                    events = {},
                    onConfirm = { true },
                    sessionId = "missing-manager"
                )
            }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sessionManager"))
        } finally {
            c.activeRun?.job?.cancel()
        }
    }
}
