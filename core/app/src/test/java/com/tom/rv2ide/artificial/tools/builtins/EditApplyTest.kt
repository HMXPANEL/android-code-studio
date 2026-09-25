package com.tom.rv2ide.artificial.tools.builtins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditApplyTest {

  private val content = "fun a() {\n  old()\n}\nfun b() {\n  old()\n}\n"

  @Test
  fun `unique match applies`() {
    val result = EditApply.apply(
        currentContent = "x = 1\n",
        expectedHash = EditApply.sha256Hex("x = 1\n"),
        search = "x = 1",
        replace = "x = 2",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Applied)
    assertEquals("x = 2\n", (result as EditApply.EditOutcome.Applied).newContent)
  }

  @Test
  fun `stale hash rejected`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = "deadbeef",
        search = "old()",
        replace = "new()",
        occurrence = 0
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
    assertTrue((result as EditApply.EditOutcome.Rejected).reason.contains("stale", ignoreCase = true))
  }

  @Test
  fun `ambiguous match without occurrence rejected`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = EditApply.sha256Hex(content),
        search = "old()",
        replace = "new()",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
    assertTrue((result as EditApply.EditOutcome.Rejected).reason.contains("2 times"))
  }

  @Test
  fun `occurrence selects match`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = EditApply.sha256Hex(content),
        search = "old()",
        replace = "new()",
        occurrence = 1
    )
    assertTrue(result is EditApply.EditOutcome.Applied)
    val applied = result as EditApply.EditOutcome.Applied
    assertEquals(2, applied.matchCount)
    assertTrue(applied.newContent.contains("fun a() {\n  old()\n}"))
    assertTrue(applied.newContent.contains("fun b() {\n  new()\n}"))
  }

  @Test
  fun `occurrence out of range rejected`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = EditApply.sha256Hex(content),
        search = "old()",
        replace = "new()",
        occurrence = 5
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
  }

  @Test
  fun `missing block rejected`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = EditApply.sha256Hex(content),
        search = "absent()",
        replace = "new()",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
  }

  @Test
  fun `empty search rejected`() {
    val result = EditApply.apply(
        currentContent = content,
        expectedHash = EditApply.sha256Hex(content),
        search = "",
        replace = "new()",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
  }

  @Test
  fun `no-op edit rejected`() {
    val result = EditApply.apply(
        currentContent = "same\n",
        expectedHash = EditApply.sha256Hex("same\n"),
        search = "same",
        replace = "same",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
  }

  @Test
  fun `emptying a file refused`() {
    val result = EditApply.apply(
        currentContent = "keep me\n",
        expectedHash = EditApply.sha256Hex("keep me\n"),
        search = "keep me\n",
        replace = "",
        occurrence = null
    )
    assertTrue(result is EditApply.EditOutcome.Rejected)
  }

  @Test
  fun `sha256 is stable hex`() {
    val a = EditApply.sha256Hex("hello")
    val b = EditApply.sha256Hex("hello")
    assertEquals(a, b)
    assertEquals(64, a.length)
  }
}
