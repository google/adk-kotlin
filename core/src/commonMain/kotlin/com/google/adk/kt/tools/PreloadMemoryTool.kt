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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part

/**
 * A tool that preloads the memory for the current user.
 *
 * This tool will be automatically executed for each llm_request, and it won't be called by the
 * model.
 *
 * NOTE: Currently this tool only uses text part from the memory.
 */
class PreloadMemoryTool : BaseTool(name = "preload_memory", description = "preload_memory") {

  override fun declaration(): FunctionDeclaration? {
    return null
  }

  override val hasLocalImplementation: Boolean
    get() = false

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    throw UnsupportedOperationException(
      "PreloadMemoryTool is not meant to be executed by the model - it modifies context instead."
    )
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val userContent = toolContext.invocationContext.userContent ?: return llmRequest
    val query = extractText(userContent.parts)
    if (query.isEmpty()) return llmRequest

    val memoryService = toolContext.invocationContext.memoryService ?: return llmRequest
    val session = toolContext.invocationContext.session

    val response =
      memoryService.searchMemory(
        appName = session.key.appName,
        userId = session.key.userId,
        query = query,
      )

    val fullMemoryText = buildString {
      for (memory in response.memories) {
        if (!memory.timestamp.isNullOrEmpty()) {
          if (isNotEmpty()) append('\n')
          append("Time: ${memory.timestamp}")
        }
        val text = extractText(memory.content.parts)
        if (text.isNotEmpty()) {
          if (isNotEmpty()) append('\n')
          append(if (!memory.author.isNullOrEmpty()) "${memory.author}: $text" else text)
        }
      }
    }

    if (fullMemoryText.isEmpty()) return llmRequest

    val instruction =
      """The following content is from your previous conversations with the user.
It may be useful for answering the user's current query.
<PAST_CONVERSATIONS>
$fullMemoryText
</PAST_CONVERSATIONS>"""
        .trim()

    return llmRequest.appendInstructions(Content(parts = listOf(Part(text = instruction))))
  }

  /** Joins all non-empty text from [parts] with spaces, ignoring null and non-text parts. */
  private fun extractText(parts: List<Part>): String =
    parts.mapNotNull { it.text }.filter { it.isNotEmpty() }.joinToString(" ")
}
