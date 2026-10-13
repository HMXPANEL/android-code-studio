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

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Hard-coded denylist for project-local files. Both the key name and a
 * secret-shaped value must pass; either one failing rejects the write.
 * Case-insensitive substring match on keys keeps future provider key names
 * covered without maintaining an exact list.
 */
object SecretDenylist {

    private val deniedKeyParts = listOf(
        "api_key", "apikey", "api-key", "token", "secret", "password", "passwd",
        "auth", "credential", "private_key", "privatekey", "bearer", "session_key"
    )

    /** `<prefix><20+ base64-ish chars>` shapes of common provider secrets. */
    private val secretValueShapes = listOf(
        Regex("^sk-[A-Za-z0-9-_]{8,}$"),
        Regex("^xox[bpas]-[A-Za-z0-9-]{8,}$"),
        Regex("^gh[pousr]_[A-Za-z0-9]{8,}$"),
        Regex("^AKIA[0-9A-Z]{12,}$"),
        Regex("^hf_[A-Za-z0-9]{8,}$")
    )

    fun isDeniedKey(key: String): Boolean {
        val lower = key.lowercase()
        return deniedKeyParts.any { it in lower }
    }

    fun isSecretShapedValue(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length < 12) return false
        return secretValueShapes.any { it.matches(trimmed) }
    }

    /** True when this pair must never be written to project-local storage. */
    fun rejects(key: String, value: String): Boolean =
        isDeniedKey(key) || isSecretShapedValue(value)
}

/**
 * Explicit project facts for the agent ("project memory"). There is no
 * automatic extraction: facts are added explicitly and surfaced verbatim into
 * the model context. Corrupt or missing files read as empty, never throw.
 */
@Serializable
data class ProjectMemory(
    val facts: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

class ProjectMemoryStore(
    private val projectRoot: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
    private val maxFacts: Int = 200,
    private val maxFactChars: Int = 2000
) {
    private val file: File
        get() = File(File(projectRoot, ProjectIdentity.DIR_NAME), "memory.json")

    fun getFacts(): List<String> = try {
        val f = file
        if (!f.isFile) emptyList() else json.decodeFromString<ProjectMemory>(f.readText()).facts
    } catch (e: Exception) {
        emptyList()
    }

    /** Returns false (without writing) for blank/oversized input or a full list. */
    fun addFact(fact: String): Boolean {
        val trimmed = fact.trim()
        if (trimmed.isEmpty() || trimmed.length > maxFactChars) return false
        return try {
            val current = getFacts()
            if (current.size >= maxFacts || trimmed in current) return false
            write(ProjectMemory(current + trimmed))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun removeFact(fact: String): Boolean {
        return try {
            val current = getFacts()
            if (fact !in current) return false
            write(ProjectMemory(current - fact))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear(): Boolean {
        return try {
            write(ProjectMemory())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun write(memory: ProjectMemory) {
        val dir = file.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.path}")
        }
        file.writeText(json.encodeToString(memory.copy(updatedAt = System.currentTimeMillis())))
    }
}

/**
 * Non-secret, project-specific agent settings (`settings.json`).
 * Every write passes through [SecretDenylist]; rejected writes return false
 * and change nothing. Provider credentials stay in app-private storage.
 */
@Serializable
data class AgentSettingsData(
    val values: Map<String, String> = emptyMap(),
    val maxContextTokens: Int? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MIN_CONTEXT_TOKENS = 2000
        const val MAX_CONTEXT_TOKENS = 128000
    }
}

class ProjectAgentSettings(
    private val projectRoot: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    private val file: File
        get() = File(File(projectRoot, ProjectIdentity.DIR_NAME), "settings.json")

    fun getAll(): Map<String, String> = read().values

    fun get(key: String): String? = read().values[key]

    fun getMaxContextTokens(): Int? = read().maxContextTokens

    /**
     * Stores one setting. Returns false (writing nothing) when the key or
     * value looks secret, the key is blank, or storage fails.
     */
    fun set(key: String, value: String): Boolean {
        val k = key.trim()
        if (k.isEmpty() || SecretDenylist.rejects(k, value)) return false
        return try {
            val current = read()
            write(current.copy(values = current.values + (k to value)))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun remove(key: String): Boolean {
        return try {
            val current = read()
            if (!current.values.containsKey(key)) return false
            write(current.copy(values = current.values - key))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stores a context-budget override, clamped to a sane range. */
    fun setMaxContextTokens(tokens: Int): Boolean {
        val clamped = tokens.coerceIn(
            AgentSettingsData.MIN_CONTEXT_TOKENS,
            AgentSettingsData.MAX_CONTEXT_TOKENS
        )
        return try {
            write(read().copy(maxContextTokens = clamped))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun read(): AgentSettingsData = try {
        val f = file
        if (!f.isFile) AgentSettingsData()
        else json.decodeFromString<AgentSettingsData>(f.readText())
    } catch (e: Exception) {
        AgentSettingsData()
    }

    private fun write(data: AgentSettingsData) {
        val dir = file.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.path}")
        }
        file.writeText(json.encodeToString(data.copy(updatedAt = System.currentTimeMillis())))
    }
}
