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

import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class StreamingResponseAggregatorTest {

  @Test
  fun testTextMerging() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Hello "))
    val unused2 = aggregator.processResponse(createResp("world!"))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    assertEquals("Hello world!", finalResp.content?.parts?.get(0)?.text)
  }

  @Test
  fun testThoughtMerging() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Thinking...", thought = true))
    val unused2 = aggregator.processResponse(createResp(" Done.", thought = true))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    assertEquals("Thinking... Done.", finalResp.content?.parts?.get(0)?.text)
    assertTrue(finalResp.content?.parts?.get(0)?.thought == true)
  }

  @Test
  fun testMixedTextAndThought() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Think", thought = true))
    val unused2 = aggregator.processResponse(createResp("ing", thought = true))
    val unused3 = aggregator.processResponse(createResp("Hello"))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(2, finalResp.content?.parts?.size)
    assertEquals("Thinking", finalResp.content?.parts?.get(0)?.text)
    assertEquals(true, finalResp.content?.parts?.get(0)?.thought)
    assertEquals("Hello", finalResp.content?.parts?.get(1)?.text)
    assertEquals(null, finalResp.content?.parts?.get(1)?.thought)
  }

  @Test
  fun testPartialFunctionCallAggregation() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        createFcResp(createPartialFc("get_weather", "$.location", "San ", willContinue = true))
      )
    val unused2 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.location", "Francisco", willContinue = false))
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    val fc = finalResp.content?.parts?.get(0)?.functionCall
    assertNotNull(fc)
    assertEquals("get_weather", fc.name)
    assertEquals("San Francisco", fc.args["location"])
  }

  @Test
  fun testNestedPartialFunctionCallAggregation() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        createFcResp(
          createPartialFc("find_place", "$.location.city", "Mountain ", willContinue = true)
        )
      )
    val unused2 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.location.city", "View", willContinue = true))
      )
    val unused3 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.location.state", "CA", willContinue = false))
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    val fc = finalResp.content?.parts?.get(0)?.functionCall
    assertNotNull(fc)
    assertEquals("find_place", fc.name)
    val location = fc.args["location"] as Map<*, *>
    assertEquals("Mountain View", location["city"])
    assertEquals("CA", location["state"])
  }

  @Test
  fun processResponse_concurrentCalls_isThreadSafe() = runTest {
    val aggregator = StreamingResponseAggregator()
    val jobCount = 100
    val chunks = (0 until jobCount).map { "$it;" }

    coroutineScope {
      for (chunk in chunks) {
        launch(Dispatchers.Default) {
          val unused = aggregator.processResponse(createResp(chunk))
        }
      }
    }

    val finalResponse = aggregator.aggregate()

    assertNotNull(finalResponse)
    assertEquals(1, finalResponse.content?.parts?.size)
    val resultText = finalResponse.content?.parts?.get(0)?.text

    // The result should contain all chunks, but potentially in a jumbled order
    val resultNumbers =
      resultText?.split(';')?.filter { it.isNotBlank() }?.map { it.toInt() }?.sorted()
    assertEquals((0 until jobCount).toList(), resultNumbers)
  }

  @Test
  fun processResponse_marksChunkPartial() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val partial = aggregator.processResponse(createResp("Hi"))
    assertEquals(true, partial.partial)
  }

  @Test
  fun parallelFunctionCalls_areContiguousInSingleFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createFcResp(FunctionCall(name = "get_weather")))
    val unused2 = aggregator.processResponse(createFcResp(FunctionCall(name = "get_time")))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(false, finalResp.partial)
    val parts = finalResp.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("get_weather", parts?.get(0)?.functionCall?.name)
    assertEquals("get_time", parts?.get(1)?.functionCall?.name)
  }

  @Test
  fun singleChunkWithTextAndFunctionCall_bothAggregated() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        GenerateContentResponse(
          candidates =
            listOf(
              Candidate(
                content =
                  Content(
                    parts =
                      listOf(Part(text = "Calling"), Part(functionCall = FunctionCall(name = "do")))
                  )
              )
            )
        )
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    val parts = finalResp.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("Calling", parts?.get(0)?.text)
    assertEquals("do", parts?.get(1)?.functionCall?.name)
  }

  @Test
  fun functionCallMissingId_sharesGeneratedIdBetweenPartialAndFinal() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val partial = aggregator.processResponse(createFcResp(FunctionCall(name = "do_thing")))
    val finalResp = aggregator.aggregate()

    val partialId = partial.content?.parts?.get(0)?.functionCall?.id
    val finalId = finalResp?.content?.parts?.get(0)?.functionCall?.id
    assertNotNull(partialId)
    assertTrue(partialId.startsWith("adk-"))
    assertEquals(partialId, finalId)
  }

  @Test
  fun functionCallWithModelId_isPreserved() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(createFcResp(FunctionCall(name = "do_thing", id = "m1")))
    val finalResp = aggregator.aggregate()

    assertEquals("m1", finalResp?.content?.parts?.get(0)?.functionCall?.id)
  }

  @Test
  fun streamedFunctionCall_capturesThoughtSignature() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(9, 8, 7)

    val chunk1 =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(
              content =
                Content(
                  parts =
                    listOf(
                      Part(
                        functionCall =
                          FunctionCall(
                            name = "search",
                            partialArgs =
                              listOf(
                                PartialArg(
                                  jsonPath = "$.q",
                                  value = PartialArgValue.StringValue("hel"),
                                )
                              ),
                            willContinue = true,
                          ),
                        thoughtSignature = signature,
                      )
                    )
                )
            )
          )
      )
    val unused1 = aggregator.processResponse(chunk1)
    val unused2 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.q", "lo", willContinue = false))
      )
    val finalResp = aggregator.aggregate()

    val part = finalResp?.content?.parts?.get(0)
    assertEquals("search", part?.functionCall?.name)
    assertEquals("hello", part?.functionCall?.args?.get("q"))
    assertNotNull(part?.thoughtSignature)
    assertTrue(signature.contentEquals(part.thoughtSignature))
  }

  @Test
  fun textThoughtSignature_reattachedToAggregatedText() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(1, 2, 3)

    val unused =
      aggregator.processResponse(
        GenerateContentResponse(
          candidates =
            listOf(
              Candidate(
                content =
                  Content(parts = listOf(Part(text = "Answer", thoughtSignature = signature)))
              )
            )
        )
      )
    val finalResp = aggregator.aggregate()

    val part = finalResp?.content?.parts?.get(0)
    assertEquals("Answer", part?.text)
    assertNotNull(part?.thoughtSignature)
    assertTrue(signature.contentEquals(part.thoughtSignature))
  }

  @Test
  fun finalResponse_carriesFinishReasonAndUsageMetadata() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        GenerateContentResponse(
          candidates =
            listOf(
              Candidate(
                content = Content(parts = listOf(Part(text = "Done"))),
                finishReason = FinishReason.STOP,
              )
            ),
          usageMetadata = UsageMetadata(totalTokenCount = 42),
        )
      )
    val finalResp = aggregator.aggregate()

    assertEquals(FinishReason.STOP, finalResp?.finishReason)
    assertEquals(42, finalResp?.usageMetadata?.totalTokenCount)
    assertEquals(null, finalResp?.errorCode)
  }

  @Test
  fun nonStopFinishReason_isSurfacedAsError() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        GenerateContentResponse(
          candidates =
            listOf(
              Candidate(
                content = Content(parts = listOf(Part(text = "Partial"))),
                finishReason = FinishReason.MAX_TOKENS,
              )
            )
        )
      )
    val finalResp = aggregator.aggregate()

    assertEquals(FinishReason.MAX_TOKENS, finalResp?.finishReason)
    assertEquals("MAX_TOKENS", finalResp?.errorCode)
  }

  private fun createResp(text: String, thought: Boolean? = null): GenerateContentResponse {
    return GenerateContentResponse(
      candidates =
        listOf(Candidate(content = Content(parts = listOf(Part(text = text, thought = thought)))))
    )
  }

  private fun createFcResp(fc: FunctionCall): GenerateContentResponse {
    return GenerateContentResponse(
      candidates = listOf(Candidate(content = Content(parts = listOf(Part(functionCall = fc)))))
    )
  }

  private fun createPartialFc(
    name: String? = null,
    jsonPath: String,
    stringValue: String,
    willContinue: Boolean,
  ): FunctionCall {
    return FunctionCall(
      name = name ?: "",
      partialArgs =
        listOf(PartialArg(jsonPath = jsonPath, value = PartialArgValue.StringValue(stringValue))),
      willContinue = willContinue,
    )
  }
}
