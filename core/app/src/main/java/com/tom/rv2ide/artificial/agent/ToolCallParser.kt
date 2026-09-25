/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.tools.ToolCall

/** Minimal JSON value parser (objects/strings/numbers/booleans/null/arrays). */
internal object MiniJson {

  class JsonError(message: String) : Exception(message)

  fun parseObject(text: String): Map<String, Any?> {
    val parser = Parser(text.trim())
    parser.skipWs()
    val value = parser.parseValue()
    parser.skipWs()
    if (!parser.atEnd()) {
      throw JsonError("Trailing characters after JSON value")
    }
    if (value !is Map<*, *>) {
      throw JsonError("Top-level JSON value must be an object")
    }
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
  }

  private class Parser(val text: String) {
    var pos: Int = 0

    fun atEnd(): Boolean = pos >= text.length

    fun skipWs() {
      while (!atEnd() && text[pos].isWhitespace()) pos++
    }

    fun parseValue(): Any? {
      skipWs()
      if (atEnd()) throw JsonError("Unexpected end of input")
      return when (text[pos]) {
        '{' -> parseObjectInner()
        '[' -> parseArray()
        '"' -> parseString()
        't' -> expectLiteral("true", true)
        'f' -> expectLiteral("false", false)
        'n' -> expectLiteral("null", null)
        '-', in '0'..'9' -> parseNumber()
        else -> throw JsonError("Unexpected character '${text[pos]}' at $pos")
      }
    }

    private fun parseObjectInner(): Map<String, Any?> {
      pos++ // {
      val map = LinkedHashMap<String, Any?>()
      skipWs()
      if (!atEnd() && text[pos] == '}') {
        pos++
        return map
      }
      while (true) {
        skipWs()
        if (atEnd() || text[pos] != '"') {
          throw JsonError("Expected string key at $pos")
        }
        val key = parseString()
        skipWs()
        if (atEnd() || text[pos] != ':') {
          throw JsonError("Expected ':' after key at $pos")
        }
        pos++
        map[key] = parseValue()
        skipWs()
        if (atEnd()) throw JsonError("Unterminated object")
        if (text[pos] == '}') {
          pos++
          return map
        }
        if (text[pos] != ',') {
          throw JsonError("Expected ',' or '}' at $pos")
        }
        pos++
      }
    }

    private fun parseArray(): List<Any?> {
      pos++ // [
      val list = mutableListOf<Any?>()
      skipWs()
      if (!atEnd() && text[pos] == ']') {
        pos++
        return list
      }
      while (true) {
        list.add(parseValue())
        skipWs()
        if (atEnd()) throw JsonError("Unterminated array")
        if (text[pos] == ']') {
          pos++
          return list
        }
        if (text[pos] != ',') {
          throw JsonError("Expected ',' or ']' at $pos")
        }
        pos++
      }
    }

    private fun parseString(): String {
      pos++ // opening quote
      val out = StringBuilder()
      while (true) {
        if (atEnd()) throw JsonError("Unterminated string")
        val c = text[pos++]
        when (c) {
          '"' -> return out.toString()
          '\\' -> {
            if (atEnd()) throw JsonError("Unterminated escape")
            when (val e = text[pos++]) {
              '"', '\\', '/' -> out.append(e)
              'b' -> out.append('\b')
              'f' -> out.append('\u000C')
              'n' -> out.append('\n')
              'r' -> out.append('\r')
              't' -> out.append('\t')
              'u' -> {
                if (pos + 4 > text.length) throw JsonError("Bad unicode escape")
                val hex = text.substring(pos, pos + 4)
                val code = hex.toIntOrNull(16)
                    ?: throw JsonError("Bad unicode escape '\\u$hex'")
                out.append(code.toChar())
                pos += 4
              }
              else -> throw JsonError("Bad escape '\\$e'")
            }
          }
          else -> out.append(c)
        }
      }
    }

    private fun expectLiteral(word: String, value: Any?): Any? {
      if (!text.startsWith(word, pos)) {
        throw JsonError("Invalid literal at $pos")
      }
      pos += word.length
      return value
    }

    private fun parseNumber(): Number {
      val start = pos
      if (!atEnd() && text[pos] == '-') pos++
      while (!atEnd() && (text[pos].isDigit() || text[pos] == '.' ||
              text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' ||
              text[pos] == '-')
      ) {
        pos++
      }
      val raw = text.substring(start, pos)
      return raw.toLongOrNull() ?: raw.toDoubleOrNull()
          ?: throw JsonError("Invalid number '$raw'")
    }
  }
}

/** One raw legacy file block (`FILE_TO_MODIFY:`), mirroring the old path. */
data class LegacyFileBlock(val path: String, val content: String)

/** Malformed-call diagnostic (always recoverable: becomes a model-facing error). */
data class ParseError(val message: String)

/** Full parse outcome for one model reply. */
data class ParsedReply(
    val calls: List<ToolCall>,
    val errors: List<ParseError>,
    val hasLegacyModifications: Boolean,
    val legacyFiles: List<LegacyFileBlock>
)

/**
 * Strict text-protocol parser. Pure Kotlin, JVM-testable.
 *
 * Grammar: a line whose trimmed form starts with `TOOL_CALL:`, followed by a
 * `<namespace>:<tool>` id token, followed by a `{...}` JSON object (may span
 * lines until braces balance, strings respected). Anything else is final text.
 */
object ToolCallParser {

  private val idPattern = Regex("^[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+$")

  fun parse(reply: String, runId: String): ParsedReply {
    val calls = mutableListOf<ToolCall>()
    val errors = mutableListOf<ParseError>()
    val lines = reply.lines()
    var index = 0
    var callIndex = 0
    while (index < lines.size) {
      val trimmed = lines[index].trimStart()
      if (!trimmed.startsWith("TOOL_CALL:")) {
        index++
        continue
      }
      val afterMarker = trimmed.removePrefix("TOOL_CALL:").trim()
      if (afterMarker.isEmpty()) {
        errors.add(ParseError("Line ${index + 1}: TOOL_CALL: block has no tool id"))
        index++
        continue
      }
      val idEnd = afterMarker.indexOfFirst { it.isWhitespace() || it == '{' }
      val toolId = if (idEnd < 0) afterMarker else afterMarker.substring(0, idEnd)
      if (!idPattern.matches(toolId)) {
        errors.add(
            ParseError("Line ${index + 1}: malformed tool id '$toolId' (expected <namespace>:<name>)")
        )
        index++
        continue
      }
      var argsText = if (idEnd < 0) "" else afterMarker.substring(idEnd).trim()
      var consumedThrough = index
      if (!argsText.contains('{')) {
        // Arguments object may start on a following line; scan ahead.
        var found = false
        var look = index + 1
        while (look < lines.size) {
          val candidate = lines[look].trim()
          if (candidate.isEmpty()) {
            look++
            continue
          }
          if (candidate.startsWith('{')) {
            argsText = lines.subList(index + 1, look + 1).joinToString("\n")
            consumedThrough = look
            found = true
            break
          }
          break
        }
        if (!found) {
          errors.add(
              ParseError("Line ${index + 1}: tool '$toolId' has no argument object (expected JSON '{...}')")
          )
          index++
          continue
        }
      }
      // Extend across lines until braces balance (strings respected). Stop
      // early at the next block marker so one malformed call cannot swallow
      // the rest of the reply.
      val collected = StringBuilder(argsText)
      var balance = braceBalance(argsText)
      var nextLine = consumedThrough + 1
      while (balance > 0 && nextLine < lines.size) {
        val nextTrimmed = lines[nextLine].trimStart()
        if (nextTrimmed.startsWith("TOOL_CALL:") || nextTrimmed.startsWith("FILE_TO_MODIFY:")) {
          break
        }
        collected.append('\n').append(lines[nextLine])
        balance = braceBalance(collected.toString())
        consumedThrough = nextLine
        nextLine++
      }
      if (balance != 0) {
        errors.add(
            ParseError("Line ${index + 1}: unbalanced braces in arguments for '$toolId'")
        )
        index = consumedThrough + 1
        continue
      }
      val jsonStart = collected.indexOf('{')
      val jsonText = collected.substring(jsonStart)
      val args: Map<String, Any?> = try {
        MiniJson.parseObject(jsonText)
      } catch (e: MiniJson.JsonError) {
        errors.add(
            ParseError("Line ${index + 1}: invalid JSON arguments for '$toolId': ${e.message}")
        )
        index = consumedThrough + 1
        continue
      }
      calls.add(
          ToolCall(
              name = toolId,
              args = args,
              callId = "call-$runId-$callIndex",
              runId = runId
          )
      )
      callIndex++
      index = consumedThrough + 1
    }
    val legacyFiles = extractLegacyFiles(reply)
    return ParsedReply(
        calls = calls,
        errors = errors,
        hasLegacyModifications = legacyFiles.isNotEmpty(),
        legacyFiles = legacyFiles
    )
  }

  /**
   * Extracts legacy `FILE_TO_MODIFY:<path>` blocks with the same line rules as
   * the old modification path (marker line starts with the token; content runs
   * until the next marker or end; empty content blocks are skipped).
   */
  fun extractLegacyFiles(reply: String): List<LegacyFileBlock> {
    val out = mutableListOf<LegacyFileBlock>()
    var currentPath: String? = null
    val content = StringBuilder()
    fun flush() {
      val path = currentPath
      if (path != null && path.isNotBlank() && content.isNotEmpty()) {
        out.add(LegacyFileBlock(path.trim(), content.toString()))
      }
      content.clear()
    }
    reply.lines().forEach { line ->
      if (line.startsWith("FILE_TO_MODIFY:")) {
        flush()
        currentPath = line.substringAfter("FILE_TO_MODIFY:").trim()
      } else if (currentPath != null) {
        content.append(line).append("\n")
      }
    }
    flush()
    return out
  }

  /** Net brace balance ignoring braces inside double-quoted strings/escapes. */
  internal fun braceBalance(text: String): Int {
    var balance = 0
    var inString = false
    var escaped = false
    text.forEach { c ->
      if (inString) {
        if (escaped) {
          escaped = false
        } else if (c == '\\') {
          escaped = true
        } else if (c == '"') {
          inString = false
        }
      } else {
        when (c) {
          '"' -> inString = true
          '{' -> balance++
          '}' -> balance--
        }
      }
    }
    return balance
  }
}
