package com.tom.rv2ide.artificial.tools.builtins

import com.tom.rv2ide.artificial.tools.ToolContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {

  @Test
  fun `dangerous commands refused`() {
    assertNotNull(CommandGate.rejectionReason("rm -rf /"))
    assertNotNull(CommandGate.rejectionReason("sudo rm x"))
    assertNotNull(CommandGate.rejectionReason("su"))
    assertNotNull(CommandGate.rejectionReason("mkfs.ext4 /dev/x"))
    assertNotNull(CommandGate.rejectionReason("dd if=/dev/zero of=/dev/x"))
    assertNotNull(CommandGate.rejectionReason(""))
    assertNotNull(CommandGate.rejectionReason("   "))
  }

  @Test
  fun `safe commands allowed`() {
    assertNull(CommandGate.rejectionReason("echo hello"))
    assertNull(CommandGate.rejectionReason("ls -la"))
    assertNull(CommandGate.rejectionReason("./gradlew tasks"))
    assertNull(CommandGate.rejectionReason("suble --help"))
  }

  @Test
  fun `run echo returns output`() = runBlocking {
    val root = Files.createTempDirectory("agent-cmd").toFile()
    try {
      val ctx = ToolContext(root, false, "t", Job(), 0)
      val result = RunCommandTool().execute(mapOf("command" to "echo hello-agent"), ctx)
      assertTrue(result.ok)
      assertTrue(result.text.contains("hello-agent"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `failing exit becomes error result`() = runBlocking {
    val root = Files.createTempDirectory("agent-cmd").toFile()
    try {
      val ctx = ToolContext(root, false, "t", Job(), 0)
      val result = RunCommandTool().execute(mapOf("command" to "exit 3"), ctx)
      assertFalse(result.ok)
      assertTrue(result.error!!.contains("code 3"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `blocked command refused without execution`() = runBlocking {
    val root = Files.createTempDirectory("agent-cmd").toFile()
    try {
      val ctx = ToolContext(root, false, "t", Job(), 0)
      val marker = File(root, "pwned.txt")
      val result = RunCommandTool().execute(
          mapOf("command" to "rm -rf / --no-preserve-root; touch " + marker.absolutePath),
          ctx
      )
      assertFalse(result.ok)
      assertFalse(marker.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `output capped at max lines`() = runBlocking {
    val root = Files.createTempDirectory("agent-cmd").toFile()
    try {
      val ctx = ToolContext(root, false, "t", Job(), 0)
      val result = RunCommandTool().execute(
          mapOf("command" to "i=1; while [ $i -le 150 ]; do echo line$i; i=$((i+1)); done"),
          ctx
      )
      assertTrue(result.ok)
      assertTrue(result.truncated)
      assertTrue(result.text.contains("capped"))
    } finally {
      root.deleteRecursively()
    }
  }
}
