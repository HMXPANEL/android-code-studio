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

import com.tom.rv2ide.artificial.agents.AIAgentManager

/**
 * Narrow provider-call seam for JVM testability.
 *
 * [AgentController] depends on this interface instead of concrete
 * [AIAgentManager], allowing a fake/test double to be injected in
 * JVM unit tests. The production implementation delegates to the
 * existing provider's [generateCode] method unchanged.
 *
 * Do not add tool-calling or native function-calling methods here;
 * those belong in [ToolCallSource] implementations (Phase 2+).
 */
interface ProviderCall {
    /**
     * Calls the AI provider with the given prompt.
     *
     * @param prompt the full assembled prompt including system, tools, history, and user request
     * @param language the target programming language (default "kotlin")
     * @param projectStructure optional project structure context
     * @return the provider's raw text response, or failure with error details
     */
    suspend fun generateCode(
        prompt: String,
        language: String = "kotlin",
        projectStructure: String? = null
    ): Result<String>

    /**
     * Returns the provider's display name for logging/debugging.
     */
    val providerName: String
}

/**
 * Production implementation that delegates to [AIAgentManager]'s current agent.
 * Keeps existing provider behavior exactly unchanged.
 */
class ProviderCallImpl(private val manager: AIAgentManager) : ProviderCall {
    override suspend fun generateCode(
        prompt: String,
        language: String = "kotlin",
        projectStructure: String? = null
    ): Result<String> {
        val agent = manager.getCurrentAgent()
        return if (agent != null) {
            agent.generateCode(
                prompt = prompt,
                context = null,
                language = language,
                projectStructure = projectStructure
            )
        } else {
            Result.failure(IllegalStateException("No AI provider is configured. Set an API key first."))
        }
    }

    override val providerName: String
        get() = manager.getCurrentProviderName()
}