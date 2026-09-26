package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.FileWriteResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for [WriteVerification]. No Android dependencies:
 * the backend writer is injected as a lambda operating on temp files.
 */
class WriteVerificationTest {

    private fun testDir(): File = Files.createTempDirectory("wv-test").toFile()

    /** Fake writer that performs a real file write. */
    private fun realWrite(): WriteVerification.WriteFn = { path, content, _ ->
        try {
            File(path).apply { parentFile?.mkdirs() }.writeText(content)
            FileWriteResult.Success(path, backupCreated = false)
        } catch (e: Exception) {
            FileWriteResult.Error("fake IO failure: ${e.message}")
        }
    }

    /** Fake writer that claims success but writes nothing (simulates lost write). */
    private fun lyingWrite(): WriteVerification.WriteFn = { path, _, _ ->
        FileWriteResult.Success(path, backupCreated = false)
    }

    /** Fake writer that always fails. */
    private fun failingWrite(): WriteVerification.WriteFn = { _, _, _ ->
        FileWriteResult.Error("Simulated write failure")
    }

    /** Fake writer that denies. */
    private fun denyingWrite(): WriteVerification.WriteFn = { _, _, _ ->
        FileWriteResult.PermissionDenied("Simulated denial")
    }

    @Test
    fun `writeAndVerify succeeds when disk matches`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt")
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "hello world",
                previous = null,
                write = realWrite()
            )
            assertTrue(result.ok)
            assertTrue(result.text.contains("Created"))
            assertEquals("hello world", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeAndVerify reports overwrite for existing file`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("old") }
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "new",
                previous = "old",
                write = realWrite()
            )
            assertTrue(result.ok)
            assertTrue(result.text.contains("Overwrote"))
            assertEquals("new", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeAndVerify fails and rolls back on phantom success`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("old content") }
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "new content",
                previous = "old content",
                write = lyingWrite()
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("FAILED verification"))
            assertTrue(result.error!!.contains("Rolled back"))
            assertEquals("old content", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeAndVerify deletes new file on phantom success`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt")
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "new content",
                previous = null,
                write = lyingWrite()
            )
            assertFalse(result.ok)
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeAndVerify propagates backend failure`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("old") }
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "new",
                previous = "old",
                write = failingWrite()
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("Write failed"))
            assertEquals("old", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeAndVerify propagates denial`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt")
            val result = WriteVerification.writeAndVerify(
                file = file,
                content = "new",
                previous = null,
                write = denyingWrite()
            )
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("refused"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `verifyContent matches exactly`() {
        val file = Files.createTempFile("wv-verify", ".txt").toFile()
        try {
            file.writeText("test content")
            assertTrue(WriteVerification.verifyContent(file, "test content"))
            assertFalse(WriteVerification.verifyContent(file, "different"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `verifyContent returns false for missing file`() {
        val missing = File(Files.createTempDirectory("wv-missing").toFile(), "missing.txt")
        assertFalse(WriteVerification.verifyContent(missing, "anything"))
    }

    @Test
    fun `restoreToPrevious restores content`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("current") }
            assertTrue(WriteVerification.restoreToPrevious(file, "restored", realWrite()))
            assertEquals("restored", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `restoreToPrevious deletes file when previous is null`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("current") }
            assertTrue(WriteVerification.restoreToPrevious(file, null, realWrite()))
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `restoreToPrevious reports failure when backend fails`() {
        val dir = testDir()
        try {
            val file = File(dir, "test.txt").apply { writeText("current") }
            assertFalse(WriteVerification.restoreToPrevious(file, "restored", failingWrite()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
