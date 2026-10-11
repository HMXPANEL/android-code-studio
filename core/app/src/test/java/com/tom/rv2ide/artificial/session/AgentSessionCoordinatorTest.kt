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

//
// Tests for [AgentSessionCoordinator]: one session per conversation, stable
// reuse across messages, explicit switching without cross-contamination, and
// visible (non-throwing) storage failures.
class AgentSessionCoordinatorTest {

    private lateinit var root: File
    private var savedId: String? = null
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        root = Files.createTempDirectory("coord_test_").toFile()
        savedId = null
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun coordinator(
        projectRoot: File = root,
        store: SessionStore? = null
    ) = AgentSessionCoordinator(
        projectRoot = projectRoot,
        scope = scope,
        sessionStore = store,
        loadCurrentId = { savedId },
        saveCurrentId = { savedId = it }
    )

    @Test
    fun testFirstUseCreatesOneSessionAndReusesIt() = runBlocking {
        val c = coordinator()
        val first = c.sessionIdFor(RunMode.BUILD, "p", "m")
        assertNotNull(first)
        assertNull(c.lastError)

        // Follow-up messages reuse the same ID; no session-per-message.
        assertEquals(first, c.sessionIdFor(RunMode.BUILD, "p", "m"))
        assertEquals(first, c.currentSessionId)
        assertEquals(first, savedId)
        assertEquals(1, c.listSessions().size)
    }

    @Test
    fun testDeletedSessionIsRecreatedNotResurrected() = runBlocking {
        val c = coordinator()
        val first = c.sessionIdFor(RunMode.BUILD, "p", "m")!!
        assertTrue(c.deleteSession(first))
        // Remembered ID is gone from the store: next use creates a new one.
        val second = c.sessionIdFor(RunMode.BUILD, "p", "m")!!
        assertNotEquals(first, second)
        assertNull(c.lastError)
    }

    @Test
    fun testSwitchingNeverMixesHistories() = runBlocking {
        val c = coordinator()
        val idA = c.startNewSession(RunMode.BUILD, "p", "m")!!
        c.sessionManager.addUserMessage(idA, "message for A")
        val idB = c.startNewSession(RunMode.BUILD, "p", "m")!!
        c.sessionManager.addUserMessage(idB, "message for B")

        assertTrue(c.switchTo(idA))
        assertEquals(idA, c.currentSessionId)
        assertEquals(listOf("message for A"), c.history(idA)!!.messages.map { it.content })
        assertEquals(listOf("message for B"), c.history(idB)!!.messages.map { it.content })

        assertFalse(c.switchTo("does-not-exist"))
        assertEquals(idA, c.currentSessionId)
    }

    @Test
    fun testForgetCurrentStartsFreshConversation() = runBlocking {
        val c = coordinator()
        val first = c.sessionIdFor(RunMode.BUILD, "p", "m")!!
        c.forgetCurrent()
        assertNull(c.currentSessionId)
        assertNull(savedId)
        val second = c.sessionIdFor(RunMode.BUILD, "p", "m")!!
        assertNotEquals(first, second)
        // The abandoned session's history is retained, not deleted.
        assertEquals(2, c.listSessions().size)
    }

    @Test
    fun testSessionsAreScopedPerProject() = runBlocking {
        val other = Files.createTempDirectory("coord_other_").toFile()
        try {
            val cA = coordinator(root)
            val cB = coordinator(other)
            val idA = cA.sessionIdFor(RunMode.BUILD, "p", "m")!!
            // A session from project A can never be adopted by project B.
            assertFalse(cB.switchTo(idA))
            assertTrue(cB.listSessions().none { it.id == idA })
            assertTrue(cA.listSessions().any { it.id == idA })
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun testStorageFailureIsVisibleNeverSilent() = runBlocking {
        val failing = object : SessionStore {
            override suspend fun create(session: AgentSession): AgentSession =
                throw java.io.IOException("disk gone")

            override suspend fun get(id: String): AgentSession? =
                throw java.io.IOException("disk gone")

            override suspend fun list(projectRoot: String): List<AgentSession> =
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
        val c = coordinator(store = failing)
        assertNull(c.sessionIdFor(RunMode.BUILD, "p", "m"))
        assertNotNull(c.lastError)
        assertTrue(c.listSessions().isEmpty())
        assertFalse(c.switchTo("x"))
    }
}
