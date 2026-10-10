package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.tools.RunMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: FileSessionStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session_test_").toFile()
        store = FileSessionStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testCreateAndGetSession() = runBlocking {
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = RunMode.BUILD,
            providerId = "test-provider",
            modelId = "test-model",
            title = "Test Session"
        )

        val created = store.create(session)
        assertEquals(session.id, created.id)

        val retrieved = store.get(session.id)
        assertNotNull(retrieved)
        assertEquals("Test Session", retrieved!!.title)
        assertEquals(RunMode.BUILD, retrieved.mode)
    }

    @Test
    fun testUpdateSession() = runBlocking {
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = RunMode.BUILD,
            providerId = "test-provider",
            modelId = "test-model"
        )
        store.create(session)
        val updated = store.update(session.copyWith(title = "Updated Title"))
        assertEquals("Updated Title", updated.title)

        val retrieved = store.get(session.id)
        assertEquals("Updated Title", retrieved!!.title)
    }

    @Test
    fun testDeleteSession() = runBlocking {
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = RunMode.BUILD,
            providerId = "test-provider",
            modelId = "test-model"
        )
        store.create(session)
        assertTrue(store.delete(session.id))
        assertNull(store.get(session.id))
        assertFalse(store.delete(session.id))
    }

    @Test
    fun testListSessionsByProject() = runBlocking {
        val projectA = File(tempDir, "projectA").apply { mkdirs() }
        val projectB = File(tempDir, "projectB").apply { mkdirs() }

        store.create(
            AgentSession(
                projectRoot = projectA.absolutePath,
                mode = RunMode.BUILD,
                providerId = "p", modelId = "m", title = "A"
            )
        )
        store.create(
            AgentSession(
                projectRoot = projectB.absolutePath,
                mode = RunMode.BUILD,
                providerId = "p", modelId = "m", title = "B"
            )
        )

        assertEquals(1, store.list(projectA.absolutePath).size)
        assertEquals(1, store.list(projectB.absolutePath).size)
        assertEquals("A", store.list(projectA.absolutePath)[0].title)
    }

    @Test
    fun testPersistMessagesAndToolCalls() = runBlocking {
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = RunMode.BUILD,
            providerId = "p", modelId = "m"
        )
        store.create(session)

        val message = PersistedMessage(
            role = MessageRole.USER,
            content = "Hello, world!",
            toolCalls = listOf(
                PersistedToolCall(callId = "call-1", name = "read_file", arguments = mapOf("path" to "test.kt"))
            ),
            toolResults = listOf(
                PersistedToolResult(callId = "call-1", success = true, output = "file content", error = null)
            )
        )
        store.update(session.copyWith(messages = listOf(message)))

        val retrieved = store.getWithHistory(session.id)
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.messages.size)
        assertEquals(MessageRole.USER, retrieved.messages[0].role)
        assertEquals("read_file", retrieved.messages[0].toolCalls[0].name)
        assertTrue(retrieved.messages[0].toolResults[0].success)
    }

    @Test
    fun testInMemorySessionStore() = runBlocking {
        val mem = InMemorySessionStore()
        val session = AgentSession(
            projectRoot = "/test/project",
            mode = RunMode.PLAN,
            providerId = "p", modelId = "m"
        )
        mem.create(session)
        assertNotNull(mem.get(session.id))
        assertEquals(1, mem.list("/test/project").size)
        assertTrue(mem.delete(session.id))
        assertNull(mem.get(session.id))
    }
}
