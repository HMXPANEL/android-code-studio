package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.tools.RunMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 3.2.2: the production legacy (one-shot) chat path previously persisted
 * nothing, and merely constructing a store fabricated empty directories.
 * These tests pin the fixed behavior: no directories until first write, the
 * legacy-shaped flow (user message + delivered final text) round-trips
 * through a new store instance, and diagnostics stay sanitized.
 */
class LegacyChatPersistenceTest {

    private lateinit var root: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        root = Files.createTempDirectory("legacy_chat_").toFile()
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun testStoreCreatesNoDirectoriesUntilFirstWrite() = runBlocking {
        val store = FileSessionStore(root)
        assertFalse(File(root, ".opencode").exists())
        assertTrue(store.listAll().isEmpty())
        assertNull(store.get("missing"))
        assertTrue(store.corruptRecords().isEmpty())

        store.create(
            AgentSession(
                projectRoot = root.absolutePath,
                mode = RunMode.BUILD,
                providerId = "p",
                modelId = "m"
            )
        )
        assertTrue(File(root, ".opencode/sessions").isDirectory)
    }

    @Test
    fun testLegacyFlowPersistsUserAndDeliveredAnswer() = runBlocking {
        // Mirrors ArtificialFragment.executeRequest: resolve session, await the
        // user record, run the provider, then await the delivered final text.
        val coordinator = AgentSessionCoordinator(root, scope, FileSessionStore(root))
        val sessionId = coordinator.sessionIdFor(RunMode.BUILD, "p", "m")
        assertNotNull(sessionId)
        assertTrue(
            coordinator.sessionManager.addUserMessage(sessionId!!, "Explain this project")
        )
        val delivered = "Here is the project structure."
        assertTrue(
            coordinator.sessionManager.addAssistantMessage(sessionId, delivered)
        )

        // Reload through a brand-new store instance, as History does.
        val reloaded = FileSessionStore(root).getWithHistory(sessionId)!!
        assertEquals(2, reloaded.messages.size)
        assertEquals(MessageRole.USER, reloaded.messages[0].role)
        assertEquals("Explain this project", reloaded.messages[0].content)
        assertEquals(MessageRole.ASSISTANT, reloaded.messages[1].role)
        assertEquals(delivered, reloaded.messages[1].content)

        val listed = coordinator.listSessions()
        assertEquals(1, listed.size)
        val row = listed.single().toConversationRow()
        assertEquals(2, row.messageCount)
        assertEquals(0, row.toolCallCount)
    }

    @Test
    fun testDiagnosticsSnapshotIsSanitizedAndAccurate() = runBlocking {
        val coordinator = AgentSessionCoordinator(root, scope, FileSessionStore(root))

        val before = coordinator.diagnostics()
        assertFalse(before.sessionsDirExists)
        assertEquals(0, before.sessionFileCount)
        assertFalse(before.hasCurrentSession)
        assertEquals(0, before.currentUserMessages)
        assertEquals(0, before.currentAssistantMessages)
        assertNull(before.lastErrorType)
        // 64-char hex digest that reveals nothing about the path itself.
        assertTrue(before.rootHash.matches(Regex("[0-9a-f]{64}")))
        assertFalse(before.rootHash.contains(root.name))
        assertFalse(before.toString().contains(root.absolutePath))

        val sessionId = coordinator.sessionIdFor(RunMode.BUILD, "p", "m")!!
        coordinator.sessionManager.addUserMessage(sessionId, "q")
        coordinator.sessionManager.addAssistantMessage(sessionId, "a")

        val after = coordinator.diagnostics()
        assertEquals(before.rootHash, after.rootHash)
        assertTrue(after.sessionsDirExists)
        assertTrue(after.sessionsDirWritable)
        assertEquals(1, after.sessionFileCount)
        assertTrue(after.hasCurrentSession)
        assertEquals(1, after.currentUserMessages)
        assertEquals(1, after.currentAssistantMessages)
        assertNull(after.lastErrorType)

        // Different projects hash differently.
        val other = Files.createTempDirectory("legacy_other_").toFile()
        try {
            val otherDiag = AgentSessionCoordinator(other, scope, FileSessionStore(other)).diagnostics()
            assertNotEquals(before.rootHash, otherDiag.rootHash)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun testDiagnosticsReportsFailureType() = runBlocking {
        val failing = object : SessionStore {
            override suspend fun create(session: AgentSession): AgentSession =
                throw java.io.IOException("disk gone")

            override suspend fun get(id: String): AgentSession? =
                throw java.io.IOException("disk gone")

            override suspend fun list(projectRoot: String): List<AgentSession> =
                throw java.io.IOException("disk gone")

            override suspend fun listAll(): List<AgentSession> =
                throw java.io.IOException("disk gone")

            override suspend fun update(session: AgentSession): AgentSession =
                throw java.io.IOException("disk gone")

            override suspend fun delete(id: String): Boolean =
                throw java.io.IOException("disk gone")

            override fun observe(projectRoot: String): Flow<List<AgentSession>> =
                flowOf(emptyList())

            override suspend fun getWithHistory(id: String): AgentSession? =
                throw java.io.IOException("disk gone")
        }
        val coordinator = AgentSessionCoordinator(root, scope, failing)
        assertNull(coordinator.sessionIdFor(RunMode.BUILD, "p", "m"))

        val diag = coordinator.diagnostics()
        assertEquals("IOException", diag.lastErrorType)
        assertFalse(diag.hasCurrentSession)
        assertEquals(0, diag.currentUserMessages)
        // Sanitized: type name only, never the message with details.
        assertFalse((diag.lastErrorType ?: "").contains("disk"))
    }
}
