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

import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive

class GenaiConvertersTest {
  @Test
  fun functionResponse_convertsCorrectly() {
    val adkFunctionResponse =
      FunctionResponse(
        name = "myFunction",
        response = mapOf("result" to "success"),
        id = "call-123",
      )

    val genaiFunctionResponse = adkFunctionResponse.toGenaiSdk()

    assertEquals("myFunction", genaiFunctionResponse.name)
    assertEquals(mapOf("result" to JsonPrimitive("success")), genaiFunctionResponse.response)
    assertEquals("call-123", genaiFunctionResponse.id)

    val convertedBack = genaiFunctionResponse.fromGenaiSdk()
    assertEquals(adkFunctionResponse, convertedBack)
  }

  @Test
  fun functionCall_convertsCorrectly() {
    val adkFunctionCall =
      FunctionCall(name = "myFunction", args = mapOf("arg1" to "value1"), id = "call-123")

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()

    assertEquals("myFunction", genaiFunctionCall.name)
    assertEquals(mapOf("arg1" to JsonPrimitive("value1")), genaiFunctionCall.args)
    assertEquals("call-123", genaiFunctionCall.id)
    assertEquals(null, genaiFunctionCall.partialArgs)
    assertEquals(null, genaiFunctionCall.willContinue)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(adkFunctionCall, convertedBack)
  }

  @Test
  fun functionCall_convertsCorrectly_withEmptyFields() {
    val adkFunctionCall =
      FunctionCall(name = "myFunction", partialArgs = emptyList(), willContinue = false)

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()
    assertEquals(null, genaiFunctionCall.partialArgs)
    assertEquals(null, genaiFunctionCall.willContinue)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(null, convertedBack.partialArgs)
    assertEquals(null, convertedBack.willContinue)
  }

  @Test
  fun jsonElementConversions_roundTripNestedValues() {
    // Exercises Any?.toJsonElement() (Map null-stripping, Iterable, Array, primitives) and
    // JsonElement.toAny() (JsonObject, JsonArray, primitive coercions) directly.
    val original: Map<String, Any?> =
      mapOf(
        "string" to "text",
        "boolean" to true,
        "int" to 7,
        "long" to 10_000_000_000L,
        "double" to 1.5,
        "list" to listOf("a", 1),
        "array" to arrayOf<Any>("b", 2),
        "nested" to mapOf("inner" to "v"),
        "dropped" to null,
      )

    val roundTripped = original.toJsonElement().toAny()

    // `dropped` is omitted (null map entries are stripped) and arrays come back as lists.
    assertEquals(
      mapOf(
        "string" to "text",
        "boolean" to true,
        "int" to 7,
        "long" to 10_000_000_000L,
        "double" to 1.5,
        "list" to listOf("a", 1),
        "array" to listOf("b", 2),
        "nested" to mapOf("inner" to "v"),
      ),
      roundTripped,
    )
  }

  @Test
  fun functionCall_convertsCorrectly_withStreamingFields() {
    val partialArgs = listOf(PartialArg(value = null, jsonPath = "$.arg"))
    val adkFunctionCall =
      FunctionCall(name = "myFunction", partialArgs = partialArgs, willContinue = true)

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()
    assertNotNull(genaiFunctionCall.partialArgs)
    assertEquals(true, genaiFunctionCall.willContinue)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(partialArgs, convertedBack.partialArgs)
    assertEquals(true, convertedBack.willContinue)
  }

  @Test
  fun blob_convertsCorrectly() {
    val adkBlob =
      Blob(mimeType = "image/png", displayName = "myImage.png", data = byteArrayOf(1, 2, 3))

    val genaiBlob = adkBlob.toGenaiSdk()

    assertEquals("image/png", genaiBlob.mimeType)
    assertEquals("myImage.png", genaiBlob.displayName)
    // Genai Sdk Blob data is ByteArray
    val genaiData = genaiBlob.data!!
    assertEquals(1, genaiData[0])
    assertEquals(2, genaiData[1])
    assertEquals(3, genaiData[2])

    val convertedBack = genaiBlob.fromGenaiSdk()

    assertEquals(adkBlob, convertedBack)
  }

  @Test
  fun candidate_convertsCorrectly() {
    val adkCandidate =
      Candidate(
        content = userMessage("hello"),
        finishReason = FinishReason.STOP,
        finishMessage = "Done",
        citationMetadata = CitationMetadata(citationSources = emptyList()),
      )
    val genaiCandidate = adkCandidate.toGenaiSdk()
    assertEquals(Role.USER, genaiCandidate.content?.role)
    assertEquals(com.google.genai.kotlin.types.FinishReason.STOP, genaiCandidate.finishReason)
    assertEquals("Done", genaiCandidate.finishMessage)
    assertNotNull(genaiCandidate.citationMetadata)

    val convertedBack = genaiCandidate.fromGenaiSdk()
    assertEquals(adkCandidate, convertedBack)
  }

  @Test
  fun citation_convertsCorrectly() {
    val adkCitation =
      Citation(title = "Example", uri = "https://example.com", startIndex = 3, endIndex = 17)
    val genaiCitation = adkCitation.toGenaiSdk()
    assertEquals("https://example.com", genaiCitation.uri)
    assertEquals(3, genaiCitation.startIndex)
    assertEquals(17, genaiCitation.endIndex)

    val convertedBack = genaiCitation.fromGenaiSdk()
    assertEquals(adkCitation, convertedBack)
  }

  @Test
  fun citationMetadata_convertsCorrectly() {
    val adkCitationMetadata = CitationMetadata()
    val genaiCitationMetadata = adkCitationMetadata.toGenaiSdk()
    val convertedBack = genaiCitationMetadata.fromGenaiSdk()
    assertEquals(adkCitationMetadata, convertedBack)
  }

  @Test
  fun content_convertsCorrectly() {
    val adkContent = userMessage("hello")
    val genaiContent = adkContent.toGenaiSdk()
    assertEquals(Role.USER, genaiContent.role)
    assertEquals(1, genaiContent.parts?.size)
    assertEquals("hello", genaiContent.parts?.get(0)?.text)

    val convertedBack = genaiContent.fromGenaiSdk()
    assertEquals(adkContent, convertedBack)
  }

  @Test
  fun fileData_convertsCorrectly() {
    val adkFileData = FileData(mimeType = "text/plain", displayName = "test.txt", fileUri = "uri")
    val genaiFileData = adkFileData.toGenaiSdk()
    assertEquals("text/plain", genaiFileData.mimeType)
    assertEquals("test.txt", genaiFileData.displayName)
    assertEquals("uri", genaiFileData.fileUri)

    val convertedBack = genaiFileData.fromGenaiSdk()
    assertEquals(adkFileData, convertedBack)
  }

  @Test
  fun functionDeclaration_convertsCorrectly() {
    val adkFunctionDeclaration =
      FunctionDeclaration(
        name = "myFunc",
        description = "desc",
        parameters = Schema(type = Type.STRING),
      )
    val genaiFunctionDeclaration = adkFunctionDeclaration.toGenaiSdk()
    assertEquals("myFunc", genaiFunctionDeclaration.name)
    assertEquals("desc", genaiFunctionDeclaration.description)
    assertNotNull(genaiFunctionDeclaration.parameters)

    val convertedBack = genaiFunctionDeclaration.fromGenaiSdk()
    assertEquals(adkFunctionDeclaration, convertedBack)
  }

  @Test
  fun generateContentConfig_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        tools =
          listOf(
            Tool(functionDeclarations = listOf(FunctionDeclaration(name = "a", description = "b")))
          ),
        thinkingConfig = ThinkingConfig(includeThoughts = true, thinkingLevel = ThinkingLevel.LOW),
      )
    val genaiConfig = adkConfig.toGenaiSdk()
    val genaiTools = genaiConfig.tools!!
    assertEquals(1, genaiTools.size)
    assertEquals(1, genaiTools[0].functionDeclarations?.size)
    assertEquals(true, genaiConfig.thinkingConfig?.includeThoughts)
    assertEquals(
      com.google.genai.kotlin.types.ThinkingLevel.LOW,
      genaiConfig.thinkingConfig?.thinkingLevel,
    )

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
  }

  @Test
  fun generateContentConfig_responseSchema_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        responseMimeType = "application/json",
        responseSchema =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("name" to Schema(type = Type.STRING)),
            required = listOf("name"),
          ),
      )

    val genaiConfig = adkConfig.toGenaiSdk()

    assertEquals("application/json", genaiConfig.responseMimeType)
    assertNotNull(genaiConfig.responseSchema)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
  }

  @Test
  fun generateContentConfig_topKIntegerRoundTrip_preservesValue() {
    // 0.5f widens cleanly to 0.5 because 0.5 is exactly representable in binary floating point.
    // 0.9f does NOT widen to 0.9 (it becomes 0.8999999761581421), so we compare via Float and
    // tolerate the widening loss explicitly.
    val adkConfig = GenerateContentConfig(temperature = 0.5f, topP = 0.9f, topK = 40)

    val genaiConfig = adkConfig.toGenaiSdk()

    assertEquals(40.0, genaiConfig.topK)
    assertEquals(0.5, genaiConfig.temperature)
    assertEquals(0.9f, genaiConfig.topP?.toFloat())

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
    assertEquals(40, convertedBack.topK)
  }

  @Test
  fun generateContentConfig_topKFractionalFromGenaiSdk_truncatesToInt() {
    val genaiConfig = com.google.genai.kotlin.types.GenerateContentConfig(topK = 40.7)

    val adkConfig = genaiConfig.fromGenaiSdk()

    assertEquals(40, adkConfig.topK)
  }

  @Test
  fun thinkingConfig_convertsCorrectly() {
    val adkThinkingConfig =
      ThinkingConfig(
        includeThoughts = true,
        thinkingBudget = 1024,
        thinkingLevel = ThinkingLevel.HIGH,
      )

    val genaiThinkingConfig = adkThinkingConfig.toGenaiSdk()

    assertEquals(true, genaiThinkingConfig.includeThoughts)
    assertEquals(1024, genaiThinkingConfig.thinkingBudget)
    assertEquals(
      com.google.genai.kotlin.types.ThinkingLevel.HIGH,
      genaiThinkingConfig.thinkingLevel,
    )

    val convertedBack = genaiThinkingConfig.fromGenaiSdk()
    assertEquals(adkThinkingConfig, convertedBack)
  }

  @Test
  fun thinkingConfig_convertsCorrectly_withDefaults() {
    val adkThinkingConfig = ThinkingConfig()

    val genaiThinkingConfig = adkThinkingConfig.toGenaiSdk()

    assertEquals(null, genaiThinkingConfig.includeThoughts)
    assertEquals(null, genaiThinkingConfig.thinkingBudget)
    assertEquals(null, genaiThinkingConfig.thinkingLevel)

    val convertedBack = genaiThinkingConfig.fromGenaiSdk()
    assertEquals(adkThinkingConfig, convertedBack)
  }

  @Test
  fun generateContentResponse_convertsCorrectly() {
    val adkResponse =
      GenerateContentResponse(
        candidates = listOf(Candidate(content = Content(role = Role.USER))),
        promptFeedback = PromptFeedback(blockReasonMessage = "msg"),
        usageMetadata =
          UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 20, totalTokenCount = 30),
        modelVersion = "1.0",
      )
    val genaiResponse = adkResponse.toGenaiSdk()
    assertEquals("1.0", genaiResponse.modelVersion)
    assertEquals(1, genaiResponse.candidates?.size)
    assertNotNull(genaiResponse.promptFeedback)
    assertNotNull(genaiResponse.usageMetadata)

    val convertedBack = genaiResponse.fromGenaiSdk()
    assertEquals(adkResponse, convertedBack)
  }

  @Test
  fun groundingMetadata_convertsCorrectly() {
    val adkGroundingMetadata = GroundingMetadata()
    val genaiGroundingMetadata = adkGroundingMetadata.toGenaiSdk()
    val convertedBack = genaiGroundingMetadata.fromGenaiSdk()
    assertEquals(adkGroundingMetadata, convertedBack)
  }

  @Test
  fun groundingMetadata_withPayload_convertsCorrectly() {
    val adkGroundingMetadata =
      GroundingMetadata(
        webSearchQueries = listOf("kotlin coroutines"),
        groundingChunks =
          listOf(
            GroundingChunk(
              web =
                GroundingChunkWeb(
                  uri = "https://example.com",
                  title = "Example",
                  domain = "example.com",
                )
            )
          ),
        groundingSupports =
          listOf(
            GroundingSupport(
              segment = Segment(startIndex = 0, endIndex = 5, partIndex = 0, text = "hello"),
              groundingChunkIndices = listOf(0),
              confidenceScores = listOf(0.75f),
            )
          ),
        searchEntryPoint = SearchEntryPoint(renderedContent = "<div>suggestions</div>"),
        retrievalMetadata = RetrievalMetadata(googleSearchDynamicRetrievalScore = 0.5f),
      )

    val genaiGroundingMetadata = adkGroundingMetadata.toGenaiSdk()
    assertEquals(listOf("kotlin coroutines"), genaiGroundingMetadata.webSearchQueries)
    assertEquals("example.com", genaiGroundingMetadata.groundingChunks?.single()?.web?.domain)

    val convertedBack = genaiGroundingMetadata.fromGenaiSdk()
    assertEquals(adkGroundingMetadata, convertedBack)
  }

  @Test
  fun googleSearch_convertsCorrectly() {
    val adkGoogleSearch = GoogleSearch()
    val genaiGoogleSearch = adkGoogleSearch.toGenaiSdk()
    val convertedBack = genaiGoogleSearch.fromGenaiSdk()
    assertEquals(adkGoogleSearch, convertedBack)
  }

  @Test
  fun googleSearch_withExcludeDomains_convertsCorrectly() {
    val adkGoogleSearch = GoogleSearch(excludeDomains = listOf("example.com", "test.org"))
    val genaiGoogleSearch = adkGoogleSearch.toGenaiSdk()
    val convertedBack = genaiGoogleSearch.fromGenaiSdk()
    assertEquals(adkGoogleSearch, convertedBack)
  }

  @Test
  fun googleMaps_convertsCorrectly() {
    val adkGoogleMaps = GoogleMaps(enableWidget = true)
    val genaiGoogleMaps = adkGoogleMaps.toGenaiSdk()
    val convertedBack = genaiGoogleMaps.fromGenaiSdk()
    assertEquals(adkGoogleMaps, convertedBack)
  }

  @Test
  fun tool_withUrlContext_convertsCorrectly() {
    val adkTool = Tool(urlContext = UrlContext())

    val genaiTool = adkTool.toGenaiSdk()
    assertNotNull(genaiTool.urlContext)

    val convertedBack = genaiTool.fromGenaiSdk()
    assertNotNull(convertedBack.urlContext)
  }

  @Test
  fun promptFeedback_convertsCorrectly() {
    val adkPromptFeedback = PromptFeedback(blockReasonMessage = "msg")
    val genaiPromptFeedback = adkPromptFeedback.toGenaiSdk()
    assertEquals("msg", genaiPromptFeedback.blockReasonMessage)

    val convertedBack = genaiPromptFeedback.fromGenaiSdk()
    assertEquals(adkPromptFeedback, convertedBack)
  }

  @Test
  fun tool_convertsCorrectly() {
    val adkTool =
      Tool(
        functionDeclarations = emptyList(),
        googleSearch = GoogleSearch(),
        googleMaps = GoogleMaps(),
      )
    val genaiTool = adkTool.toGenaiSdk()
    assertNotNull(genaiTool.functionDeclarations)
    assertNotNull(genaiTool.googleSearch)
    assertNotNull(genaiTool.googleMaps)

    val convertedBack = genaiTool.fromGenaiSdk()
    assertEquals(adkTool, convertedBack)
  }

  @Test
  fun tool_withRetrieval_convertsCorrectly() {
    val adkTool =
      Tool(
        retrieval =
          Retrieval(
            vertexAiSearch =
              VertexAISearch(
                dataStoreSpecs =
                  listOf(VertexAISearchDataStoreSpec(dataStore = "ds", filter = "f")),
                datastore = "datastore",
                engine = "engine",
                filter = "filter",
                maxResults = 5,
              )
          )
      )

    val genaiTool = adkTool.toGenaiSdk()
    assertEquals("engine", genaiTool.retrieval?.vertexAiSearch?.engine)
    assertEquals(5, genaiTool.retrieval?.vertexAiSearch?.maxResults)
    assertEquals("ds", genaiTool.retrieval?.vertexAiSearch?.dataStoreSpecs?.single()?.dataStore)

    val convertedBack = genaiTool.fromGenaiSdk()
    assertEquals(adkTool, convertedBack)
  }

  @Test
  fun generateContentConfig_toolConfig_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        toolConfig =
          ToolConfig(
            functionCallingConfig =
              FunctionCallingConfig(
                allowedFunctionNames = listOf("getWeather", "getTime"),
                streamFunctionCallArguments = true,
              )
          )
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals(
      listOf("getWeather", "getTime"),
      genaiConfig.toolConfig?.functionCallingConfig?.allowedFunctionNames,
    )
    assertEquals(true, genaiConfig.toolConfig?.functionCallingConfig?.streamFunctionCallArguments)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.toolConfig, convertedBack.toolConfig)
  }

  @Test
  fun generateContentConfig_samplingAndMisc_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        presencePenalty = 0.5f,
        frequencyPenalty = 0.25f,
        responseLogprobs = true,
        mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW,
        serviceTier = ServiceTier.PRIORITY,
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals(0.5, genaiConfig.presencePenalty)
    assertEquals(0.25, genaiConfig.frequencyPenalty)
    assertEquals(true, genaiConfig.responseLogprobs)
    assertEquals("MEDIA_RESOLUTION_LOW", genaiConfig.mediaResolution?.value)
    assertEquals("PRIORITY", genaiConfig.serviceTier?.value)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.presencePenalty, convertedBack.presencePenalty)
    assertEquals(adkConfig.frequencyPenalty, convertedBack.frequencyPenalty)
    assertEquals(adkConfig.responseLogprobs, convertedBack.responseLogprobs)
    assertEquals(adkConfig.mediaResolution, convertedBack.mediaResolution)
    assertEquals(adkConfig.serviceTier, convertedBack.serviceTier)
  }

  @Test
  fun generateContentConfig_safetySettings_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        safetySettings =
          listOf(
            SafetySetting(
              category = HarmCategory.HARM_CATEGORY_HATE_SPEECH,
              threshold = HarmBlockThreshold.BLOCK_ONLY_HIGH,
            )
          )
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals("HARM_CATEGORY_HATE_SPEECH", genaiConfig.safetySettings?.single()?.category?.value)
    assertEquals("BLOCK_ONLY_HIGH", genaiConfig.safetySettings?.single()?.threshold?.value)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.safetySettings, convertedBack.safetySettings)
  }

  @Test
  fun generateContentConfig_labels_convertsCorrectly() {
    val adkConfig = GenerateContentConfig(labels = mapOf("team" to "search", "env" to "prod"))

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals(mapOf("team" to "search", "env" to "prod"), genaiConfig.labels)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.labels, convertedBack.labels)
  }

  @Test
  fun generateContentConfig_routingConfigAutoMode_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        routingConfig =
          GenerationConfigRoutingConfig(
            autoMode =
              GenerationConfigRoutingConfigAutoRoutingMode(
                modelRoutingPreference = ModelRoutingPreference.PRIORITIZE_QUALITY
              )
          )
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals(
      "PRIORITIZE_QUALITY",
      genaiConfig.routingConfig?.autoMode?.modelRoutingPreference?.value,
    )

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.routingConfig, convertedBack.routingConfig)
  }

  @Test
  fun generateContentConfig_routingConfigManualMode_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        routingConfig =
          GenerationConfigRoutingConfig(
            manualMode = GenerationConfigRoutingConfigManualRoutingMode(modelName = "gemini-pro")
          )
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals("gemini-pro", genaiConfig.routingConfig?.manualMode?.modelName)

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.routingConfig, convertedBack.routingConfig)
  }

  @Test
  fun candidate_logprobs_convertsCorrectly() {
    val adkCandidate =
      Candidate(
        content = Content(role = Role.MODEL),
        avgLogprobs = -0.25,
        logprobsResult =
          LogprobsResult(
            chosenCandidates =
              listOf(LogprobsResultCandidate(token = "hi", tokenId = 7, logProbability = -0.1)),
            topCandidates =
              listOf(
                LogprobsResultTopCandidates(
                  candidates =
                    listOf(
                      LogprobsResultCandidate(token = "hi", tokenId = 7, logProbability = -0.1)
                    )
                )
              ),
            logProbabilitySum = -0.1,
          ),
      )

    val genaiCandidate = adkCandidate.toGenaiSdk()
    assertEquals(-0.25, genaiCandidate.avgLogprobs)

    val convertedBack = genaiCandidate.fromGenaiSdk()
    assertEquals(adkCandidate.avgLogprobs, convertedBack.avgLogprobs)
    assertEquals(adkCandidate.logprobsResult, convertedBack.logprobsResult)
  }

  @Test
  fun usageMetadata_convertsCorrectly() {
    val adkUsageMetadata =
      UsageMetadata(
        promptTokenCount = 1,
        candidatesTokenCount = 2,
        totalTokenCount = 3,
        thoughtsTokenCount = 4,
        toolUsePromptTokenCount = 5,
        cachedContentTokenCount = 6,
        promptTokensDetails =
          listOf(ModalityTokenCount(modality = MediaModality.TEXT, tokenCount = 1)),
        candidatesTokensDetails =
          listOf(ModalityTokenCount(modality = MediaModality.IMAGE, tokenCount = 2)),
      )
    val genaiUsageMetadata = adkUsageMetadata.toGenaiSdk()
    assertEquals(1, genaiUsageMetadata.promptTokenCount)
    assertEquals(2, genaiUsageMetadata.candidatesTokenCount)
    assertEquals(3, genaiUsageMetadata.totalTokenCount)
    assertEquals(4, genaiUsageMetadata.thoughtsTokenCount)
    assertEquals(5, genaiUsageMetadata.toolUsePromptTokenCount)
    assertEquals(6, genaiUsageMetadata.cachedContentTokenCount)

    val convertedBack = genaiUsageMetadata.fromGenaiSdk()
    assertEquals(adkUsageMetadata, convertedBack)
  }

  @Test
  fun part_convertsCorrectly() {
    val adkPart =
      Part(
        text = "hello",
        inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3)),
        fileData = FileData(fileUri = "uri"),
        functionCall = FunctionCall(name = "name"),
        functionResponse = FunctionResponse(name = "name"),
        thought = true,
        thoughtSignature = byteArrayOf(1, 2, 3),
      )
    val genaiPart = adkPart.toGenaiSdk()
    assertEquals("hello", genaiPart.text)
    assertNotNull(genaiPart.inlineData)
    assertNotNull(genaiPart.fileData)
    assertNotNull(genaiPart.functionCall)
    assertNotNull(genaiPart.functionResponse)
    assertEquals(true, genaiPart.thought)
    assertNotNull(genaiPart.thoughtSignature)

    val convertedBack = genaiPart.fromGenaiSdk()
    assertEquals(adkPart, convertedBack)
  }

  @Test
  fun part_videoMetadataAndPartMetadata_convertsCorrectly() {
    val adkPart =
      Part(
        text = "clip",
        videoMetadata = VideoMetadata(startOffset = 1.seconds, endOffset = 5.seconds, fps = 24.0),
        partMetadata = mapOf("source" to "camera-1"),
      )

    val genaiPart = adkPart.toGenaiSdk()
    assertEquals(24.0, genaiPart.videoMetadata?.fps)

    val convertedBack = genaiPart.fromGenaiSdk()
    assertEquals(adkPart, convertedBack)
  }

  @Test
  fun partialArg_null_convertsCorrectly() {
    val adkPartialArgNull =
      PartialArg(value = PartialArgValue.NullValue, jsonPath = "$.null", willContinue = false)
    val genaiPartialArgNull = adkPartialArgNull.toGenaiSdk()
    assertEquals(com.google.genai.kotlin.types.NullValue.NULL_VALUE, genaiPartialArgNull.nullValue)

    val convertedBackNull = genaiPartialArgNull.fromGenaiSdk()
    assertEquals(adkPartialArgNull, convertedBackNull)
  }

  @Test
  fun partialArg_empty_convertsCorrectly() {
    val adkPartialArgEmpty = PartialArg(value = null, jsonPath = "$.empty", willContinue = null)
    val genaiPartialArgEmpty = adkPartialArgEmpty.toGenaiSdk()
    assertEquals(null, genaiPartialArgEmpty.nullValue)
    assertEquals(null, genaiPartialArgEmpty.stringValue)
    assertEquals(null, genaiPartialArgEmpty.boolValue)
    assertEquals(null, genaiPartialArgEmpty.numberValue)

    val convertedBackEmpty = genaiPartialArgEmpty.fromGenaiSdk()
    assertEquals(adkPartialArgEmpty, convertedBackEmpty)
  }

  @Test
  fun partialArg_string_convertsCorrectly() {
    val adkPartialArgString =
      PartialArg(
        value = PartialArgValue.StringValue("hello"),
        jsonPath = "$.string",
        willContinue = true,
      )
    val genaiPartialArgString = adkPartialArgString.toGenaiSdk()
    assertEquals("hello", genaiPartialArgString.stringValue)

    val convertedBackString = genaiPartialArgString.fromGenaiSdk()
    assertEquals(adkPartialArgString, convertedBackString)
  }

  @Test
  fun partialArg_number_convertsCorrectly() {
    val adkPartialArgNumber =
      PartialArg(
        value = PartialArgValue.NumberValue(42.0),
        jsonPath = "$.number",
        willContinue = false,
      )
    val genaiPartialArgNumber = adkPartialArgNumber.toGenaiSdk()
    assertEquals(42.0, genaiPartialArgNumber.numberValue)

    val convertedBackNumber = genaiPartialArgNumber.fromGenaiSdk()
    assertEquals(adkPartialArgNumber, convertedBackNumber)
  }

  @Test
  fun partialArg_bool_convertsCorrectly() {
    val adkPartialArgBool =
      PartialArg(value = PartialArgValue.BoolValue(true), jsonPath = "$.bool", willContinue = true)
    val genaiPartialArgBool = adkPartialArgBool.toGenaiSdk()
    assertEquals(true, genaiPartialArgBool.boolValue)

    val convertedBackBool = genaiPartialArgBool.fromGenaiSdk()
    assertEquals(adkPartialArgBool, convertedBackBool)
  }

  @Test
  fun generateContentResponse_functionCallArgs_convertToPlainValues() {
    // Mirrors the conversion path from the production crash stack trace:
    // GenerateContentResponse -> Candidate -> Content -> Part -> FunctionCall.args.
    // The GenAI SDK models args as Map<String, JsonElement>; fromGenaiSdk() must surface plain
    // Kotlin values on the ADK FunctionCall.
    val genaiResponse =
      com.google.genai.kotlin.types.GenerateContentResponse(
        candidates =
          listOf(
            com.google.genai.kotlin.types.Candidate(
              content =
                com.google.genai.kotlin.types.Content(
                  role = "model",
                  parts =
                    listOf(
                      com.google.genai.kotlin.types.Part(
                        functionCall =
                          com.google.genai.kotlin.types.FunctionCall(
                            name = "getWeather",
                            args =
                              mapOf("city" to JsonPrimitive("Paris"), "days" to JsonPrimitive(3)),
                          )
                      )
                    ),
                )
            )
          )
      )

    val adkResponse = genaiResponse.fromGenaiSdk()

    val functionCall = adkResponse.candidates.single().content.parts.single().functionCall
    assertNotNull(functionCall)
    assertEquals("getWeather", functionCall.name)
    assertEquals("Paris", functionCall.args["city"])
    assertEquals(3, functionCall.args["days"])
  }

  @Test
  fun retrieval_withVertexRagStore_convertsCorrectly() {
    val adkRetrieval =
      Retrieval(
        vertexRagStore =
          VertexRagStore(
            ragCorpora = listOf("corpus1"),
            ragResources =
              listOf(VertexRagStoreRagResource(ragCorpus = "corpus2", ragFileIds = listOf("f1"))),
            similarityTopK = 3,
            vectorDistanceThreshold = 0.5,
          )
      )

    val genaiRetrieval = adkRetrieval.toGenaiSdk()

    val store = genaiRetrieval.vertexRagStore
    assertNotNull(store)
    assertEquals(listOf("corpus1"), store.ragCorpora)
    assertEquals("corpus2", store.ragResources?.single()?.ragCorpus)
    assertEquals(listOf("f1"), store.ragResources?.single()?.ragFileIds)
    assertEquals(3, store.similarityTopK)
    assertEquals(0.5, store.vectorDistanceThreshold)
    assertEquals(adkRetrieval, genaiRetrieval.fromGenaiSdk())
  }
}
