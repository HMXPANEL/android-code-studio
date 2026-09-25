package com.tom.rv2ide.artificial.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {

  @Test
  fun `relative path inside root resolves`() {
    val root = File("/proj")
    val resolved = PathJail.resolveInside(root, "a/b.kt")
    assertNotNull(resolved)
    assertTrue(resolved!!.canonicalPath.startsWith(File("/proj").canonicalPath))
  }

  @Test
  fun `traversal outside root rejected`() {
    val root = File("/proj")
    assertNull(PathJail.resolveInside(root, "../etc/passwd"))
    assertNull(PathJail.resolveInside(root, "a/../../.."))
  }

  @Test
  fun `absolute path outside root rejected, inside accepted`() {
    val root = File("/proj")
    assertNull(PathJail.resolveInside(root, "/etc/hosts"))
    assertNotNull(PathJail.resolveInside(root, "/proj/sub/f.kt"))
  }

  @Test
  fun `root itself resolves`() {
    val root = File("/proj")
    assertNotNull(PathJail.resolveInside(root, "."))
  }

  @Test
  fun `line truncation caps and signals`() {
    val text = (1..10).joinToString("\n") { "l$it" }
    val capped = Truncate.lines(text, 3)
    assertTrue(capped.truncated)
    assertEquals(7, capped.dropped)
    assertTrue(capped.text.contains("truncated to 3 lines"))
    val untouched = Truncate.lines("a\nb", 5)
    assertFalse(untouched.truncated)
  }

  @Test
  fun `char truncation caps and signals`() {
    val capped = Truncate.chars("abcdef", 3)
    assertTrue(capped.truncated)
    assertTrue(capped.text.startsWith("abc"))
    assertTrue(capped.text.contains("truncated"))
  }
}
