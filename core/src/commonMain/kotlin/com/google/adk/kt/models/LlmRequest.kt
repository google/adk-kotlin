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
package com.google.adk.kt.models

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LlmConstants
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool

/**
 * LlmRequest represents a request to an LLM.
 *
 * @property model The model to use for the request.
 * @property contents The contents of the request.
 * @property config The configuration for generating content.
 * @property toolsDict Internal mapping of tools added during request processing.
 * @property cacheConfig Context cache configuration for this request. When `null`, context caching
 *   is disabled for the request.
 * @property cacheMetadata Cache metadata carried over from previous requests, used to validate and
 *   reuse an existing cache.
 * @property cacheableContentsTokenCount Prompt token count from the previous request, used to gate
 *   cache creation on a minimum size.
 */
data class LlmRequest(
  val model: Model? = null,
  val contents: List<Content> = emptyList(),
  val config: GenerateContentConfig = GenerateContentConfig(),
  internal val toolsDict: List<BaseTool> = emptyList(),
  val cacheConfig: ContextCacheConfig? = null,
  val cacheMetadata: CacheMetadata? = null,
  val cacheableContentsTokenCount: Int? = null,
) {
  /**
   * Appends tools to the request and merges any new function declarations.
   *
   * @param tools The list of tools to append.
   * @return A new [LlmRequest] with the specified tools appended.
   */
  fun appendTools(tools: List<BaseTool>): LlmRequest {
    if (tools.isEmpty()) return this

    val newFunctionDeclarations = tools.mapNotNull { it.declaration() }

    if (newFunctionDeclarations.isEmpty()) {
      return this.copy(toolsDict = toolsDict + tools)
    }

    val existingTools = config.tools?.toMutableList() ?: mutableListOf()
    val existingFunctionToolIndex = existingTools.indexOfFirst { it.functionDeclarations != null }

    if (existingFunctionToolIndex == -1) {
      existingTools.add(Tool(functionDeclarations = newFunctionDeclarations))
    } else {
      val existingTool = existingTools[existingFunctionToolIndex]
      val updatedDeclarations =
        (existingTool.functionDeclarations ?: emptyList()) + newFunctionDeclarations
      existingTools[existingFunctionToolIndex] =
        existingTool.copy(functionDeclarations = updatedDeclarations)
    }
    return this.copy(config = config.copy(tools = existingTools), toolsDict = toolsDict + tools)
  }

  /**
   * Appends instructions to the system instruction.
   *
   * Note: Model API requires system_instruction to be a string. Non-text parts in Content are
   * processed with references in system_instruction and added as user contents.
   *
   * Behavior:
   * - types.Content: extracts text parts with references to non-text parts, adds non-text parts as
   *   user contents
   *
   * @param instructions The instructions to append.
   */
  fun appendInstructions(instructions: Content): LlmRequest {
    val textParts = mutableListOf<String>()
    val userContents = mutableListOf<Content>()

    class RefGenerator {
      private var referenceCount = 0

      fun next(dataType: String) = "${dataType}_${referenceCount++}"
    }
    val refGenerator = RefGenerator()

    // Process all parts, creating references for non-text parts
    for (part in instructions.parts) {
      // Local variables are required to enable smart casts on expect properties.
      val text = part.text
      val inlineData = part.inlineData
      val fileData = part.fileData

      when {
        text != null -> textParts.add(text)
        inlineData != null -> {
          val partInfo =
            PartInfo(
              refGenerator.next(LlmConstants.INLINE_DATA),
              "inline binary data",
              buildList {
                inlineData.displayName?.let { add("'$it'") }
                inlineData.mimeType?.let { add("type: $it") }
              },
            )
          textParts.add(partInfo.referenceText)
          userContents.add(partInfo.toContent(inlineData))
        }
        fileData != null -> {
          val partInfo =
            PartInfo(
              refGenerator.next(LlmConstants.FILE_DATA),
              "file data",
              buildList {
                fileData.displayName?.let { add("'$it'") }
                fileData.fileUri?.let { add("URI: $it") }
                fileData.mimeType?.let { add("type: $it") }
              },
            )
          textParts.add(partInfo.referenceText)
          userContents.add(partInfo.toContent(fileData))
        }
      }
    }

    var newSystemInstruction: Content? = config.systemInstruction
    // Handle text parts for system instruction
    if (textParts.isNotEmpty()) {
      val newTextRaw = textParts.joinToString("\n\n")

      if (newSystemInstruction == null) {
        newSystemInstruction = Content(parts = listOf(Part(text = newTextRaw)))
      } else {
        val existingParts = newSystemInstruction.parts.toMutableList()
        // Prepend \n\n if there are existing parts
        val textToAdd = if (existingParts.isNotEmpty()) "\n\n$newTextRaw" else newTextRaw
        existingParts.add(Part(text = textToAdd))
        newSystemInstruction = Content(parts = existingParts)
      }
    }

    // Add user contents directly to contents
    return this.copy(
      config = config.copy(systemInstruction = newSystemInstruction),
      contents = contents + userContents,
    )
  }

  /**
   * Appends a content block to the request.
   *
   * @param content The content to append.
   * @return A new [LlmRequest] with the specified content appended.
   */
  fun appendContent(content: Content): LlmRequest {
    return this.copy(contents = contents + content)
  }

  private class PartInfo(val referenceId: String, val dataType: String, displayInfo: List<String>) {
    val displayInfoValue =
      displayInfo.joinToString(", ").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
    val referenceText = "[Reference to $dataType: $referenceId$displayInfoValue]"

    fun toContent(fileData: FileData): Content =
      Content(
        role = Role.USER,
        parts = listOf(Part(text = "Referenced $dataType: $referenceId"), Part(fileData = fileData)),
      )

    fun toContent(inlineData: Blob): Content =
      Content(
        role = Role.USER,
        parts =
          listOf(Part(text = "Referenced $dataType: $referenceId"), Part(inlineData = inlineData)),
      )
  }
}
