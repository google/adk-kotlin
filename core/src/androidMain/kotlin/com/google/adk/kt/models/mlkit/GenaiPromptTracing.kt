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

package com.google.adk.kt.models.mlkit

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.utils.mlkit.AggregatedResponse
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart

/**
 * Helpers that format ML Kit GenAI and ADK request/response objects for tracing.
 *
 * Sensitive content (text parts, system instructions, function call arguments and function
 * responses) is redacted so that only metadata such as character counts and finish reasons is
 * logged.
 *
 * For ADK types this is done by building a redacted copy of the object - replacing the sensitive
 * content with a [redactedText] placeholder - and relying on the data classes' built-in [toString]
 * implementations, rather than formatting every field by hand. The ML Kit request/response types
 * are not data classes and have no usable [toString], so they keep a small hand-written formatter.
 */
internal object GenaiPromptTracing {

  internal fun format(generateContentRequest: GenerateContentRequest): String {
    val parts = generateContentRequest.contents.flatMap { it.parts }
    val textLength = parts.filterIsInstance<TextPart>().sumOf { it.textString.length }
    val imageCount = parts.filterIsInstance<ImagePart>().size
    val systemInstructionLength = generateContentRequest.systemInstruction?.textString?.length ?: 0
    return "generateContentRequest: text: ${redactedText(textLength)}, " +
      "systemInstruction: ${redactedText(systemInstructionLength)}, images: $imageCount"
  }

  internal fun format(generateContentResponse: GenerateContentResponse): String {
    val candidate = generateContentResponse.candidates.firstOrNull()
    return "generateContentResponse text: ${redactedText(candidate?.text?.length ?: 0)}, finishReason: ${candidate?.finishReason}"
  }

  internal fun format(aggregatedResponse: AggregatedResponse): String {
    val candidatesTrace =
      aggregatedResponse.candidates.joinToString(prefix = "[", postfix = "]") { candidate ->
        "{ text: ${redactedText(candidate.text.length)}, finishReason: ${candidate.finishReason} }"
      }
    return "aggregatedResponse candidates: $candidatesTrace"
  }

  internal fun format(llmResponse: LlmResponse): String = llmResponse.redacted().toString()

  internal fun format(llmRequest: LlmRequest): String {
    val redactedContents = llmRequest.contents.map { it.redacted() }
    val redactedConfig =
      llmRequest.config.copy(systemInstruction = llmRequest.config.systemInstruction?.redacted())
    // The model is rendered by name and the internal toolsDict is omitted, so the request can't be
    // fully delegated to LlmRequest.toString().
    return "LlmRequest(model=${llmRequest.model?.name}, contents=$redactedContents, " +
      "config=$redactedConfig)"
  }

  /** Returns a copy of this [LlmResponse] with its content redacted. */
  private fun LlmResponse.redacted(): LlmResponse = copy(content = content?.redacted())

  /** Returns a copy of this [Content] with every part redacted. */
  private fun Content.redacted(): Content = copy(parts = parts.map { it.redacted() })

  /**
   * Returns a copy of this [Part] with any sensitive content - text, function call arguments and
   * function responses - redacted. Non-sensitive fields (inline data, file data, thoughts) are left
   * untouched.
   */
  private fun Part.redacted(): Part =
    copy(
      text = text?.let { redactedText(it.length) },
      functionCall = functionCall?.redacted(),
      functionResponse = functionResponse?.redacted(),
    )

  /** Returns a copy of this [FunctionCall] with its argument values redacted. */
  private fun FunctionCall.redacted(): FunctionCall = copy(args = args.redactValues())

  /** Returns a copy of this [FunctionResponse] with its response values redacted. */
  private fun FunctionResponse.redacted(): FunctionResponse =
    copy(response = response.redactValues())

  /** Redacts the values of a map while keeping the keys intact. */
  private fun Map<String, Any?>.redactValues(): Map<String, Any?> = mapValues { (_, value) ->
    redactedText(value.toString().length)
  }

  /** A redaction placeholder that retains only the character [length] of the original content. */
  private fun redactedText(length: Int): String = "[REDACTED - length $length chars]"
}
