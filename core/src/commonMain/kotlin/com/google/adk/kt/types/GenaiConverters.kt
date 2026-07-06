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
 * Converters to map between the Kotlin ADK [com.google.adk.kt.types] and the
 * [com.google.genai.kotlin.types] from the Kotlin GenAI SDK.
 */
package com.google.adk.kt.types

import com.google.adk.kt.serialization.Json
import com.google.genai.kotlin.types.Blob as GenAiBlob
import com.google.genai.kotlin.types.Candidate as GenAiCandidate
import com.google.genai.kotlin.types.Citation as GenAiCitation
import com.google.genai.kotlin.types.CitationMetadata as GenAiCitationMetadata
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.FileData as GenAiFileData
import com.google.genai.kotlin.types.FunctionCall as GenAiFunctionCall
import com.google.genai.kotlin.types.FunctionCallingConfig as GenAiFunctionCallingConfig
import com.google.genai.kotlin.types.FunctionDeclaration as GenAiFunctionDeclaration
import com.google.genai.kotlin.types.FunctionResponse as GenAiFunctionResponse
import com.google.genai.kotlin.types.GenerateContentConfig as GenAiGenerateContentConfig
import com.google.genai.kotlin.types.GenerateContentResponse as GenAiGenerateContentResponse
import com.google.genai.kotlin.types.GenerateContentResponsePromptFeedback as GenAiGenerateContentResponsePromptFeedback
import com.google.genai.kotlin.types.GenerateContentResponseUsageMetadata as GenAiGenerateContentResponseUsageMetadata
import com.google.genai.kotlin.types.GoogleMaps as GenAiGoogleMaps
import com.google.genai.kotlin.types.GoogleSearch as GenAiGoogleSearch
import com.google.genai.kotlin.types.GroundingChunk as GenAiGroundingChunk
import com.google.genai.kotlin.types.GroundingChunkRetrievedContext as GenAiGroundingChunkRetrievedContext
import com.google.genai.kotlin.types.GroundingChunkWeb as GenAiGroundingChunkWeb
import com.google.genai.kotlin.types.GroundingMetadata as GenAiGroundingMetadata
import com.google.genai.kotlin.types.GroundingSupport as GenAiGroundingSupport
import com.google.genai.kotlin.types.LogprobsResult as GenAiLogprobsResult
import com.google.genai.kotlin.types.LogprobsResultCandidate as GenAiLogprobsResultCandidate
import com.google.genai.kotlin.types.LogprobsResultTopCandidates as GenAiLogprobsResultTopCandidates
import com.google.genai.kotlin.types.ModalityTokenCount as GenAiModalityTokenCount
import com.google.genai.kotlin.types.NullValue as GenAiNullValue
import com.google.genai.kotlin.types.Part as GenAiPart
import com.google.genai.kotlin.types.PartialArg as GenAiPartialArg
import com.google.genai.kotlin.types.Retrieval as GenAiRetrieval
import com.google.genai.kotlin.types.RetrievalMetadata as GenAiRetrievalMetadata
import com.google.genai.kotlin.types.SafetySetting as GenAiSafetySetting
import com.google.genai.kotlin.types.Schema as GenAiSchema
import com.google.genai.kotlin.types.SearchEntryPoint as GenAiSearchEntryPoint
import com.google.genai.kotlin.types.Segment as GenAiSegment
import com.google.genai.kotlin.types.ThinkingConfig as GenAiThinkingConfig
import com.google.genai.kotlin.types.Tool as GenAiTool
import com.google.genai.kotlin.types.ToolConfig as GenAiToolConfig
import com.google.genai.kotlin.types.UrlContext as GenAiUrlContext
import com.google.genai.kotlin.types.VertexAISearch as GenAiVertexAISearch
import com.google.genai.kotlin.types.VertexAISearchDataStoreSpec as GenAiVertexAISearchDataStoreSpec
import com.google.genai.kotlin.types.VideoMetadata as GenAiVideoMetadata
import kotlinx.serialization.json.Json as KmpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// --- JsonElement <-> Any? helpers ---
/**
 * Recursively converts an arbitrary value into a [JsonElement] for the GenAI SDK.
 *
 * Null map entries are stripped so they are omitted from the serialized JSON of
 * `FunctionResponse.response`.
 */
internal fun Any?.toJsonElement(): JsonElement =
  when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> ->
      JsonObject(
        this.entries
          .filter { (_, v) -> v != null }
          .associate { (k, v) -> k.toString() to v.toJsonElement() }
      )
    is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
    is Array<*> -> JsonArray(this.map { it.toJsonElement() })
    // Edge-case fallback for a custom object arriving as erased `Any`; KSP `@Tool`s don't reach
    // here (data-class returns are decomposed to maps). Best-effort via the platform [Json].
    else -> KmpJson.parseToJsonElement(Json.toJsonString(this))
  }

/** Recursively converts a [JsonElement] back to plain Kotlin types. */
internal fun JsonElement.toAny(): Any? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> {
      when {
        this.isString -> this.contentOrNull
        else ->
          this.booleanOrNull
            ?: this.intOrNull
            ?: this.longOrNull
            ?: this.doubleOrNull
            ?: this.contentOrNull
      }
    }
    is JsonObject -> this.mapValues { it.value.toAny() }
    is JsonArray -> this.map { it.toAny() }
  }

// --- Blob ---
/** Converts a [GenAiBlob] from the GenAI SDK to an ADK [Blob]. */
internal fun GenAiBlob.fromGenaiSdk(): Blob =
  Blob(mimeType = mimeType, displayName = displayName, data = data)

/** Converts an ADK [Blob] to a [GenAiBlob] for the GenAI SDK. */
internal fun Blob.toGenaiSdk(): GenAiBlob =
  GenAiBlob(mimeType = mimeType, displayName = displayName, data = data)

// --- Candidate ---
/** Converts a [GenAiCandidate] from the GenAI SDK to an ADK [Candidate]. */
internal fun GenAiCandidate.fromGenaiSdk(): Candidate =
  Candidate(
    content = content?.fromGenaiSdk() ?: Content(),
    finishReason = finishReason?.toKt(),
    finishMessage = finishMessage,
    citationMetadata = citationMetadata?.fromGenaiSdk(),
    groundingMetadata = groundingMetadata?.fromGenaiSdk(),
    avgLogprobs = avgLogprobs,
    logprobsResult = logprobsResult?.fromGenaiSdk(),
  )

/** Converts an ADK [Candidate] to a [GenAiCandidate] for the GenAI SDK. */
internal fun Candidate.toGenaiSdk(): GenAiCandidate =
  GenAiCandidate(
    content = content.toGenaiSdk(),
    finishReason = finishReason?.toGenaiSdk(),
    finishMessage = finishMessage,
    citationMetadata = citationMetadata?.toGenaiSdk(),
    groundingMetadata = groundingMetadata?.toGenaiSdk(),
    avgLogprobs = avgLogprobs,
    logprobsResult = logprobsResult?.toGenaiSdk(),
  )

// --- LogprobsResult ---
/** Converts a [GenAiLogprobsResult] from the GenAI SDK to an ADK [LogprobsResult]. */
internal fun GenAiLogprobsResult.fromGenaiSdk(): LogprobsResult =
  LogprobsResult(
    chosenCandidates = chosenCandidates?.map { it.fromGenaiSdk() },
    topCandidates = topCandidates?.map { it.fromGenaiSdk() },
    logProbabilitySum = logProbabilitySum,
  )

/** Converts an ADK [LogprobsResult] to a [GenAiLogprobsResult] for the GenAI SDK. */
internal fun LogprobsResult.toGenaiSdk(): GenAiLogprobsResult =
  GenAiLogprobsResult(
    chosenCandidates = chosenCandidates?.map { it.toGenaiSdk() },
    topCandidates = topCandidates?.map { it.toGenaiSdk() },
    logProbabilitySum = logProbabilitySum,
  )

// --- LogprobsResultCandidate ---
/**
 * Converts a [GenAiLogprobsResultCandidate] from the GenAI SDK to an ADK [LogprobsResultCandidate].
 */
internal fun GenAiLogprobsResultCandidate.fromGenaiSdk(): LogprobsResultCandidate =
  LogprobsResultCandidate(token = token, tokenId = tokenId, logProbability = logProbability)

/**
 * Converts an ADK [LogprobsResultCandidate] to a [GenAiLogprobsResultCandidate] for the GenAI SDK.
 */
internal fun LogprobsResultCandidate.toGenaiSdk(): GenAiLogprobsResultCandidate =
  GenAiLogprobsResultCandidate(token = token, tokenId = tokenId, logProbability = logProbability)

// --- LogprobsResultTopCandidates ---
/**
 * Converts a [GenAiLogprobsResultTopCandidates] from the GenAI SDK to an ADK
 * [LogprobsResultTopCandidates].
 */
internal fun GenAiLogprobsResultTopCandidates.fromGenaiSdk(): LogprobsResultTopCandidates =
  LogprobsResultTopCandidates(candidates = candidates?.map { it.fromGenaiSdk() })

/**
 * Converts an ADK [LogprobsResultTopCandidates] to a [GenAiLogprobsResultTopCandidates] for the
 * GenAI SDK.
 */
internal fun LogprobsResultTopCandidates.toGenaiSdk(): GenAiLogprobsResultTopCandidates =
  GenAiLogprobsResultTopCandidates(candidates = candidates?.map { it.toGenaiSdk() })

// --- Citation ---
/** Converts a [GenAiCitation] from the GenAI SDK to an ADK [Citation]. */
internal fun GenAiCitation.fromGenaiSdk(): Citation =
  Citation(title = title, uri = uri, startIndex = startIndex, endIndex = endIndex)

/** Converts an ADK [Citation] to a [GenAiCitation] for the GenAI SDK. */
internal fun Citation.toGenaiSdk(): GenAiCitation =
  GenAiCitation(title = title, uri = uri, startIndex = startIndex, endIndex = endIndex)

// --- CitationMetadata ---
/** Converts a [GenAiCitationMetadata] from the GenAI SDK to an ADK [CitationMetadata]. */
internal fun GenAiCitationMetadata.fromGenaiSdk(): CitationMetadata =
  CitationMetadata(citationSources = citations?.map { it.fromGenaiSdk() } ?: emptyList())

/** Converts an ADK [CitationMetadata] to a [GenAiCitationMetadata] for the GenAI SDK. */
internal fun CitationMetadata.toGenaiSdk(): GenAiCitationMetadata =
  GenAiCitationMetadata(citations = citationSources.map { it.toGenaiSdk() })

// --- Content ---
/** Converts a [GenAiContent] from the GenAI SDK to an ADK [Content]. */
internal fun GenAiContent.fromGenaiSdk(): Content =
  Content(role = role, parts = parts?.map { it.fromGenaiSdk() } ?: emptyList())

/** Converts an ADK [Content] to a [GenAiContent] for the GenAI SDK. */
internal fun Content.toGenaiSdk(): GenAiContent =
  GenAiContent(role = role, parts = parts.map { it.toGenaiSdk() })

// --- FileData ---
/** Converts a [GenAiFileData] from the GenAI SDK to an ADK [FileData]. */
internal fun GenAiFileData.fromGenaiSdk(): FileData =
  FileData(mimeType = mimeType, displayName = displayName, fileUri = fileUri)

/** Converts an ADK [FileData] to a [GenAiFileData] for the GenAI SDK. */
internal fun FileData.toGenaiSdk(): GenAiFileData =
  GenAiFileData(mimeType = mimeType, displayName = displayName, fileUri = fileUri)

// --- FunctionCall ---
/** Converts a [GenAiFunctionCall] from the GenAI SDK to an ADK [FunctionCall]. */
internal fun GenAiFunctionCall.fromGenaiSdk(): FunctionCall =
  FunctionCall(
    name = name ?: "",
    args = args?.mapValues { it.value.toAny() } ?: emptyMap(),
    id = id,
    partialArgs = partialArgs?.map { it.fromGenaiSdk() },
    willContinue = willContinue,
  )

/** Converts an ADK [FunctionCall] to a [GenAiFunctionCall] for the GenAI SDK. */
internal fun FunctionCall.toGenaiSdk(): GenAiFunctionCall =
  GenAiFunctionCall(
    name = name,
    args = args.mapValues { it.value.toJsonElement() },
    id = id,
    partialArgs = partialArgs?.takeIf { it.isNotEmpty() }?.map { it.toGenaiSdk() },
    willContinue = willContinue?.takeIf { it },
  )

// --- FunctionDeclaration ---
/** Converts a [GenAiFunctionDeclaration] from the GenAI SDK to an ADK [FunctionDeclaration]. */
internal fun GenAiFunctionDeclaration.fromGenaiSdk(): FunctionDeclaration =
  FunctionDeclaration(
    name = name ?: "",
    description = description ?: "",
    parameters = parameters?.toKtSchema(),
  )

/** Converts an ADK [FunctionDeclaration] to a [GenAiFunctionDeclaration] for the GenAI SDK. */
internal fun FunctionDeclaration.toGenaiSdk(): GenAiFunctionDeclaration =
  GenAiFunctionDeclaration(
    name = name,
    description = description,
    parameters = parameters?.toGenAiSchema(),
  )

// --- FunctionResponse ---
/** Converts a [GenAiFunctionResponse] from the GenAI SDK to an ADK [FunctionResponse]. */
internal fun GenAiFunctionResponse.fromGenaiSdk(): FunctionResponse =
  FunctionResponse(
    name = name ?: "",
    response = response?.mapValues { it.value.toAny() } ?: emptyMap(),
    id = id,
  )

/** Converts an ADK [FunctionResponse] to a [GenAiFunctionResponse] for the GenAI SDK. */
internal fun FunctionResponse.toGenaiSdk(): GenAiFunctionResponse =
  GenAiFunctionResponse(
    name = name,
    response = response.mapValues { it.value.toJsonElement() },
    id = id,
  )

// --- GenerateContentConfig ---
/**
 * Converts a [GenAiGenerateContentConfig] from the GenAI SDK to an ADK [GenerateContentConfig].
 *
 * Note: the Kotlin SDK does not model the `labels` field on `GenerateContentConfig`, so it is left
 * as `null` after a round-trip from SDK to ADK.
 */
internal fun GenAiGenerateContentConfig.fromGenaiSdk(): GenerateContentConfig =
  GenerateContentConfig(
    tools = tools?.map { it.fromGenaiSdk() },
    labels = null,
    systemInstruction = systemInstruction?.fromGenaiSdk(),
    temperature = temperature?.toFloat(),
    topP = topP?.toFloat(),
    topK = topK?.toInt(),
    candidateCount = candidateCount,
    maxOutputTokens = maxOutputTokens,
    stopSequences = stopSequences,
    presencePenalty = presencePenalty?.toFloat(),
    frequencyPenalty = frequencyPenalty?.toFloat(),
    responseLogprobs = responseLogprobs,
    responseMimeType = responseMimeType,
    responseSchema = responseSchema?.toKtSchema(),
    thinkingConfig = thinkingConfig?.fromGenaiSdk(),
    toolConfig = toolConfig?.fromGenaiSdk(),
    safetySettings = safetySettings?.map { it.fromGenaiSdk() },
    mediaResolution = mediaResolution?.toKt(),
    serviceTier = serviceTier?.toKt(),
  )

/**
 * Converts an ADK [GenerateContentConfig] to a [GenAiGenerateContentConfig] for the GenAI SDK.
 *
 * Note: the Kotlin SDK does not currently model the `labels` field, so it is dropped on conversion.
 */
internal fun GenerateContentConfig.toGenaiSdk(): GenAiGenerateContentConfig =
  GenAiGenerateContentConfig(
    tools = tools?.map { it.toGenaiSdk() },
    systemInstruction = systemInstruction?.toGenaiSdk(),
    temperature = temperature?.toDouble(),
    topP = topP?.toDouble(),
    topK = topK?.toDouble(),
    candidateCount = candidateCount,
    maxOutputTokens = maxOutputTokens,
    stopSequences = stopSequences,
    presencePenalty = presencePenalty?.toDouble(),
    frequencyPenalty = frequencyPenalty?.toDouble(),
    responseLogprobs = responseLogprobs,
    responseMimeType = responseMimeType,
    responseSchema = responseSchema?.toGenAiSchema(),
    thinkingConfig = thinkingConfig?.toGenaiSdk(),
    toolConfig = toolConfig?.toGenaiSdk(),
    safetySettings = safetySettings?.map { it.toGenaiSdk() },
    mediaResolution = mediaResolution?.toGenaiSdk(),
    serviceTier = serviceTier?.toGenaiSdk(),
  )

// --- FunctionCallingConfig ---
/** Converts a [GenAiFunctionCallingConfig] from the GenAI SDK to an ADK [FunctionCallingConfig]. */
internal fun GenAiFunctionCallingConfig.fromGenaiSdk(): FunctionCallingConfig =
  FunctionCallingConfig(allowedFunctionNames = allowedFunctionNames)

/** Converts an ADK [FunctionCallingConfig] to a [GenAiFunctionCallingConfig] for the GenAI SDK. */
internal fun FunctionCallingConfig.toGenaiSdk(): GenAiFunctionCallingConfig =
  GenAiFunctionCallingConfig(allowedFunctionNames = allowedFunctionNames)

// --- ToolConfig ---
/** Converts a [GenAiToolConfig] from the GenAI SDK to an ADK [ToolConfig]. */
internal fun GenAiToolConfig.fromGenaiSdk(): ToolConfig =
  ToolConfig(functionCallingConfig = functionCallingConfig?.fromGenaiSdk())

/** Converts an ADK [ToolConfig] to a [GenAiToolConfig] for the GenAI SDK. */
internal fun ToolConfig.toGenaiSdk(): GenAiToolConfig =
  GenAiToolConfig(functionCallingConfig = functionCallingConfig?.toGenaiSdk())

// --- SafetySetting ---
/** Converts a [GenAiSafetySetting] from the GenAI SDK to an ADK [SafetySetting]. */
internal fun GenAiSafetySetting.fromGenaiSdk(): SafetySetting =
  SafetySetting(category = category?.toKt(), threshold = threshold?.toKt())

/** Converts an ADK [SafetySetting] to a [GenAiSafetySetting] for the GenAI SDK. */
internal fun SafetySetting.toGenaiSdk(): GenAiSafetySetting =
  GenAiSafetySetting(category = category?.toGenaiSdk(), threshold = threshold?.toGenaiSdk())

// --- GenerateContentResponse ---
/**
 * Converts a [GenAiGenerateContentResponse] from the GenAI SDK to an ADK [GenerateContentResponse].
 */
internal fun GenAiGenerateContentResponse.fromGenaiSdk(): GenerateContentResponse =
  GenerateContentResponse(
    candidates = candidates?.map { it.fromGenaiSdk() } ?: emptyList(),
    promptFeedback = promptFeedback?.fromGenaiSdk(),
    usageMetadata = usageMetadata?.fromGenaiSdk(),
    modelVersion = modelVersion,
  )

/**
 * Converts an ADK [GenerateContentResponse] to a [GenAiGenerateContentResponse] for the GenAI SDK.
 */
internal fun GenerateContentResponse.toGenaiSdk(): GenAiGenerateContentResponse =
  GenAiGenerateContentResponse(
    candidates = candidates.map { it.toGenaiSdk() },
    promptFeedback = promptFeedback?.toGenaiSdk(),
    usageMetadata = usageMetadata?.toGenaiSdk(),
    modelVersion = modelVersion,
  )

// --- GroundingMetadata ---
/** Converts a [GenAiGroundingMetadata] from the GenAI SDK to an ADK [GroundingMetadata]. */
internal fun GenAiGroundingMetadata.fromGenaiSdk(): GroundingMetadata =
  GroundingMetadata(
    imageSearchQueries = imageSearchQueries ?: emptyList(),
    groundingChunks = groundingChunks?.map { it.fromGenaiSdk() },
    groundingSupports = groundingSupports?.map { it.fromGenaiSdk() },
    webSearchQueries = webSearchQueries,
    searchEntryPoint = searchEntryPoint?.fromGenaiSdk(),
    retrievalMetadata = retrievalMetadata?.fromGenaiSdk(),
  )

/** Converts an ADK [GroundingMetadata] to a [GenAiGroundingMetadata] for the GenAI SDK. */
internal fun GroundingMetadata.toGenaiSdk(): GenAiGroundingMetadata =
  GenAiGroundingMetadata(
    imageSearchQueries = imageSearchQueries,
    groundingChunks = groundingChunks?.map { it.toGenaiSdk() },
    groundingSupports = groundingSupports?.map { it.toGenaiSdk() },
    webSearchQueries = webSearchQueries,
    searchEntryPoint = searchEntryPoint?.toGenaiSdk(),
    retrievalMetadata = retrievalMetadata?.toGenaiSdk(),
  )

// --- GroundingChunk ---
/** Converts a [GenAiGroundingChunk] from the GenAI SDK to an ADK [GroundingChunk]. */
internal fun GenAiGroundingChunk.fromGenaiSdk(): GroundingChunk =
  GroundingChunk(web = web?.fromGenaiSdk(), retrievedContext = retrievedContext?.fromGenaiSdk())

/** Converts an ADK [GroundingChunk] to a [GenAiGroundingChunk] for the GenAI SDK. */
internal fun GroundingChunk.toGenaiSdk(): GenAiGroundingChunk =
  GenAiGroundingChunk(web = web?.toGenaiSdk(), retrievedContext = retrievedContext?.toGenaiSdk())

// --- GroundingChunkWeb ---
/** Converts a [GenAiGroundingChunkWeb] from the GenAI SDK to an ADK [GroundingChunkWeb]. */
internal fun GenAiGroundingChunkWeb.fromGenaiSdk(): GroundingChunkWeb =
  GroundingChunkWeb(uri = uri, title = title, domain = domain)

/** Converts an ADK [GroundingChunkWeb] to a [GenAiGroundingChunkWeb] for the GenAI SDK. */
internal fun GroundingChunkWeb.toGenaiSdk(): GenAiGroundingChunkWeb =
  GenAiGroundingChunkWeb(uri = uri, title = title, domain = domain)

// --- GroundingChunkRetrievedContext ---
/**
 * Converts a [GenAiGroundingChunkRetrievedContext] from the GenAI SDK to an ADK
 * [GroundingChunkRetrievedContext].
 */
internal fun GenAiGroundingChunkRetrievedContext.fromGenaiSdk(): GroundingChunkRetrievedContext =
  GroundingChunkRetrievedContext(uri = uri, title = title, text = text)

/**
 * Converts an ADK [GroundingChunkRetrievedContext] to a [GenAiGroundingChunkRetrievedContext] for
 * the GenAI SDK.
 */
internal fun GroundingChunkRetrievedContext.toGenaiSdk(): GenAiGroundingChunkRetrievedContext =
  GenAiGroundingChunkRetrievedContext(uri = uri, title = title, text = text)

// --- GroundingSupport ---
/** Converts a [GenAiGroundingSupport] from the GenAI SDK to an ADK [GroundingSupport]. */
internal fun GenAiGroundingSupport.fromGenaiSdk(): GroundingSupport =
  GroundingSupport(
    segment = segment?.fromGenaiSdk(),
    groundingChunkIndices = groundingChunkIndices,
    confidenceScores = confidenceScores?.map { it.toFloat() },
  )

/** Converts an ADK [GroundingSupport] to a [GenAiGroundingSupport] for the GenAI SDK. */
internal fun GroundingSupport.toGenaiSdk(): GenAiGroundingSupport =
  GenAiGroundingSupport(
    segment = segment?.toGenaiSdk(),
    groundingChunkIndices = groundingChunkIndices,
    confidenceScores = confidenceScores?.map { it.toDouble() },
  )

// --- Segment ---
/** Converts a [GenAiSegment] from the GenAI SDK to an ADK [Segment]. */
internal fun GenAiSegment.fromGenaiSdk(): Segment =
  Segment(startIndex = startIndex, endIndex = endIndex, partIndex = partIndex, text = text)

/** Converts an ADK [Segment] to a [GenAiSegment] for the GenAI SDK. */
internal fun Segment.toGenaiSdk(): GenAiSegment =
  GenAiSegment(startIndex = startIndex, endIndex = endIndex, partIndex = partIndex, text = text)

// --- SearchEntryPoint ---
/** Converts a [GenAiSearchEntryPoint] from the GenAI SDK to an ADK [SearchEntryPoint]. */
internal fun GenAiSearchEntryPoint.fromGenaiSdk(): SearchEntryPoint =
  SearchEntryPoint(renderedContent = renderedContent)

/** Converts an ADK [SearchEntryPoint] to a [GenAiSearchEntryPoint] for the GenAI SDK. */
internal fun SearchEntryPoint.toGenaiSdk(): GenAiSearchEntryPoint =
  GenAiSearchEntryPoint(renderedContent = renderedContent)

// --- RetrievalMetadata ---
/** Converts a [GenAiRetrievalMetadata] from the GenAI SDK to an ADK [RetrievalMetadata]. */
internal fun GenAiRetrievalMetadata.fromGenaiSdk(): RetrievalMetadata =
  RetrievalMetadata(
    googleSearchDynamicRetrievalScore = googleSearchDynamicRetrievalScore?.toFloat()
  )

/** Converts an ADK [RetrievalMetadata] to a [GenAiRetrievalMetadata] for the GenAI SDK. */
internal fun RetrievalMetadata.toGenaiSdk(): GenAiRetrievalMetadata =
  GenAiRetrievalMetadata(
    googleSearchDynamicRetrievalScore = googleSearchDynamicRetrievalScore?.toDouble()
  )

// --- PromptFeedback ---
/**
 * Converts a [GenAiGenerateContentResponsePromptFeedback] from the GenAI SDK to an ADK
 * [PromptFeedback].
 */
internal fun GenAiGenerateContentResponsePromptFeedback.fromGenaiSdk(): PromptFeedback =
  PromptFeedback(blockReason = blockReason?.toKt(), blockReasonMessage = blockReasonMessage)

/**
 * Converts an ADK [PromptFeedback] to a [GenAiGenerateContentResponsePromptFeedback] for the GenAI
 * SDK.
 */
internal fun PromptFeedback.toGenaiSdk(): GenAiGenerateContentResponsePromptFeedback =
  GenAiGenerateContentResponsePromptFeedback(
    blockReason = blockReason?.toGenaiSdk(),
    blockReasonMessage = blockReasonMessage,
  )

// --- GoogleMaps ---
/** Converts a [GenAiGoogleMaps] from the GenAI SDK to an ADK [GoogleMaps]. */
internal fun GenAiGoogleMaps.fromGenaiSdk(): GoogleMaps = GoogleMaps(enableWidget = enableWidget)

/** Converts an ADK [GoogleMaps] to a [GenAiGoogleMaps] for the GenAI SDK. */
internal fun GoogleMaps.toGenaiSdk(): GenAiGoogleMaps = GenAiGoogleMaps(enableWidget = enableWidget)

// --- GoogleSearch ---
/** Converts a [GenAiGoogleSearch] from the GenAI SDK to an ADK [GoogleSearch]. */
internal fun GenAiGoogleSearch.fromGenaiSdk(): GoogleSearch =
  GoogleSearch(excludeDomains = excludeDomains ?: emptyList())

/** Converts an ADK [GoogleSearch] to a [GenAiGoogleSearch] for the GenAI SDK. */
internal fun GoogleSearch.toGenaiSdk(): GenAiGoogleSearch =
  GenAiGoogleSearch(excludeDomains = excludeDomains.takeIf { it.isNotEmpty() })

// --- Schema ---
/** Converts a [GenAiSchema] from the GenAI SDK to an ADK [Schema]. */
internal fun GenAiSchema.toKtSchema(): Schema =
  Schema(
    type = type?.toKt(),
    properties = properties?.mapValues { it.value.toKtSchema() },
    items = items?.toKtSchema(),
    required = required,
    description = description,
    enum = enum,
  )

/** Converts an ADK [Schema] to a [GenAiSchema] for the GenAI SDK. */
internal fun Schema.toGenAiSchema(): GenAiSchema =
  GenAiSchema(
    type = type?.toGenaiSdk(),
    properties = properties?.mapValues { it.value.toGenAiSchema() },
    items = items?.toGenAiSchema(),
    required = required,
    description = description,
    enum = enum,
  )

// --- UrlContext ---
/** Converts a [GenAiUrlContext] from the GenAI SDK to an ADK [UrlContext]. */
internal fun GenAiUrlContext.fromGenaiSdk(): UrlContext = UrlContext()

/** Converts an ADK [UrlContext] to a [GenAiUrlContext] for the GenAI SDK. */
internal fun UrlContext.toGenaiSdk(): GenAiUrlContext = GenAiUrlContext()

// --- Tool ---
/** Converts a [GenAiTool] from the GenAI SDK to an ADK [Tool]. */
internal fun GenAiTool.fromGenaiSdk(): Tool =
  Tool(
    functionDeclarations = functionDeclarations?.map { it.fromGenaiSdk() },
    googleSearch = googleSearch?.fromGenaiSdk(),
    googleMaps = googleMaps?.fromGenaiSdk(),
    retrieval = retrieval?.fromGenaiSdk(),
    urlContext = urlContext?.fromGenaiSdk(),
  )

/** Converts an ADK [Tool] to a [GenAiTool] for the GenAI SDK. */
internal fun Tool.toGenaiSdk(): GenAiTool =
  GenAiTool(
    functionDeclarations = functionDeclarations?.map { it.toGenaiSdk() },
    googleSearch = googleSearch?.toGenaiSdk(),
    googleMaps = googleMaps?.toGenaiSdk(),
    retrieval = retrieval?.toGenaiSdk(),
    urlContext = urlContext?.toGenaiSdk(),
  )

// --- Retrieval ---
/** Converts a [GenAiRetrieval] from the GenAI SDK to an ADK [Retrieval]. */
internal fun GenAiRetrieval.fromGenaiSdk(): Retrieval =
  Retrieval(vertexAiSearch = vertexAiSearch?.fromGenaiSdk())

/** Converts an ADK [Retrieval] to a [GenAiRetrieval] for the GenAI SDK. */
internal fun Retrieval.toGenaiSdk(): GenAiRetrieval =
  GenAiRetrieval(vertexAiSearch = vertexAiSearch?.toGenaiSdk())

// --- VertexAISearch ---
/** Converts a [GenAiVertexAISearch] from the GenAI SDK to an ADK [VertexAISearch]. */
internal fun GenAiVertexAISearch.fromGenaiSdk(): VertexAISearch =
  VertexAISearch(
    dataStoreSpecs = dataStoreSpecs?.map { it.fromGenaiSdk() },
    datastore = datastore,
    engine = engine,
    filter = filter,
    maxResults = maxResults,
  )

/** Converts an ADK [VertexAISearch] to a [GenAiVertexAISearch] for the GenAI SDK. */
internal fun VertexAISearch.toGenaiSdk(): GenAiVertexAISearch =
  GenAiVertexAISearch(
    dataStoreSpecs = dataStoreSpecs?.map { it.toGenaiSdk() },
    datastore = datastore,
    engine = engine,
    filter = filter,
    maxResults = maxResults,
  )

// --- VertexAISearchDataStoreSpec ---
/**
 * Converts a [GenAiVertexAISearchDataStoreSpec] from the GenAI SDK to an ADK
 * [VertexAISearchDataStoreSpec].
 */
internal fun GenAiVertexAISearchDataStoreSpec.fromGenaiSdk(): VertexAISearchDataStoreSpec =
  VertexAISearchDataStoreSpec(dataStore = dataStore, filter = filter)

/**
 * Converts an ADK [VertexAISearchDataStoreSpec] to a [GenAiVertexAISearchDataStoreSpec] for the
 * GenAI SDK.
 */
internal fun VertexAISearchDataStoreSpec.toGenaiSdk(): GenAiVertexAISearchDataStoreSpec =
  GenAiVertexAISearchDataStoreSpec(dataStore = dataStore, filter = filter)

// --- ModalityTokenCount ---
/** Converts a [GenAiModalityTokenCount] from the GenAI SDK to an ADK [ModalityTokenCount]. */
internal fun GenAiModalityTokenCount.fromGenaiSdk(): ModalityTokenCount =
  ModalityTokenCount(modality = modality?.toKt(), tokenCount = tokenCount)

/** Converts an ADK [ModalityTokenCount] to a [GenAiModalityTokenCount] for the GenAI SDK. */
internal fun ModalityTokenCount.toGenaiSdk(): GenAiModalityTokenCount =
  GenAiModalityTokenCount(modality = modality?.toGenaiSdk(), tokenCount = tokenCount)

// --- UsageMetadata ---
/**
 * Converts a [GenAiGenerateContentResponseUsageMetadata] from the GenAI SDK to an ADK
 * [UsageMetadata].
 */
internal fun GenAiGenerateContentResponseUsageMetadata.fromGenaiSdk(): UsageMetadata =
  UsageMetadata(
    promptTokenCount = promptTokenCount,
    candidatesTokenCount = candidatesTokenCount,
    totalTokenCount = totalTokenCount,
    thoughtsTokenCount = thoughtsTokenCount,
    toolUsePromptTokenCount = toolUsePromptTokenCount,
    cachedContentTokenCount = cachedContentTokenCount,
    promptTokensDetails = promptTokensDetails?.map { it.fromGenaiSdk() },
    candidatesTokensDetails = candidatesTokensDetails?.map { it.fromGenaiSdk() },
  )

/**
 * Converts an ADK [UsageMetadata] to a [GenAiGenerateContentResponseUsageMetadata] for the GenAI
 * SDK.
 */
internal fun UsageMetadata.toGenaiSdk(): GenAiGenerateContentResponseUsageMetadata =
  GenAiGenerateContentResponseUsageMetadata(
    promptTokenCount = promptTokenCount,
    candidatesTokenCount = candidatesTokenCount,
    totalTokenCount = totalTokenCount,
    thoughtsTokenCount = thoughtsTokenCount,
    toolUsePromptTokenCount = toolUsePromptTokenCount,
    cachedContentTokenCount = cachedContentTokenCount,
    promptTokensDetails = promptTokensDetails?.map { it.toGenaiSdk() },
    candidatesTokensDetails = candidatesTokensDetails?.map { it.toGenaiSdk() },
  )

// --- Part ---
/** Converts a [GenAiPart] from the GenAI SDK to an ADK [Part]. */
internal fun GenAiPart.fromGenaiSdk(): Part =
  Part(
    text = text,
    inlineData = inlineData?.fromGenaiSdk(),
    fileData = fileData?.fromGenaiSdk(),
    functionCall = functionCall?.fromGenaiSdk(),
    functionResponse = functionResponse?.fromGenaiSdk(),
    thought = thought,
    thoughtSignature = thoughtSignature,
    videoMetadata = videoMetadata?.fromGenaiSdk(),
    partMetadata = partMetadata?.mapValues { it.value.toAny() },
  )

/** Converts an ADK [Part] to a [GenAiPart] for the GenAI SDK. */
internal fun Part.toGenaiSdk(): GenAiPart =
  GenAiPart(
    text = text,
    inlineData = inlineData?.toGenaiSdk(),
    fileData = fileData?.toGenaiSdk(),
    functionCall = functionCall?.toGenaiSdk(),
    functionResponse = functionResponse?.toGenaiSdk(),
    thought = thought,
    thoughtSignature = thoughtSignature,
    videoMetadata = videoMetadata?.toGenaiSdk(),
    partMetadata = partMetadata?.mapValues { it.value.toJsonElement() },
  )

// --- VideoMetadata ---
/** Converts a [GenAiVideoMetadata] from the GenAI SDK to an ADK [VideoMetadata]. */
internal fun GenAiVideoMetadata.fromGenaiSdk(): VideoMetadata =
  VideoMetadata(startOffset = startOffset, endOffset = endOffset, fps = fps)

/** Converts an ADK [VideoMetadata] to a [GenAiVideoMetadata] for the GenAI SDK. */
internal fun VideoMetadata.toGenaiSdk(): GenAiVideoMetadata =
  GenAiVideoMetadata(startOffset = startOffset, endOffset = endOffset, fps = fps)

// --- PartialArg ---
/** Converts a [GenAiPartialArg] from the GenAI SDK to an ADK [PartialArg]. */
internal fun GenAiPartialArg.fromGenaiSdk(): PartialArg =
  PartialArg(
    value =
      boolValue?.let { PartialArgValue.BoolValue(it) }
        ?: numberValue?.let { PartialArgValue.NumberValue(it) }
        ?: stringValue?.let { PartialArgValue.StringValue(it) }
        ?: nullValue?.let { PartialArgValue.NullValue },
    jsonPath = jsonPath,
    willContinue = willContinue,
  )

/** Converts an ADK [PartialArg] to a [GenAiPartialArg] for the GenAI SDK. */
internal fun PartialArg.toGenaiSdk(): GenAiPartialArg {
  val v = this.value
  return GenAiPartialArg(
    boolValue = (v as? PartialArgValue.BoolValue)?.value,
    numberValue = (v as? PartialArgValue.NumberValue)?.value,
    stringValue = (v as? PartialArgValue.StringValue)?.value,
    nullValue = if (v is PartialArgValue.NullValue) GenAiNullValue.NULL_VALUE else null,
    jsonPath = jsonPath,
    willContinue = willContinue,
  )
}

// --- ThinkingConfig ---
/** Converts a [GenAiThinkingConfig] from the GenAI SDK to an ADK [ThinkingConfig]. */
internal fun GenAiThinkingConfig.fromGenaiSdk(): ThinkingConfig =
  ThinkingConfig(
    includeThoughts = includeThoughts,
    thinkingBudget = thinkingBudget,
    thinkingLevel = thinkingLevel?.toKt(),
  )

/** Converts an ADK [ThinkingConfig] to a [GenAiThinkingConfig] for the GenAI SDK. */
internal fun ThinkingConfig.toGenaiSdk(): GenAiThinkingConfig =
  GenAiThinkingConfig(
    includeThoughts = includeThoughts,
    thinkingBudget = thinkingBudget,
    thinkingLevel = thinkingLevel?.toGenaiSdk(),
  )
