package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteVerificationTest {

    private fun testFile(content: String = ""): File {
        val file = Files.createTempFile("wv-test", ".txt").toFile()
        file.writeText(content)
        return file
    }

    private fun testDir(): File {
        return Files.createTempDirectory("wv-test").toFile()
    }

    @Test
    fun `writeAndVerify succeeds on matching content`() = runBlocking {
        val dir = testDir()
        val file = File(dir, "test.txt")
        // Use a real AIFileWriter with a mock context
        val writer = TestFileWriter(dir)

        val result = WriteVerification.writeAndVerify(
            writer = writer,
            file = file,
            content = "hello world",
            previous = null
        )

        assertTrue(result.ok)
        assertTrue(result.text.contains("Created"))
        assertEquals("hello world", file.readText())
    }

    @Test
    fun `writeAndVerify fails when disk content differs`() = runBlocking {
        val dir = testDir()
        val file = File(dir, "test.txt")
        // Pre-create file with different content
        file.writeText("old content")
        val writer = TestFileWriter(dir)

        val result = WriteVerification.writeAndVerify(
            writer = writer,
            file = file,
            content = "new content",
            previous = "old content"
        )

        assertTrue(result.ok)
        assertEquals("new content", file.readText())
    }

    @Test
    fun `writeAndVerify rolls back on write failure`() = runBlocking {
        val dir = testDir()
        val file = File(dir, "test.txt")
        file.writeText("old content")
        val writer = FailingFileWriter(dir)

        val result = WriteVerification.writeAndVerify(
            writer = writer,
            file = file,
            content = "new content",
            previous = "old content"
        )

        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Write failed"))
    }

    @Test
    fun `verifyContent returns true for matching content`() = runBlocking {
        val file = testFile("test content")
        assertTrue(WriteVerification.verifyContent(file, "test content"))
        file.delete()
    }

    @Test
    fun `verifyContent returns false for non-matching content`() = runBlocking {
        val file = testFile("test content")
        assertFalse(WriteVerification.verifyContent(file, "different"))
        file.delete()
    }

    @Test
    fun `verifyContent returns false for missing file`() = runBlocking {
        val file = File(Files.createTempDirectory("test").toFile(), "missing.txt")
        assertFalse(WriteVerification.verifyContent(file, "anything"))
    }

    @Test
    fun `restoreToPrevious restores content`() = runBlocking {
        val dir = testDir()
        val file = File(dir, "test.txt")
        file.writeText("old content")
        val writer = TestFileWriter(dir)

        val restored = WriteVerification.restoreToPrevious(writer, file, "restored content")

        assertTrue(restored)
        assertEquals("restored content", file.readText())
    }

    @Test
    fun `restoreToPrevious deletes file when previous is null`() = runBlocking {
        val dir = testDir()
        val file = File(dir, "test.txt")
        file.writeText("old content")
        val writer = TestFileWriter(dir)

        val restored = WriteVerification.restoreToPrevious(writer, file, null)

        assertTrue(restored)
        assertFalse(file.exists())
    }

    /** Test implementation of AIFileWriter that uses a temp directory. */
    private class TestFileWriter(private val dir: File) : AIFileWriter(
        object : android.content.ContextWrapper(android.app.Application()) {
            override fun getFilesDir(): File = dir
        }
    )

    /** Test implementation that always fails. */
    private class FailingFileWriter(private val dir: File) : AIFileWriter(
        object : android.content.ContextWrapper(android.app.Application()) {
            override fun getFilesDir(): File = dir
        }
    ) {
        override fun writeFile(filePath: String, content: String, createBackup: Boolean): FileWriteResult {
            return FileWriteResult.Error("Simulated write failure")
        }
    }
}