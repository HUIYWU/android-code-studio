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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire helpers for the OpenAI-compatible chat format, shared by every provider that speaks it:
 * DeepSeek, LocalLLM, OpenAI and Grok.
 */
internal object OpenAiCompat {

    /**
     * The request's messages, with every system message merged into one.
     *
     * A request carries the tool prompt and, once the history has been compressed, a summary of the
     * earlier turns — both as [AgentMessage.System]. Providers that accept a single system prompt
     * drop the rest, so several system messages are defined here as their join, and every provider
     * then sees the same one.
     */
    fun messagesJson(messages: List<AgentMessage>): JSONArray {
        val system = messages.filterIsInstance<AgentMessage.System>()
            .joinToString("\n\n") { it.content }

        return JSONArray().apply {
            var systemWritten = false
            messages.forEach { message ->
                if (message is AgentMessage.System) {
                    if (!systemWritten) {
                        systemWritten = true
                        put(messageJson(AgentMessage.System(system)))
                    }
                } else {
                    put(messageJson(message))
                }
            }
        }
    }

    fun toolsJson(tools: List<AgentToolSpec>): JSONArray =
        JSONArray().apply {
            tools.forEach { tool ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        )
                    }
                )
            }
        }

    /** The `choices[0].delta` of one SSE chunk, or null when the payload is not a delta. */
    fun deltaOf(payload: String): JSONObject? =
        try {
            JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
        } catch (e: Exception) {
            null
        }

    fun contentOf(delta: JSONObject): String = stringOf(delta, "content")

    fun reasoningOf(delta: JSONObject): String = stringOf(delta, "reasoning_content")

    /** Reads a string field that may be absent or explicitly null; both mean "no fragment". */
    fun stringOf(node: JSONObject, name: String): String {
        val raw = node.opt(name)
        return if (raw == null || raw === JSONObject.NULL) "" else raw as? String ?: ""
    }

    private fun messageJson(message: AgentMessage): JSONObject =
        JSONObject().apply {
            put("role", message.role)
            when (message) {
                is AgentMessage.System -> put("content", message.content)
                is AgentMessage.User -> put("content", message.content)
                is AgentMessage.Assistant -> {
                    put("content", message.content)
                    if (message.reasoningContent.isNotBlank()) {
                        put("reasoning_content", message.reasoningContent)
                    }
                    if (message.toolCalls.isNotEmpty()) {
                        put("tool_calls", callsJson(message.toolCalls))
                    }
                }
                is AgentMessage.Tool -> {
                    put("tool_call_id", message.toolCallId)
                    put("content", message.content)
                }
            }
        }

    private fun callsJson(calls: List<AgentToolCall>): JSONArray =
        JSONArray().apply {
            calls.forEach { call ->
                put(
                    JSONObject().apply {
                        put("id", call.id)
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        )
                    }
                )
            }
        }
}

/**
 * Accumulates the `tool_calls` fragments of an OpenAI-compatible stream.
 *
 * A call is announced with its id and name; the arguments then arrive split across chunks. The
 * fragments are keyed by call index because several calls can be interleaved in one stream;
 * first-appearance order is preserved.
 */
internal class ToolCallAccumulator {

    private class Partial {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private val order = mutableListOf<Int>()
    private val partials = mutableMapOf<Int, Partial>()

    fun accept(rawCalls: JSONArray?) {
        if (rawCalls == null) return
        for (i in 0 until rawCalls.length()) {
            val node = rawCalls.optJSONObject(i) ?: continue
            val index = node.optInt("index", 0)
            val partial = partials.getOrPut(index) {
                order.add(index)
                Partial()
            }

            OpenAiCompat.stringOf(node, "id").takeIf { it.isNotEmpty() }?.let { partial.id = it }

            val function = node.optJSONObject("function") ?: continue
            OpenAiCompat.stringOf(function, "name").takeIf { it.isNotEmpty() }?.let { partial.name = it }

            val rawArguments = function.opt("arguments")
            if (rawArguments != null && rawArguments !== JSONObject.NULL) {
                partial.arguments.append(rawArguments.toString())
            }
        }
    }

    fun hasCalls(): Boolean = order.isNotEmpty()

    fun finish(): List<AgentToolCall> =
        order.mapNotNull { index ->
            val partial = partials[index] ?: return@mapNotNull null
            if (partial.name.isEmpty()) return@mapNotNull null
            AgentToolCall(
                id = partial.id.ifEmpty { CALL_ID_PREFIX + index },
                name = partial.name,
                arguments = partial.arguments.toString()
            )
        }

    private companion object {
        const val CALL_ID_PREFIX = "call_"
    }
}