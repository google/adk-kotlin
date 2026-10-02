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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.serialization.LenientByteArraySerializer
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Serializable

/** Configuration for generating content. */
@Serializable
data class GenerateContentConfig
@JvmOverloads
constructor(
  val tools: List<Tool>? = null,
  val labels: Map<String, String>? = null,
  val systemInstruction: Content? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val candidateCount: Int? = null,
  val maxOutputTokens: Int? = null,
  val stopSequences: List<String>? = null,
  val responseMimeType: String? = null,
  val responseSchema: Schema? = null,
  val thinkingConfig: ThinkingConfig? = null,
  val toolConfig: ToolConfig? = null,
  val safetySettings: List<SafetySetting>? = null,
  val mediaResolution: MediaResolution? = null,
  val serviceTier: ServiceTier? = null,
  val presencePenalty: Float? = null,
  val frequencyPenalty: Float? = null,
  val responseLogprobs: Boolean? = null,
  val routingConfig: GenerationConfigRoutingConfig? = null,
  val cachedContent: String? = null,
  val responseModalities: List<String>? = null,
  val seed: Int? = null,
  /**
   * An opaque token that resumes a generation the model paused with [FinishReason.CONTINUATION].
   * The framework typically sets it: ADK's Gemini model resumes paused generations itself. A token
   * set here, such as on an agent's config, is sent with every request using this config.
   */
  @Serializable(with = LenientByteArraySerializer::class) val continuationToken: ByteArray? = null,
) {
  // Hand-written because of the ByteArray: add new properties here and in hashCode, Builder,
  // toBuilder, and tests.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is GenerateContentConfig) return false

    return tools == other.tools &&
      labels == other.labels &&
      systemInstruction == other.systemInstruction &&
      // Compare Floats like generated equals so NaN and -0.0 stay consistent with hashCode().
      compareValues(temperature, other.temperature) == 0 &&
      compareValues(topP, other.topP) == 0 &&
      topK == other.topK &&
      candidateCount == other.candidateCount &&
      maxOutputTokens == other.maxOutputTokens &&
      stopSequences == other.stopSequences &&
      responseMimeType == other.responseMimeType &&
      responseSchema == other.responseSchema &&
      thinkingConfig == other.thinkingConfig &&
      toolConfig == other.toolConfig &&
      safetySettings == other.safetySettings &&
      mediaResolution == other.mediaResolution &&
      serviceTier == other.serviceTier &&
      compareValues(presencePenalty, other.presencePenalty) == 0 &&
      compareValues(frequencyPenalty, other.frequencyPenalty) == 0 &&
      responseLogprobs == other.responseLogprobs &&
      routingConfig == other.routingConfig &&
      cachedContent == other.cachedContent &&
      responseModalities == other.responseModalities &&
      seed == other.seed &&
      continuationToken.contentEquals(other.continuationToken)
  }

  override fun hashCode(): Int {
    var result = tools?.hashCode() ?: 0
    result = 31 * result + (labels?.hashCode() ?: 0)
    result = 31 * result + (systemInstruction?.hashCode() ?: 0)
    result = 31 * result + (temperature?.hashCode() ?: 0)
    result = 31 * result + (topP?.hashCode() ?: 0)
    result = 31 * result + (topK?.hashCode() ?: 0)
    result = 31 * result + (candidateCount?.hashCode() ?: 0)
    result = 31 * result + (maxOutputTokens?.hashCode() ?: 0)
    result = 31 * result + (stopSequences?.hashCode() ?: 0)
    result = 31 * result + (responseMimeType?.hashCode() ?: 0)
    result = 31 * result + (responseSchema?.hashCode() ?: 0)
    result = 31 * result + (thinkingConfig?.hashCode() ?: 0)
    result = 31 * result + (toolConfig?.hashCode() ?: 0)
    result = 31 * result + (safetySettings?.hashCode() ?: 0)
    result = 31 * result + (mediaResolution?.hashCode() ?: 0)
    result = 31 * result + (serviceTier?.hashCode() ?: 0)
    result = 31 * result + (presencePenalty?.hashCode() ?: 0)
    result = 31 * result + (frequencyPenalty?.hashCode() ?: 0)
    result = 31 * result + (responseLogprobs?.hashCode() ?: 0)
    result = 31 * result + (routingConfig?.hashCode() ?: 0)
    result = 31 * result + (cachedContent?.hashCode() ?: 0)
    result = 31 * result + (responseModalities?.hashCode() ?: 0)
    result = 31 * result + (seed?.hashCode() ?: 0)
    result = 31 * result + (continuationToken?.contentHashCode() ?: 0)
    return result
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .tools(tools)
      .labels(labels)
      .systemInstruction(systemInstruction)
      .temperature(temperature)
      .topP(topP)
      .topK(topK)
      .candidateCount(candidateCount)
      .maxOutputTokens(maxOutputTokens)
      .stopSequences(stopSequences)
      .responseMimeType(responseMimeType)
      .responseSchema(responseSchema)
      .thinkingConfig(thinkingConfig)
      .toolConfig(toolConfig)
      .safetySettings(safetySettings)
      .mediaResolution(mediaResolution)
      .serviceTier(serviceTier)
      .presencePenalty(presencePenalty)
      .frequencyPenalty(frequencyPenalty)
      .responseLogprobs(responseLogprobs)
      .routingConfig(routingConfig)
      .cachedContent(cachedContent)
      .responseModalities(responseModalities)
      .seed(seed)
      .continuationToken(continuationToken)

  /**
   * Fluent builder for [GenerateContentConfig], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var tools: List<Tool>? = null
    private var labels: Map<String, String>? = null
    private var systemInstruction: Content? = null
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var candidateCount: Int? = null
    private var maxOutputTokens: Int? = null
    private var stopSequences: List<String>? = null
    private var responseMimeType: String? = null
    private var responseSchema: Schema? = null
    private var thinkingConfig: ThinkingConfig? = null
    private var toolConfig: ToolConfig? = null
    private var safetySettings: List<SafetySetting>? = null
    private var mediaResolution: MediaResolution? = null
    private var serviceTier: ServiceTier? = null
    private var presencePenalty: Float? = null
    private var frequencyPenalty: Float? = null
    private var responseLogprobs: Boolean? = null
    private var routingConfig: GenerationConfigRoutingConfig? = null
    private var cachedContent: String? = null
    private var responseModalities: List<String>? = null
    private var seed: Int? = null
    private var continuationToken: ByteArray? = null

    fun tools(tools: List<Tool>?): Builder = apply { this.tools = tools }

    fun labels(labels: Map<String, String>?): Builder = apply { this.labels = labels }

    fun systemInstruction(systemInstruction: Content?): Builder = apply {
      this.systemInstruction = systemInstruction
    }

    fun temperature(temperature: Float?): Builder = apply { this.temperature = temperature }

    fun topP(topP: Float?): Builder = apply { this.topP = topP }

    fun topK(topK: Int?): Builder = apply { this.topK = topK }

    fun candidateCount(candidateCount: Int?): Builder = apply {
      this.candidateCount = candidateCount
    }

    fun maxOutputTokens(maxOutputTokens: Int?): Builder = apply {
      this.maxOutputTokens = maxOutputTokens
    }

    fun stopSequences(stopSequences: List<String>?): Builder = apply {
      this.stopSequences = stopSequences
    }

    fun responseMimeType(responseMimeType: String?): Builder = apply {
      this.responseMimeType = responseMimeType
    }

    fun responseSchema(responseSchema: Schema?): Builder = apply {
      this.responseSchema = responseSchema
    }

    fun thinkingConfig(thinkingConfig: ThinkingConfig?): Builder = apply {
      this.thinkingConfig = thinkingConfig
    }

    fun toolConfig(toolConfig: ToolConfig?): Builder = apply { this.toolConfig = toolConfig }

    fun safetySettings(safetySettings: List<SafetySetting>?): Builder = apply {
      this.safetySettings = safetySettings
    }

    fun mediaResolution(mediaResolution: MediaResolution?): Builder = apply {
      this.mediaResolution = mediaResolution
    }

    fun serviceTier(serviceTier: ServiceTier?): Builder = apply { this.serviceTier = serviceTier }

    fun presencePenalty(presencePenalty: Float?): Builder = apply {
      this.presencePenalty = presencePenalty
    }

    fun frequencyPenalty(frequencyPenalty: Float?): Builder = apply {
      this.frequencyPenalty = frequencyPenalty
    }

    fun responseLogprobs(responseLogprobs: Boolean?): Builder = apply {
      this.responseLogprobs = responseLogprobs
    }

    fun routingConfig(routingConfig: GenerationConfigRoutingConfig?): Builder = apply {
      this.routingConfig = routingConfig
    }

    fun cachedContent(cachedContent: String?): Builder = apply {
      this.cachedContent = cachedContent
    }

    fun responseModalities(responseModalities: List<String>?): Builder = apply {
      this.responseModalities = responseModalities
    }

    fun seed(seed: Int?): Builder = apply { this.seed = seed }

    fun continuationToken(continuationToken: ByteArray?): Builder = apply {
      this.continuationToken = continuationToken
    }

    fun build(): GenerateContentConfig =
      GenerateContentConfig(
        tools = tools,
        labels = labels,
        systemInstruction = systemInstruction,
        temperature = temperature,
        topP = topP,
        topK = topK,
        candidateCount = candidateCount,
        maxOutputTokens = maxOutputTokens,
        stopSequences = stopSequences,
        responseMimeType = responseMimeType,
        responseSchema = responseSchema,
        thinkingConfig = thinkingConfig,
        toolConfig = toolConfig,
        safetySettings = safetySettings,
        mediaResolution = mediaResolution,
        serviceTier = serviceTier,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        responseLogprobs = responseLogprobs,
        routingConfig = routingConfig,
        cachedContent = cachedContent,
        responseModalities = responseModalities,
        seed = seed,
        continuationToken = continuationToken,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
