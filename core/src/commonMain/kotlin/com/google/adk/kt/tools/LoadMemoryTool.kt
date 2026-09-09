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

package com.google.adk.kt.tools

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * A tool that loads the memory for the current user.
 *
 * NOTE: Currently this tool only uses text part from the memory.
 */
class LoadMemoryTool :
  BaseTool(name = "load_memory", description = "Loads the memory for the current user.") {

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties = mapOf("query" to Schema(type = Type.STRING)),
          required = listOf("query"),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val query =
      args["query"] as? String
        ?: return mapOf(
          "error" to "Missing 'query' parameter.",
          "error_code" to "INVALID_ARGUMENTS",
        )

    val memoryService =
      context.invocationContext.memoryService
        ?: return mapOf(
          "error" to "MemoryService is not configured.",
          "error_code" to "UNCONFIGURED",
        )

    val session = context.invocationContext.session
    val response =
      memoryService.searchMemory(
        appName = session.key.appName,
        userId = session.key.userId,
        query = query,
      )
    // BaseTool wraps only a non-Map result, so this tool builds the wrapper itself.
    return mapOf(
      BaseTool.RESULT_KEY to mapOf("memories" to response.memories.map { it.toJsonNative() })
    )
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val newRequest = super.processLlmRequest(toolContext, llmRequest)

    // Tell the model about the memory availability.
    val instructions =
      """
      You have memory. You can use it to answer questions. If any questions need
      you to look up the memory, you should call load_memory function with a query.
      """
        .trimIndent()

    return newRequest.appendInstructions(Content(parts = listOf(Part(text = instructions))))
  }
}

/**
 * Converts a memory entry to JSON-native values through its own serializer, so the payload follows
 * [MemoryEntry] instead of a hand-written field list that has to be kept in step with it.
 *
 * Not the platform JSON helper: its Android implementation stringifies a data class instead of
 * encoding it, and its JVM implementation skips the custom serializers declared on `Part`.
 */
@OptIn(FrameworkInternalApi::class)
private fun MemoryEntry.toJsonNative(): Any =
  // checkNotNull, not a null-skip: encoding a non-null entry never yields JSON null, so an absent
  // value is a bug rather than an entry to drop silently.
  checkNotNull(jsonElementToAny(adkJson.encodeToJsonElement(MemoryEntry.serializer(), this)))
