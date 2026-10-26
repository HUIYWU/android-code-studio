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

package com.tom.rv2ide.artificial.agents

import org.json.JSONObject

/**
 * One entry of a tool-aware request, in provider-neutral form.
 *
 * Providers serialize these into their own wire formats in [AIAgent.generateTurn]; requests that
 * fall back to the text protocol never build any.
 */
sealed interface AgentMessage {
    val role: String
    val content: String

    data class System(override val content: String) : AgentMessage {
        override val role = "system"
    }

    data class User(override val content: String) : AgentMessage {
        override val role = "user"
    }

    /** [toolCalls] holds the calls the model asked for; empty for a pure text turn. */
    data class Assistant(
        override val content: String,
        val toolCalls: List<AgentToolCall> = emptyList(),
        val reasoningContent: String = ""
    ) : AgentMessage {
        override val role = "assistant"
    }

    /** The outcome of the call identified by [toolCallId]. */
    data class Tool(
        val toolCallId: String,
        override val content: String,
        val isError: Boolean = false
    ) : AgentMessage {
        override val role = "tool"
    }
}

data class AgentToolCall(
    val id: String,
    val name: String,
    /** Arguments exactly as the model produced them; each tool parses its own. */
    val arguments: String
)

data class AgentToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema of the arguments, in the shape the providers accept. */
    val parameters: JSONObject
)

/** One round's outcome: the text, reasoning, and tool calls the model produced. */
data class AgentTurn(
    val text: String,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val reasoningContent: String = ""
)
