package com.tom.rv2ide.artificial.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RelevanceEngineTest {

    private fun projectWithFiles(): File {
        val tempDir = Files.createTempDirectory("relevance_test_").toFile()
        val srcDir = File(tempDir, "src/main/kotlin/com/example").apply { mkdirs() }
        File(srcDir, "MainActivity.kt").writeText(
            "package com.example\nclass MainActivity {\n" +
                "  fun onCreate() {\n    val vm = UserViewModel()\n  }\n}"
        )
        File(srcDir, "UserViewModel.kt").writeText(
            "package com.example\nclass UserViewModel {\n" +
                "  private val repository = UserRepository()\n  fun loadUser() { repository.getUser() }\n}"
        )
        File(srcDir, "UserRepository.kt").writeText(
            "package com.example\nclass UserRepository {\n  fun getUser() = \"User\"\n}"
        )
        return tempDir
    }

    @Test
    fun testFindRelevantFilesByName() = runBlocking {
        val dir = projectWithFiles()
        try {
            val engine = RelevanceEngine(ProjectIndexer())
            val results = engine.findRelevantFiles(dir, "MainActivity", maxFiles = 5)
            assertTrue(results.isNotEmpty())
            assertNotNull(results.find { it.path.contains("MainActivity") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFindRelevantFilesByContent() = runBlocking {
        val dir = projectWithFiles()
        try {
            val engine = RelevanceEngine(ProjectIndexer())
            val results = engine.findRelevantFiles(dir, "UserRepository", maxFiles = 5)
            assertTrue(results.isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFindRelevantFilesSessionHistory() = runBlocking {
        val dir = projectWithFiles()
        try {
            val engine = RelevanceEngine(ProjectIndexer())
            val results = engine.findRelevantFiles(
                projectRoot = dir,
                query = "repository",
                sessionHistory = "UserViewModel and UserRepository",
                maxFiles = 5
            )
            assertTrue(results.isNotEmpty())
            assertNotNull(results.find { it.path.contains("UserRepository") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testMaxResultsLimit() = runBlocking {
        val dir = projectWithFiles()
        try {
            val engine = RelevanceEngine(ProjectIndexer())
            val results = engine.findRelevantFiles(dir, "class", maxFiles = 2)
            assertTrue(results.size <= 2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testClearCache() = runBlocking {
        val dir = projectWithFiles()
        try {
            val engine = RelevanceEngine(ProjectIndexer())
            engine.findRelevantFiles(dir, "test", maxFiles = 5)
            engine.clearCache(dir)
            assertNotNull(engine.findRelevantFiles(dir, "test", maxFiles = 5))
        } finally {
            dir.deleteRecursively()
        }
    }
}
