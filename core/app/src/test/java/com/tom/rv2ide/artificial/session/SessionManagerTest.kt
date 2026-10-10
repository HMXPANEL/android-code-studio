package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: SessionManager

    @Before
    fun setUp() {
        SessionManager.clearForTests()
        tempDir = Files.createTempDirectory("manager_test_").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        manager = SessionManager.getOrCreate(tempDir, scope, InMemorySessionStore())
    }

    @Test
    fun testCreateSession() = runBlocking {
        val session = manager.createSession(tempDir, RunMode.BUILD, "p", "m", "Test Session")
        assertNotNull(session)
        assertEquals("Test Session", session.title)
        assertEquals(tempDir.canonicalPath, session.projectRoot)
        assertEquals(RunMode.BUILD, session.mode)
    }

    @Test
    fun testGetSession() = runBlocking {
        val created = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        val retrieved = manager.getSession(created.id)
        assertNotNull(retrieved)
        assertEquals(created.id, retrieved!!.id)
    }

    @Test
    fun testListSessions() = runBlocking {
        manager.createSession(tempDir, RunMode.BUILD, "p", "m", "Session 1")
        manager.createSession(tempDir, RunMode.BUILD, "p", "m", "Session 2")
        assertEquals(2, manager.listSessions(tempDir).size)
    }

    @Test
    fun testRenameSession() = runBlocking {
        val created = manager.createSession(tempDir, RunMode.BUILD, "p", "m", "Original")
        assertTrue(manager.renameSession(created.id, "Renamed"))
        assertEquals("Renamed", manager.getSession(created.id)!!.title)
    }

    @Test
    fun testDeleteSession() = runBlocking {
        val created = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        assertTrue(manager.deleteSession(created.id))
        assertNull(manager.getSession(created.id))
    }

    @Test
    fun testAddUserMessage() = runBlocking {
        val session = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        assertTrue(manager.addUserMessage(session.id, "Hello"))
        val retrieved = manager.getSession(session.id)!!
        assertEquals(1, retrieved.messages.size)
        assertEquals(MessageRole.USER, retrieved.messages[0].role)
        assertEquals("Hello", retrieved.messages[0].content)
    }

    @Test
    fun testAddAssistantMessageWithToolCalls() = runBlocking {
        val session = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        val call = ToolCall(name = "local:read_file", args = mapOf("path" to "test.kt"), callId = "call-1", runId = "run-1")
        assertTrue(manager.addAssistantMessage(session.id, "I'll read the file", listOf(call)))

        val retrieved = manager.getSession(session.id)!!
        assertEquals(1, retrieved.messages.size)
        assertEquals(MessageRole.ASSISTANT, retrieved.messages[0].role)
        assertEquals(1, retrieved.messages[0].toolCalls.size)
        assertEquals("local:read_file", retrieved.messages[0].toolCalls[0].name)
        assertEquals("test.kt", retrieved.messages[0].toolCalls[0].arguments["path"])
    }

    @Test
    fun testAddToolResult() = runBlocking {
        val session = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        val call = ToolCall(name = "local:read_file", args = mapOf("path" to "test.kt"), callId = "call-1", runId = "run-1")
        manager.addAssistantMessage(session.id, "Reading file", listOf(call))

        assertTrue(manager.addToolResult(session.id, call, ToolResult.success("file content")))

        val retrieved = manager.getSession(session.id)!!
        assertEquals(1, retrieved.messages[0].toolResults.size)
        assertTrue(retrieved.messages[0].toolResults[0].success)
        assertEquals("file content", retrieved.messages[0].toolResults[0].output)
        assertEquals(ToolCallStatus.COMPLETED, retrieved.messages[0].toolCalls[0].status)
    }

    @Test
    fun testAddCheckpoint() = runBlocking {
        val session = manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        assertTrue(manager.addCheckpoint(session.id, "abc123", "Initial commit"))
        val retrieved = manager.getSession(session.id)!!
        assertEquals(1, retrieved.checkpoints.size)
        assertEquals("abc123", retrieved.checkpoints[0].commitHash)
    }

    @Test
    fun testObserveSessions() = runBlocking {
        assertTrue(manager.observeSessions(tempDir).first().isEmpty())
        manager.createSession(tempDir, RunMode.BUILD, "p", "m")
        assertEquals(1, manager.observeSessions(tempDir).first().size)
    }
}
