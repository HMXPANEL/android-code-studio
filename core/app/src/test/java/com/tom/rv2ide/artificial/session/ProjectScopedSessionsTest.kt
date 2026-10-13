package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.agent.AgentRun
import com.tom.rv2ide.artificial.tools.RunMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for stable project identity in session discovery: projectId
 * round-trip, legacy path records, moved/foreign projects, traversal
 * hardening, corrupt-record reporting and the history row mapper.
 */
class ProjectScopedSessionsTest {

    private lateinit var root: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        root = Files.createTempDirectory("proj_sess_").toFile()
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun managerWith(store: SessionStore) =
        SessionManager(
            store,
            ContextEngine(store, ProjectIndexer(), RelevanceEngine(ProjectIndexer())),
            ProjectIndexer(),
            RelevanceEngine(ProjectIndexer()),
            scope
        )

    @Test
    fun testCreateSessionWritesProjectId() = runBlocking {
        val store = FileSessionStore(root)
        val manager = managerWith(store)
        val pid = (ProjectIdentity.getOrCreate(root)
            as ProjectIdentity.Resolution.Stable).id
        val session = manager.createSession(root, RunMode.BUILD, "p", "m", projectId = pid)
        assertEquals(pid, session.projectId)
        assertEquals(pid, store.get(session.id)!!.projectId)
    }

    @Test
    fun testLegacyRecordWithoutProjectIdStillDecodes() = runBlocking {
        val store = FileSessionStore(root)
        val legacy = AgentSession(
            projectRoot = root.absolutePath,
            mode = RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "legacy"
        )
        store.create(legacy)
        // Simulate a pre-ID record by stripping the field from disk.
        val file = File(root, ".opencode/sessions/${legacy.id}.json")
        file.writeText(file.readText().replace(Regex("\"projectId\"[^,]*,?"), ""))
        val reloaded = FileSessionStore(root).get(legacy.id)
        assertNotNull(reloaded)
        assertNull(reloaded!!.projectId)
        assertEquals("legacy", reloaded.title)
        assertEquals(RunMode.BUILD, reloaded.mode)
    }

    @Test
    fun testMovedProjectAdoptsByIdAndDirWhileForeignExcluded() = runBlocking {
        val oldDir = File(root, "old").apply { mkdirs() }
        val store = FileSessionStore(oldDir)
        val manager = managerWith(store)
        val pid = (ProjectIdentity.getOrCreate(oldDir)
            as ProjectIdentity.Resolution.Stable).id
        val withId = manager.createSession(oldDir, RunMode.BUILD, "p", "m", projectId = pid)
        // Legacy record with a stale path, physically present in the store dir.
        val legacy = AgentSession(
            projectRoot = "/somewhere/else",
            mode = RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "legacy-stale-path"
        )
        store.create(legacy)
        // Foreign record smuggled in with another project's ID.
        val foreign = AgentSession(
            projectRoot = oldDir.absolutePath,
            mode = RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "foreign",
            projectId = "00000000-0000-0000-0000-000000000000"
        )
        store.create(foreign)

        // Simulate a real move: the whole .opencode tree (marker + sessions)
        // travels to the new path; discovery runs against the new store.
        val newDir = File(root, "new").apply { mkdirs() }
        File(oldDir, ".opencode").copyRecursively(File(newDir, ".opencode"))
        val movedPid = (ProjectIdentity.resolve(newDir)
            as ProjectIdentity.Resolution.Stable).id
        assertEquals(pid, movedPid)

        val movedStore = FileSessionStore(newDir)
        val movedManager = managerWith(movedStore)
        val listed = movedManager.listSessions(newDir, movedPid).map { it.id }.toSet()
        assertTrue(listed.contains(withId.id)) // same ID, stale path
        assertTrue(listed.contains(legacy.id)) // adopted by directory
        assertFalse(listed.contains(foreign.id)) // different ID excluded

        // Legacy path-only mode keeps exact old behavior on the old store.
        val pathOnly = manager.listSessions(oldDir, null).map { it.id }.toSet()
        assertTrue(pathOnly.contains(withId.id))
        assertFalse(pathOnly.contains(legacy.id))
    }

    @Test
    fun testSwitchToMovedProjectSession() = runBlocking {
        val oldDir = File(root, "old2").apply { mkdirs() }
        val coord = AgentSessionCoordinator(oldDir, scope, FileSessionStore(oldDir))
        val id = coord.startNewSession(RunMode.BUILD, "p", "m")!!
        assertNotNull(coord.currentProjectId)

        val newDir = File(root, "new2").apply { mkdirs() }
        File(oldDir, ".opencode").copyRecursively(File(newDir, ".opencode"))
        val coord2 = AgentSessionCoordinator(newDir, scope, FileSessionStore(newDir))
        assertTrue(coord2.switchTo(id))
        assertEquals(id, coord2.currentSessionId)
    }

    @Test
    fun testCoordinatorRestoresCurrentIdAcrossRecreation() = runBlocking {
        val store = InMemorySessionStore()
        var saved: String? = null
        val c1 = AgentSessionCoordinator(
            root, scope, store,
            loadCurrentId = { saved }, saveCurrentId = { saved = it }
        )
        val id = c1.sessionIdFor(RunMode.BUILD, "p", "m")!!
        // Simulate chat-screen recreation: same store, same pref backing.
        val c2 = AgentSessionCoordinator(
            root, scope, store,
            loadCurrentId = { saved }, saveCurrentId = { saved = it }
        )
        assertEquals(id, c2.currentSessionId)
        assertEquals(id, c2.sessionIdFor(RunMode.BUILD, "p", "m"))
    }

    @Test
    fun testFileAsProjectRootFailsFast() {
        val file = File(root, "plain.txt").apply { writeText("x") }
        try {
            AgentSessionCoordinator(file, scope, InMemorySessionStore())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not a directory"))
        }
    }

    @Test
    fun testTraversalIdsRejected() = runBlocking {
        val store = FileSessionStore(root)
        try {
            store.create(
                AgentSession(
                    id = "../evil",
                    projectRoot = root.absolutePath,
                    mode = RunMode.BUILD,
                    providerId = "p",
                    modelId = "m"
                )
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Fail visible, nothing written outside the sessions dir.
        }
        assertNull(store.get("../evil"))
        assertFalse(store.delete("..\\evil"))
        assertFalse(File(root, "evil.json").exists())
        assertFalse(File(root, ".opencode/evil.json").exists())
    }

    @Test
    fun testCorruptRecordsReportedValidIntact() = runBlocking {
        val store = FileSessionStore(root)
        val good = AgentSession(
            projectRoot = root.absolutePath,
            mode = RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "good"
        )
        store.create(good)
        File(root, ".opencode/sessions/bad.json").writeText("{nope")

        val store2 = FileSessionStore(root)
        assertEquals(listOf("bad.json"), store2.corruptRecords())
        assertEquals("good", store2.get(good.id)!!.title)
        // The corrupt file is left in place, never deleted or overwritten.
        assertTrue(File(root, ".opencode/sessions/bad.json").exists())
    }

    @Test
    fun testMemoryFactsAndBudgetOverrideReachContext() = runBlocking {
        val store = InMemorySessionStore()
        val memory = ProjectMemoryStore(root)
        val settings = ProjectAgentSettings(root)
        assertTrue(memory.addFact("Project uses minSdk 26"))
        assertTrue(settings.setMaxContextTokens(2500))
        val mgr = SessionManager(
            store,
            ContextEngine(store, ProjectIndexer(), RelevanceEngine(ProjectIndexer())),
            ProjectIndexer(),
            RelevanceEngine(ProjectIndexer()),
            scope,
            memory,
            settings
        )
        val session = mgr.createSession(root, RunMode.BUILD, "p", "m")
        mgr.addUserMessage(session.id, "hello")
        val run = AgentRun(mode = RunMode.BUILD, sessionId = session.id)
        val compiled = mgr.compileContext(run, root, "hi", emptyList())
        assertTrue(compiled.projectInstructions.contains("minSdk 26"))
        // 2500-token budget on a debug-size context forces compaction, while
        // the 32k default would not: proves the override took effect.
        assertTrue(compiled.wasCompacted)
        assertTrue(compiled.systemInstructions.isNotBlank())
        assertEquals("hi", compiled.userRequest)
    }

    @Test
    fun testConversationRowMapping() {
        val session = AgentSession(
            projectRoot = "/r",
            mode = RunMode.BUILD,
            providerId = "p",
            modelId = "m",
            title = "",
            messages = listOf(
                PersistedMessage(role = MessageRole.USER, content = "hi"),
                PersistedMessage(
                    role = MessageRole.ASSISTANT,
                    content = "ok",
                    toolCalls = listOf(
                        PersistedToolCall(callId = "c1", name = "t", arguments = mapOf("a" to "b"))
                    ),
                    toolResults = listOf(
                        PersistedToolResult(callId = "c1", success = true, output = "o", error = null)
                    )
                )
            )
        )
        val row = session.toConversationRow()
        assertEquals(session.id, row.sessionId)
        assertEquals("Untitled conversation", row.title)
        assertEquals(2, row.messageCount)
        assertEquals(1, row.toolCallCount)
        assertEquals(session.updatedAt, row.updatedAt)
    }
}
