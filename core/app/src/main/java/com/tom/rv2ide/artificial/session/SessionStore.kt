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

import com.tom.rv2ide.artificial.tools.RunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A session represents a single agent conversation with full history.
 * Mode is stored via RunMode's built-in serializer (see Tool.kt).
 */
@Serializable
data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val projectRoot: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val mode: RunMode,
    val providerId: String,
    val modelId: String,
    var title: String = "New Session",
    var messages: List<PersistedMessage> = emptyList(),
    var toolCalls: List<PersistedToolCall> = emptyList(),
    var checkpoints: List<Checkpoint> = emptyList(),
    var metadata: Map<String, String> = emptyMap()
) {
    fun copyWith(
        title: String? = null,
        messages: List<PersistedMessage>? = null,
        toolCalls: List<PersistedToolCall>? = null,
        checkpoints: List<Checkpoint>? = null,
        metadata: Map<String, String>? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): AgentSession {
        return copy(
            title = title ?: this.title,
            messages = messages ?: this.messages,
            toolCalls = toolCalls ?: this.toolCalls,
            checkpoints = checkpoints ?: this.checkpoints,
            metadata = metadata ?: this.metadata,
            updatedAt = updatedAt
        )
    }
}

/**
 * A message in the session conversation.
 */
@Serializable
data class PersistedMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCalls: List<PersistedToolCall> = emptyList(),
    val toolResults: List<PersistedToolResult> = emptyList()
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

/**
 * A tool call that was made during the session.
 * Arguments are stored as strings (values stringified) so the record
 * stays serializable; use helpers to convert to/from ToolCall.args.
 */
@Serializable
data class PersistedToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: ToolCallStatus = ToolCallStatus.PENDING
)

@Serializable
enum class ToolCallStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

/**
 * The result of a tool execution.
 */
@Serializable
data class PersistedToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A Git checkpoint for session rollback.
 */
@Serializable
data class Checkpoint(
    val id: String = UUID.randomUUID().toString(),
    val commitHash: String,
    val timestamp: Long = System.currentTimeMillis(),
    val description: String = ""
)

/**
 * Interface for session persistence.
 */
interface SessionStore {
    suspend fun create(session: AgentSession): AgentSession
    suspend fun get(id: String): AgentSession?
    suspend fun list(projectRoot: String): List<AgentSession>
    suspend fun update(session: AgentSession): AgentSession
    suspend fun delete(id: String): Boolean
    fun observe(projectRoot: String): Flow<List<AgentSession>>
    suspend fun getWithHistory(id: String): AgentSession?
}

/**
 * File-based implementation of SessionStore using Kotlin Serialization JSON.
 * Stores each session as a separate JSON file in the project's .opencode/sessions directory.
 */
class FileSessionStore(
    private val baseDir: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
) : SessionStore {

    private val sessionsDir: File = File(baseDir, ".opencode/sessions").apply { mkdirs() }
    private val sessionsFlow = MutableStateFlow(emptyList<AgentSession>())

    init {
        loadAll()
    }

    private fun sessionFile(id: String): File = File(sessionsDir, "$id.json")

    private fun readSession(file: File): AgentSession? = try {
        json.decodeFromString<AgentSession>(file.readText())
    } catch (e: Exception) {
        null
    }

    private fun loadAll() {
        val sessions = sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { readSession(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        sessionsFlow.value = sessions
    }

    override suspend fun create(session: AgentSession): AgentSession {
        sessionFile(session.id).writeText(json.encodeToString(session))
        loadAll()
        return session
    }

    override suspend fun get(id: String): AgentSession? {
        val file = sessionFile(id)
        return if (file.exists()) readSession(file) else null
    }

    override suspend fun list(projectRoot: String): List<AgentSession> {
        return sessionsFlow.value.filter { it.projectRoot == projectRoot }
    }

    override suspend fun update(session: AgentSession): AgentSession {
        val updated = session.copyWith(updatedAt = System.currentTimeMillis())
        sessionFile(session.id).writeText(json.encodeToString(updated))
        loadAll()
        return updated
    }

    override suspend fun delete(id: String): Boolean {
        val deleted = sessionFile(id).delete()
        if (deleted) loadAll()
        return deleted
    }

    override fun observe(projectRoot: String): Flow<List<AgentSession>> {
        return sessionsFlow.map { sessions -> sessions.filter { it.projectRoot == projectRoot } }
    }

    override suspend fun getWithHistory(id: String): AgentSession? = get(id)
}

/**
 * In-memory session store for testing.
 */
class InMemorySessionStore : SessionStore {
    private val sessions = mutableMapOf<String, AgentSession>()
    private val sessionsFlow = MutableStateFlow(emptyList<AgentSession>())

    override suspend fun create(session: AgentSession): AgentSession {
        sessions[session.id] = session
        updateFlow()
        return session
    }

    override suspend fun get(id: String): AgentSession? = sessions[id]

    override suspend fun list(projectRoot: String): List<AgentSession> {
        return sessions.values.filter { it.projectRoot == projectRoot }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun update(session: AgentSession): AgentSession {
        val updated = session.copyWith(updatedAt = System.currentTimeMillis())
        sessions[session.id] = updated
        updateFlow()
        return updated
    }

    override suspend fun delete(id: String): Boolean {
        val removed = sessions.remove(id) != null
        if (removed) updateFlow()
        return removed
    }

    override fun observe(projectRoot: String): Flow<List<AgentSession>> {
        return sessionsFlow.map { all -> all.filter { it.projectRoot == projectRoot } }
    }

    override suspend fun getWithHistory(id: String): AgentSession? = sessions[id]

    private fun updateFlow() {
        sessionsFlow.value = sessions.values.toList()
    }
}
