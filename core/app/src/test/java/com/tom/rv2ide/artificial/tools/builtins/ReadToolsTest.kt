package com.tom.rv2ide.artificial.tools.builtins

import com.tom.rv2ide.artificial.tools.ToolContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testCtx(root: File): ToolContext = ToolContext(
    projectRoot = root,
    planMode = false,
    runId = "t",
    job = Job(),
    stepIndex = 0
)

private fun newProject(): File {
  val root = Files.createTempDirectory("agent-proj").toFile()
  File(root, "src").mkdirs()
  File(root, "src/A.kt").writeText("line1\nline2\nline3\n")
  File(root, "build").mkdirs()
  File(root, "build/out.txt").writeText("should be skipped\n")
  File(root, ".gradle").mkdirs()
  File(root, ".gradle/x.txt").writeText("should be skipped\n")
  return root
}

class ListFilesToolTest {

  @Test
  fun `lists project entries and skips build dirs`() = runBlocking {
    val root = newProject()
    try {
      val result = ListFilesTool().execute(mapOf("dir" to "."), testCtx(root))
      assertTrue(result.ok)
      assertTrue(result.text.contains("A.kt"))
      assertFalse(result.text.contains("out.txt"))
      assertFalse(result.text.contains(".gradle"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `outside jail rejected`() = runBlocking {
    val root = newProject()
    try {
      val result = ListFilesTool().execute(mapOf("dir" to ".."), testCtx(root))
      assertFalse(result.ok)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `missing dir fails cleanly`() = runBlocking {
    val root = newProject()
    try {
      val result = ListFilesTool().execute(mapOf("dir" to "nope"), testCtx(root))
      assertFalse(result.ok)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `entry cap truncates with signal`() = runBlocking {
    val root = newProject()
    try {
      repeat(10) { File(root, "f$it.txt").writeText("x\n") }
      val result = ListFilesTool().execute(mapOf("maxEntries" to 3), testCtx(root))
      assertTrue(result.ok)
      assertTrue(result.truncated)
      assertTrue(result.text.contains("truncated"))
    } finally {
      root.deleteRecursively()
    }
  }
}

class ReadFileToolTest {

  @Test
  fun `reads with line numbers and pages`() = runBlocking {
    val root = newProject()
    try {
      val first = ReadFileTool().execute(
          mapOf("path" to "src/A.kt", "maxLines" to 2), testCtx(root)
      )
      assertTrue(first.ok)
      assertTrue(first.text.contains("1: line1"))
      assertTrue(first.text.contains("2: line2"))
      assertTrue(first.truncated)
      assertTrue(first.text.contains("offset=2"))

      val second = ReadFileTool().execute(
          mapOf("path" to "src/A.kt", "offset" to 2, "maxLines" to 2), testCtx(root)
      )
      assertTrue(second.ok)
      assertTrue(second.text.contains("3: line3"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `missing and directory paths fail cleanly`() = runBlocking {
    val root = newProject()
    try {
      assertFalse(
          ReadFileTool().execute(mapOf("path" to "nope.kt"), testCtx(root)).ok
      )
      assertFalse(
          ReadFileTool().execute(mapOf("path" to "src"), testCtx(root)).ok
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `binary file refused`() = runBlocking {
    val root = newProject()
    try {
      File(root, "bin.dat").writeBytes(byteArrayOf(1, 2, 0, 3, 4))
      val result = ReadFileTool().execute(mapOf("path" to "bin.dat"), testCtx(root))
      assertFalse(result.ok)
      assertTrue(result.error!!.contains("binary"))
    } finally {
      root.deleteRecursively()
    }
  }
}

class SearchFilesToolTest {

  @Test
  fun `finds matches as path-line rows`() = runBlocking {
    val root = newProject()
    try {
      File(root, "src/B.kt").writeText("nothing here\nneedle in haystack\n")
      val result = SearchFilesTool().execute(mapOf("query" to "needle"), testCtx(root))
      assertTrue(result.ok)
      assertTrue(result.text.contains("B.kt:2:"))
      assertTrue(result.text.contains("needle"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `skips build output and reports no matches`() = runBlocking {
    val root = newProject()
    try {
      val result = SearchFilesTool().execute(mapOf("query" to "skipped"), testCtx(root))
      assertTrue(result.ok)
      assertTrue(result.text.contains("No matches"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `extension filter and caps work`() = runBlocking {
    val root = newProject()
    try {
      repeat(5) { File(root, "src/M$it.kt").writeText("hit\n".repeat(3)) }
      File(root, "src/N.txt").writeText("hit\n")
      val result = SearchFilesTool().execute(
          mapOf("query" to "hit", "extensions" to "kt", "maxResults" to 4),
          testCtx(root)
      )
      assertTrue(result.ok)
      assertTrue(result.truncated)
      assertFalse(result.text.contains("N.txt"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `blank query rejected`() = runBlocking {
    val root = newProject()
    try {
      assertFalse(
          SearchFilesTool().execute(mapOf("query" to "  "), testCtx(root)).ok
      )
    } finally {
      root.deleteRecursively()
    }
  }
}
