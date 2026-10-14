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
 *
 * [projectId] is the stable UUID from `<project>/.opencode/project-id.txt`
 * (see ProjectIdentity). Null for records written before stable IDs existed;
 * those remain discoverable by project path (see SessionManager.listSessions).
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
    var metadata: Map<String, String> = emptyMap(),
    val projectId: String? = null
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
    /**
     * Every record physically present in this store's directory, regardless of
     * stored path. Lets owners adopt moved/renamed projects' legacy records by
     * directory membership (see SessionManager.listSessions).
     */
    suspend fun listAll(): List<AgentSession>
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

    // NOTE: no mkdirs here by design. The directory is created lazily on the
    // first write (create/update) so that merely opening History or resolving
    // a coordinator never fabricates empty `.opencode/sessions/` directories.
    private val sessionsDir: File = File(baseDir, ".opencode/sessions")
    private val sessionsFlow = MutableStateFlow(emptyList<AgentSession>())

    /**
     * Names of session files that failed to parse on the last load. The files
     * are deliberately left in place (never auto-deleted or overwritten); see
     * [corruptRecords]. Refreshed by every [loadAll].
     */
    private val corruptNames = mutableListOf<String>()

    init {
        loadAll()
    }

    /**
     * Resolves a record file, rejecting anything that could escape
     * [sessionsDir]. Session IDs are UUIDs generated internally; anything else
     * is treated as hostile input, never as a path.
     */
    private fun sessionFile(id: String): File {
        require(id.isNotBlank() && '/' !in id && '\\' !in id && ".." !in id) {
            "Invalid session id"
        }
        return File(sessionsDir, "$id.json")
    }

    private fun readSession(file: File): AgentSession? = try {
        json.decodeFromString<AgentSession>(file.readText())
    } catch (e: Exception) {
        null
    }

    private fun loadAll() {
        val sessions = mutableListOf<AgentSession>()
        val corrupt = mutableListOf<String>()
        sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { file ->
                val session = readSession(file)
                if (session != null) sessions.add(session) else corrupt.add(file.name)
            }
        sessions.sortByDescending { it.updatedAt }
        sessionsFlow.value = sessions.toList()
        corruptNames.clear()
        corruptNames.addAll(corrupt.sorted())
    }

    /**
     * Malformed record file names from the last load, for visible reporting.
     * Quarantine policy: report only — files are never moved, deleted, or
     * overwritten by the store.
     */
    fun corruptRecords(): List<String> = corruptNames.toList()

    /** The resolved sessions directory (may not exist yet — see lazy mkdirs). */
    fun sessionsDir(): File = sessionsDir

    /**
     * Creates parent directories on first write only. Throws with a clear
     * message when the project location is not writable, so callers can
     * surface the failure instead of silently losing the session.
     */
    private fun ensureSessionsDir() {
        if (!sessionsDir.isDirectory && !sessionsDir.mkdirs()) {
            throw IllegalStateException("Cannot create session directory: ${sessionsDir.name}")
        }
    }

    override suspend fun create(session: AgentSession): AgentSession {
        ensureSessionsDir()
        sessionFile(session.id).writeText(json.encodeToString(session))
        loadAll()
        return session
    }

    override suspend fun get(id: String): AgentSession? {
        val file = try {
            sessionFile(id)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (file.exists()) readSession(file) else null
    }

    override suspend fun list(projectRoot: String): List<AgentSession> {
        return sessionsFlow.value.filter { it.projectRoot == projectRoot }
    }

    override suspend fun listAll(): List<AgentSession> {
        return sessionsFlow.value.toList()
    }

    override suspend fun update(session: AgentSession): AgentSession {
        val updated = session.copyWith(updatedAt = System.currentTimeMillis())
        ensureSessionsDir()
        sessionFile(session.id).writeText(json.encodeToString(updated))
        loadAll()
        return updated
    }

    override suspend fun delete(id: String): Boolean {
        val file = try {
            sessionFile(id)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val deleted = file.delete()
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

    override suspend fun listAll(): List<AgentSession> {
        return sessions.values.sortedByDescending { it.updatedAt }
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

/**
 * Display model for one saved conversation in history UI. Pure data — date
 * formatting stays in the UI layer (locale-dependent). Deliberately distinct
 * from [com.tom.rv2ide.artificial.agents.UnifiedModificationAttempt]: chat
 * conversations must never be mislabeled as file modifications.
 */
data class ConversationRow(
    val sessionId: String,
    val title: String,
    val messageCount: Int,
    val toolCallCount: Int,
    val updatedAt: Long
)

/** Maps a persisted session to its history row. Total functions, no I/O. */
fun AgentSession.toConversationRow(): ConversationRow {
    val calls = messages.sumOf { it.toolCalls.size }
    return ConversationRow(
        sessionId = id,
        title = title.ifBlank { "Untitled conversation" },
        messageCount = messages.size,
        toolCallCount = calls,
        updatedAt = updatedAt
    )
}
