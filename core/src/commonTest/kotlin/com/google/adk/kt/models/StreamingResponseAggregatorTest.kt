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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.models

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
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

  // The last partialArgs chunk keeps willContinue=true; completion arrives on a separate empty
  // willContinue=false marker, then trailing text follows. The marker must flush the call so it
  // precedes the text. Without handling the marker, aggregate() flushes the call after the text,
  // reversing their order (a single call alone would be masked by that end-of-stream flush).
  @Test
  fun testStreamedCallEndedByEmptyMarker_flushesCallBeforeTrailingText() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        createFcResp(createPartialFc("book_flight", "$.origin", "Krak", willContinue = true))
      )
    val unused2 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.origin", "ow", willContinue = true))
      )
    val unused3 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.destination", "Warsaw", willContinue = true))
      )
    val unused4 = aggregator.processResponse(createFcResp(FunctionCall(willContinue = false)))
    val unused5 = aggregator.processResponse(createResp("Booked."))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    val parts = finalResp.content?.parts
    assertNotNull(parts)
    assertEquals(2, parts.size)
    val fc = parts[0].functionCall
    assertNotNull(fc)
    assertEquals("book_flight", fc.name)
    assertEquals("Krakow", fc.args["origin"])
    assertEquals("Warsaw", fc.args["destination"])
    assertEquals("Booked.", parts[1].text)
  }

  // Two multi-arg streamed calls each ended by an empty willContinue=false marker must not drop the
  // first call nor bleed its args into the second (distinct values catch any bleed).
  @Test
  fun testTwoStreamedCallsEndedByEmptyMarkers_keepArgsSeparate() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        createFcResp(createPartialFc("get_temperature", "$.city", "Krakow", willContinue = true))
      )
    val unused2 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.unit", "C", willContinue = true))
      )
    val unused3 = aggregator.processResponse(createFcResp(FunctionCall(willContinue = false)))
    val unused4 =
      aggregator.processResponse(
        createFcResp(createPartialFc("get_condition", "$.city", "Warsaw", willContinue = true))
      )
    val unused5 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.unit", "F", willContinue = true))
      )
    val unused6 = aggregator.processResponse(createFcResp(FunctionCall(willContinue = false)))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(2, finalResp.content?.parts?.size)
    val first = finalResp.content?.parts?.get(0)?.functionCall
    val second = finalResp.content?.parts?.get(1)?.functionCall
    assertNotNull(first)
    assertNotNull(second)
    assertEquals("get_temperature", first.name)
    assertEquals("Krakow", first.args["city"])
    assertEquals("C", first.args["unit"])
    assertEquals("get_condition", second.name)
    assertEquals("Warsaw", second.args["city"])
    assertEquals("F", second.args["unit"])
  }

  // Safety guard for non-conforming output: a streamed call still in progress (the model should
  // have terminated it with willContinue=false) followed by a complete non-streaming call. The
  // in-progress call is flushed before appending, so neither is dropped nor merged.
  @Test
  fun testStreamedCallFollowedByCompleteCall_flushesInProgressFirst() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        createFcResp(createPartialFc("stream_call", "$.a", "1", willContinue = true))
      )
    val unused2 =
      aggregator.processResponse(
        createFcResp(FunctionCall(name = "plain_call", args = mapOf("b" to "2")))
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(2, finalResp.content?.parts?.size)
    val first = finalResp.content?.parts?.get(0)?.functionCall
    val second = finalResp.content?.parts?.get(1)?.functionCall
    assertNotNull(first)
    assertNotNull(second)
    assertEquals("stream_call", first.name)
    assertEquals("1", first.args["a"])
    assertEquals("plain_call", second.name)
    assertEquals("2", second.args["b"])
  }

  // A stray nameless willContinue=false marker with no call in progress must be a safe no-op (the
  // currentFcName != null half of the guard): it must not add a function call nor split the
  // surrounding text. Without that half it would be treated as a streamed part and prematurely
  // flush the text buffer, splitting "Hello world" into two parts.
  @Test
  fun testStrayNamelessMarker_isNoOp_doesNotSplitSurroundingText() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Hello "))
    val unused2 = aggregator.processResponse(createFcResp(FunctionCall(willContinue = false)))
    val unused3 = aggregator.processResponse(createResp("world"))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    assertEquals("Hello world", finalResp.content?.parts?.get(0)?.text)
    assertTrue(finalResp.content?.parts?.none { it.functionCall != null } == true)
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
        LlmResponse(
          content =
            Content(
              parts = listOf(Part(text = "Calling"), Part(functionCall = FunctionCall(name = "do")))
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
      LlmResponse(
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
                          PartialArg(jsonPath = "$.q", value = PartialArgValue.StringValue("hel"))
                        ),
                      willContinue = true,
                    ),
                  thoughtSignature = signature,
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
        LlmResponse(
          content = Content(parts = listOf(Part(text = "Answer", thoughtSignature = signature)))
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
        LlmResponse(
          content = Content(parts = listOf(Part(text = "Done"))),
          finishReason = FinishReason.STOP,
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
        LlmResponse(
          content = Content(parts = listOf(Part(text = "Partial"))),
          finishReason = FinishReason.MAX_TOKENS,
        )
      )
    val finalResp = aggregator.aggregate()

    assertEquals(FinishReason.MAX_TOKENS, finalResp?.finishReason)
    assertEquals("MAX_TOKENS", finalResp?.errorCode)
  }

  // Defensive: if a trailing chunk ever carries only metadata (no content), the aggregator must
  // not discard the content accumulated from the chunks before it.
  @Test
  fun trailingChunkWithoutContent_keepsAggregatedText() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Hello "))
    val unused2 = aggregator.processResponse(createResp("world!"))
    val unused3 =
      aggregator.processResponse(LlmResponse(usageMetadata = UsageMetadata(totalTokenCount = 7)))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(1, finalResp.content?.parts?.size)
    assertEquals("Hello world!", finalResp.content?.parts?.get(0)?.text)
    assertEquals(7, finalResp.usageMetadata?.totalTokenCount)
  }

  // A blocked prompt yields no content at all. The turn must still end with a non-partial response
  // carrying the error, otherwise the caller is left waiting on a stream that never concludes.
  @Test
  fun errorWithoutContent_stillProducesFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        LlmResponse(finishReason = FinishReason.SAFETY, errorMessage = "Blocked for safety.")
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(null, finalResp.content)
    assertEquals(false, finalResp.partial)
    assertEquals(FinishReason.SAFETY, finalResp.finishReason)
    assertEquals("SAFETY", finalResp.errorCode)
    assertEquals("Blocked for safety.", finalResp.errorMessage)
  }

  // The shape a thinking model produces when it exhausts maxOutputTokens before emitting any text:
  // a candidate carrying content but no parts. The turn must still conclude.
  @Test
  fun emptyPartsWithFinishReason_stillProducesFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        LlmResponse(
          content = Content(parts = emptyList()),
          finishReason = FinishReason.MAX_TOKENS,
          errorMessage = "Unknown error.",
        )
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(null, finalResp.content)
    assertEquals(false, finalResp.partial)
    assertEquals(FinishReason.MAX_TOKENS, finalResp.finishReason)
    assertEquals("MAX_TOKENS", finalResp.errorCode)
    assertEquals("Unknown error.", finalResp.errorMessage)
  }

  // A terminal chunk carrying a non-STOP finishReason and errorMessage alongside content: the final
  // response keeps the merged content and surfaces the error code and message.
  @Test
  fun contentThenFinalChunkWithError_carriesContentAndError() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Partial "))
    val unused2 =
      aggregator.processResponse(
        LlmResponse(
          content = Content(parts = listOf(Part(text = "answer"))),
          finishReason = FinishReason.MAX_TOKENS,
          errorMessage = "Unknown error.",
        )
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals("Partial answer", finalResp.content?.parts?.get(0)?.text)
    assertEquals(FinishReason.MAX_TOKENS, finalResp.finishReason)
    assertEquals("MAX_TOKENS", finalResp.errorCode)
    assertEquals("Unknown error.", finalResp.errorMessage)
  }

  // A chunk carrying an errorMessage but no finishReason: the final response keeps the earlier
  // content and surfaces the errorMessage with no errorCode.
  @Test
  fun errorMessageWithoutFinishReason_reachesFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Partial "))
    val unused2 = aggregator.processResponse(createResp("answer"))
    val unused3 = aggregator.processResponse(LlmResponse(errorMessage = "Generation failed."))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals("Partial answer", finalResp.content?.parts?.get(0)?.text)
    assertEquals(null, finalResp.finishReason)
    assertEquals(null, finalResp.errorCode)
    assertEquals("Generation failed.", finalResp.errorMessage)
  }

  // A content-free stream with no finish reason and no error still concludes with a non-partial,
  // empty final frame, so the turn always terminates rather than leaving the caller waiting on a
  // stream that never ends. Usage metadata rides along.
  @Test
  fun contentFreeStreamWithoutError_stillProducesEmptyFinalFrame() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(LlmResponse(usageMetadata = UsageMetadata(totalTokenCount = 3)))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(false, finalResp.partial)
    assertEquals(null, finalResp.content)
    assertEquals(null, finalResp.finishReason)
    assertEquals(null, finalResp.errorCode)
    assertEquals(null, finalResp.errorMessage)
    assertEquals(3, finalResp.usageMetadata?.totalTokenCount)
  }

  // With no responses processed at all there is nothing to conclude, so aggregate() returns null
  // (mirroring the Python aggregator's close()).
  @Test
  fun noResponsesProcessed_producesNoFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    assertEquals(null, aggregator.aggregate())
  }

  // A non-STOP finish with no content and no error message still concludes: the finish reason
  // alone is surfaced as an error code, so the turn does not end in silence.
  @Test
  fun errorCodeOnlyWithoutContent_stillProducesFinalResponse() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused = aggregator.processResponse(LlmResponse(finishReason = FinishReason.OTHER))
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(null, finalResp.content)
    assertEquals(false, finalResp.partial)
    assertEquals(FinishReason.OTHER, finalResp.finishReason)
    assertEquals("OTHER", finalResp.errorCode)
    assertEquals(null, finalResp.errorMessage)
  }

  // A candidate with empty parts and a STOP finish reason must still produce a final (non-partial)
  // frame so the turn concludes, and must not be classified as an error.
  @Test
  fun emptyPartsWithStop_producesEmptyFinalFrame() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val partial =
      aggregator.processResponse(
        LlmResponse(content = Content(parts = emptyList()), finishReason = FinishReason.STOP)
      )
    val finalResp = aggregator.aggregate()

    // The empty STOP chunk passes through as a partial without being flagged as an error.
    assertEquals(null, partial.errorCode)
    assertEquals(null, partial.errorMessage)

    // The turn concludes with a non-partial, error-free final frame carrying STOP and no content.
    assertNotNull(finalResp)
    assertEquals(false, finalResp.partial)
    assertEquals(null, finalResp.content)
    assertEquals(FinishReason.STOP, finalResp.finishReason)
    assertEquals(null, finalResp.errorCode)
    assertEquals(null, finalResp.errorMessage)
  }

  private fun createResp(text: String, thought: Boolean? = null): LlmResponse {
    return LlmResponse(content = Content(parts = listOf(Part(text = text, thought = thought))))
  }

  private fun createFcResp(fc: FunctionCall): LlmResponse {
    return LlmResponse(content = Content(parts = listOf(Part(functionCall = fc))))
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
