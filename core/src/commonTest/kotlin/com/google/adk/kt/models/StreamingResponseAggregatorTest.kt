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
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolType
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

  // One chunk holding both a text part and a call, each with its own signature: the trailing
  // re-attach must not stamp the text's signature over the call's.
  @Test
  fun singleChunkWithTextAndFunctionCall_eachKeepsItsSignature() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val textSignature = byteArrayOf(2, 2, 2)
    val fcSignature = byteArrayOf(1, 1, 1)

    val unused =
      aggregator.processResponse(
        LlmResponse(
          content =
            Content(
              parts =
                listOf(
                  Part(text = "Calling", thoughtSignature = textSignature),
                  Part(functionCall = FunctionCall(name = "do"), thoughtSignature = fcSignature),
                )
            )
        )
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertTrue(textSignature.contentEquals(parts?.get(0)?.thoughtSignature))
    assertTrue(fcSignature.contentEquals(parts?.get(1)?.thoughtSignature))
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

  // Consecutive text chunks merge into a part built from scratch, so a signature the chunks carried
  // is lost unless it is copied across; the model expects it back verbatim.
  @Test
  fun mergedTextSignature_isPreserved() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(1, 2, 3)

    val unused1 = aggregator.processResponse(createResp("At minute 5 ", signature = signature))
    val unused2 = aggregator.processResponse(createResp("the presenter speaks."))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertEquals("At minute 5 the presenter speaks.", parts?.get(0)?.text)
    assertTrue(signature.contentEquals(parts?.get(0)?.thoughtSignature))
  }

  // The signature can land on any chunk of the run, not just the first.
  @Test
  fun signatureOnLaterTextChunk_isPreserved() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(4, 5, 6)

    val unused1 = aggregator.processResponse(createResp("At minute 5 "))
    val unused2 = aggregator.processResponse(createResp("the presenter ", signature = signature))
    val unused3 = aggregator.processResponse(createResp("speaks."))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertEquals("At minute 5 the presenter speaks.", parts?.get(0)?.text)
    assertTrue(signature.contentEquals(parts?.get(0)?.thoughtSignature))
  }

  // An empty signature must not occupy the run's slot and block the real one behind it.
  @Test
  fun emptySignatureThenRealOne_keepsTheRealOne() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val realSignature = byteArrayOf(5, 5, 5)

    val unused1 = aggregator.processResponse(createResp("Hel", signature = byteArrayOf()))
    val unused2 = aggregator.processResponse(createResp("lo", signature = realSignature))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertTrue(realSignature.contentEquals(parts?.get(0)?.thoughtSignature))
  }

  // The same rule on the streamed function call's own slot: an empty signature must not occupy it.
  @Test
  fun emptySignatureThenRealOneOnAStreamedCall_keepsTheRealOne() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val realSignature = byteArrayOf(5, 5, 5)

    val unused1 =
      aggregator.processResponse(
        signedFcResp(createPartialFc("search", "$.q", "hel", willContinue = true), byteArrayOf())
      )
    val unused2 =
      aggregator.processResponse(
        signedFcResp(createPartialFc(null, "$.q", "lo", willContinue = false), realSignature)
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertTrue(realSignature.contentEquals(parts?.get(0)?.thoughtSignature))
  }

  // A merged part carries one signature; the run keeps the first it saw, as ADK Python does.
  @Test
  fun multipleSignaturesInOneRun_keepsTheFirst() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val firstSignature = byteArrayOf(1)

    val unused1 = aggregator.processResponse(createResp("At minute 5 ", signature = firstSignature))
    val unused2 =
      aggregator.processResponse(createResp("the presenter ", signature = byteArrayOf(9, 9, 9)))
    val unused3 = aggregator.processResponse(createFcResp(FunctionCall(name = "done", id = "fc1")))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertTrue(firstSignature.contentEquals(parts?.get(0)?.thoughtSignature))
  }

  // A thought run and an answer run flush separately and must not swap signatures: the answer's
  // arrives on the chunk that triggers the flush of the thought.
  @Test
  fun thoughtAndAnswerRuns_keepTheirOwnSignatures() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val thoughtSignature = byteArrayOf(1, 1, 1)
    val answerSignature = byteArrayOf(2, 2, 2)

    val unused1 =
      aggregator.processResponse(
        createResp("Let me check.", thought = true, signature = thoughtSignature)
      )
    val unused2 =
      aggregator.processResponse(createResp("It is a dog.", signature = answerSignature))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals(true, parts?.get(0)?.thought)
    assertTrue(thoughtSignature.contentEquals(parts?.get(0)?.thoughtSignature))
    assertTrue(answerSignature.contentEquals(parts?.get(1)?.thoughtSignature))
  }

  // An empty text part that carries anything else survives; only the bare one is dropped.
  @Test
  fun emptyTextPartWithToolCall_isKept() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Checked."))
    val unused2 =
      aggregator.processResponse(
        LlmResponse(
          content = Content(parts = listOf(Part(text = "", toolCall = ToolCall(id = "tc1"))))
        )
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("tc1", parts?.get(1)?.toolCall?.id)
  }

  @Test
  fun emptyTextPartWithInlineData_isKept() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Here."))
    val unused2 =
      aggregator.processResponse(
        LlmResponse(
          content =
            Content(
              parts =
                listOf(
                  Part(text = "", inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1)))
                )
            )
        )
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("image/png", parts?.get(1)?.inlineData?.mimeType)
  }

  // Gemini 3 ends a stream with an empty text part; a signature riding on it must survive as its
  // own part rather than being absorbed by whatever came before.
  @Test
  fun emptyTextPartWithSignature_isKeptAsItsOwnPart() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(7, 7, 7)

    val unused1 = aggregator.processResponse(createResp("Let me check."))
    val unused2 = aggregator.processResponse(createResp("", signature = signature))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("Let me check.", parts?.get(0)?.text)
    assertEquals(null, parts?.get(0)?.thoughtSignature)
    assertTrue(signature.contentEquals(parts?.get(1)?.thoughtSignature))
  }

  // A thought-marked server-side tool call carries payload the model has to see again, so the
  // thought marker alone must not cause the part to be dropped on the way through the stream.
  @Test
  fun thoughtMarkedServerSideToolCall_survivesTheStream() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 =
      aggregator.processResponse(
        LlmResponse(
          content =
            Content(
              parts =
                listOf(
                  Part(
                    thought = true,
                    toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT),
                    thoughtSignature = byteArrayOf(7, 7, 7),
                  )
                )
            )
        )
      )
    val unused2 = aggregator.processResponse(createResp("Found it."))
    val finalResp = aggregator.aggregate()

    assertTrue(finalResp?.content?.parts?.any { it.toolCall != null } == true)
  }

  // The other half of the rule above: an empty text part carrying nothing at all only marks the end
  // of a Gemini 3 stream, so it must not reach the caller as a part of its own.
  @Test
  fun bareEmptyTextPart_isDropped() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Let me check."))
    val unused2 = aggregator.processResponse(createResp(""))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertEquals("Let me check.", parts?.get(0)?.text)
  }

  // The terminator is recognised by shape, not by identity: one that also carries an explicit
  // thought marker is still nothing to keep, and must not split the run it lands in.
  @Test
  fun emptyTextPartWithExplicitThoughtFalse_isDropped() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused1 = aggregator.processResponse(createResp("Hello "))
    val unused2 = aggregator.processResponse(createResp("", thought = false))
    val unused3 = aggregator.processResponse(createResp("world."))
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(1, parts?.size)
    assertEquals("Hello world.", parts?.get(0)?.text)
  }

  // Server-side media tools return signatures on parts holding nothing else; such a part survives
  // on its own rather than being folded into the surrounding text.
  @Test
  fun contentFreeSignaturePart_isKept() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val signature = byteArrayOf(7, 7, 7)

    val unused1 = aggregator.processResponse(createResp("At minute 5 the presenter speaks."))
    val unused2 =
      aggregator.processResponse(
        LlmResponse(content = Content(parts = listOf(Part(thoughtSignature = signature))))
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("At minute 5 the presenter speaks.", parts?.get(0)?.text)
    assertEquals(null, parts?.get(0)?.thoughtSignature)
    assertEquals(null, parts?.get(1)?.text)
    assertTrue(signature.contentEquals(parts?.get(1)?.thoughtSignature))
  }

  // A text chunk arriving mid-stream of a function call must not take the call's signature with it:
  // the two runs flush together and each keeps its own.
  @Test
  fun textInterleavedWithStreamedCall_keepsBothSignatures() = runBlocking {
    val aggregator = StreamingResponseAggregator()
    val fcSignature = byteArrayOf(1, 1, 1)
    val textSignature = byteArrayOf(2, 2, 2)

    val unused1 =
      aggregator.processResponse(
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
                    thoughtSignature = fcSignature,
                  )
                )
            )
        )
      )
    val unused2 =
      aggregator.processResponse(createResp("Working on it.", signature = textSignature))
    val unused3 =
      aggregator.processResponse(
        createFcResp(createPartialFc(null, "$.q", "lo", willContinue = false))
      )
    val finalResp = aggregator.aggregate()

    val parts = finalResp?.content?.parts
    assertEquals(2, parts?.size)
    assertEquals("Working on it.", parts?.get(0)?.text)
    assertTrue(textSignature.contentEquals(parts?.get(0)?.thoughtSignature))
    assertEquals("search", parts?.get(1)?.functionCall?.name)
    assertTrue(fcSignature.contentEquals(parts?.get(1)?.thoughtSignature))
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

  // A content-free chunk with a STOP finish aggregates to a non-partial, empty final frame.
  @Test
  fun contentFreeStreamWithStop_stillProducesEmptyFinalFrame() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        LlmResponse(
          finishReason = FinishReason.STOP,
          usageMetadata = UsageMetadata(totalTokenCount = 3),
        )
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(false, finalResp.partial)
    assertEquals(null, finalResp.content)
    assertEquals(FinishReason.STOP, finalResp.finishReason)
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

  // A lone empty text part with a STOP finish aggregates to a non-partial, empty final frame.
  @Test
  fun emptyTextPartWithStop_producesEmptyFinalFrame() = runBlocking {
    val aggregator = StreamingResponseAggregator()

    val unused =
      aggregator.processResponse(
        LlmResponse(
          content = Content(parts = listOf(Part(text = ""))),
          finishReason = FinishReason.STOP,
        )
      )
    val finalResp = aggregator.aggregate()

    assertNotNull(finalResp)
    assertEquals(false, finalResp.partial)
    assertEquals(null, finalResp.content)
    assertEquals(FinishReason.STOP, finalResp.finishReason)
    assertEquals(null, finalResp.errorCode)
    assertEquals(null, finalResp.errorMessage)
  }

  private fun createResp(
    text: String,
    thought: Boolean? = null,
    signature: ByteArray? = null,
  ): LlmResponse {
    return LlmResponse(
      content =
        Content(parts = listOf(Part(text = text, thought = thought, thoughtSignature = signature)))
    )
  }

  private fun createFcResp(fc: FunctionCall): LlmResponse {
    return LlmResponse(content = Content(parts = listOf(Part(functionCall = fc))))
  }

  private fun signedFcResp(fc: FunctionCall, signature: ByteArray): LlmResponse {
    return LlmResponse(
      content = Content(parts = listOf(Part(functionCall = fc, thoughtSignature = signature)))
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
