package com.tom.rv2ide.artificial.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectIndexerTest {

    private fun projectWithFiles(): File {
        val tempDir = Files.createTempDirectory("indexer_test_").toFile()
        val srcDir = File(tempDir, "src/main/kotlin/com/example").apply { mkdirs() }
        File(srcDir, "Main.kt").writeText("fun main() { println(\"Hello\") }")
        File(srcDir, "Utils.kt").writeText("object Utils { fun help() = \"help\" }")
        File(tempDir, "build.gradle.kts").writeText("plugins { id(\"kotlin\") }")
        File(tempDir, "README.md").writeText("# Test Project")
        return tempDir
    }

    @Test
    fun testBuildIndex() = runBlocking {
        val dir = projectWithFiles()
        try {
            val indexer = ProjectIndexer()
            val index = indexer.getIndex(dir)

            assertTrue(index.fileCount > 0)
            assertTrue(index.files.containsKey("src/main/kotlin/com/example/Main.kt"))
            assertTrue(index.files.containsKey("src/main/kotlin/com/example/Utils.kt"))
            assertTrue(index.files.containsKey("build.gradle.kts"))
            assertTrue(index.files.containsKey("README.md"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testIndexCaching() = runBlocking {
        val dir = projectWithFiles()
        try {
            val indexer = ProjectIndexer()
            val index1 = indexer.getIndex(dir)
            val index2 = indexer.getIndex(dir)
            assertSame(index1, index2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testForceRefresh() = runBlocking {
        val dir = projectWithFiles()
        try {
            val indexer = ProjectIndexer()
            val index1 = indexer.getIndex(dir)
            val index2 = indexer.getIndex(dir, forceRefresh = true)
            assertNotSame(index1, index2)
            assertEquals(index1.fileCount, index2.fileCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testInvalidate() = runBlocking {
        val dir = projectWithFiles()
        try {
            val indexer = ProjectIndexer()
            val index1 = indexer.getIndex(dir)
            indexer.invalidate(dir)
            val index2 = indexer.getIndex(dir)
            assertNotSame(index1, index2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testGetStats() = runBlocking {
        val dir = projectWithFiles()
        try {
            val indexer = ProjectIndexer()
            indexer.getIndex(dir)
            val stats = indexer.getStats(dir)
            assertNotNull(stats)
            assertTrue(stats!!.languages.containsKey("kotlin"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testExcludedDirectories() = runBlocking {
        val dir = projectWithFiles()
        try {
            val buildDir = File(dir, "build").apply { mkdirs() }
            File(buildDir, "generated.kt").writeText("// generated")
            val indexer = ProjectIndexer()
            val index = indexer.getIndex(dir, forceRefresh = true)
            assertFalse(index.files.keys.any { it.startsWith("build/") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
