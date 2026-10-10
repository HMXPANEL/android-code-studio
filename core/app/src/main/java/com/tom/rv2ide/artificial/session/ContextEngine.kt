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

package com.tom.rv2ide.artificial.session

import com.tom.rv2ide.artificial.agent.AgentRun
import com.tom.rv2ide.artificial.agent.ToolDefinition
import com.tom.rv2ide.artificial.tools.Tool
import java.io.File

/**
 * Context assembly configuration.
 */
data class ContextConfig(
    /** Maximum tokens for the entire context window. */
    val maxContextTokens: Int = 32000,
    /** Reserved tokens for model output. */
    val reservedOutputTokens: Int = 4096,
    /** Reserved tokens for tool definitions. */
    val reservedToolTokens: Int = 2048,
    /** Maximum tokens for conversation history. */
    val maxHistoryTokens: Int = 16384,
    /** Maximum tokens for relevant file excerpts. */
    val maxFileTokens: Int = 8192,
    /** Maximum number of recent messages to include. */
    val maxRecentMessages: Int = 20,
    /** Maximum number of file excerpts to include. */
    val maxFileExcerpts: Int = 10,
    /** Maximum characters per file excerpt. */
    val maxExcerptChars: Int = 2000,
    /** Enable context compaction when budget exceeded. */
    val enableCompaction: Boolean = true,
    /** Token budget for summaries. */
    val summaryTokenBudget: Int = 2048
) {
    /** Available tokens for dynamic content (history + files). */
    val dynamicTokenBudget: Int
        get() = maxContextTokens - reservedOutputTokens - reservedToolTokens
}

/**
 * A compiled context ready for provider consumption.
 */
data class CompiledContext(
    val systemInstructions: String,
    val projectInstructions: String,
    val userRequest: String,
    val conversationHistory: List<ContextMessage>,
    val fileExcerpts: List<FileExcerpt>,
    val toolDefinitions: List<ToolDefinition>,
    val estimatedTokens: Int,
    val wasCompacted: Boolean = false,
    val compactionSummary: String? = null
)

/** Tool call as rendered in model-facing context (decoupled from ToolCall). */
data class ContextToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

/** Tool result as rendered in model-facing context (decoupled from ToolResult). */
data class ContextToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
    val error: String? = null
)

/**
 * A message in the compiled context.
 */
data class ContextMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ContextToolCall> = emptyList(),
    val toolResults: List<ContextToolResult> = emptyList(),
    val timestamp: Long
)

/**
 * A relevant file excerpt for context.
 */
data class FileExcerpt(
    val path: String,
    val content: String,
    val language: String?,
    val relevanceScore: Double,
    val tokenEstimate: Int
)

/**
 * ContextEngine assembles model context from session history, project files, and tool definitions.
 */
class ContextEngine(
    private val sessionStore: SessionStore,
    private val projectIndexer: ProjectIndexer,
    private val relevanceEngine: RelevanceEngine,
    private val config: ContextConfig = ContextConfig()
) {

    private val tokenEstimator = TokenEstimator()

    /**
     * Compile context for the next model turn.
     */
    suspend fun compileContext(
        run: AgentRun,
        projectRoot: File,
        userRequest: String,
        availableTools: List<Tool>,
        systemInstructions: String? = null,
        projectInstructions: String? = null
    ): CompiledContext {
        val sessionId = run.sessionId
        val session = if (sessionId != null) sessionStore.getWithHistory(sessionId) else null
            ?: return emptyContext(projectRoot, userRequest, availableTools, systemInstructions, projectInstructions)

        // 1. Build tool definitions
        val toolDefs = buildToolDefinitions(availableTools)

        // 2. Get relevant file excerpts
        val fileExcerpts = if (userRequest.isNotBlank()) {
            try {
                relevanceEngine.findRelevantFiles(
                    projectRoot = projectRoot,
                    query = userRequest,
                    sessionHistory = session.messages.map { it.content }.joinToString("\n"),
                    maxFiles = config.maxFileExcerpts
                )
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        // 3. Build conversation history with priority
        val history = buildConversationHistory(session)

        // 4. Estimate tokens and compact if needed
        var compiled = CompiledContext(
            systemInstructions = buildSystemInstructions(systemInstructions),
            projectInstructions = buildProjectInstructions(projectRoot, projectInstructions),
            userRequest = userRequest,
            conversationHistory = history,
            fileExcerpts = fileExcerpts,
            toolDefinitions = toolDefs,
            estimatedTokens = 0
        )

        compiled = compiled.copy(estimatedTokens = estimateTokens(compiled))

        // 5. Compact if over budget
        if (compiled.estimatedTokens > config.dynamicTokenBudget && config.enableCompaction) {
            compiled = compactContext(compiled)
        }

        return compiled
    }

    /**
     * Build tool definitions from available tools.
     */
    private fun buildToolDefinitions(tools: List<Tool>): List<ToolDefinition> {
        return tools.map { tool ->
            ToolDefinition(
                name = tool.id,
                description = tool.description,
                parameters = tool.schema
            )
        }
    }

    /**
     * Build conversation history with priority-based selection.
     */
    private fun buildConversationHistory(session: AgentSession): List<ContextMessage> {
        val messages = mutableListOf<ContextMessage>()

        // Priority: recent conversation turns (mandatory system/safety text lives
        // in systemInstructions/projectInstructions, never trimmed).
        val recentMessages = session.messages.takeLast(config.maxRecentMessages)

        for (msg in recentMessages) {
            messages.add(
                ContextMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    toolCalls = msg.toolCalls.map { tc ->
                        ContextToolCall(callId = tc.callId, name = tc.name, arguments = tc.arguments)
                    },
                    toolResults = msg.toolResults.map { tr ->
                        ContextToolResult(
                            callId = tr.callId,
                            success = tr.success,
                            output = tr.output,
                            error = tr.error
                        )
                    },
                    timestamp = msg.timestamp
                )
            )
        }

        validateToolCallConsistency(messages, session)
        return messages
    }

    /**
     * Ensure tool call/result pairs are consistent: never emit an orphaned
     * result without its call (some providers reject such sequences).
     */
    private fun validateToolCallConsistency(
        messages: MutableList<ContextMessage>,
        session: AgentSession
    ) {
        val callIdsInMessages = messages.flatMap { it.toolCalls.map { it.callId } }.toSet()

        val allCalls = session.messages.flatMap { it.toolCalls }.associateBy { it.callId }
        val allResults = session.messages.flatMap { it.toolResults }.associateBy { it.callId }

        for (result in allResults.values) {
            if (result.callId !in callIdsInMessages && allCalls.containsKey(result.callId)) {
                val call = allCalls.getValue(result.callId)
                messages.add(
                    ContextMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ContextToolCall(callId = call.callId, name = call.name, arguments = call.arguments)
                        ),
                        toolResults = emptyList(),
                        timestamp = call.timestamp
                    )
                )
            }
        }
    }

    private fun buildSystemInstructions(systemInstructions: String?): String {
        return systemInstructions ?: """
            You are an expert software engineer working in AndroidCodeStudio.
            You have access to tools for reading, writing, and modifying code.
            Follow the user's instructions precisely and use tools when needed.
            """.trimIndent()
    }

    private fun buildProjectInstructions(projectRoot: File, projectInstructions: String?): String {
        val workingDir = "\n\nWorking directory: ${projectRoot.canonicalPath}"
        return if (!projectInstructions.isNullOrBlank()) {
            "$projectInstructions$workingDir"
        } else {
            workingDir
        }
    }

    private fun estimateTokens(context: CompiledContext): Int {
        var tokens = 0
        tokens += tokenEstimator.estimate(context.systemInstructions)
        tokens += tokenEstimator.estimate(context.projectInstructions)
        tokens += tokenEstimator.estimate(context.userRequest)
        for (msg in context.conversationHistory) {
            tokens += tokenEstimator.estimate(msg.content)
            for (tc in msg.toolCalls) {
                tokens += tokenEstimator.estimate(tc.name) + tc.arguments.values.sumOf { tokenEstimator.estimate(it) }
            }
            for (tr in msg.toolResults) {
                tokens += tokenEstimator.estimate(tr.output) + (tr.error?.let { tokenEstimator.estimate(it) } ?: 0)
            }
        }
        for (excerpt in context.fileExcerpts) {
            tokens += excerpt.tokenEstimate
        }
        for (tool in context.toolDefinitions) {
            tokens += tokenEstimator.estimate(tool.name) + tokenEstimator.estimate(tool.description)
            tokens += tool.parameters.fields.sumOf { tokenEstimator.estimate(it.name) + tokenEstimator.estimate(it.description) }
        }
        return tokens
    }

    /**
     * Compact context when over token budget. Never deletes the canonical
     * persisted transcript; only the model-facing representation is trimmed.
     */
    private fun compactContext(context: CompiledContext): CompiledContext {
        val history = context.conversationHistory.toMutableList()
        val fileExcerpts = context.fileExcerpts.toMutableList()

        val mandatoryTokens = tokenEstimator.estimate(context.systemInstructions) +
            tokenEstimator.estimate(context.projectInstructions) +
            tokenEstimator.estimate(context.userRequest) +
            context.toolDefinitions.sumOf { tokenEstimator.estimate(it.name) + tokenEstimator.estimate(it.description) }

        var availableTokens = config.dynamicTokenBudget - mandatoryTokens
        if (availableTokens <= 0) {
            return context.copy(
                conversationHistory = history.takeLast(3),
                fileExcerpts = emptyList(),
                wasCompacted = true,
                compactionSummary = "Severe budget pressure: kept only last 3 messages"
            )
        }

        val fileTokens = fileExcerpts.sumOf { it.tokenEstimate }
        if (fileTokens > availableTokens / 2) {
            fileExcerpts.sortByDescending { it.relevanceScore }
            var runningTotal = 0
            val keptFiles = mutableListOf<FileExcerpt>()
            for (excerpt in fileExcerpts) {
                if (runningTotal + excerpt.tokenEstimate <= availableTokens / 2) {
                    keptFiles.add(excerpt)
                    runningTotal += excerpt.tokenEstimate
                } else {
                    break
                }
            }
            fileExcerpts.clear()
            fileExcerpts.addAll(keptFiles)
            availableTokens -= runningTotal
        }

        var historyTokens = historyTokens(history)
        if (historyTokens > availableTokens) {
            val summary = summarizeHistory(history, config.summaryTokenBudget)
            val summaryTokens = tokenEstimator.estimate(summary)

            if (summaryTokens <= availableTokens) {
                val keepRecent = minOf(config.maxRecentMessages, history.size)
                val recentMessages = history.takeLast(keepRecent)
                val summaryMessage = ContextMessage(
                    role = "system",
                    content = "[Conversation Summary]\n$summary",
                    timestamp = System.currentTimeMillis()
                )
                return context.copy(
                    conversationHistory = listOf(summaryMessage) + recentMessages,
                    fileExcerpts = fileExcerpts,
                    wasCompacted = true,
                    compactionSummary = "Summarized ${history.size - keepRecent} older messages"
                )
            }
        }

        while (historyTokens > availableTokens && history.size > 3) {
            history.removeFirst()
            historyTokens = historyTokens(history)
        }

        return context.copy(
            conversationHistory = history,
            fileExcerpts = fileExcerpts,
            wasCompacted = true,
            compactionSummary = "Trimmed older messages to fit budget"
        )
    }

    private fun historyTokens(history: List<ContextMessage>): Int {
        return history.sumOf { msg ->
            tokenEstimator.estimate(msg.content) +
                msg.toolCalls.sumOf { tokenEstimator.estimate(it.name) + it.arguments.values.sumOf { tokenEstimator.estimate(it) } } +
                msg.toolResults.sumOf { tokenEstimator.estimate(it.output) + (it.error?.let { tokenEstimator.estimate(it) } ?: 0) }
        }
    }

    /**
     * Summarize conversation history using a simple heuristic.
     * In production, this could call a smaller/cheaper model.
     */
    private fun summarizeHistory(messages: List<ContextMessage>, budget: Int): String {
        val builder = StringBuilder()
        builder.append("Previous conversation summary:\n")

        var tokens = 0
        for (msg in messages) {
            val msgTokens = tokenEstimator.estimate(msg.content)
            if (tokens + msgTokens > budget) break

            when (msg.role) {
                "user" -> builder.append("- User: ${msg.content.take(200)}...\n")
                "assistant" -> {
                    if (msg.toolCalls.isNotEmpty()) {
                        builder.append("- Assistant used tools: ${msg.toolCalls.map { it.name }.joinToString(", ")}\n")
                    } else {
                        builder.append("- Assistant: ${msg.content.take(200)}...\n")
                    }
                }
                "tool" -> builder.append("- Tool result: ${msg.content.take(100)}...\n")
            }
            tokens += msgTokens
        }

        return builder.toString()
    }

    private fun emptyContext(
        projectRoot: File,
        userRequest: String,
        tools: List<Tool>,
        systemInstructions: String?,
        projectInstructions: String?
    ): CompiledContext {
        val toolDefs = buildToolDefinitions(tools)
        val sys = buildSystemInstructions(systemInstructions)
        val proj = buildProjectInstructions(projectRoot, projectInstructions)
        return CompiledContext(
            systemInstructions = sys,
            projectInstructions = proj,
            userRequest = userRequest,
            conversationHistory = emptyList(),
            fileExcerpts = emptyList(),
            toolDefinitions = toolDefs,
            estimatedTokens = tokenEstimator.estimate(sys) +
                tokenEstimator.estimate(proj) +
                tokenEstimator.estimate(userRequest) +
                toolDefs.sumOf { tokenEstimator.estimate(it.name) + tokenEstimator.estimate(it.description) }
        )
    }
}

/**
 * Simple token estimator using character-based approximation.
 * ~4 characters per token for English text, more conservative for code.
 */
class TokenEstimator {
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val isCode = text.contains("\n") || text.contains("{") || text.contains("}") || text.contains(";")
        val charsPerToken = if (isCode) 3 else 4
        return maxOf(1, text.length / charsPerToken)
    }
}
