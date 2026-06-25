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

    assertEquals("myFunction", genaiFunctionResponse.name().get())
    assertEquals(mapOf("result" to "success"), genaiFunctionResponse.response().get())
    assertEquals("call-123", genaiFunctionResponse.id().get())

    val convertedBack = genaiFunctionResponse.fromGenaiSdk()
    assertEquals(adkFunctionResponse, convertedBack)
  }

  @Test
  fun functionCall_convertsCorrectly() {
    val adkFunctionCall =
      FunctionCall(name = "myFunction", args = mapOf("arg1" to "value1"), id = "call-123")

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()

    assertEquals("myFunction", genaiFunctionCall.name().get())
    assertEquals(mapOf("arg1" to "value1"), genaiFunctionCall.args().get())
    assertEquals("call-123", genaiFunctionCall.id().get())
    assertEquals(false, genaiFunctionCall.partialArgs().isPresent)
    assertEquals(false, genaiFunctionCall.willContinue().isPresent)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(adkFunctionCall, convertedBack)
  }

  @Test
  fun functionCall_convertsCorrectly_withEmptyFields() {
    val adkFunctionCall =
      FunctionCall(name = "myFunction", partialArgs = emptyList(), willContinue = false)

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()
    assertEquals(false, genaiFunctionCall.partialArgs().isPresent)
    assertEquals(false, genaiFunctionCall.willContinue().isPresent)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(null, convertedBack.partialArgs)
    assertEquals(null, convertedBack.willContinue)
  }

  @Test
  fun functionCall_convertsCorrectly_withStreamingFields() {
    val partialArgs = listOf(PartialArg(value = null, jsonPath = "$.arg"))
    val adkFunctionCall =
      FunctionCall(name = "myFunction", partialArgs = partialArgs, willContinue = true)

    val genaiFunctionCall = adkFunctionCall.toGenaiSdk()
    assertEquals(true, genaiFunctionCall.partialArgs().isPresent)
    assertEquals(true, genaiFunctionCall.willContinue().isPresent)

    val convertedBack = genaiFunctionCall.fromGenaiSdk()
    assertEquals(partialArgs, convertedBack.partialArgs)
    assertEquals(true, convertedBack.willContinue)
  }

  @Test
  fun blob_convertsCorrectly() {
    val adkBlob =
      Blob(mimeType = "image/png", displayName = "myImage.png", data = byteArrayOf(1, 2, 3))

    val genaiBlob = adkBlob.toGenaiSdk()

    assertEquals("image/png", genaiBlob.mimeType().get())
    assertEquals("myImage.png", genaiBlob.displayName().get())
    // Genai Sdk Blob data is byte[]
    val genaiData = genaiBlob.data().get()
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
    assertEquals(Role.USER, genaiCandidate.content().get().role().get())
    assertEquals(
      com.google.genai.types.FinishReason.Known.STOP,
      genaiCandidate.finishReason().get().knownEnum(),
    )
    assertEquals("Done", genaiCandidate.finishMessage().get())
    assertNotNull(genaiCandidate.citationMetadata().get())

    val convertedBack = genaiCandidate.fromGenaiSdk()
    assertEquals(adkCandidate, convertedBack)
  }

  @Test
  fun citation_convertsCorrectly() {
    val adkCitation = Citation()
    val genaiCitation = adkCitation.toGenaiSdk()
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
    assertEquals(Role.USER, genaiContent.role().get())
    assertEquals(1, genaiContent.parts().get().size)
    assertEquals("hello", genaiContent.parts().get()[0].text().get())

    val convertedBack = genaiContent.fromGenaiSdk()
    assertEquals(adkContent, convertedBack)
  }

  @Test
  fun fileData_convertsCorrectly() {
    val adkFileData = FileData(mimeType = "text/plain", displayName = "test.txt", fileUri = "uri")
    val genaiFileData = adkFileData.toGenaiSdk()
    assertEquals("text/plain", genaiFileData.mimeType().get())
    assertEquals("test.txt", genaiFileData.displayName().get())
    assertEquals("uri", genaiFileData.fileUri().get())

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
    assertEquals("myFunc", genaiFunctionDeclaration.name().get())
    assertEquals("desc", genaiFunctionDeclaration.description().get())
    assertNotNull(genaiFunctionDeclaration.parameters().get())

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
    val genaiTools = genaiConfig.tools().get()
    assertEquals(1, genaiTools.size)
    assertEquals(1, genaiTools[0].functionDeclarations().get().size)
    assertEquals(true, genaiConfig.thinkingConfig().get().includeThoughts().get())
    assertEquals(
      com.google.genai.types.ThinkingLevel.Known.LOW,
      genaiConfig.thinkingConfig().get().thinkingLevel().get().knownEnum(),
    )

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
  }

  @Test
  fun generateContentConfig_toolConfig_convertsCorrectly() {
    val adkConfig =
      GenerateContentConfig(
        toolConfig =
          ToolConfig(
            functionCallingConfig =
              FunctionCallingConfig(allowedFunctionNames = listOf("getWeather", "getTime"))
          )
      )

    val genaiConfig = adkConfig.toGenaiSdk()
    assertEquals(
      listOf("getWeather", "getTime"),
      genaiConfig.toolConfig().get().functionCallingConfig().get().allowedFunctionNames().get(),
    )

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.toolConfig, convertedBack.toolConfig)
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
    val genaiSetting = genaiConfig.safetySettings().get().single()
    assertEquals("HARM_CATEGORY_HATE_SPEECH", genaiSetting.category().get().toString())
    assertEquals("BLOCK_ONLY_HIGH", genaiSetting.threshold().get().toString())

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.safetySettings, convertedBack.safetySettings)
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
    assertEquals(0.5f, genaiConfig.presencePenalty().get())
    assertEquals(0.25f, genaiConfig.frequencyPenalty().get())
    assertEquals(true, genaiConfig.responseLogprobs().get())
    assertEquals("MEDIA_RESOLUTION_LOW", genaiConfig.mediaResolution().get().toString())
    assertEquals("PRIORITY", genaiConfig.serviceTier().get().toString())

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig.presencePenalty, convertedBack.presencePenalty)
    assertEquals(adkConfig.frequencyPenalty, convertedBack.frequencyPenalty)
    assertEquals(adkConfig.responseLogprobs, convertedBack.responseLogprobs)
    assertEquals(adkConfig.mediaResolution, convertedBack.mediaResolution)
    assertEquals(adkConfig.serviceTier, convertedBack.serviceTier)
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

    assertEquals("application/json", genaiConfig.responseMimeType().get())
    assertNotNull(genaiConfig.responseSchema().get())

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
  }

  @Test
  fun generateContentConfig_topKIntegerRoundTrip_preservesValue() {
    val adkConfig = GenerateContentConfig(temperature = 0.5f, topP = 0.9f, topK = 40)

    val genaiConfig = adkConfig.toGenaiSdk()

    assertEquals(40f, genaiConfig.topK().get())
    assertEquals(0.5f, genaiConfig.temperature().get())
    assertEquals(0.9f, genaiConfig.topP().get())

    val convertedBack = genaiConfig.fromGenaiSdk()
    assertEquals(adkConfig, convertedBack)
    assertEquals(40, convertedBack.topK)
  }

  @Test
  fun generateContentConfig_topKFloatFromGenaiSdk_truncatesToInt() {
    val genaiConfig = com.google.genai.types.GenerateContentConfig.builder().topK(40.7f).build()

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

    assertEquals(true, genaiThinkingConfig.includeThoughts().get())
    assertEquals(1024, genaiThinkingConfig.thinkingBudget().get())
    assertEquals(
      com.google.genai.types.ThinkingLevel.Known.HIGH,
      genaiThinkingConfig.thinkingLevel().get().knownEnum(),
    )

    val convertedBack = genaiThinkingConfig.fromGenaiSdk()
    assertEquals(adkThinkingConfig, convertedBack)
  }

  @Test
  fun thinkingConfig_convertsCorrectly_withDefaults() {
    val adkThinkingConfig = ThinkingConfig()

    val genaiThinkingConfig = adkThinkingConfig.toGenaiSdk()

    assertEquals(false, genaiThinkingConfig.includeThoughts().isPresent)
    assertEquals(false, genaiThinkingConfig.thinkingBudget().isPresent)
    assertEquals(false, genaiThinkingConfig.thinkingLevel().isPresent)

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
    assertEquals("1.0", genaiResponse.modelVersion().get())
    assertEquals(1, genaiResponse.candidates().get().size)
    assertNotNull(genaiResponse.promptFeedback().get())
    assertNotNull(genaiResponse.usageMetadata().get())

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
    assertEquals(listOf("kotlin coroutines"), genaiGroundingMetadata.webSearchQueries().get())
    assertEquals(
      "example.com",
      genaiGroundingMetadata.groundingChunks().get().single().web().get().domain().get(),
    )

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
    assertEquals(true, genaiTool.urlContext().isPresent)

    val convertedBack = genaiTool.fromGenaiSdk()
    assertNotNull(convertedBack.urlContext)
  }

  @Test
  fun promptFeedback_convertsCorrectly() {
    val adkPromptFeedback = PromptFeedback(blockReasonMessage = "msg")
    val genaiPromptFeedback = adkPromptFeedback.toGenaiSdk()
    assertEquals("msg", genaiPromptFeedback.blockReasonMessage().get())

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
    assertNotNull(genaiTool.functionDeclarations().get())
    assertNotNull(genaiTool.googleSearch().get())
    assertNotNull(genaiTool.googleMaps().get())

    val convertedBack = genaiTool.fromGenaiSdk()
    assertEquals(adkTool, convertedBack)
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
        promptTokensDetails =
          listOf(ModalityTokenCount(modality = MediaModality.TEXT, tokenCount = 1)),
        candidatesTokensDetails =
          listOf(ModalityTokenCount(modality = MediaModality.IMAGE, tokenCount = 2)),
      )
    val genaiUsageMetadata = adkUsageMetadata.toGenaiSdk()
    assertEquals(1, genaiUsageMetadata.promptTokenCount().get())
    assertEquals(2, genaiUsageMetadata.candidatesTokenCount().get())
    assertEquals(3, genaiUsageMetadata.totalTokenCount().get())
    assertEquals(4, genaiUsageMetadata.thoughtsTokenCount().get())
    assertEquals(5, genaiUsageMetadata.toolUsePromptTokenCount().get())

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
    assertEquals("hello", genaiPart.text().get())
    assertNotNull(genaiPart.inlineData().get())
    assertNotNull(genaiPart.fileData().get())
    assertNotNull(genaiPart.functionCall().get())
    assertNotNull(genaiPart.functionResponse().get())
    assertEquals(true, genaiPart.thought().get())
    assertNotNull(genaiPart.thoughtSignature().get())

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
    assertEquals(24.0, genaiPart.videoMetadata().get().fps().get())

    val convertedBack = genaiPart.fromGenaiSdk()
    assertEquals(adkPart, convertedBack)
  }

  @Test
  fun partialArg_null_convertsCorrectly() {
    val adkPartialArgNull =
      PartialArg(value = PartialArgValue.NullValue, jsonPath = "$.null", willContinue = false)
    val genaiPartialArgNull = adkPartialArgNull.toGenaiSdk()
    assertEquals(
      com.google.genai.types.NullValue.Known.NULL_VALUE,
      genaiPartialArgNull.nullValue().get().knownEnum(),
    )

    val convertedBackNull = genaiPartialArgNull.fromGenaiSdk()
    assertEquals(adkPartialArgNull, convertedBackNull)
  }

  @Test
  fun partialArg_empty_convertsCorrectly() {
    val adkPartialArgEmpty = PartialArg(value = null, jsonPath = "$.empty", willContinue = null)
    val genaiPartialArgEmpty = adkPartialArgEmpty.toGenaiSdk()
    assertEquals(false, genaiPartialArgEmpty.nullValue().isPresent)
    assertEquals(false, genaiPartialArgEmpty.stringValue().isPresent)
    assertEquals(false, genaiPartialArgEmpty.boolValue().isPresent)
    assertEquals(false, genaiPartialArgEmpty.numberValue().isPresent)

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
    assertEquals("hello", genaiPartialArgString.stringValue().get())

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
    assertEquals(42.0, genaiPartialArgNumber.numberValue().get())

    val convertedBackNumber = genaiPartialArgNumber.fromGenaiSdk()
    assertEquals(adkPartialArgNumber, convertedBackNumber)
  }

  @Test
  fun partialArg_bool_convertsCorrectly() {
    val adkPartialArgBool =
      PartialArg(value = PartialArgValue.BoolValue(true), jsonPath = "$.bool", willContinue = true)
    val genaiPartialArgBool = adkPartialArgBool.toGenaiSdk()
    assertEquals(true, genaiPartialArgBool.boolValue().get())

    val convertedBackBool = genaiPartialArgBool.fromGenaiSdk()
    assertEquals(adkPartialArgBool, convertedBackBool)
  }
}
