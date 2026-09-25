package com.tom.rv2ide.artificial.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

  @Test
  fun `valid single call on one line parses`() {
    val parsed = ToolCallParser.parse(
        "Let me look.\nTOOL_CALL: local:read_file {\"path\": \"a/b.kt\"}\nDone.",
        "r1"
    )
    assertTrue(parsed.errors.isEmpty())
    assertEquals(1, parsed.calls.size)
    assertEquals("local:read_file", parsed.calls[0].name)
    assertEquals("a/b.kt", parsed.calls[0].args["path"])
    assertEquals("call-r1-0", parsed.calls[0].callId)
    assertFalse(parsed.hasLegacyModifications)
  }

  @Test
  fun `multiline json with escapes parses`() {
    val reply = "TOOL_CALL: local:write_file {\n" +
        "\"path\": \"a.txt\",\n" +
        "\"content\": \"line1\\nline2 \\\"q\\\"\"\n" +
        "}"
    val parsed = ToolCallParser.parse(reply, "r2")
    assertTrue(parsed.errors.isEmpty())
    assertEquals(1, parsed.calls.size)
    assertEquals("line1\nline2 \"q\"", parsed.calls[0].args["content"])
  }

  @Test
  fun `numbers booleans null and nesting parse`() {
    val parsed = ToolCallParser.parse(
        "TOOL_CALL: local:run_command {\"command\": \"ls\", \"timeout\": 60, \"bg\": false, \"env\": null, \"tags\": [\"a\", 1]}",
        "r3"
    )
    assertTrue(parsed.errors.isEmpty())
    val args = parsed.calls[0].args
    assertEquals(60L, args["timeout"])
    assertEquals(false, args["bg"])
    assertEquals(null, args["env"])
    assertEquals(listOf("a", 1L), args["tags"])
  }

  @Test
  fun `multiple sequential calls all parse`() {
    val parsed = ToolCallParser.parse(
        "TOOL_CALL: local:list_files {\"dir\": \".\"}\nTOOL_CALL: local:read_file {\"path\": \"x\"}",
        "r4"
    )
    assertEquals(2, parsed.calls.size)
    assertEquals("call-r4-0", parsed.calls[0].callId)
    assertEquals("call-r4-1", parsed.calls[1].callId)
  }

  @Test
  fun `missing tool id is an error`() {
    val parsed = ToolCallParser.parse("TOOL_CALL:\nSome text", "r5")
    assertTrue(parsed.calls.isEmpty())
    assertEquals(1, parsed.errors.size)
  }

  @Test
  fun `missing argument object is an error`() {
    val parsed = ToolCallParser.parse("TOOL_CALL: local:read_file\nno braces here", "r6")
    assertTrue(parsed.calls.isEmpty())
    assertEquals(1, parsed.errors.size)
  }

  @Test
  fun `unbalanced braces are an error and do not swallow next block`() {
    val parsed = ToolCallParser.parse(
        "TOOL_CALL: local:read_file {\"path\": \"x\"\nTOOL_CALL: local:list_files {\"dir\": \".\"}",
        "r7"
    )
    assertEquals(1, parsed.errors.size)
    assertEquals(1, parsed.calls.size)
    assertEquals("local:list_files", parsed.calls[0].name)
  }

  @Test
  fun `invalid json is an error`() {
    val parsed = ToolCallParser.parse(
        "TOOL_CALL: local:read_file {path: oops}",
        "r8"
    )
    assertTrue(parsed.calls.isEmpty())
    assertEquals(1, parsed.errors.size)
  }

  @Test
  fun `malformed id is an error`() {
    val parsed = ToolCallParser.parse("TOOL_CALL: not-an-id {\"a\": 1}", "r9")
    assertTrue(parsed.calls.isEmpty())
    assertEquals(1, parsed.errors.size)
  }

  @Test
  fun `plain text yields no calls and no errors`() {
    val parsed = ToolCallParser.parse("Just a normal answer.\nNo tools here.", "r10")
    assertTrue(parsed.calls.isEmpty())
    assertTrue(parsed.errors.isEmpty())
    assertFalse(parsed.hasLegacyModifications)
  }

  @Test
  fun `legacy blocks extract with path and content`() {
    val reply = "Intro\nFILE_TO_MODIFY: src/A.kt\nline1\nline2\nFILE_TO_MODIFY: src/B.kt\nonly\nTail"
    val parsed = ToolCallParser.parse(reply, "r11")
    assertTrue(parsed.hasLegacyModifications)
    assertEquals(2, parsed.legacyFiles.size)
    assertEquals("src/A.kt", parsed.legacyFiles[0].path)
    assertTrue(parsed.legacyFiles[0].content.contains("line1"))
    assertEquals("src/B.kt", parsed.legacyFiles[1].path)
  }

  @Test
  fun `legacy block with empty content is skipped`() {
    val parsed = ToolCallParser.parse("FILE_TO_MODIFY: src/Empty.kt", "r12")
    assertFalse(parsed.hasLegacyModifications)
    assertTrue(parsed.legacyFiles.isEmpty())
  }

  @Test
  fun `tool call and legacy block coexist`() {
    val parsed = ToolCallParser.parse(
        "TOOL_CALL: local:list_files {\"dir\": \".\"}\nFILE_TO_MODIFY: a.kt\ncontent\n",
        "r13"
    )
    assertEquals(1, parsed.calls.size)
    assertTrue(parsed.hasLegacyModifications)
  }

  @Test
  fun `brace balance ignores braces inside strings`() {
    assertEquals(0, ToolCallParser.braceBalance("{\"a\": \"}{\"}"))
    assertEquals(1, ToolCallParser.braceBalance("{\"a\": 1"))
  }
}
