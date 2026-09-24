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
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

// -- Content / Part builders ---------------------------------------------------------------------

/** A `user`-role text [Content] (the typical user message). */
fun userMessage(text: String): Content =
  Content(role = Role.USER, parts = listOf(Part(text = text)))

/**
 * A `user`-role [Content] of one or more parts.
 *
 * There is no zero-part overload: build an empty turn with [Content] so the test shows it.
 */
fun userMessage(part: Part, vararg moreParts: Part): Content =
  Content(role = Role.USER, parts = listOf(part) + moreParts)

/** A `model`-role text [Content] (the typical model response body). */
fun modelMessage(text: String): Content =
  Content(role = Role.MODEL, parts = listOf(Part(text = text)))

/** The model-role counterpart of the parts overload of [userMessage]. */
fun modelMessage(part: Part, vararg moreParts: Part): Content =
  Content(role = Role.MODEL, parts = listOf(part) + moreParts)

/**
 * A `user`-role [Content] carrying a single [FunctionResponse] - what the caller sends back when
 * resuming after a long-running tool, an HITL approval, or a manual function-response injection.
 *
 * [id] has no default: a resume lookup matches on it, so a test that means to omit it has to write
 * `id = null` rather than leaving it off by accident.
 */
fun userFunctionResponse(
  name: String,
  id: String?,
  response: Map<String, Any?> = emptyMap(),
): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(Part(functionResponse = FunctionResponse(name = name, id = id, response = response))),
  )

/**
 * A `model`-role [Content] carrying a single [FunctionCall].
 *
 * Use it where a call goes straight into an event or into request history; use
 * [modelFunctionCallResponse] where it goes into an [LlmResponse].
 */
fun modelFunctionCall(
  name: String,
  args: Map<String, Any?> = emptyMap(),
  id: String? = null,
): Content =
  Content(
    role = Role.MODEL,
    parts = listOf(Part(functionCall = FunctionCall(name = name, args = args, id = id))),
  )

// -- LlmResponse builders ------------------------------------------------------------------------

/** A model response containing a single [FunctionCall] part. */
fun modelFunctionCallResponse(
  name: String,
  args: Map<String, Any?> = emptyMap(),
  id: String? = null,
): LlmResponse = LlmResponse(content = modelFunctionCall(name, args, id))

/** A model response containing several [FunctionCall] parts in a single turn. */
fun modelParallelFunctionCallsResponse(vararg calls: FunctionCall): LlmResponse =
  LlmResponse(content = Content(Role.MODEL, calls.map { Part(functionCall = it) }))
