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
import com.tom.rv2ide.artificial.tools.RunMode
import com.tom.rv2ide.artificial.tools.Tool
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * SessionManager coordinates session persistence, context assembly, and agent integration.
 */
class SessionManager(
    private val sessionStore: SessionStore,
    private val contextEngine: ContextEngine,
    private val projectIndexer: ProjectIndexer,
    private val relevanceEngine: RelevanceEngine,
    private val scope: CoroutineScope
) {

    suspend fun createSession(
        projectRoot: File,
        mode: RunMode,
        providerId: String,
        modelId: String,
        title: String? = null
    ): AgentSession {
        val session = AgentSession(
            projectRoot = projectRoot.canonicalPath,
            mode = mode,
            providerId = providerId,
            modelId = modelId,
            title = title ?: "Session ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
        )
        return sessionStore.create(session)
    }

    suspend fun getSession(id: String): AgentSession? = sessionStore.getWithHistory(id)

    suspend fun listSessions(projectRoot: File): List<AgentSession> {
        return sessionStore.list(projectRoot.canonicalPath)
    }

    fun observeSessions(projectRoot: File): Flow<List<AgentSession>> {
        return sessionStore.observe(projectRoot.canonicalPath)
    }

    suspend fun renameSession(id: String, newTitle: String): Boolean {
        val session = sessionStore.get(id) ?: return false
        sessionStore.update(session.copyWith(title = newTitle))
        return true
    }

    suspend fun deleteSession(id: String): Boolean {
        return sessionStore.delete(id)
    }

    suspend fun addUserMessage(sessionId: String, content: String): Boolean {
        val session = sessionStore.getWithHistory(sessionId) ?: return false
        val message = PersistedMessage(
            role = MessageRole.USER,
            content = content
        )
        sessionStore.update(session.copyWith(messages = session.messages + message))
        return true
    }

    suspend fun addAssistantMessage(
        sessionId: String,
        content: String,
        toolCalls: List<ToolCall> = emptyList()
    ): Boolean {
        val session = sessionStore.getWithHistory(sessionId) ?: return false
        val persistedCalls = toolCalls.map { tc ->
            PersistedToolCall(
                callId = tc.callId,
                name = tc.name,
                arguments = tc.args.mapValues { (_, v) -> v?.toString() ?: "null" }
            )
        }
        val message = PersistedMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            toolCalls = persistedCalls
        )
        sessionStore.update(session.copyWith(messages = session.messages + message))
        return true
    }

    /**
     * Record one tool result. Attaches to the last assistant message when the
     * callId matches (preserves call/result pairing); otherwise appends a
     * standalone TOOL message so no result is ever silently dropped.
     */
    suspend fun addToolResult(
        sessionId: String,
        callId: String,
        success: Boolean,
        output: String,
        error: String?
    ): Boolean {
        val session = sessionStore.getWithHistory(sessionId) ?: return false
        val persisted = PersistedToolResult(
            callId = callId,
            success = success,
            output = output,
            error = error
        )

        val messages = session.messages.toMutableList()
        val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }

        if (lastAssistantIndex >= 0) {
            val lastMsg = messages[lastAssistantIndex]
            val updatedCalls = lastMsg.toolCalls.map { tc ->
                if (tc.callId == callId) {
                    tc.copy(
                        status = if (success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
                    )
                } else tc
            }
            messages[lastAssistantIndex] = lastMsg.copy(
                toolCalls = updatedCalls,
                toolResults = lastMsg.toolResults + persisted
            )
            sessionStore.update(session.copyWith(messages = messages))
            return true
        }

        val message = PersistedMessage(
            role = MessageRole.TOOL,
            content = "$callId: ${if (success) "OK" else "ERROR"}",
            toolResults = listOf(persisted)
        )
        sessionStore.update(session.copyWith(messages = session.messages + message))
        return true
    }

    /** Convenience for a single executed call. */
    suspend fun addToolResult(sessionId: String, call: ToolCall, result: ToolResult): Boolean {
        return addToolResult(sessionId, call.callId, result.ok, result.text, result.error)
    }

    suspend fun addCheckpoint(sessionId: String, commitHash: String, description: String = ""): Boolean {
        val session = sessionStore.getWithHistory(sessionId) ?: return false
        val checkpoint = Checkpoint(
            commitHash = commitHash,
            description = description
        )
        sessionStore.update(session.copyWith(checkpoints = session.checkpoints + checkpoint))
        return true
    }

    suspend fun compileContext(
        run: AgentRun,
        projectRoot: File,
        userRequest: String,
        availableTools: List<Tool>
    ): CompiledContext {
        return contextEngine.compileContext(run, projectRoot, userRequest, availableTools)
    }

    fun getIndexStats(projectRoot: File): IndexStats? {
        return projectIndexer.getStats(projectRoot)
    }

    fun invalidateIndex(projectRoot: File) {
        projectIndexer.invalidate(projectRoot)
        relevanceEngine.clearCache(projectRoot)
        scope.launch { projectIndexer.getIndex(projectRoot, forceRefresh = true) }
    }

    companion object {
        private val managers = mutableMapOf<String, SessionManager>()

        fun getOrCreate(
            projectRoot: File,
            scope: CoroutineScope,
            sessionStore: SessionStore? = null
        ): SessionManager {
            val key = try {
                projectRoot.canonicalPath
            } catch (e: Exception) {
                projectRoot.absolutePath
            }
            return managers.getOrPut(key) {
                val store = sessionStore ?: FileSessionStore(projectRoot)
                val indexer = ProjectIndexer()
                val relevance = RelevanceEngine(indexer)
                val engine = ContextEngine(store, indexer, relevance)
                SessionManager(store, engine, indexer, relevance, scope)
            }
        }

        /** Test hook: clears cached managers. */
        fun clearForTests() {
            managers.clear()
        }
    }
}
