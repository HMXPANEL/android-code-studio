package com.tom.rv2ide.artificial.session

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Acceptance tests for file-backed session persistence.
 *
 * Test B (disk reload via a NEW store instance), Test D (interrupted tool
 * calls) and Test E (corrupt / missing / unknown-field records).
 */
class SessionPersistenceTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sess_persist_").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sessionsDir(): File = File(tempDir, ".opencode/sessions")

    @Test
    fun testReloadViaNewStoreInstanceReadsFromDisk() = runBlocking {
        val store1 = FileSessionStore(tempDir)
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = com.tom.rv2ide.artificial.tools.RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "Disk session",
            metadata = mapOf("goal" to "verify-reload")
        )
        store1.create(session)
        store1.update(
            session.copyWith(
                messages = listOf(
                    PersistedMessage(role = MessageRole.USER, content = "first question"),
                    PersistedMessage(
                        role = MessageRole.ASSISTANT,
                        content = "reading file",
                        toolCalls = listOf(
                            PersistedToolCall(
                                callId = "call-1",
                                name = "local:read_file",
                                arguments = mapOf("path" to "a/b.kt")
                            )
                        ),
                        toolResults = listOf(
                            PersistedToolResult(
                                callId = "call-1",
                                success = true,
                                output = "content here",
                                error = null
                            )
                        )
                    )
                )
            )
        )

        // Prove the record is on disk, then drop all in-memory state.
        assertTrue(File(sessionsDir(), "${session.id}.json").exists())

        val store2 = FileSessionStore(tempDir)
        val reloaded = store2.getWithHistory(session.id)
        assertNotNull(reloaded)
        reloaded!!
        assertEquals(session.id, reloaded.id)
        assertEquals("Disk session", reloaded.title)
        assertEquals(mapOf("goal" to "verify-reload"), reloaded.metadata)
        assertEquals(2, reloaded.messages.size)
        assertEquals("first question", reloaded.messages[0].content)
        assertEquals("a/b.kt", reloaded.messages[1].toolCalls.single().arguments["path"])
        assertEquals("content here", reloaded.messages[1].toolResults.single().output)
        assertTrue(reloaded.messages[1].toolResults.single().success)
        assertTrue(reloaded.createdAt > 0)
        assertTrue(reloaded.updatedAt >= reloaded.createdAt)
    }

    @Test
    fun testInterruptedToolCallRestorableWithoutDuplicateResults() = runBlocking {
        val store = InMemorySessionStore()
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = com.tom.rv2ide.artificial.tools.RunMode.BUILD,
            providerId = "p",
            modelId = "m"
        )
        store.create(session)

        // Simulate a crash between persisting the call and its result.
        store.update(
            session.copyWith(
                messages = listOf(
                    PersistedMessage(
                        role = MessageRole.ASSISTANT,
                        content = "deleting file",
                        toolCalls = listOf(
                            PersistedToolCall(
                                callId = "call-9",
                                name = "local:delete_file",
                                arguments = mapOf("path" to "old.txt")
                            )
                        )
                    )
                )
            )
        )

        val restored = store.getWithHistory(session.id)!!
        assertEquals(ToolCallStatus.PENDING, restored.messages.single().toolCalls.single().status)
        assertTrue(restored.messages.single().toolResults.isEmpty())

        // Recovery records the result exactly once; no blind re-execution is
        // triggered by the store itself (execution only happens on fresh turns).
        val manager = SessionManager(
            store,
            ContextEngine(store, ProjectIndexer(), RelevanceEngine(ProjectIndexer())),
            ProjectIndexer(),
            RelevanceEngine(ProjectIndexer()),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        assertTrue(manager.addToolResult(session.id, "call-9", true, "deleted", null))
        val recovered = store.getWithHistory(session.id)!!
        assertEquals(1, recovered.messages.single().toolResults.size)
        assertEquals(ToolCallStatus.COMPLETED, recovered.messages.single().toolCalls.single().status)
    }

    @Test
    fun testCorruptRecordDoesNotDeleteValidSessions() = runBlocking {
        val store1 = FileSessionStore(tempDir)
        val good = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = com.tom.rv2ide.artificial.tools.RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "good"
        )
        store1.create(good)

        // Corrupt record alongside the valid one.
        File(sessionsDir(), "bad.json").writeText("{not valid json{{{")

        val store2 = FileSessionStore(tempDir)
        val listed = store2.list(tempDir.absolutePath)
        assertEquals(1, listed.size)
        assertEquals("good", listed[0].title)
        assertNotNull(store2.get(good.id))
        assertNull(store2.get("bad"))
    }

    @Test
    fun testUnknownFieldsToleratedAndMissingReturnsNull() = runBlocking {
        val store1 = FileSessionStore(tempDir)
        val session = AgentSession(
            projectRoot = tempDir.absolutePath,
            mode = com.tom.rv2ide.artificial.tools.RunMode.PLAN,
            providerId = "p",
            modelId = "m",
            title = "future-proof"
        )
        store1.create(session)

        // Inject an unknown field as a future-version migration would see it.
        val file = File(sessionsDir(), "${session.id}.json")
        file.writeText(file.readText().replaceFirst("\"title\":", "\"futureField\":{\"x\":1},\"title\":"))

        val store2 = FileSessionStore(tempDir)
        val reloaded = store2.get(session.id)
        assertNotNull(reloaded)
        assertEquals("future-proof", reloaded!!.title)
        assertEquals(com.tom.rv2ide.artificial.tools.RunMode.PLAN, reloaded.mode)

        assertNull(store2.get("does-not-exist"))
        assertFalse(store2.delete("does-not-exist"))
    }
}
