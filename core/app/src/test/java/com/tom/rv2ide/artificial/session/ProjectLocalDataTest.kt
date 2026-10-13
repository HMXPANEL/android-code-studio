package com.tom.rv2ide.artificial.session

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for project-local memory ([ProjectMemoryStore]), settings
 * ([ProjectAgentSettings]) and the secret denylist ([SecretDenylist]).
 */
class ProjectLocalDataTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("proj_data_").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun testMemoryRoundTripAddRemoveClear() {
        val store = ProjectMemoryStore(root)
        assertTrue(store.getFacts().isEmpty())
        assertTrue(store.addFact("Uses Kotlin 2.1"))
        assertTrue(store.addFact("Min SDK 26"))
        assertEquals(listOf("Uses Kotlin 2.1", "Min SDK 26"), store.getFacts())
        assertTrue(File(root, ".opencode/memory.json").isFile)

        // Reload from disk through a new instance.
        assertEquals(
            listOf("Uses Kotlin 2.1", "Min SDK 26"),
            ProjectMemoryStore(root).getFacts()
        )

        assertFalse(store.addFact("Uses Kotlin 2.1")) // duplicate
        assertFalse(store.addFact("   ")) // blank
        assertTrue(store.removeFact("Min SDK 26"))
        assertEquals(listOf("Uses Kotlin 2.1"), store.getFacts())
        assertFalse(store.removeFact("absent"))
        assertTrue(store.clear())
        assertTrue(store.getFacts().isEmpty())
    }

    @Test
    fun testCorruptMemoryReadsAsEmpty() {
        File(root, ".opencode").mkdirs()
        File(root, ".opencode/memory.json").writeText("{broken")
        assertEquals(emptyList<String>(), ProjectMemoryStore(root).getFacts())
        // Recovery works: a new fact rewrites a valid file.
        assertTrue(ProjectMemoryStore(root).addFact("fresh"))
        assertEquals(listOf("fresh"), ProjectMemoryStore(root).getFacts())
    }

    @Test
    fun testSettingsRoundTrip() {
        val settings = ProjectAgentSettings(root)
        assertTrue(settings.set("theme", "dark"))
        assertEquals("dark", settings.get("theme"))
        assertEquals(mapOf("theme" to "dark"), settings.getAll())
        assertTrue(File(root, ".opencode/settings.json").isFile)

        assertEquals(mapOf("theme" to "dark"), ProjectAgentSettings(root).getAll())
        assertTrue(settings.remove("theme"))
        assertNull(settings.get("theme"))
        assertFalse(settings.remove("theme"))
    }

    @Test
    fun testSecretShapedKeysRejected() {
        val settings = ProjectAgentSettings(root)
        assertFalse(settings.set("gemini_api_key", "anything"))
        assertFalse(settings.set("authToken", "anything"))
        assertFalse(settings.set("DB_PASSWORD", "anything"))
        assertFalse(settings.set("secret", "anything"))
        assertNull(settings.get("gemini_api_key"))
        // Nothing secret-bearing was written.
        assertFalse(File(root, ".opencode/settings.json").exists())
    }

    @Test
    fun testSecretShapedValuesRejected() {
        val settings = ProjectAgentSettings(root)
        assertFalse(settings.set("notes", "sk-abcdefghijklmnop"))
        assertFalse(settings.set("notes", "xoxb-1234567890"))
        assertFalse(settings.set("notes", "AKIAIOSFODNN7EXAMPLE"))
        assertTrue(settings.set("notes", "plain project note"))
        assertEquals("plain project note", settings.get("notes"))
    }

    @Test
    fun testContextBudgetClamped() {
        val settings = ProjectAgentSettings(root)
        assertTrue(settings.setMaxContextTokens(1))
        assertEquals(AgentSettingsData.MIN_CONTEXT_TOKENS, settings.getMaxContextTokens())
        assertTrue(settings.setMaxContextTokens(1_000_000_000))
        assertEquals(AgentSettingsData.MAX_CONTEXT_TOKENS, settings.getMaxContextTokens())
        assertTrue(settings.setMaxContextTokens(8000))
        assertEquals(8000, settings.getMaxContextTokens())
    }

    @Test
    fun testDenylistDirectly() {
        assertTrue(SecretDenylist.rejects("openai_api_key", "x"))
        assertTrue(SecretDenylist.rejects("notes", "sk-abcdefghijklmnop"))
        assertFalse(SecretDenylist.rejects("theme", "dark"))
        assertFalse(SecretDenylist.rejects("notes", "short"))
    }
}
