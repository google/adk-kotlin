/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.kt.testing

import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

// -- Content / Part builders ---------------------------------------------------------------------

/** A `user`-role text [Content] (the typical user message). */
fun userMessage(text: String): Content =
  Content(role = Role.USER, parts = listOf(Part(text = text)))

/** A `model`-role text [Content] (the typical model response body). */
fun modelMessage(text: String): Content =
  Content(role = Role.MODEL, parts = listOf(Part(text = text)))

/**
 * A `user`-role [Content] carrying a single [FunctionResponse] - what the caller sends back when
 * resuming after a long-running tool, an HITL approval, or a manual function-response injection.
 */
fun userFunctionResponse(
  name: String,
  id: String,
  response: Map<String, Any?> = emptyMap(),
): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(Part(functionResponse = FunctionResponse(name = name, id = id, response = response))),
  )

// -- LlmResponse builders ------------------------------------------------------------------------

/** A model response containing a single [FunctionCall] part. */
fun modelFunctionCallResponse(
  name: String,
  args: Map<String, Any> = emptyMap(),
  id: String? = null,
): LlmResponse =
  LlmResponse(
    content =
      Content(
        Role.MODEL,
        listOf(Part(functionCall = FunctionCall(name = name, args = args, id = id))),
      )
  )

/** A model response containing several [FunctionCall] parts in a single turn. */
fun modelParallelFunctionCallsResponse(vararg calls: FunctionCall): LlmResponse =
  LlmResponse(content = Content(Role.MODEL, calls.map { Part(functionCall = it) }))

/**
 * A model response asking the framework to transfer to [agentName] - i.e. a
 * [TRANSFER_TO_AGENT_TOOL_NAME] function call.
 */
fun modelTransferToAgentResponse(agentName: String, id: String = "transfer_call"): LlmResponse =
  modelFunctionCallResponse(
    name = TRANSFER_TO_AGENT_TOOL_NAME,
    args = mapOf("agent_name" to agentName),
    id = id,
  )

// -- Expected-Part builders (use with simplifyEvents) --------------------------------------------

/**
 * The [Part] form of a `transfer_to_agent` function call (without an id), suitable for
 * `assertEquals` against a result of [simplifyEvents].
 */
fun transferToAgentCallPart(agentName: String): Part =
  Part(
    functionCall =
      FunctionCall(name = TRANSFER_TO_AGENT_TOOL_NAME, args = mapOf("agent_name" to agentName))
  )

/**
 * The [Part] form of the framework-emitted response to a `transfer_to_agent` call (without an id).
 * The transfer tool returns an empty map.
 */
val TRANSFER_TO_AGENT_RESPONSE_PART: Part =
  Part(
    functionResponse = FunctionResponse(name = TRANSFER_TO_AGENT_TOOL_NAME, response = emptyMap())
  )
