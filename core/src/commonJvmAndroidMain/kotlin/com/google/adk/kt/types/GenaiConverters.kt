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

/**
 * Converters to map between the Kotlin ADK [com.google.adk.kt.types] and the underlying
 * [com.google.genai.types] from the GenAI SDK.
 */
package com.google.adk.kt.types

import kotlin.jvm.optionals.getOrNull
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

// --- Blob ---
/** Converts a [com.google.genai.types.Blob] from the GenAI SDK to an ADK [Blob]. */
internal fun com.google.genai.types.Blob.fromGenaiSdk(): Blob =
  Blob(
    mimeType = mimeType().getOrNull(),
    displayName = displayName().getOrNull(),
    data = data().getOrNull(),
  )

/** Converts an ADK [Blob] to a [com.google.genai.types.Blob] for the GenAI SDK. */
internal fun Blob.toGenaiSdk(): com.google.genai.types.Blob =
  com.google.genai.types.Blob.builder()
    .apply {
      this@toGenaiSdk.mimeType?.let { mimeType(it) }
      this@toGenaiSdk.displayName?.let { displayName(it) }
      this@toGenaiSdk.data?.let { data(it) }
    }
    .build()

// --- Candidate ---
/** Converts a [com.google.genai.types.Candidate] from the GenAI SDK to an ADK [Candidate]. */
internal fun com.google.genai.types.Candidate.fromGenaiSdk(): Candidate =
  Candidate(
    content = content().getOrNull()?.fromGenaiSdk() ?: Content(),
    finishReason = finishReason().getOrNull()?.toKt(),
    finishMessage = finishMessage().getOrNull(),
    citationMetadata = citationMetadata().getOrNull()?.fromGenaiSdk(),
    groundingMetadata = groundingMetadata().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [Candidate] to a [com.google.genai.types.Candidate] for the GenAI SDK. */
internal fun Candidate.toGenaiSdk(): com.google.genai.types.Candidate =
  com.google.genai.types.Candidate.builder()
    .apply {
      content(this@toGenaiSdk.content.toGenaiSdk())
      this@toGenaiSdk.finishReason?.let { finishReason(it.toJava()) }
      this@toGenaiSdk.finishMessage?.let { finishMessage(it) }
      this@toGenaiSdk.citationMetadata?.let { citationMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.groundingMetadata?.let { groundingMetadata(it.toGenaiSdk()) }
    }
    .build()

// --- Citation ---
/** Converts a [com.google.genai.types.Citation] from the GenAI SDK to an ADK [Citation]. */
internal fun com.google.genai.types.Citation.fromGenaiSdk(): Citation =
  Citation(title = title().getOrNull())

/** Converts an ADK [Citation] to a [com.google.genai.types.Citation] for the GenAI SDK. */
internal fun Citation.toGenaiSdk(): com.google.genai.types.Citation =
  com.google.genai.types.Citation.builder()
    .apply { this@toGenaiSdk.title?.let { title(it) } }
    .build()

// --- CitationMetadata ---
/**
 * Converts a [com.google.genai.types.CitationMetadata] from the GenAI SDK to an ADK
 * [CitationMetadata].
 */
internal fun com.google.genai.types.CitationMetadata.fromGenaiSdk(): CitationMetadata =
  CitationMetadata(
    citationSources = citations().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList()
  )

/**
 * Converts an ADK [CitationMetadata] to a [com.google.genai.types.CitationMetadata] for the GenAI
 * SDK.
 */
internal fun CitationMetadata.toGenaiSdk(): com.google.genai.types.CitationMetadata =
  com.google.genai.types.CitationMetadata.builder()
    .apply { citations(this@toGenaiSdk.citationSources.map { it.toGenaiSdk() }) }
    .build()

// --- Content ---
/** Converts a [com.google.genai.types.Content] from the GenAI SDK to an ADK [Content]. */
internal fun com.google.genai.types.Content.fromGenaiSdk(): Content =
  Content(
    role = role().getOrNull(),
    parts = parts().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList(),
  )

/** Converts an ADK [Content] to a [com.google.genai.types.Content] for the GenAI SDK. */
internal fun Content.toGenaiSdk(): com.google.genai.types.Content =
  com.google.genai.types.Content.builder()
    .apply {
      this@toGenaiSdk.role?.let { role(it) }
      parts(this@toGenaiSdk.parts.map { it.toGenaiSdk() })
    }
    .build()

// --- FileData ---
/** Converts a [com.google.genai.types.FileData] from the GenAI SDK to an ADK [FileData]. */
internal fun com.google.genai.types.FileData.fromGenaiSdk(): FileData =
  FileData(
    mimeType = mimeType().getOrNull(),
    displayName = displayName().getOrNull(),
    fileUri = fileUri().getOrNull(),
  )

/** Converts an ADK [FileData] to a [com.google.genai.types.FileData] for the GenAI SDK. */
internal fun FileData.toGenaiSdk(): com.google.genai.types.FileData =
  com.google.genai.types.FileData.builder()
    .apply {
      this@toGenaiSdk.mimeType?.let { mimeType(it) }
      this@toGenaiSdk.displayName?.let { displayName(it) }
      this@toGenaiSdk.fileUri?.let { fileUri(it) }
    }
    .build()

// --- FunctionCall ---
/** Converts a [com.google.genai.types.FunctionCall] from the GenAI SDK to an ADK [FunctionCall]. */
internal fun com.google.genai.types.FunctionCall.fromGenaiSdk(): FunctionCall =
  FunctionCall(
    name = name().getOrNull() ?: "",
    args = args().getOrNull() ?: emptyMap(),
    id = id().getOrNull(),
    partialArgs = partialArgs().getOrNull()?.map { it.fromGenaiSdk() },
    willContinue = willContinue().getOrNull(),
  )

/** Converts an ADK [FunctionCall] to a [com.google.genai.types.FunctionCall] for the GenAI SDK. */
internal fun FunctionCall.toGenaiSdk(): com.google.genai.types.FunctionCall =
  com.google.genai.types.FunctionCall.builder()
    .apply {
      name(this@toGenaiSdk.name)
      args(this@toGenaiSdk.args)
      this@toGenaiSdk.id?.let { id(it) }
      this@toGenaiSdk.partialArgs
        ?.takeIf { it.isNotEmpty() }
        ?.let { partialArgs(it.map { p -> p.toGenaiSdk() }) }
      this@toGenaiSdk.willContinue?.takeIf { it }?.let { willContinue(it) }
    }
    .build()

// --- FunctionDeclaration ---
/**
 * Converts a [com.google.genai.types.FunctionDeclaration] from the GenAI SDK to an ADK
 * [FunctionDeclaration].
 */
internal fun com.google.genai.types.FunctionDeclaration.fromGenaiSdk(): FunctionDeclaration =
  FunctionDeclaration(
    name = name().get(),
    description = description().get(),
    parameters = parameters().getOrNull()?.toKtSchema(),
  )

/**
 * Converts an ADK [FunctionDeclaration] to a [com.google.genai.types.FunctionDeclaration] for the
 * GenAI SDK.
 */
internal fun FunctionDeclaration.toGenaiSdk(): com.google.genai.types.FunctionDeclaration =
  com.google.genai.types.FunctionDeclaration.builder()
    .apply {
      name(this@toGenaiSdk.name)
      description(this@toGenaiSdk.description)
      this@toGenaiSdk.parameters?.let { parameters(it.toGenAiSchema()) }
    }
    .build()

// --- FunctionResponse ---
/**
 * Converts a [com.google.genai.types.FunctionResponse] from the GenAI SDK to an ADK
 * [FunctionResponse].
 */
internal fun com.google.genai.types.FunctionResponse.fromGenaiSdk(): FunctionResponse =
  FunctionResponse(
    name = name().get(),
    response = response().getOrNull() ?: emptyMap(),
    id = id().getOrNull(),
  )

/**
 * Converts an ADK [FunctionResponse] to a [com.google.genai.types.FunctionResponse] for the GenAI
 * SDK.
 */
internal fun FunctionResponse.toGenaiSdk(): com.google.genai.types.FunctionResponse =
  com.google.genai.types.FunctionResponse.builder()
    .apply {
      name(this@toGenaiSdk.name)
      response(this@toGenaiSdk.response)
      this@toGenaiSdk.id?.let { id(it) }
    }
    .build()

// --- GenerateContentConfig ---
/**
 * Converts a [com.google.genai.types.GenerateContentConfig] from the GenAI SDK to an ADK
 * [GenerateContentConfig].
 */
internal fun com.google.genai.types.GenerateContentConfig.fromGenaiSdk(): GenerateContentConfig =
  GenerateContentConfig(
    tools = tools().getOrNull()?.map { it.fromGenaiSdk() },
    labels = labels().getOrNull(),
    systemInstruction = systemInstruction().getOrNull()?.fromGenaiSdk(),
    temperature = temperature().getOrNull(),
    topP = topP().getOrNull(),
    topK = topK().getOrNull()?.toInt(),
    candidateCount = candidateCount().getOrNull(),
    maxOutputTokens = maxOutputTokens().getOrNull(),
    stopSequences = stopSequences().getOrNull(),
    responseMimeType = responseMimeType().getOrNull(),
    responseSchema = responseSchema().getOrNull()?.toKtSchema(),
    thinkingConfig = thinkingConfig().getOrNull()?.fromGenaiSdk(),
    toolConfig = toolConfig().getOrNull()?.fromGenaiSdk(),
    safetySettings = safetySettings().getOrNull()?.map { it.fromGenaiSdk() },
    mediaResolution = mediaResolution().getOrNull()?.toKt(),
    serviceTier = serviceTier().getOrNull()?.toKt(),
    presencePenalty = presencePenalty().getOrNull(),
    frequencyPenalty = frequencyPenalty().getOrNull(),
    responseLogprobs = responseLogprobs().getOrNull(),
  )

/**
 * Converts an ADK [GenerateContentConfig] to a [com.google.genai.types.GenerateContentConfig] for
 * the GenAI SDK.
 */
internal fun GenerateContentConfig.toGenaiSdk(): com.google.genai.types.GenerateContentConfig =
  com.google.genai.types.GenerateContentConfig.builder()
    .apply {
      this@toGenaiSdk.tools?.let { tools(it.map { t -> t.toGenaiSdk() }) }
      this@toGenaiSdk.labels?.let { labels(it) }
      this@toGenaiSdk.systemInstruction?.let { systemInstruction(it.toGenaiSdk()) }
      this@toGenaiSdk.temperature?.let { temperature(it) }
      this@toGenaiSdk.topP?.let { topP(it) }
      this@toGenaiSdk.topK?.let { topK(it.toFloat()) }
      this@toGenaiSdk.candidateCount?.let { candidateCount(it) }
      this@toGenaiSdk.maxOutputTokens?.let { maxOutputTokens(it) }
      this@toGenaiSdk.stopSequences?.let { stopSequences(it) }
      this@toGenaiSdk.responseMimeType?.let { responseMimeType(it) }
      this@toGenaiSdk.responseSchema?.let { responseSchema(it.toGenAiSchema()) }
      this@toGenaiSdk.thinkingConfig?.let { thinkingConfig(it.toGenaiSdk()) }
      this@toGenaiSdk.toolConfig?.let { toolConfig(it.toGenaiSdk()) }
      this@toGenaiSdk.safetySettings?.let { safetySettings(it.map { s -> s.toGenaiSdk() }) }
      this@toGenaiSdk.mediaResolution?.let { mediaResolution(it.toJava()) }
      this@toGenaiSdk.serviceTier?.let { serviceTier(it.toJava()) }
      this@toGenaiSdk.presencePenalty?.let { presencePenalty(it) }
      this@toGenaiSdk.frequencyPenalty?.let { frequencyPenalty(it) }
      this@toGenaiSdk.responseLogprobs?.let { responseLogprobs(it) }
    }
    .build()

// --- FunctionCallingConfig ---
/**
 * Converts a [com.google.genai.types.FunctionCallingConfig] from the GenAI SDK to an ADK
 * [FunctionCallingConfig].
 */
internal fun com.google.genai.types.FunctionCallingConfig.fromGenaiSdk(): FunctionCallingConfig =
  FunctionCallingConfig(allowedFunctionNames = allowedFunctionNames().getOrNull())

/**
 * Converts an ADK [FunctionCallingConfig] to a [com.google.genai.types.FunctionCallingConfig] for
 * the GenAI SDK.
 */
internal fun FunctionCallingConfig.toGenaiSdk(): com.google.genai.types.FunctionCallingConfig =
  com.google.genai.types.FunctionCallingConfig.builder()
    .apply { this@toGenaiSdk.allowedFunctionNames?.let { allowedFunctionNames(it) } }
    .build()

// --- ToolConfig ---
/** Converts a [com.google.genai.types.ToolConfig] from the GenAI SDK to an ADK [ToolConfig]. */
internal fun com.google.genai.types.ToolConfig.fromGenaiSdk(): ToolConfig =
  ToolConfig(functionCallingConfig = functionCallingConfig().getOrNull()?.fromGenaiSdk())

/** Converts an ADK [ToolConfig] to a [com.google.genai.types.ToolConfig] for the GenAI SDK. */
internal fun ToolConfig.toGenaiSdk(): com.google.genai.types.ToolConfig =
  com.google.genai.types.ToolConfig.builder()
    .apply { this@toGenaiSdk.functionCallingConfig?.let { functionCallingConfig(it.toGenaiSdk()) } }
    .build()

// --- SafetySetting ---
/**
 * Converts a [com.google.genai.types.SafetySetting] from the GenAI SDK to an ADK [SafetySetting].
 */
internal fun com.google.genai.types.SafetySetting.fromGenaiSdk(): SafetySetting =
  SafetySetting(
    category = category().getOrNull()?.toKt(),
    threshold = threshold().getOrNull()?.toKt(),
  )

/**
 * Converts an ADK [SafetySetting] to a [com.google.genai.types.SafetySetting] for the GenAI SDK.
 */
internal fun SafetySetting.toGenaiSdk(): com.google.genai.types.SafetySetting =
  com.google.genai.types.SafetySetting.builder()
    .apply {
      this@toGenaiSdk.category?.let { category(it.toJava()) }
      this@toGenaiSdk.threshold?.let { threshold(it.toJava()) }
    }
    .build()

// --- GenerateContentResponse ---
/**
 * Converts a [com.google.genai.types.GenerateContentResponse] from the GenAI SDK to an ADK
 * [GenerateContentResponse].
 */
internal fun com.google.genai.types.GenerateContentResponse.fromGenaiSdk():
  GenerateContentResponse =
  GenerateContentResponse(
    candidates = candidates().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList(),
    promptFeedback = promptFeedback().getOrNull()?.fromGenaiSdk(),
    usageMetadata = usageMetadata().getOrNull()?.fromGenaiSdk(),
    modelVersion = modelVersion().getOrNull(),
  )

/**
 * Converts an ADK [GenerateContentResponse] to a [com.google.genai.types.GenerateContentResponse]
 * for the GenAI SDK.
 */
internal fun GenerateContentResponse.toGenaiSdk(): com.google.genai.types.GenerateContentResponse =
  com.google.genai.types.GenerateContentResponse.builder()
    .apply {
      candidates(this@toGenaiSdk.candidates.map { it.toGenaiSdk() })
      this@toGenaiSdk.promptFeedback?.let { promptFeedback(it.toGenaiSdk()) }
      this@toGenaiSdk.usageMetadata?.let { usageMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.modelVersion?.let { modelVersion(it) }
    }
    .build()

// --- GroundingMetadata ---
/**
 * Converts a [com.google.genai.types.GroundingMetadata] from the GenAI SDK to an ADK
 * [GroundingMetadata].
 */
internal fun com.google.genai.types.GroundingMetadata.fromGenaiSdk(): GroundingMetadata =
  GroundingMetadata(
    imageSearchQueries = imageSearchQueries().getOrNull() ?: emptyList(),
    groundingChunks = groundingChunks().getOrNull()?.map { it.fromGenaiSdk() },
    groundingSupports = groundingSupports().getOrNull()?.map { it.fromGenaiSdk() },
    webSearchQueries = webSearchQueries().getOrNull(),
    searchEntryPoint = searchEntryPoint().getOrNull()?.fromGenaiSdk(),
    retrievalMetadata = retrievalMetadata().getOrNull()?.fromGenaiSdk(),
  )

/**
 * Converts an ADK [GroundingMetadata] to a [com.google.genai.types.GroundingMetadata] for the GenAI
 * SDK.
 */
internal fun GroundingMetadata.toGenaiSdk(): com.google.genai.types.GroundingMetadata =
  com.google.genai.types.GroundingMetadata.builder()
    .apply {
      imageSearchQueries(this@toGenaiSdk.imageSearchQueries)
      this@toGenaiSdk.groundingChunks?.let { groundingChunks(it.map { c -> c.toGenaiSdk() }) }
      this@toGenaiSdk.groundingSupports?.let { groundingSupports(it.map { s -> s.toGenaiSdk() }) }
      this@toGenaiSdk.webSearchQueries?.let { webSearchQueries(it) }
      this@toGenaiSdk.searchEntryPoint?.let { searchEntryPoint(it.toGenaiSdk()) }
      this@toGenaiSdk.retrievalMetadata?.let { retrievalMetadata(it.toGenaiSdk()) }
    }
    .build()

// --- GroundingChunk ---
/**
 * Converts a [com.google.genai.types.GroundingChunk] from the GenAI SDK to an ADK [GroundingChunk].
 */
internal fun com.google.genai.types.GroundingChunk.fromGenaiSdk(): GroundingChunk =
  GroundingChunk(
    web = web().getOrNull()?.fromGenaiSdk(),
    retrievedContext = retrievedContext().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [GroundingChunk] to a [com.google.genai.types.GroundingChunk] for the SDK. */
internal fun GroundingChunk.toGenaiSdk(): com.google.genai.types.GroundingChunk =
  com.google.genai.types.GroundingChunk.builder()
    .apply {
      this@toGenaiSdk.web?.let { web(it.toGenaiSdk()) }
      this@toGenaiSdk.retrievedContext?.let { retrievedContext(it.toGenaiSdk()) }
    }
    .build()

// --- GroundingChunkWeb ---
/**
 * Converts a [com.google.genai.types.GroundingChunkWeb] from the GenAI SDK to an ADK
 * [GroundingChunkWeb].
 */
internal fun com.google.genai.types.GroundingChunkWeb.fromGenaiSdk(): GroundingChunkWeb =
  GroundingChunkWeb(
    uri = uri().getOrNull(),
    title = title().getOrNull(),
    domain = domain().getOrNull(),
  )

/**
 * Converts an ADK [GroundingChunkWeb] to a [com.google.genai.types.GroundingChunkWeb] for the SDK.
 */
internal fun GroundingChunkWeb.toGenaiSdk(): com.google.genai.types.GroundingChunkWeb =
  com.google.genai.types.GroundingChunkWeb.builder()
    .apply {
      this@toGenaiSdk.uri?.let { uri(it) }
      this@toGenaiSdk.title?.let { title(it) }
      this@toGenaiSdk.domain?.let { domain(it) }
    }
    .build()

// --- GroundingChunkRetrievedContext ---
/**
 * Converts a [com.google.genai.types.GroundingChunkRetrievedContext] from the GenAI SDK to an ADK
 * [GroundingChunkRetrievedContext].
 */
internal fun com.google.genai.types.GroundingChunkRetrievedContext.fromGenaiSdk():
  GroundingChunkRetrievedContext =
  GroundingChunkRetrievedContext(
    uri = uri().getOrNull(),
    title = title().getOrNull(),
    text = text().getOrNull(),
  )

/**
 * Converts an ADK [GroundingChunkRetrievedContext] to a
 * [com.google.genai.types.GroundingChunkRetrievedContext] for the GenAI SDK.
 */
internal fun GroundingChunkRetrievedContext.toGenaiSdk():
  com.google.genai.types.GroundingChunkRetrievedContext =
  com.google.genai.types.GroundingChunkRetrievedContext.builder()
    .apply {
      this@toGenaiSdk.uri?.let { uri(it) }
      this@toGenaiSdk.title?.let { title(it) }
      this@toGenaiSdk.text?.let { text(it) }
    }
    .build()

// --- GroundingSupport ---
/**
 * Converts a [com.google.genai.types.GroundingSupport] from the GenAI SDK to an ADK
 * [GroundingSupport].
 */
internal fun com.google.genai.types.GroundingSupport.fromGenaiSdk(): GroundingSupport =
  GroundingSupport(
    segment = segment().getOrNull()?.fromGenaiSdk(),
    groundingChunkIndices = groundingChunkIndices().getOrNull(),
    confidenceScores = confidenceScores().getOrNull(),
  )

/**
 * Converts an ADK [GroundingSupport] to a [com.google.genai.types.GroundingSupport] for the SDK.
 */
internal fun GroundingSupport.toGenaiSdk(): com.google.genai.types.GroundingSupport =
  com.google.genai.types.GroundingSupport.builder()
    .apply {
      this@toGenaiSdk.segment?.let { segment(it.toGenaiSdk()) }
      this@toGenaiSdk.groundingChunkIndices?.let { groundingChunkIndices(it) }
      this@toGenaiSdk.confidenceScores?.let { confidenceScores(it) }
    }
    .build()

// --- Segment ---
/** Converts a [com.google.genai.types.Segment] from the GenAI SDK to an ADK [Segment]. */
internal fun com.google.genai.types.Segment.fromGenaiSdk(): Segment =
  Segment(
    startIndex = startIndex().getOrNull(),
    endIndex = endIndex().getOrNull(),
    partIndex = partIndex().getOrNull(),
    text = text().getOrNull(),
  )

/** Converts an ADK [Segment] to a [com.google.genai.types.Segment] for the GenAI SDK. */
internal fun Segment.toGenaiSdk(): com.google.genai.types.Segment =
  com.google.genai.types.Segment.builder()
    .apply {
      this@toGenaiSdk.startIndex?.let { startIndex(it) }
      this@toGenaiSdk.endIndex?.let { endIndex(it) }
      this@toGenaiSdk.partIndex?.let { partIndex(it) }
      this@toGenaiSdk.text?.let { text(it) }
    }
    .build()

// --- SearchEntryPoint ---
/**
 * Converts a [com.google.genai.types.SearchEntryPoint] from the GenAI SDK to an ADK
 * [SearchEntryPoint].
 */
internal fun com.google.genai.types.SearchEntryPoint.fromGenaiSdk(): SearchEntryPoint =
  SearchEntryPoint(renderedContent = renderedContent().getOrNull())

/**
 * Converts an ADK [SearchEntryPoint] to a [com.google.genai.types.SearchEntryPoint] for the SDK.
 */
internal fun SearchEntryPoint.toGenaiSdk(): com.google.genai.types.SearchEntryPoint =
  com.google.genai.types.SearchEntryPoint.builder()
    .apply { this@toGenaiSdk.renderedContent?.let { renderedContent(it) } }
    .build()

// --- RetrievalMetadata ---
/**
 * Converts a [com.google.genai.types.RetrievalMetadata] from the GenAI SDK to an ADK
 * [RetrievalMetadata].
 */
internal fun com.google.genai.types.RetrievalMetadata.fromGenaiSdk(): RetrievalMetadata =
  RetrievalMetadata(
    googleSearchDynamicRetrievalScore = googleSearchDynamicRetrievalScore().getOrNull()
  )

/**
 * Converts an ADK [RetrievalMetadata] to a [com.google.genai.types.RetrievalMetadata] for the GenAI
 * SDK.
 */
internal fun RetrievalMetadata.toGenaiSdk(): com.google.genai.types.RetrievalMetadata =
  com.google.genai.types.RetrievalMetadata.builder()
    .apply {
      this@toGenaiSdk.googleSearchDynamicRetrievalScore?.let {
        googleSearchDynamicRetrievalScore(it)
      }
    }
    .build()

// --- PromptFeedback ---
/**
 * Converts a [com.google.genai.types.GenerateContentResponsePromptFeedback] from the GenAI SDK to
 * an ADK [PromptFeedback].
 */
internal fun com.google.genai.types.GenerateContentResponsePromptFeedback.fromGenaiSdk():
  PromptFeedback =
  PromptFeedback(
    blockReason = blockReason().getOrNull()?.toKt(),
    blockReasonMessage = blockReasonMessage().getOrNull(),
  )

/**
 * Converts an ADK [PromptFeedback] to a
 * [com.google.genai.types.GenerateContentResponsePromptFeedback] for the GenAI SDK.
 */
internal fun PromptFeedback.toGenaiSdk():
  com.google.genai.types.GenerateContentResponsePromptFeedback =
  com.google.genai.types.GenerateContentResponsePromptFeedback.builder()
    .apply {
      this@toGenaiSdk.blockReason?.let { blockReason(it.toJava()) }
      this@toGenaiSdk.blockReasonMessage?.let { blockReasonMessage(it) }
    }
    .build()

// --- GoogleMaps ---
/** Converts a [com.google.genai.types.GoogleMaps] from the GenAI SDK to an ADK [GoogleMaps]. */
internal fun com.google.genai.types.GoogleMaps.fromGenaiSdk(): GoogleMaps =
  GoogleMaps(enableWidget = enableWidget().getOrNull())

/** Converts an ADK [GoogleMaps] to a [com.google.genai.types.GoogleMaps] for the GenAI SDK. */
internal fun GoogleMaps.toGenaiSdk(): com.google.genai.types.GoogleMaps =
  com.google.genai.types.GoogleMaps.builder()
    .apply { this@toGenaiSdk.enableWidget?.let { enableWidget(it) } }
    .build()

// --- GoogleSearch ---
/** Converts a [com.google.genai.types.GoogleSearch] from the GenAI SDK to an ADK [GoogleSearch]. */
internal fun com.google.genai.types.GoogleSearch.fromGenaiSdk(): GoogleSearch =
  GoogleSearch(excludeDomains = excludeDomains().getOrNull() ?: emptyList())

/** Converts an ADK [GoogleSearch] to a [com.google.genai.types.GoogleSearch] for the GenAI SDK. */
internal fun GoogleSearch.toGenaiSdk(): com.google.genai.types.GoogleSearch =
  com.google.genai.types.GoogleSearch.builder()
    .apply { this@toGenaiSdk.excludeDomains.takeIf { it.isNotEmpty() }?.let { excludeDomains(it) } }
    .build()

// --- Schema ---
/** Converts a [com.google.genai.types.Schema] from the GenAI SDK to an ADK [Schema]. */
internal fun com.google.genai.types.Schema.toKtSchema(): Schema =
  Schema(
    type = type().getOrNull()?.toString()?.let { Type.valueOf(it) },
    properties = properties().getOrNull()?.mapValues { it.value.toKtSchema() },
    items = items().getOrNull()?.toKtSchema(),
    required = required().getOrNull(),
    description = description().getOrNull(),
    enum = enum_().getOrNull(),
  )

/** Converts an ADK [Schema] to a [com.google.genai.types.Schema] for the GenAI SDK. */
internal fun Schema.toGenAiSchema(): com.google.genai.types.Schema =
  com.google.genai.types.Schema.builder()
    .apply {
      this@toGenAiSchema.type?.let { type(it.name) }
      this@toGenAiSchema.properties?.let { props ->
        properties(props.mapValues { it.value.toGenAiSchema() })
      }
      this@toGenAiSchema.items?.let { items(it.toGenAiSchema()) }
      this@toGenAiSchema.required?.let { required(it) }
      this@toGenAiSchema.description?.let { description(it) }
      this@toGenAiSchema.enum?.let { enum_(it) }
    }
    .build()

// --- UrlContext ---
/** Converts a [com.google.genai.types.UrlContext] from the GenAI SDK to an ADK [UrlContext]. */
internal fun com.google.genai.types.UrlContext.fromGenaiSdk(): UrlContext = UrlContext()

/** Converts an ADK [UrlContext] to a [com.google.genai.types.UrlContext] for the GenAI SDK. */
internal fun UrlContext.toGenaiSdk(): com.google.genai.types.UrlContext =
  com.google.genai.types.UrlContext.builder().build()

// --- Tool ---
/** Converts a [com.google.genai.types.Tool] from the GenAI SDK to an ADK [Tool]. */
internal fun com.google.genai.types.Tool.fromGenaiSdk(): Tool =
  Tool(
    functionDeclarations = functionDeclarations().getOrNull()?.map { it.fromGenaiSdk() },
    googleSearch = googleSearch().getOrNull()?.fromGenaiSdk(),
    googleMaps = googleMaps().getOrNull()?.fromGenaiSdk(),
    urlContext = urlContext().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [Tool] to a [com.google.genai.types.Tool] for the GenAI SDK. */
internal fun Tool.toGenaiSdk(): com.google.genai.types.Tool =
  com.google.genai.types.Tool.builder()
    .apply {
      this@toGenaiSdk.functionDeclarations?.let {
        functionDeclarations(it.map { f -> f.toGenaiSdk() })
      }
      this@toGenaiSdk.googleSearch?.let { googleSearch(it.toGenaiSdk()) }
      this@toGenaiSdk.googleMaps?.let { googleMaps(it.toGenaiSdk()) }
      this@toGenaiSdk.urlContext?.let { urlContext(it.toGenaiSdk()) }
    }
    .build()

// --- ModalityTokenCount ---
/**
 * Converts a [com.google.genai.types.ModalityTokenCount] from the GenAI SDK to an ADK
 * [ModalityTokenCount].
 */
internal fun com.google.genai.types.ModalityTokenCount.fromGenaiSdk(): ModalityTokenCount =
  ModalityTokenCount(
    modality = modality().getOrNull()?.toKt(),
    tokenCount = tokenCount().getOrNull(),
  )

/**
 * Converts an ADK [ModalityTokenCount] to a [com.google.genai.types.ModalityTokenCount] for the
 * GenAI SDK.
 */
internal fun ModalityTokenCount.toGenaiSdk(): com.google.genai.types.ModalityTokenCount =
  com.google.genai.types.ModalityTokenCount.builder()
    .apply {
      this@toGenaiSdk.modality?.let { modality(it.toJava()) }
      this@toGenaiSdk.tokenCount?.let { tokenCount(it) }
    }
    .build()

// --- UsageMetadata ---
/**
 * Converts a [com.google.genai.types.GenerateContentResponseUsageMetadata] from the GenAI SDK to an
 * ADK [UsageMetadata].
 */
internal fun com.google.genai.types.GenerateContentResponseUsageMetadata.fromGenaiSdk():
  UsageMetadata =
  UsageMetadata(
    promptTokenCount = promptTokenCount().getOrNull(),
    candidatesTokenCount = candidatesTokenCount().getOrNull(),
    totalTokenCount = totalTokenCount().getOrNull(),
    thoughtsTokenCount = thoughtsTokenCount().getOrNull(),
    toolUsePromptTokenCount = toolUsePromptTokenCount().getOrNull(),
    promptTokensDetails = promptTokensDetails().getOrNull()?.map { it.fromGenaiSdk() },
    candidatesTokensDetails = candidatesTokensDetails().getOrNull()?.map { it.fromGenaiSdk() },
  )

/**
 * Converts an ADK [UsageMetadata] to a
 * [com.google.genai.types.GenerateContentResponseUsageMetadata] for the GenAI SDK.
 */
internal fun UsageMetadata.toGenaiSdk():
  com.google.genai.types.GenerateContentResponseUsageMetadata =
  com.google.genai.types.GenerateContentResponseUsageMetadata.builder()
    .apply {
      this@toGenaiSdk.promptTokenCount?.let { promptTokenCount(it) }
      this@toGenaiSdk.candidatesTokenCount?.let { candidatesTokenCount(it) }
      this@toGenaiSdk.totalTokenCount?.let { totalTokenCount(it) }
      this@toGenaiSdk.thoughtsTokenCount?.let { thoughtsTokenCount(it) }
      this@toGenaiSdk.toolUsePromptTokenCount?.let { toolUsePromptTokenCount(it) }
      this@toGenaiSdk.promptTokensDetails?.let {
        promptTokensDetails(it.map { d -> d.toGenaiSdk() })
      }
      this@toGenaiSdk.candidatesTokensDetails?.let {
        candidatesTokensDetails(it.map { d -> d.toGenaiSdk() })
      }
    }
    .build()

// --- Part ---
/** Converts a [com.google.genai.types.Part] from the GenAI SDK to an ADK [Part]. */
internal fun com.google.genai.types.Part.fromGenaiSdk(): Part =
  Part(
    text = text().getOrNull(),
    inlineData = inlineData().getOrNull()?.fromGenaiSdk(),
    fileData = fileData().getOrNull()?.fromGenaiSdk(),
    functionCall = functionCall().getOrNull()?.fromGenaiSdk(),
    functionResponse = functionResponse().getOrNull()?.fromGenaiSdk(),
    thought = thought().getOrNull(),
    thoughtSignature = thoughtSignature().getOrNull(),
    videoMetadata = videoMetadata().getOrNull()?.fromGenaiSdk(),
    partMetadata = partMetadata().getOrNull(),
  )

/** Converts an ADK [Part] to a [com.google.genai.types.Part] for the GenAI SDK. */
internal fun Part.toGenaiSdk(): com.google.genai.types.Part =
  com.google.genai.types.Part.builder()
    .apply {
      this@toGenaiSdk.text?.let { text(it) }
      this@toGenaiSdk.inlineData?.let { inlineData(it.toGenaiSdk()) }
      this@toGenaiSdk.fileData?.let { fileData(it.toGenaiSdk()) }
      this@toGenaiSdk.functionCall?.let { functionCall(it.toGenaiSdk()) }
      this@toGenaiSdk.functionResponse?.let { functionResponse(it.toGenaiSdk()) }
      this@toGenaiSdk.thought?.let { thought(it) }
      this@toGenaiSdk.thoughtSignature?.let { thoughtSignature(it) }
      this@toGenaiSdk.videoMetadata?.let { videoMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.partMetadata?.let { partMetadata(it) }
    }
    .build()

// --- VideoMetadata ---
/**
 * Converts a [com.google.genai.types.VideoMetadata] from the GenAI SDK to an ADK [VideoMetadata].
 */
internal fun com.google.genai.types.VideoMetadata.fromGenaiSdk(): VideoMetadata =
  VideoMetadata(
    startOffset = startOffset().getOrNull()?.toKotlinDuration(),
    endOffset = endOffset().getOrNull()?.toKotlinDuration(),
    fps = fps().getOrNull(),
  )

/**
 * Converts an ADK [VideoMetadata] to a [com.google.genai.types.VideoMetadata] for the GenAI SDK.
 */
internal fun VideoMetadata.toGenaiSdk(): com.google.genai.types.VideoMetadata =
  com.google.genai.types.VideoMetadata.builder()
    .apply {
      this@toGenaiSdk.startOffset?.let { startOffset(it.toJavaDuration()) }
      this@toGenaiSdk.endOffset?.let { endOffset(it.toJavaDuration()) }
      this@toGenaiSdk.fps?.let { fps(it) }
    }
    .build()

// --- PartialArg ---
/** Converts a [com.google.genai.types.PartialArg] from the GenAI SDK to an ADK [PartialArg]. */
internal fun com.google.genai.types.PartialArg.fromGenaiSdk(): PartialArg =
  PartialArg(
    value =
      boolValue().getOrNull()?.let { PartialArgValue.BoolValue(it) }
        ?: numberValue().getOrNull()?.let { PartialArgValue.NumberValue(it) }
        ?: stringValue().getOrNull()?.let { PartialArgValue.StringValue(it) }
        ?: nullValue().getOrNull()?.let { PartialArgValue.NullValue },
    jsonPath = jsonPath().getOrNull(),
    willContinue = willContinue().getOrNull(),
  )

/** Converts an ADK [PartialArg] to a [com.google.genai.types.PartialArg] for the GenAI SDK. */
internal fun PartialArg.toGenaiSdk(): com.google.genai.types.PartialArg =
  com.google.genai.types.PartialArg.builder()
    .apply {
      when (val value = this@toGenaiSdk.value) {
        is PartialArgValue.BoolValue -> boolValue(value.value)
        is PartialArgValue.NumberValue -> numberValue(value.value)
        is PartialArgValue.StringValue -> stringValue(value.value)
        is PartialArgValue.NullValue -> nullValue(com.google.genai.types.NullValue.Known.NULL_VALUE)
        null -> {}
      }
      this@toGenaiSdk.jsonPath?.let { jsonPath(it) }
      this@toGenaiSdk.willContinue?.let { willContinue(it) }
    }
    .build()

// --- ThinkingConfig ---
/**
 * Converts a [com.google.genai.types.ThinkingConfig] from the GenAI SDK to an ADK [ThinkingConfig].
 */
internal fun com.google.genai.types.ThinkingConfig.fromGenaiSdk(): ThinkingConfig =
  ThinkingConfig(
    includeThoughts = includeThoughts().getOrNull(),
    thinkingBudget = thinkingBudget().getOrNull(),
    thinkingLevel = thinkingLevel().getOrNull()?.toKt(),
  )

/**
 * Converts an ADK [ThinkingConfig] to a [com.google.genai.types.ThinkingConfig] for the GenAI SDK.
 */
internal fun ThinkingConfig.toGenaiSdk(): com.google.genai.types.ThinkingConfig =
  com.google.genai.types.ThinkingConfig.builder()
    .apply {
      this@toGenaiSdk.includeThoughts?.let { includeThoughts(it) }
      this@toGenaiSdk.thinkingBudget?.let { thinkingBudget(it) }
      this@toGenaiSdk.thinkingLevel?.let { thinkingLevel(it.toJava()) }
    }
    .build()
