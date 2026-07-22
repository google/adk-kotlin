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
package com.google.adk.kt.mlkit

import androidx.core.net.toUri
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content as MlKitContent
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart

/** Utility functions for converting between ADK and ML Kit request and response formats. */
internal object GenaiPromptConversions {
  private val logger = LoggerFactory.getLogger(GenaiPromptConversions::class)

  private fun String?.isImageMimeType(): Boolean {
    return this?.startsWith("image/") == true
  }

  private val instructionSeparator = "\n\n"

  /** Guidance prepended to the system instruction for multi-turn requests. */
  private val multiTurnSystemInstruction =
    "You are given a multi-turn conversation history where each turn is prefixed with a role " +
      "marker such as \"[user]:\" or \"[model]:\". Do not prefix your own response with a role " +
      "marker such as \"[model]:\"."

  /**
   * Converts an [LlmRequest] to a [GenerateContentRequest].
   *
   * Each ADK [Content] (turn) maps to an ML Kit [MlKitContent], keeping all images and turn order.
   * Since ML Kit has no per-turn role, multi-turn requests prefix each turn's text with a `[role]:`
   * marker and prepend a default [multiTurnSystemInstruction] explaining the markers. The system
   * instruction is passed through the [SystemInstruction] field.
   */
  internal fun LlmRequest.toGenerateContentRequest(): GenerateContentRequest {
    val isMultiTurn = contents.size > 1
    val mlKitContents = contents.mapNotNull { it.toMlKitContent(includeRoleMarkers = isMultiTurn) }

    // ML Kit requires at least one content; fall back to an empty text prompt.
    val requestContents = mlKitContents.ifEmpty {
      listOf(MlKitContent.builder().addPart(TextPart("")).build())
    }

    // For multi-turn requests, prepend the guidance that explains the `[role]:` markers.
    val systemText =
      listOfNotNull(multiTurnSystemInstruction.takeIf { isMultiTurn }, systemInstructionText())
        .joinToString(instructionSeparator)
        .takeIf { it.isNotEmpty() }

    val builder = GenerateContentRequest.Builder(requestContents)

    builder.apply {
      config.temperature?.let { temperature = it }
      config.topK?.let { topK = it }
      config.candidateCount?.let { candidateCount = it }
      config.maxOutputTokens?.let { maxOutputTokens = it }
      systemText?.let { systemInstruction = SystemInstruction(it) }
    }

    return builder.build()
  }

  /**
   * Maps an ADK [Content] (turn) to an ML Kit [MlKitContent], preserving the original order of
   * parts: consecutive text parts are joined with "\n\n", and image parts keep their position
   * relative to the text. Returns `null` if the turn has no text or image.
   *
   * When [includeRoleMarkers] is true, a `[role]:` marker is attached to the start of the turn (as
   * a prefix on the first text, or as a leading text part if the turn starts with an image).
   */
  private fun Content.toMlKitContent(includeRoleMarkers: Boolean): MlKitContent? {
    val builder = MlKitContent.builder()
    val textGroup = StringBuilder()
    var addedPart = false
    // The `[role]:` marker to attach to the start of the turn; consumed once emitted.
    var pendingMarker = if (includeRoleMarkers) "[${role ?: Role.USER}]:" else null

    fun flushText() {
      if (textGroup.isEmpty()) return
      val marker = pendingMarker
      val text =
        if (marker != null) {
          pendingMarker = null
          "$marker $textGroup"
        } else {
          textGroup.toString()
        }
      builder.addPart(TextPart(text))
      textGroup.setLength(0)
      addedPart = true
    }

    for (part in parts) {
      val text = part.text
      if (!text.isNullOrEmpty()) {
        if (textGroup.isNotEmpty()) textGroup.append(instructionSeparator)
        textGroup.append(text)
        continue
      }
      val imagePart = part.toImagePartOrNull() ?: continue
      flushText()
      val marker = pendingMarker
      if (marker != null) {
        pendingMarker = null
        builder.addPart(TextPart(marker))
        addedPart = true
      }
      builder.addPart(imagePart)
      addedPart = true
    }
    flushText()

    return if (addedPart) builder.build() else null
  }

  /** Converts an ADK [Part] to an ML Kit [ImagePart], or `null` if it is not an image. */
  private fun Part.toImagePartOrNull(): ImagePart? {
    val inlineData = inlineData
    val fileData = fileData
    return when {
      inlineData != null && inlineData.mimeType.isImageMimeType() ->
        inlineData.data?.let { ImagePart(it) }
      fileData != null && fileData.mimeType.isImageMimeType() ->
        fileData.fileUri?.let { ImagePart(it.toUri()) }
      else -> null
    }
  }

  /** Returns the request's own system instruction text, or `null` if none is set. */
  private fun LlmRequest.systemInstructionText(): String? =
    config.systemInstruction
      ?.parts
      ?.mapNotNull { it.text }
      ?.joinToString(instructionSeparator)
      ?.takeIf { it.isNotEmpty() }

  /**
   * Converts a [GenerateContentResponse] to an [LlmResponse].
   *
   * Only the first candidate is used. If no candidate is returned, an error message is set.
   *
   * Error message is also set in case a finish reason is present and it is not STOP.
   *
   * @return The [LlmResponse] containing the text from the first candidate and the finish reason if
   *   present.
   */
  internal fun GenerateContentResponse.toLlmResponse(): LlmResponse {
    return this.toAggregatedResponse().toLlmResponse()
  }

  /**
   * Converts a [AggregatedResponse] to an [LlmResponse].
   *
   * Only the first candidate is used. If no candidate is returned, an error message is set.
   *
   * Error message is also set in case a finish reason is present and it is not STOP.
   *
   * @return The [LlmResponse] containing the text from the first candidate and the finish reason if
   *   present.
   */
  internal fun AggregatedResponse.toLlmResponse(): LlmResponse {
    if (candidates.size > 1) {
      logger.warn {
        "Multiple candidates present in GenerateContentResponse. Only the first one will be used in the LlmResponse."
      }
    }

    val candidate = candidates.firstOrNull()
    val finishReason =
      candidate?.finishReason?.let {
        when (it) {
          Candidate.FinishReason.STOP -> FinishReason.STOP
          Candidate.FinishReason.MAX_TOKENS -> FinishReason.MAX_TOKENS
          else -> FinishReason.OTHER
        }
      }

    return LlmResponse(
      content = candidate?.let { Content(role = Role.MODEL, parts = listOf(Part(text = it.text))) },
      finishReason = finishReason,
      errorMessage =
        when {
          candidate == null -> "No candidates returned."
          finishReason != null && finishReason != FinishReason.STOP ->
            "Generation finished with reason: $finishReason"
          else -> null
        },
    )
  }
}
