package com.tom.rv2ide.artificial.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Acceptance tests for incremental index refresh, invalidation, exclusion
 * rules and project-root containment. Proves updateFile/invalidateFile do
 * real work instead of merely existing.
 */
class ProjectIndexRefreshTest {

    private fun projectDir(): File {
        val dir = Files.createTempDirectory("idx_refresh_").toFile()
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "A.kt").writeText("class A")
        File(src, "B.kt").writeText("class B")
        return dir
    }

    @Test
    fun testModifiedFileDetectedWithoutForceRefresh() = runBlocking {
        val dir = projectDir()
        try {
            val indexer = ProjectIndexer()
            val before = indexer.getIndex(dir)
            val sizeBefore = before.files.getValue("src/A.kt").size

            File(dir, "src/A.kt").appendText("\n// much more content appended here to change size")
            val after = indexer.getIndex(dir)
            assertTrue(after.files.getValue("src/A.kt").size > sizeBefore)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testNewFileStaysInvisibleUntilRefresh() = runBlocking {
        val dir = projectDir()
        try {
            val indexer = ProjectIndexer()
            val before = indexer.getIndex(dir)
            assertFalse(before.files.containsKey("src/New.kt"))

            File(dir, "src/New.kt").writeText("class New")
            // Documented stale-index behavior: no background watcher, so the
            // cached index is returned until an explicit refresh.
            val stale = indexer.getIndex(dir)
            assertFalse(stale.files.containsKey("src/New.kt"))

            val refreshed = indexer.getIndex(dir, forceRefresh = true)
            assertTrue(refreshed.files.containsKey("src/New.kt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testDeletedFileDisappearsOnNextAccess() = runBlocking {
        val dir = projectDir()
        try {
            val indexer = ProjectIndexer()
            assertTrue(indexer.getIndex(dir).files.containsKey("src/B.kt"))
            assertTrue(File(dir, "src/B.kt").delete())
            assertFalse(indexer.getIndex(dir).files.containsKey("src/B.kt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testInvalidateFileAndUpdateFileDoRealWork() = runBlocking {
        val dir = projectDir()
        try {
            val indexer = ProjectIndexer()
            indexer.getIndex(dir)
            assertNotNull(indexer.getStats(dir))

            indexer.invalidateFile(dir, "src/A.kt")
            // Cache still served (no disk change), entry gone.
            assertFalse(indexer.getIndex(dir).files.containsKey("src/A.kt"))

            indexer.updateFile(dir, File(dir, "src/A.kt"))
            assertTrue(indexer.getIndex(dir).files.containsKey("src/A.kt"))

            indexer.invalidate(dir)
            assertNull(indexer.getStats(dir))
            assertTrue(indexer.getIndex(dir).files.containsKey("src/A.kt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testSecretLikeAndBinaryExtensionsExcluded() = runBlocking {
        val dir = projectDir()
        try {
            File(dir, "id_rsa").writeText("PRIVATE KEY DATA")
            File(dir, "cert.pem").writeText("CERT DATA")
            File(dir, "notes.txt").writeText("plain notes")
            val indexer = ProjectIndexer()
            val index = indexer.getIndex(dir, forceRefresh = true)
            assertFalse(index.files.containsKey("id_rsa"))
            assertFalse(index.files.containsKey("cert.pem"))
            assertTrue(index.files.containsKey("notes.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFilesOutsideProjectRootNeverIndexed() = runBlocking {
        val dir = projectDir()
        val outside = Files.createTempDirectory("idx_outside_").toFile()
        try {
            File(outside, "Evil.kt").writeText("class Evil")
            val indexer = ProjectIndexer()
            val index = indexer.getIndex(dir, forceRefresh = true)
            assertTrue(index.files.keys.none { it.contains("Evil") })
            assertTrue(index.files.keys.none { it.startsWith("..") })
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
