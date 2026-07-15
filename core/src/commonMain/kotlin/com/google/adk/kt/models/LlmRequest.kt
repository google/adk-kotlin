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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LlmConstants
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * LlmRequest represents a request to an LLM.
 *
 * @property model The model to use for the request.
 * @property contents The contents of the request.
 * @property config The configuration for generating content.
 * @property toolsDict Internal mapping of tools added during request processing.
 */
data class LlmRequest(
  val model: Model? = null,
  val contents: List<Content> = emptyList(),
  val config: GenerateContentConfig = GenerateContentConfig(),
  internal val toolsDict: List<BaseTool> = emptyList(),
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

/**
 * Builds the filtered view of this request recorded on the `call_llm` span's
 * `gcp.vertex.agent.llm_request` attribute (parity with Python `_build_llm_request_for_trace`).
 *
 * Mirrors Python's deliberate exclusions, so the traced request carries the same fields Python
 * traces -- no more, no less:
 * - content parts carrying binary [Part.inlineData] are dropped (raw image/audio/video/file bytes
 *   are never sent to traces);
 * - the request [GenerateContentConfig.responseSchema] is excluded.
 *
 * Everything else is preserved. The result is built with the shared [adkJson] serializer, which
 * omits null/empty fields (`exclude_none`) and produces byte-identical JSON on every platform.
 */
@OptIn(FrameworkInternalApi::class)
internal fun LlmRequest.toTracePayload(): JsonObject {
  val fields = linkedMapOf<String, JsonElement>()
  model?.name?.let { fields["model"] = JsonPrimitive(it) }
  fields["config"] = adkJson.encodeToJsonElement(config.copy(responseSchema = null))
  fields["contents"] =
    adkJson.encodeToJsonElement(
      contents.map { content ->
        content.copy(parts = content.parts.filter { it.inlineData == null })
      }
    )
  return JsonObject(fields)
}
