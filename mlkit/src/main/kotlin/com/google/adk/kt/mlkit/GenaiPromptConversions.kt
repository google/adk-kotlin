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
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.ThinkingLevel
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content as MlKitContent
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart

/** Separator between grouped text segments and system-instruction parts. */
internal const val instructionSeparator = "\n\n"

private fun String?.isImageMimeType(): Boolean = this?.startsWith("image/") == true

/** Converts an ADK [Part] to an ML Kit [ImagePart], or `null` if it is not an image. */
internal fun Part.toImagePartOrNull(): ImagePart? {
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
internal fun LlmRequest.systemInstructionText(): String? =
  config.systemInstruction
    ?.parts
    ?.mapNotNull { it.text }
    ?.joinToString(instructionSeparator)
    ?.takeIf { it.isNotEmpty() }

/** Utility functions for converting between ADK and ML Kit request and response formats. */
internal object GenaiPromptConversions {
  private val logger = LoggerFactory.getLogger(GenaiPromptConversions::class)

  /** Guidance prepended to the system instruction for multi-turn requests. */
  private val multiTurnSystemInstruction =
    "The conversation history below prefixes each turn with a role marker, \"[user]:\" or " +
      "\"[model]:\". The markers only label who spoke. Two rules about them are absolute:\n" +
      "1. Never write a marker yourself: your reply must not begin with \"[model]:\", and must " +
      "not contain \"[user]:\" or \"[model]:\" anywhere.\n" +
      "2. Write only your own reply: never continue the transcript with another turn, and never " +
      "reproduce an earlier turn unless the user asks you to quote or summarize it."

  /**
   * Converts an [LlmRequest] to a [GenerateContentRequest].
   *
   * The public ML Kit Prompt API has no per-turn role, so multi-turn requests prefix each turn's
   * text with a `[role]:` marker and prepend [multiTurnSystemInstruction] explaining the markers.
   * Thinking is enabled unless the request has no [ThinkingConfig] or sets `thinkingBudget = 0`.
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
      enableThinking = config.thinkingConfig.enablesThinking()
    }

    return builder.build()
  }

  /**
   * Maps an ADK [Content] (turn) to an ML Kit [MlKitContent], preserving the original order of
   * parts: consecutive text parts are joined with "\n\n", and image parts keep their position
   * relative to the text. Thought parts are dropped. Returns `null` if the turn has no text or
   * image left.
   *
   * When [includeRoleMarkers] is true, a `[role]:` marker is attached to the start of the turn (as
   * a prefix on the first text, or as a leading text part if the turn starts with an image).
   * Function-call and function-response parts are dropped: the public ML Kit API cannot carry them.
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
      // A thought is the model's private reasoning. Replaying it would spend the device's small
      // context on it and tell the model it had said its own reasoning out loud.
      if (part.thought == true) continue
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

  /**
   * Whether ML Kit's thinking mode should be enabled for this [ThinkingConfig].
   *
   * ML Kit offers a single on/off switch, so thinking is on whenever a config is present, except
   * for `thinkingBudget = 0`, which is the genai encoding of DISABLED. An explicit positive token
   * budget and [ThinkingLevel] have no ML Kit equivalent and are ignored with a debug log.
   */
  private fun ThinkingConfig?.enablesThinking(): Boolean {
    if (this == null) return false
    val budget = thinkingBudget
    if (budget == 0) return false

    val unsupported =
      listOfNotNull(
        budget?.takeIf { it > 0 }?.let { "thinkingBudget=$it" },
        thinkingLevel
          ?.takeIf { it != ThinkingLevel.THINKING_LEVEL_UNSPECIFIED }
          ?.let { "thinkingLevel=$it" },
      )
    if (unsupported.isNotEmpty()) {
      // Debug, not warn: this fires on every request of an agent whose config is shared with a
      // cloud model, and the ignored fields are a config-level fact rather than a per-call problem.
      logger.debug {
        "ML Kit supports thinking as on or off only; ignoring ${unsupported.joinToString()}."
      }
    }
    return true
  }

  /**
   * Whether the caller asked for the model's thoughts to be returned, mirroring genai's
   * [ThinkingConfig.includeThoughts].
   */
  internal fun LlmRequest.includeThoughts(): Boolean =
    config.thinkingConfig?.includeThoughts == true

  /**
   * Converts a [GenerateContentResponse] to an [LlmResponse].
   *
   * Only the first candidate and the first thought are used, and the two are not necessarily
   * related: ML Kit sorts and dedupes candidates by score but leaves thoughts in their original
   * order, so above `candidateCount = 1` their pairing is not guaranteed.
   *
   * The thought is surfaced only when [includeThoughts] is set, so a request behaves the way the
   * same [ThinkingConfig] would on a cloud model.
   */
  internal fun GenerateContentResponse.toLlmResponse(includeThoughts: Boolean): LlmResponse {
    if (candidates.size > 1) {
      logger.warn {
        "Multiple candidates present in GenerateContentResponse. Only the first one will be used in the LlmResponse."
      }
    }
    val thoughtText = selectThoughtText(thoughtProcess.map { it.text }, includeThoughts)

    val candidate = candidates.firstOrNull()
    return buildLlmResponse(
      text = candidate?.text,
      thoughtText = thoughtText,
      mlKitFinishReason = candidate?.finishReason,
      hasThoughtProcess = thoughtProcess.isNotEmpty(),
    )
  }

  /**
   * The thought to surface, or `null` when there is none or the caller did not ask for one.
   *
   * Takes plain strings rather than a [GenerateContentResponse] so this gate stays unit-testable:
   * the published ML Kit artifact hides the factories that would build one, so a test running
   * against that artifact cannot fabricate a thought-bearing response.
   */
  internal fun selectThoughtText(thoughtTexts: List<String>, includeThoughts: Boolean): String? {
    if (!includeThoughts) return null
    if (thoughtTexts.size > 1) {
      logger.warn {
        "Multiple thoughts present in GenerateContentResponse. Only the first one will be used in the LlmResponse."
      }
    }
    return thoughtTexts.firstOrNull()?.takeIf { it.isNotEmpty() }
  }

  /**
   * Builds an [LlmResponse] from an ML Kit candidate's fields.
   *
   * Having neither [text] nor [thoughtText] is an error, unless [hasThoughtProcess] is true: ML Kit
   * streams thoughts as candidate-less chunks, and reporting those as errors would propagate into
   * the aggregated final response.
   *
   * Takes plain values rather than a [GenerateContentResponse] so these rules stay unit-testable.
   */
  internal fun buildLlmResponse(
    text: String?,
    mlKitFinishReason: Int?,
    hasThoughtProcess: Boolean,
    thoughtText: String? = null,
  ): LlmResponse {
    val finishReason = mlKitFinishReason?.let {
      when (it) {
        Candidate.FinishReason.STOP -> FinishReason.STOP
        Candidate.FinishReason.MAX_TOKENS -> FinishReason.MAX_TOKENS
        else -> FinishReason.OTHER
      }
    }

    // Within one response the thought leads, so a reader meets the reasoning before its answer.
    val parts = buildList {
      thoughtText?.let { add(Part(text = it, thought = true)) }
      text?.let { add(Part(text = it)) }
    }

    val errorMessage =
      when {
        parts.isEmpty() && !hasThoughtProcess -> "No candidates returned."
        finishReason != null && finishReason != FinishReason.STOP ->
          "Generation finished with reason: $finishReason"
        else -> null
      }

    return LlmResponse(
      content = parts.ifEmpty { null }?.let { Content(role = Role.MODEL, parts = it) },
      finishReason = finishReason,
      errorMessage = errorMessage,
    )
  }
}
