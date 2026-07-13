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

import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aggregates partial streaming responses into a single, cohesive response.
 *
 * This class processes a stream of [GenerateContentResponse] objects, handling partial text and
 * function call arguments by buffering and merging them into complete [Part]s as they arrive.
 *
 * The [processResponse] method should be called for each response in the stream. After all
 * responses have been processed, the [aggregate] method should be called to retrieve the final
 * aggregated [LlmResponse] containing all parts in the order they were received by the aggregator.
 */
internal class StreamingResponseAggregator {
  private val mutex = Mutex()
  private var response: GenerateContentResponse? = null
  private var usageMetadata: UsageMetadata? = null
  private var groundingMetadata: GroundingMetadata? = null
  private var citationMetadata: CitationMetadata? = null
  private var finishReason: FinishReason? = null

  private val partsSequence = mutableListOf<Part>()
  private val currentTextBuffer = StringBuilder()
  private var currentTextIsThought: Boolean? = null

  private var currentFcName: String? = null
  private val currentFcArgs = mutableMapOf<String, Any?>()
  private var currentFcId: String? = null
  private var currentThoughtSignature: ByteArray? = null

  /**
   * Processes a single model response from a stream, adding its content to the aggregation buffer.
   *
   * This method buffers consecutive text parts and merges partial function call arguments received
   * in streaming responses. When the type of content changes (e.g., from text to a function call,
   * or from thought to non-thought text), or when a function call stream ends, the buffered content
   * is flushed as a single [Part] to an internal list. It also accumulates metadata like
   * [UsageMetadata] and [FinishReason] across all responses.
   *
   * @param response The response chunk to process.
   * @return The [LlmResponse] corresponding to the current chunk, marked as partial.
   */
  suspend fun processResponse(response: GenerateContentResponse): LlmResponse = mutex.withLock {
    this.response = response
    val llmResponse = LlmResponse.from(response)

    llmResponse.usageMetadata?.let { usageMetadata = it }
    llmResponse.groundingMetadata?.let { groundingMetadata = it }
    llmResponse.citationMetadata?.let { citationMetadata = it }
    llmResponse.finishReason?.let { finishReason = it }

    // Assign a client id to any function call missing one up front, so the partial chunk and the
    // final response share it.
    val parts = (llmResponse.content?.parts ?: emptyList()).map { it.ensureFunctionCallId() }
    for (part in parts) {
      when {
        part.text != null -> processTextPart(part)
        part.functionCall != null -> processFunctionCallPart(part)
        else -> {
          // Other non-text parts (blobs, etc.)
          flushTextBufferToSequence()
          partsSequence.add(part)
        }
      }
    }

    // In Progressive SSE mode, all intermediate chunks are partial (with any generated ids).
    llmResponse.copy(content = llmResponse.content?.copy(parts = parts), partial = true)
  }

  /**
   * Flushes any buffered content and returns the final aggregated response.
   *
   * This method should be called once after all streaming responses have been processed via
   * [processResponse]. It ensures any pending text or function call data is added to the parts
   * list, and then constructs a single [LlmResponse] containing all aggregated parts and metadata,
   * with `partial` set to `false`.
   *
   * @return The final aggregated [LlmResponse], or null if no responses were processed.
   */
  suspend fun aggregate(): LlmResponse? = mutex.withLock {
    val currentResponse = response ?: return@withLock null
    val candidate = currentResponse.candidates.firstOrNull() ?: return@withLock null

    flushTextBufferToSequence()
    flushFunctionCallToSequence()

    if (partsSequence.isEmpty()) return@withLock null

    // Attach a trailing thought signature from the final chunk to the last aggregated part.
    candidate.content.parts.firstOrNull()?.thoughtSignature?.let { signature ->
      partsSequence[partsSequence.lastIndex] =
        partsSequence[partsSequence.lastIndex].copy(thoughtSignature = signature)
    }

    val finalFinishReason = finishReason ?: candidate.finishReason

    LlmResponse(
      content = Content(role = Role.MODEL, parts = partsSequence.toList()),
      usageMetadata = usageMetadata,
      finishReason = finalFinishReason,
      errorCode = finalFinishReason?.takeIf { it != FinishReason.STOP }?.name,
      errorMessage = if (finalFinishReason == FinishReason.STOP) null else candidate.finishMessage,
      partial = false,
      modelVersion = currentResponse.modelVersion,
      citationMetadata = citationMetadata,
      groundingMetadata = groundingMetadata,
    )
  }

  private fun flushTextBufferToSequence() {
    if (currentTextBuffer.isNotEmpty()) {
      partsSequence.add(
        Part(
          text = currentTextBuffer.toString(),
          thought = currentTextIsThought,
          thoughtSignature = currentThoughtSignature,
        )
      )
      currentTextBuffer.clear()
      currentTextIsThought = null
      currentThoughtSignature = null
    }
  }

  private fun processTextPart(part: Part) {
    // Flush on text-type change, then capture any signature so it rides on the flushed text.
    if (currentTextBuffer.isNotEmpty() && part.thought != currentTextIsThought) {
      flushTextBufferToSequence()
    }

    part.thoughtSignature?.let { currentThoughtSignature = it }
    currentTextIsThought = part.thought
    currentTextBuffer.append(part.text)
  }

  /** Returns a copy with a generated client id if this is a function call missing one. */
  private fun Part.ensureFunctionCallId(): Part {
    val fc = functionCall ?: return this
    if (!fc.id.isNullOrEmpty()) return this
    return copy(functionCall = fc.copy(id = FunctionCall.generateId()))
  }

  private fun processFunctionCallPart(part: Part) {
    val fc =
      part.functionCall
        ?: throw IllegalStateException(
          "Expected functionCall to be non-null when processing a function call part."
        )

    val hasName = fc.name.isNotEmpty()
    // A streamed call: it has partialArgs or willContinue, or is the nameless terminal
    // marker of an in-progress call. Gemini may end a call with a separate empty
    // willContinue=false part, so that marker completes it.
    val streamedPart =
      fc.partialArgs?.isNotEmpty() == true ||
        fc.willContinue == true ||
        (currentFcName != null && !hasName)
    if (streamedPart) {
      if (part.thoughtSignature != null && currentThoughtSignature == null) {
        currentThoughtSignature = part.thoughtSignature
      }
      processStreamingFunctionCall(fc)
    } else if (hasName) {
      // Non-streaming call. Safety guard: the model should terminate a streamed call with
      // willContinue=false before starting a new one; flush any still-in-progress call so it is
      // neither dropped nor merged.
      flushTextBufferToSequence()
      flushFunctionCallToSequence()
      partsSequence.add(part)
    }
  }

  private fun processStreamingFunctionCall(fc: FunctionCall) {
    if (fc.name.isNotEmpty()) {
      currentFcName = fc.name
    }
    if (fc.id != null) {
      currentFcId = fc.id
    }

    for (partialArg in fc.partialArgs.orEmpty()) {
      val jsonPath = partialArg.jsonPath ?: continue
      val pathKeys = jsonPath.toJsonPathKeys()
      val parsed = parsePartialArg(partialArg, pathKeys)
      if (parsed != null) {
        setValueByPath(pathKeys, parsed.value)
      }
    }

    if (fc.willContinue != true) {
      flushTextBufferToSequence()
      flushFunctionCallToSequence()
    }
  }

  private fun flushFunctionCallToSequence() {
    val name = currentFcName ?: return
    val fcPart =
      Part(
        functionCall = FunctionCall(name = name, args = currentFcArgs.toMap(), id = currentFcId),
        thoughtSignature = currentThoughtSignature,
      )
    partsSequence.add(fcPart)

    // Reset FC state
    currentFcName = null
    currentFcArgs.clear()
    currentFcId = null
    currentThoughtSignature = null
  }

  private class ParsedArg(val value: Any?)

  private fun String.toJsonPathKeys(): List<String> = removePrefix("$.").split('.')

  private fun parsePartialArg(partialArg: PartialArg, pathKeys: List<String>): ParsedArg? {
    return when {
      partialArg.stringValue != null -> {
        val chunk = partialArg.stringValue
        val existing = getValueByPathKeys(pathKeys) as? String
        ParsedArg(if (existing != null) existing + chunk else chunk)
      }
      partialArg.numberValue != null -> ParsedArg(partialArg.numberValue)
      partialArg.boolValue != null -> ParsedArg(partialArg.boolValue)
      partialArg.nullValue == true -> ParsedArg(null)
      else -> null
    }
  }

  private fun getValueByPathKeys(pathKeys: List<String>): Any? {
    return pathKeys.fold(currentFcArgs as Any?) { current, key ->
      (current as? Map<*, *>)?.get(key)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun setValueByPath(pathKeys: List<String>, value: Any?) {
    var current = currentFcArgs
    for (i in 0 until pathKeys.size - 1) {
      val key = pathKeys[i]
      current = current.getOrPut(key) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
    }
    current[pathKeys.last()] = value
  }
}
