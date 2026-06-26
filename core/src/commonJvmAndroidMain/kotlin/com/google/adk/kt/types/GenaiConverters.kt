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

import com.google.genai.types.Blob as GenAiBlob
import com.google.genai.types.Candidate as GenAiCandidate
import com.google.genai.types.Citation as GenAiCitation
import com.google.genai.types.CitationMetadata as GenAiCitationMetadata
import com.google.genai.types.Content as GenAiContent
import com.google.genai.types.FileData as GenAiFileData
import com.google.genai.types.FunctionCall as GenAiFunctionCall
import com.google.genai.types.FunctionCallingConfig as GenAiFunctionCallingConfig
import com.google.genai.types.FunctionDeclaration as GenAiFunctionDeclaration
import com.google.genai.types.FunctionResponse as GenAiFunctionResponse
import com.google.genai.types.GenerateContentConfig as GenAiGenerateContentConfig
import com.google.genai.types.GenerateContentResponse as GenAiGenerateContentResponse
import com.google.genai.types.GenerateContentResponsePromptFeedback as GenAiGenerateContentResponsePromptFeedback
import com.google.genai.types.GenerateContentResponseUsageMetadata as GenAiGenerateContentResponseUsageMetadata
import com.google.genai.types.GoogleMaps as GenAiGoogleMaps
import com.google.genai.types.GoogleSearch as GenAiGoogleSearch
import com.google.genai.types.GroundingChunk as GenAiGroundingChunk
import com.google.genai.types.GroundingChunkRetrievedContext as GenAiGroundingChunkRetrievedContext
import com.google.genai.types.GroundingChunkWeb as GenAiGroundingChunkWeb
import com.google.genai.types.GroundingMetadata as GenAiGroundingMetadata
import com.google.genai.types.GroundingSupport as GenAiGroundingSupport
import com.google.genai.types.LogprobsResult as GenAiLogprobsResult
import com.google.genai.types.LogprobsResultCandidate as GenAiLogprobsResultCandidate
import com.google.genai.types.LogprobsResultTopCandidates as GenAiLogprobsResultTopCandidates
import com.google.genai.types.ModalityTokenCount as GenAiModalityTokenCount
import com.google.genai.types.NullValue as GenAiNullValue
import com.google.genai.types.Part as GenAiPart
import com.google.genai.types.PartialArg as GenAiPartialArg
import com.google.genai.types.RetrievalMetadata as GenAiRetrievalMetadata
import com.google.genai.types.SafetySetting as GenAiSafetySetting
import com.google.genai.types.Schema as GenAiSchema
import com.google.genai.types.SearchEntryPoint as GenAiSearchEntryPoint
import com.google.genai.types.Segment as GenAiSegment
import com.google.genai.types.ThinkingConfig as GenAiThinkingConfig
import com.google.genai.types.Tool as GenAiTool
import com.google.genai.types.ToolConfig as GenAiToolConfig
import com.google.genai.types.UrlContext as GenAiUrlContext
import com.google.genai.types.VideoMetadata as GenAiVideoMetadata
import kotlin.jvm.optionals.getOrNull
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

// --- Blob ---
/** Converts a [GenAiBlob] from the GenAI SDK to an ADK [Blob]. */
internal fun GenAiBlob.fromGenaiSdk(): Blob =
  Blob(
    mimeType = mimeType().getOrNull(),
    displayName = displayName().getOrNull(),
    data = data().getOrNull(),
  )

/** Converts an ADK [Blob] to a [GenAiBlob] for the GenAI SDK. */
internal fun Blob.toGenaiSdk(): GenAiBlob =
  GenAiBlob.builder()
    .apply {
      this@toGenaiSdk.mimeType?.let { mimeType(it) }
      this@toGenaiSdk.displayName?.let { displayName(it) }
      this@toGenaiSdk.data?.let { data(it) }
    }
    .build()

// --- Candidate ---
/** Converts a [GenAiCandidate] from the GenAI SDK to an ADK [Candidate]. */
internal fun GenAiCandidate.fromGenaiSdk(): Candidate =
  Candidate(
    content = content().getOrNull()?.fromGenaiSdk() ?: Content(),
    finishReason = finishReason().getOrNull()?.toKt(),
    finishMessage = finishMessage().getOrNull(),
    citationMetadata = citationMetadata().getOrNull()?.fromGenaiSdk(),
    groundingMetadata = groundingMetadata().getOrNull()?.fromGenaiSdk(),
    avgLogprobs = avgLogprobs().getOrNull(),
    logprobsResult = logprobsResult().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [Candidate] to a [GenAiCandidate] for the GenAI SDK. */
internal fun Candidate.toGenaiSdk(): GenAiCandidate =
  GenAiCandidate.builder()
    .apply {
      content(this@toGenaiSdk.content.toGenaiSdk())
      this@toGenaiSdk.finishReason?.let { finishReason(it.toJava()) }
      this@toGenaiSdk.finishMessage?.let { finishMessage(it) }
      this@toGenaiSdk.citationMetadata?.let { citationMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.groundingMetadata?.let { groundingMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.avgLogprobs?.let { avgLogprobs(it) }
      this@toGenaiSdk.logprobsResult?.let { logprobsResult(it.toGenaiSdk()) }
    }
    .build()

// --- LogprobsResult ---
/** Converts a [GenAiLogprobsResult] from the GenAI SDK to an ADK [LogprobsResult]. */
internal fun GenAiLogprobsResult.fromGenaiSdk(): LogprobsResult =
  LogprobsResult(
    chosenCandidates = chosenCandidates().getOrNull()?.map { it.fromGenaiSdk() },
    topCandidates = topCandidates().getOrNull()?.map { it.fromGenaiSdk() },
    logProbabilitySum = logProbabilitySum().getOrNull()?.toDouble(),
  )

/** Converts an ADK [LogprobsResult] to a [GenAiLogprobsResult] for the SDK. */
internal fun LogprobsResult.toGenaiSdk(): GenAiLogprobsResult =
  GenAiLogprobsResult.builder()
    .apply {
      this@toGenaiSdk.chosenCandidates?.let { chosenCandidates(it.map { c -> c.toGenaiSdk() }) }
      this@toGenaiSdk.topCandidates?.let { topCandidates(it.map { c -> c.toGenaiSdk() }) }
      this@toGenaiSdk.logProbabilitySum?.let { logProbabilitySum(it.toFloat()) }
    }
    .build()

// --- LogprobsResultCandidate ---
/**
 * Converts a [GenAiLogprobsResultCandidate] from the GenAI SDK to an ADK [LogprobsResultCandidate].
 */
internal fun GenAiLogprobsResultCandidate.fromGenaiSdk(): LogprobsResultCandidate =
  LogprobsResultCandidate(
    token = token().getOrNull(),
    tokenId = tokenId().getOrNull(),
    logProbability = logProbability().getOrNull()?.toDouble(),
  )

/**
 * Converts an ADK [LogprobsResultCandidate] to a [GenAiLogprobsResultCandidate] for the GenAI SDK.
 */
internal fun LogprobsResultCandidate.toGenaiSdk(): GenAiLogprobsResultCandidate =
  GenAiLogprobsResultCandidate.builder()
    .apply {
      this@toGenaiSdk.token?.let { token(it) }
      this@toGenaiSdk.tokenId?.let { tokenId(it) }
      this@toGenaiSdk.logProbability?.let { logProbability(it.toFloat()) }
    }
    .build()

// --- LogprobsResultTopCandidates ---
/**
 * Converts a [GenAiLogprobsResultTopCandidates] from the GenAI SDK to an ADK
 * [LogprobsResultTopCandidates].
 */
internal fun GenAiLogprobsResultTopCandidates.fromGenaiSdk(): LogprobsResultTopCandidates =
  LogprobsResultTopCandidates(candidates = candidates().getOrNull()?.map { it.fromGenaiSdk() })

/**
 * Converts an ADK [LogprobsResultTopCandidates] to a [GenAiLogprobsResultTopCandidates] for the
 * GenAI SDK.
 */
internal fun LogprobsResultTopCandidates.toGenaiSdk(): GenAiLogprobsResultTopCandidates =
  GenAiLogprobsResultTopCandidates.builder()
    .apply { this@toGenaiSdk.candidates?.let { candidates(it.map { c -> c.toGenaiSdk() }) } }
    .build()

// --- Citation ---
/** Converts a [GenAiCitation] from the GenAI SDK to an ADK [Citation]. */
internal fun GenAiCitation.fromGenaiSdk(): Citation =
  Citation(
    title = title().getOrNull(),
    uri = uri().getOrNull(),
    startIndex = startIndex().getOrNull(),
    endIndex = endIndex().getOrNull(),
  )

/** Converts an ADK [Citation] to a [GenAiCitation] for the GenAI SDK. */
internal fun Citation.toGenaiSdk(): GenAiCitation =
  GenAiCitation.builder()
    .apply {
      this@toGenaiSdk.title?.let { title(it) }
      this@toGenaiSdk.uri?.let { uri(it) }
      this@toGenaiSdk.startIndex?.let { startIndex(it) }
      this@toGenaiSdk.endIndex?.let { endIndex(it) }
    }
    .build()

// --- CitationMetadata ---
/** Converts a [GenAiCitationMetadata] from the GenAI SDK to an ADK [CitationMetadata]. */
internal fun GenAiCitationMetadata.fromGenaiSdk(): CitationMetadata =
  CitationMetadata(
    citationSources = citations().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList()
  )

/** Converts an ADK [CitationMetadata] to a [GenAiCitationMetadata] for the GenAI SDK. */
internal fun CitationMetadata.toGenaiSdk(): GenAiCitationMetadata =
  GenAiCitationMetadata.builder()
    .apply { citations(this@toGenaiSdk.citationSources.map { it.toGenaiSdk() }) }
    .build()

// --- Content ---
/** Converts a [GenAiContent] from the GenAI SDK to an ADK [Content]. */
internal fun GenAiContent.fromGenaiSdk(): Content =
  Content(
    role = role().getOrNull(),
    parts = parts().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList(),
  )

/** Converts an ADK [Content] to a [GenAiContent] for the GenAI SDK. */
internal fun Content.toGenaiSdk(): GenAiContent =
  GenAiContent.builder()
    .apply {
      this@toGenaiSdk.role?.let { role(it) }
      parts(this@toGenaiSdk.parts.map { it.toGenaiSdk() })
    }
    .build()

// --- FileData ---
/** Converts a [GenAiFileData] from the GenAI SDK to an ADK [FileData]. */
internal fun GenAiFileData.fromGenaiSdk(): FileData =
  FileData(
    mimeType = mimeType().getOrNull(),
    displayName = displayName().getOrNull(),
    fileUri = fileUri().getOrNull(),
  )

/** Converts an ADK [FileData] to a [GenAiFileData] for the GenAI SDK. */
internal fun FileData.toGenaiSdk(): GenAiFileData =
  GenAiFileData.builder()
    .apply {
      this@toGenaiSdk.mimeType?.let { mimeType(it) }
      this@toGenaiSdk.displayName?.let { displayName(it) }
      this@toGenaiSdk.fileUri?.let { fileUri(it) }
    }
    .build()

// --- FunctionCall ---
/** Converts a [GenAiFunctionCall] from the GenAI SDK to an ADK [FunctionCall]. */
internal fun GenAiFunctionCall.fromGenaiSdk(): FunctionCall =
  FunctionCall(
    name = name().getOrNull() ?: "",
    args = args().getOrNull() ?: emptyMap(),
    id = id().getOrNull(),
    partialArgs = partialArgs().getOrNull()?.map { it.fromGenaiSdk() },
    willContinue = willContinue().getOrNull(),
  )

/** Converts an ADK [FunctionCall] to a [GenAiFunctionCall] for the GenAI SDK. */
internal fun FunctionCall.toGenaiSdk(): GenAiFunctionCall =
  GenAiFunctionCall.builder()
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
/** Converts a [GenAiFunctionDeclaration] from the GenAI SDK to an ADK [FunctionDeclaration]. */
internal fun GenAiFunctionDeclaration.fromGenaiSdk(): FunctionDeclaration =
  FunctionDeclaration(
    name = name().get(),
    description = description().get(),
    parameters = parameters().getOrNull()?.toKtSchema(),
  )

/** Converts an ADK [FunctionDeclaration] to a [GenAiFunctionDeclaration] for the GenAI SDK. */
internal fun FunctionDeclaration.toGenaiSdk(): GenAiFunctionDeclaration =
  GenAiFunctionDeclaration.builder()
    .apply {
      name(this@toGenaiSdk.name)
      description(this@toGenaiSdk.description)
      this@toGenaiSdk.parameters?.let { parameters(it.toGenAiSchema()) }
    }
    .build()

// --- FunctionResponse ---
/** Converts a [GenAiFunctionResponse] from the GenAI SDK to an ADK [FunctionResponse]. */
internal fun GenAiFunctionResponse.fromGenaiSdk(): FunctionResponse =
  FunctionResponse(
    name = name().get(),
    response = response().getOrNull() ?: emptyMap(),
    id = id().getOrNull(),
  )

/** Converts an ADK [FunctionResponse] to a [GenAiFunctionResponse] for the GenAI SDK. */
internal fun FunctionResponse.toGenaiSdk(): GenAiFunctionResponse =
  GenAiFunctionResponse.builder()
    .apply {
      name(this@toGenaiSdk.name)
      response(this@toGenaiSdk.response)
      this@toGenaiSdk.id?.let { id(it) }
    }
    .build()

// --- GenerateContentConfig ---
/** Converts a [GenAiGenerateContentConfig] from the GenAI SDK to an ADK [GenerateContentConfig]. */
internal fun GenAiGenerateContentConfig.fromGenaiSdk(): GenerateContentConfig =
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

/** Converts an ADK [GenerateContentConfig] to a [GenAiGenerateContentConfig] for the GenAI SDK. */
internal fun GenerateContentConfig.toGenaiSdk(): GenAiGenerateContentConfig =
  GenAiGenerateContentConfig.builder()
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
/** Converts a [GenAiFunctionCallingConfig] from the GenAI SDK to an ADK [FunctionCallingConfig]. */
internal fun GenAiFunctionCallingConfig.fromGenaiSdk(): FunctionCallingConfig =
  FunctionCallingConfig(allowedFunctionNames = allowedFunctionNames().getOrNull())

/** Converts an ADK [FunctionCallingConfig] to a [GenAiFunctionCallingConfig] for the GenAI SDK. */
internal fun FunctionCallingConfig.toGenaiSdk(): GenAiFunctionCallingConfig =
  GenAiFunctionCallingConfig.builder()
    .apply { this@toGenaiSdk.allowedFunctionNames?.let { allowedFunctionNames(it) } }
    .build()

// --- ToolConfig ---
/** Converts a [GenAiToolConfig] from the GenAI SDK to an ADK [ToolConfig]. */
internal fun GenAiToolConfig.fromGenaiSdk(): ToolConfig =
  ToolConfig(functionCallingConfig = functionCallingConfig().getOrNull()?.fromGenaiSdk())

/** Converts an ADK [ToolConfig] to a [GenAiToolConfig] for the GenAI SDK. */
internal fun ToolConfig.toGenaiSdk(): GenAiToolConfig =
  GenAiToolConfig.builder()
    .apply { this@toGenaiSdk.functionCallingConfig?.let { functionCallingConfig(it.toGenaiSdk()) } }
    .build()

// --- SafetySetting ---
/** Converts a [GenAiSafetySetting] from the GenAI SDK to an ADK [SafetySetting]. */
internal fun GenAiSafetySetting.fromGenaiSdk(): SafetySetting =
  SafetySetting(
    category = category().getOrNull()?.toKt(),
    threshold = threshold().getOrNull()?.toKt(),
  )

/** Converts an ADK [SafetySetting] to a [GenAiSafetySetting] for the GenAI SDK. */
internal fun SafetySetting.toGenaiSdk(): GenAiSafetySetting =
  GenAiSafetySetting.builder()
    .apply {
      this@toGenaiSdk.category?.let { category(it.toJava()) }
      this@toGenaiSdk.threshold?.let { threshold(it.toJava()) }
    }
    .build()

// --- GenerateContentResponse ---
/**
 * Converts a [GenAiGenerateContentResponse] from the GenAI SDK to an ADK [GenerateContentResponse].
 */
internal fun GenAiGenerateContentResponse.fromGenaiSdk(): GenerateContentResponse =
  GenerateContentResponse(
    candidates = candidates().getOrNull()?.map { it.fromGenaiSdk() } ?: emptyList(),
    promptFeedback = promptFeedback().getOrNull()?.fromGenaiSdk(),
    usageMetadata = usageMetadata().getOrNull()?.fromGenaiSdk(),
    modelVersion = modelVersion().getOrNull(),
  )

/**
 * Converts an ADK [GenerateContentResponse] to a [GenAiGenerateContentResponse] for the GenAI SDK.
 */
internal fun GenerateContentResponse.toGenaiSdk(): GenAiGenerateContentResponse =
  GenAiGenerateContentResponse.builder()
    .apply {
      candidates(this@toGenaiSdk.candidates.map { it.toGenaiSdk() })
      this@toGenaiSdk.promptFeedback?.let { promptFeedback(it.toGenaiSdk()) }
      this@toGenaiSdk.usageMetadata?.let { usageMetadata(it.toGenaiSdk()) }
      this@toGenaiSdk.modelVersion?.let { modelVersion(it) }
    }
    .build()

// --- GroundingMetadata ---
/** Converts a [GenAiGroundingMetadata] from the GenAI SDK to an ADK [GroundingMetadata]. */
internal fun GenAiGroundingMetadata.fromGenaiSdk(): GroundingMetadata =
  GroundingMetadata(
    imageSearchQueries = imageSearchQueries().getOrNull() ?: emptyList(),
    groundingChunks = groundingChunks().getOrNull()?.map { it.fromGenaiSdk() },
    groundingSupports = groundingSupports().getOrNull()?.map { it.fromGenaiSdk() },
    webSearchQueries = webSearchQueries().getOrNull(),
    searchEntryPoint = searchEntryPoint().getOrNull()?.fromGenaiSdk(),
    retrievalMetadata = retrievalMetadata().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [GroundingMetadata] to a [GenAiGroundingMetadata] for the GenAI SDK. */
internal fun GroundingMetadata.toGenaiSdk(): GenAiGroundingMetadata =
  GenAiGroundingMetadata.builder()
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
/** Converts a [GenAiGroundingChunk] from the GenAI SDK to an ADK [GroundingChunk]. */
internal fun GenAiGroundingChunk.fromGenaiSdk(): GroundingChunk =
  GroundingChunk(
    web = web().getOrNull()?.fromGenaiSdk(),
    retrievedContext = retrievedContext().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [GroundingChunk] to a [GenAiGroundingChunk] for the SDK. */
internal fun GroundingChunk.toGenaiSdk(): GenAiGroundingChunk =
  GenAiGroundingChunk.builder()
    .apply {
      this@toGenaiSdk.web?.let { web(it.toGenaiSdk()) }
      this@toGenaiSdk.retrievedContext?.let { retrievedContext(it.toGenaiSdk()) }
    }
    .build()

// --- GroundingChunkWeb ---
/** Converts a [GenAiGroundingChunkWeb] from the GenAI SDK to an ADK [GroundingChunkWeb]. */
internal fun GenAiGroundingChunkWeb.fromGenaiSdk(): GroundingChunkWeb =
  GroundingChunkWeb(
    uri = uri().getOrNull(),
    title = title().getOrNull(),
    domain = domain().getOrNull(),
  )

/** Converts an ADK [GroundingChunkWeb] to a [GenAiGroundingChunkWeb] for the SDK. */
internal fun GroundingChunkWeb.toGenaiSdk(): GenAiGroundingChunkWeb =
  GenAiGroundingChunkWeb.builder()
    .apply {
      this@toGenaiSdk.uri?.let { uri(it) }
      this@toGenaiSdk.title?.let { title(it) }
      this@toGenaiSdk.domain?.let { domain(it) }
    }
    .build()

// --- GroundingChunkRetrievedContext ---
/**
 * Converts a [GenAiGroundingChunkRetrievedContext] from the GenAI SDK to an ADK
 * [GroundingChunkRetrievedContext].
 */
internal fun GenAiGroundingChunkRetrievedContext.fromGenaiSdk(): GroundingChunkRetrievedContext =
  GroundingChunkRetrievedContext(
    uri = uri().getOrNull(),
    title = title().getOrNull(),
    text = text().getOrNull(),
  )

/**
 * Converts an ADK [GroundingChunkRetrievedContext] to a [GenAiGroundingChunkRetrievedContext] for
 * the GenAI SDK.
 */
internal fun GroundingChunkRetrievedContext.toGenaiSdk(): GenAiGroundingChunkRetrievedContext =
  GenAiGroundingChunkRetrievedContext.builder()
    .apply {
      this@toGenaiSdk.uri?.let { uri(it) }
      this@toGenaiSdk.title?.let { title(it) }
      this@toGenaiSdk.text?.let { text(it) }
    }
    .build()

// --- GroundingSupport ---
/** Converts a [GenAiGroundingSupport] from the GenAI SDK to an ADK [GroundingSupport]. */
internal fun GenAiGroundingSupport.fromGenaiSdk(): GroundingSupport =
  GroundingSupport(
    segment = segment().getOrNull()?.fromGenaiSdk(),
    groundingChunkIndices = groundingChunkIndices().getOrNull(),
    confidenceScores = confidenceScores().getOrNull(),
  )

/** Converts an ADK [GroundingSupport] to a [GenAiGroundingSupport] for the SDK. */
internal fun GroundingSupport.toGenaiSdk(): GenAiGroundingSupport =
  GenAiGroundingSupport.builder()
    .apply {
      this@toGenaiSdk.segment?.let { segment(it.toGenaiSdk()) }
      this@toGenaiSdk.groundingChunkIndices?.let { groundingChunkIndices(it) }
      this@toGenaiSdk.confidenceScores?.let { confidenceScores(it) }
    }
    .build()

// --- Segment ---
/** Converts a [GenAiSegment] from the GenAI SDK to an ADK [Segment]. */
internal fun GenAiSegment.fromGenaiSdk(): Segment =
  Segment(
    startIndex = startIndex().getOrNull(),
    endIndex = endIndex().getOrNull(),
    partIndex = partIndex().getOrNull(),
    text = text().getOrNull(),
  )

/** Converts an ADK [Segment] to a [GenAiSegment] for the GenAI SDK. */
internal fun Segment.toGenaiSdk(): GenAiSegment =
  GenAiSegment.builder()
    .apply {
      this@toGenaiSdk.startIndex?.let { startIndex(it) }
      this@toGenaiSdk.endIndex?.let { endIndex(it) }
      this@toGenaiSdk.partIndex?.let { partIndex(it) }
      this@toGenaiSdk.text?.let { text(it) }
    }
    .build()

// --- SearchEntryPoint ---
/** Converts a [GenAiSearchEntryPoint] from the GenAI SDK to an ADK [SearchEntryPoint]. */
internal fun GenAiSearchEntryPoint.fromGenaiSdk(): SearchEntryPoint =
  SearchEntryPoint(renderedContent = renderedContent().getOrNull())

/** Converts an ADK [SearchEntryPoint] to a [GenAiSearchEntryPoint] for the SDK. */
internal fun SearchEntryPoint.toGenaiSdk(): GenAiSearchEntryPoint =
  GenAiSearchEntryPoint.builder()
    .apply { this@toGenaiSdk.renderedContent?.let { renderedContent(it) } }
    .build()

// --- RetrievalMetadata ---
/** Converts a [GenAiRetrievalMetadata] from the GenAI SDK to an ADK [RetrievalMetadata]. */
internal fun GenAiRetrievalMetadata.fromGenaiSdk(): RetrievalMetadata =
  RetrievalMetadata(
    googleSearchDynamicRetrievalScore = googleSearchDynamicRetrievalScore().getOrNull()
  )

/** Converts an ADK [RetrievalMetadata] to a [GenAiRetrievalMetadata] for the GenAI SDK. */
internal fun RetrievalMetadata.toGenaiSdk(): GenAiRetrievalMetadata =
  GenAiRetrievalMetadata.builder()
    .apply {
      this@toGenaiSdk.googleSearchDynamicRetrievalScore?.let {
        googleSearchDynamicRetrievalScore(it)
      }
    }
    .build()

// --- PromptFeedback ---
/**
 * Converts a [GenAiGenerateContentResponsePromptFeedback] from the GenAI SDK to an ADK
 * [PromptFeedback].
 */
internal fun GenAiGenerateContentResponsePromptFeedback.fromGenaiSdk(): PromptFeedback =
  PromptFeedback(
    blockReason = blockReason().getOrNull()?.toKt(),
    blockReasonMessage = blockReasonMessage().getOrNull(),
  )

/**
 * Converts an ADK [PromptFeedback] to a [GenAiGenerateContentResponsePromptFeedback] for the GenAI
 * SDK.
 */
internal fun PromptFeedback.toGenaiSdk(): GenAiGenerateContentResponsePromptFeedback =
  GenAiGenerateContentResponsePromptFeedback.builder()
    .apply {
      this@toGenaiSdk.blockReason?.let { blockReason(it.toJava()) }
      this@toGenaiSdk.blockReasonMessage?.let { blockReasonMessage(it) }
    }
    .build()

// --- GoogleMaps ---
/** Converts a [GenAiGoogleMaps] from the GenAI SDK to an ADK [GoogleMaps]. */
internal fun GenAiGoogleMaps.fromGenaiSdk(): GoogleMaps =
  GoogleMaps(enableWidget = enableWidget().getOrNull())

/** Converts an ADK [GoogleMaps] to a [GenAiGoogleMaps] for the GenAI SDK. */
internal fun GoogleMaps.toGenaiSdk(): GenAiGoogleMaps =
  GenAiGoogleMaps.builder().apply { this@toGenaiSdk.enableWidget?.let { enableWidget(it) } }.build()

// --- GoogleSearch ---
/** Converts a [GenAiGoogleSearch] from the GenAI SDK to an ADK [GoogleSearch]. */
internal fun GenAiGoogleSearch.fromGenaiSdk(): GoogleSearch =
  GoogleSearch(excludeDomains = excludeDomains().getOrNull() ?: emptyList())

/** Converts an ADK [GoogleSearch] to a [GenAiGoogleSearch] for the GenAI SDK. */
internal fun GoogleSearch.toGenaiSdk(): GenAiGoogleSearch =
  GenAiGoogleSearch.builder()
    .apply { this@toGenaiSdk.excludeDomains.takeIf { it.isNotEmpty() }?.let { excludeDomains(it) } }
    .build()

// --- Schema ---
/** Converts a [GenAiSchema] from the GenAI SDK to an ADK [Schema]. */
internal fun GenAiSchema.toKtSchema(): Schema =
  Schema(
    type = type().getOrNull()?.toString()?.let { Type.valueOf(it) },
    properties = properties().getOrNull()?.mapValues { it.value.toKtSchema() },
    items = items().getOrNull()?.toKtSchema(),
    required = required().getOrNull(),
    description = description().getOrNull(),
    enum = enum_().getOrNull(),
  )

/** Converts an ADK [Schema] to a [GenAiSchema] for the GenAI SDK. */
internal fun Schema.toGenAiSchema(): GenAiSchema =
  GenAiSchema.builder()
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
/** Converts a [GenAiUrlContext] from the GenAI SDK to an ADK [UrlContext]. */
internal fun GenAiUrlContext.fromGenaiSdk(): UrlContext = UrlContext()

/** Converts an ADK [UrlContext] to a [GenAiUrlContext] for the GenAI SDK. */
internal fun UrlContext.toGenaiSdk(): GenAiUrlContext = GenAiUrlContext.builder().build()

// --- Tool ---
/** Converts a [GenAiTool] from the GenAI SDK to an ADK [Tool]. */
internal fun GenAiTool.fromGenaiSdk(): Tool =
  Tool(
    functionDeclarations = functionDeclarations().getOrNull()?.map { it.fromGenaiSdk() },
    googleSearch = googleSearch().getOrNull()?.fromGenaiSdk(),
    googleMaps = googleMaps().getOrNull()?.fromGenaiSdk(),
    urlContext = urlContext().getOrNull()?.fromGenaiSdk(),
  )

/** Converts an ADK [Tool] to a [GenAiTool] for the GenAI SDK. */
internal fun Tool.toGenaiSdk(): GenAiTool =
  GenAiTool.builder()
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
/** Converts a [GenAiModalityTokenCount] from the GenAI SDK to an ADK [ModalityTokenCount]. */
internal fun GenAiModalityTokenCount.fromGenaiSdk(): ModalityTokenCount =
  ModalityTokenCount(
    modality = modality().getOrNull()?.toKt(),
    tokenCount = tokenCount().getOrNull(),
  )

/** Converts an ADK [ModalityTokenCount] to a [GenAiModalityTokenCount] for the GenAI SDK. */
internal fun ModalityTokenCount.toGenaiSdk(): GenAiModalityTokenCount =
  GenAiModalityTokenCount.builder()
    .apply {
      this@toGenaiSdk.modality?.let { modality(it.toJava()) }
      this@toGenaiSdk.tokenCount?.let { tokenCount(it) }
    }
    .build()

// --- UsageMetadata ---
/**
 * Converts a [GenAiGenerateContentResponseUsageMetadata] from the GenAI SDK to an ADK
 * [UsageMetadata].
 */
internal fun GenAiGenerateContentResponseUsageMetadata.fromGenaiSdk(): UsageMetadata =
  UsageMetadata(
    promptTokenCount = promptTokenCount().getOrNull(),
    candidatesTokenCount = candidatesTokenCount().getOrNull(),
    totalTokenCount = totalTokenCount().getOrNull(),
    thoughtsTokenCount = thoughtsTokenCount().getOrNull(),
    toolUsePromptTokenCount = toolUsePromptTokenCount().getOrNull(),
    cachedContentTokenCount = cachedContentTokenCount().getOrNull(),
    promptTokensDetails = promptTokensDetails().getOrNull()?.map { it.fromGenaiSdk() },
    candidatesTokensDetails = candidatesTokensDetails().getOrNull()?.map { it.fromGenaiSdk() },
  )

/**
 * Converts an ADK [UsageMetadata] to a [GenAiGenerateContentResponseUsageMetadata] for the GenAI
 * SDK.
 */
internal fun UsageMetadata.toGenaiSdk(): GenAiGenerateContentResponseUsageMetadata =
  GenAiGenerateContentResponseUsageMetadata.builder()
    .apply {
      this@toGenaiSdk.promptTokenCount?.let { promptTokenCount(it) }
      this@toGenaiSdk.candidatesTokenCount?.let { candidatesTokenCount(it) }
      this@toGenaiSdk.totalTokenCount?.let { totalTokenCount(it) }
      this@toGenaiSdk.thoughtsTokenCount?.let { thoughtsTokenCount(it) }
      this@toGenaiSdk.toolUsePromptTokenCount?.let { toolUsePromptTokenCount(it) }
      this@toGenaiSdk.cachedContentTokenCount?.let { cachedContentTokenCount(it) }
      this@toGenaiSdk.promptTokensDetails?.let {
        promptTokensDetails(it.map { d -> d.toGenaiSdk() })
      }
      this@toGenaiSdk.candidatesTokensDetails?.let {
        candidatesTokensDetails(it.map { d -> d.toGenaiSdk() })
      }
    }
    .build()

// --- Part ---
/** Converts a [GenAiPart] from the GenAI SDK to an ADK [Part]. */
internal fun GenAiPart.fromGenaiSdk(): Part =
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

/** Converts an ADK [Part] to a [GenAiPart] for the GenAI SDK. */
internal fun Part.toGenaiSdk(): GenAiPart =
  GenAiPart.builder()
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
/** Converts a [GenAiVideoMetadata] from the GenAI SDK to an ADK [VideoMetadata]. */
internal fun GenAiVideoMetadata.fromGenaiSdk(): VideoMetadata =
  VideoMetadata(
    startOffset = startOffset().getOrNull()?.toKotlinDuration(),
    endOffset = endOffset().getOrNull()?.toKotlinDuration(),
    fps = fps().getOrNull(),
  )

/** Converts an ADK [VideoMetadata] to a [GenAiVideoMetadata] for the GenAI SDK. */
internal fun VideoMetadata.toGenaiSdk(): GenAiVideoMetadata =
  GenAiVideoMetadata.builder()
    .apply {
      this@toGenaiSdk.startOffset?.let { startOffset(it.toJavaDuration()) }
      this@toGenaiSdk.endOffset?.let { endOffset(it.toJavaDuration()) }
      this@toGenaiSdk.fps?.let { fps(it) }
    }
    .build()

// --- PartialArg ---
/** Converts a [GenAiPartialArg] from the GenAI SDK to an ADK [PartialArg]. */
internal fun GenAiPartialArg.fromGenaiSdk(): PartialArg =
  PartialArg(
    value =
      boolValue().getOrNull()?.let { PartialArgValue.BoolValue(it) }
        ?: numberValue().getOrNull()?.let { PartialArgValue.NumberValue(it) }
        ?: stringValue().getOrNull()?.let { PartialArgValue.StringValue(it) }
        ?: nullValue().getOrNull()?.let { PartialArgValue.NullValue },
    jsonPath = jsonPath().getOrNull(),
    willContinue = willContinue().getOrNull(),
  )

/** Converts an ADK [PartialArg] to a [GenAiPartialArg] for the GenAI SDK. */
internal fun PartialArg.toGenaiSdk(): GenAiPartialArg =
  GenAiPartialArg.builder()
    .apply {
      when (val value = this@toGenaiSdk.value) {
        is PartialArgValue.BoolValue -> boolValue(value.value)
        is PartialArgValue.NumberValue -> numberValue(value.value)
        is PartialArgValue.StringValue -> stringValue(value.value)
        is PartialArgValue.NullValue -> nullValue(GenAiNullValue.Known.NULL_VALUE)
        null -> {}
      }
      this@toGenaiSdk.jsonPath?.let { jsonPath(it) }
      this@toGenaiSdk.willContinue?.let { willContinue(it) }
    }
    .build()

// --- ThinkingConfig ---
/** Converts a [GenAiThinkingConfig] from the GenAI SDK to an ADK [ThinkingConfig]. */
internal fun GenAiThinkingConfig.fromGenaiSdk(): ThinkingConfig =
  ThinkingConfig(
    includeThoughts = includeThoughts().getOrNull(),
    thinkingBudget = thinkingBudget().getOrNull(),
    thinkingLevel = thinkingLevel().getOrNull()?.toKt(),
  )

/** Converts an ADK [ThinkingConfig] to a [GenAiThinkingConfig] for the GenAI SDK. */
internal fun ThinkingConfig.toGenaiSdk(): GenAiThinkingConfig =
  GenAiThinkingConfig.builder()
    .apply {
      this@toGenaiSdk.includeThoughts?.let { includeThoughts(it) }
      this@toGenaiSdk.thinkingBudget?.let { thinkingBudget(it) }
      this@toGenaiSdk.thinkingLevel?.let { thinkingLevel(it.toJava()) }
    }
    .build()
