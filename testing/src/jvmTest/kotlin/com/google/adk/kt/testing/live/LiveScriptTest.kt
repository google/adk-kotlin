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
@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.testing.userFunctionResponse
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests the turn-control steps, and that the presets deliver responses in the order the real
 * connection does.
 */
class LiveScriptTest {

  @Test
  fun turnComplete_withUsage_emitsUsageAsItsOwnResponseFirst(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().turnComplete().endTurn().build())

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(2)
    assertThat(responses[0].usageMetadata).isEqualTo(LiveScript.DEFAULT_USAGE)
    assertThat(responses[0].turnComplete).isNull()
    assertThat(responses[1].turnComplete).isTrue()
    assertThat(responses[1].usageMetadata).isNull()
  }

  @Test
  fun endTurn_onATurnThatEmittedNothing_emitsTurnCompleteSoTheCollectionIsNotEmpty(): Unit =
    runBlocking {
      val connection =
        FakeLiveConnection(LiveScript.builder().endTurn().text("next").endStream().build())

      val firstTurn = connection.receive().toList()
      val secondTurn = connection.receive().toList()

      assertThat(firstTurn).hasSize(1)
      assertThat(firstTurn[0].turnComplete).isTrue()
      assertThat(secondTurn.single().content?.parts?.single()?.text).isEqualTo("next")
    }

  @Test
  fun endTurn_afterAResponseThatDoesNotCompleteTheTurn_addsTurnComplete(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().text("only").endTurn().build())

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(2)
    assertThat(responses[1].turnComplete).isTrue()
  }

  @Test
  fun endTurn_afterATurnCompleteResponse_addsNothing(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(LiveScript.builder().turnComplete(usageMetadata = null).endTurn().build())

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(1)
    assertThat(responses[0].turnComplete).isTrue()
  }

  @Test
  fun turnComplete_withoutUsage_emitsOnlyTheTurnCompleteResponse(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().turnComplete(null).endTurn().build())

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(1)
    assertThat(responses.single().turnComplete).isTrue()
  }

  @Test
  fun goAway_withTimeLeft_emitsTheReconnectWarning(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().goAway(5.seconds).endStream().build())

    val response = connection.receive().toList().single()

    assertThat(response.goAway?.timeLeft).isEqualTo(5.seconds)
  }

  @Test
  fun goAwayMillis_timeLeftInMillis_emitsThatDuration(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.builder().goAwayMillis(1500).endStream().build())

    val response = connection.receive().toList().single()

    assertThat(response.goAway?.timeLeft).isEqualTo(1500.milliseconds)
  }

  @Test
  fun transcriptions_inputAndOutput_landOnSeparateResponses(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .inputTranscription("what is the weather", finished = true)
          .outputTranscription("it is sunny")
          .endStream()
          .build()
      )

    val responses = connection.receive().toList()

    assertThat(responses[0].inputTranscription?.text).isEqualTo("what is the weather")
    assertThat(responses[0].inputTranscription?.finished).isTrue()
    assertThat(responses[1].outputTranscription?.text).isEqualTo("it is sunny")
  }

  @Test
  fun simpleAudioTurn_collectedOnce_endsAtTurnCompleteWithoutTheResumptionHandle(): Unit =
    runBlocking {
      val connection = FakeLiveConnection(LiveScript.simpleAudioTurn(audioFrames = 2))

      val turn = connection.receive().toList()

      assertThat(turn.last().turnComplete).isTrue()
      assertThat(turn.mapNotNull { it.liveSessionResumptionUpdate }).isEmpty()
    }

  @Test
  fun simpleAudioTurn_collectedOnce_emitsATranscriptionChunkThenTheAggregate(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.simpleAudioTurn(audioFrames = 2))

    val turn = connection.receive().toList()

    // The real connection streams a chunk (partial, unfinished) then a finished aggregate.
    val transcriptions = turn.filter { it.outputTranscription != null }
    assertThat(
        transcriptions.map {
          Triple(it.outputTranscription?.text, it.partial, it.outputTranscription?.finished)
        }
      )
      .containsExactly(Triple("hello there", true, false), Triple("hello there", false, true))
      .inOrder()
    // Usage precedes the finished aggregate, which precedes turn complete.
    val usageIndex = turn.indexOfFirst { it.usageMetadata != null }
    val aggregateIndex = turn.indexOfFirst { it.outputTranscription?.finished == true }
    val turnCompleteIndex = turn.indexOfFirst { it.turnComplete == true }
    assertThat(usageIndex).isLessThan(aggregateIndex)
    assertThat(aggregateIndex).isLessThan(turnCompleteIndex)
  }

  @Test
  fun simpleAudioTurn_collectedAgain_deliversTheTrailingResumptionHandle(): Unit = runBlocking {
    // What the preset guards: a caller that stops at turn complete loses the resumption handle.
    val connection = FakeLiveConnection(LiveScript.simpleAudioTurn(audioFrames = 2))
    connection.receive().toList()

    // Bounded here: the preset stays open after the handle, as a real stream does.
    val afterTurn = connection.receive().take(1).toList()

    assertThat(afterTurn.single().liveSessionResumptionUpdate?.newHandle)
      .isEqualTo("handle-after-turn")
    assertThat(connection.isClosed).isFalse()
  }

  @Test
  fun toolRoundTrip_toolResponseAnswered_completesTheTurn(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.toolRoundTrip("GetWeather"))
    connection.sendContent(userFunctionResponse("GetWeather", id = null))

    val responses = connection.receive().toList()

    assertThat(responses.first().content?.parts?.first()?.functionCall?.name)
      .isEqualTo("GetWeather")
    assertThat(responses.last().turnComplete).isTrue()
  }

  @Test
  fun toolRoundTrip_toolResponseNeverSent_stopsAtTheToolCall(): Unit = runBlocking {
    // Gemini 3.x withholds turn completion until the tool response, so the gate must stall.
    val connection =
      FakeLiveConnection(LiveScript.toolRoundTrip("GetWeather"), gateTimeout = 50.milliseconds)

    val error = assertFailsWith<AssertionError> { connection.receive().collect {} }

    assertThat(error).hasMessageThat().contains("a tool response")
  }

  @Test
  fun bargeIn_userSpeaksOver_reportsInterruptionAfterTheAudio(): Unit = runBlocking {
    val connection = FakeLiveConnection(LiveScript.bargeIn(audioFramesBeforeInterrupt = 3))

    val responses = connection.receive().toList()

    assertThat(responses).hasSize(5)
    assertThat(responses.take(3).all { it.content?.parts?.firstOrNull()?.inlineData != null })
      .isTrue()
    assertThat(responses[3].interrupted).isTrue()
    assertThat(responses.last().turnComplete).isTrue()
  }

  @Test
  fun endTurn_emptyTurnAfterACompletedOne_stillAddsTurnComplete(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .turnComplete(usageMetadata = null)
          .endTurn()
          .endTurn()
          .endStream()
          .build()
      )
    connection.receive().toList()

    val secondTurn = connection.receive().toList()

    assertThat(secondTurn.single().turnComplete).isTrue()
  }

  @Test
  fun endTurn_afterAGateFollowingTurnComplete_endsTheSecondCollection(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .turnComplete(usageMetadata = null)
          .awaitToolResponse()
          .endTurn()
          .build(),
        gateTimeout = 300.milliseconds,
      )
    connection.receive().toList()
    connection.sendContent(userFunctionResponse("GetWeather", id = null))

    val secondTurn = connection.receive().toList()

    assertThat(secondTurn.last().turnComplete).isTrue()
  }

  @Test
  fun inputTranscription_chunkThenAggregate_marksOnlyTheChunkPartial(): Unit = runBlocking {
    val connection =
      FakeLiveConnection(
        LiveScript.builder()
          .inputTranscription("what")
          .inputTranscription("what is", finished = true)
          .endStream()
          .build()
      )

    val responses = connection.receive().toList()

    assertThat(
        responses.map {
          Triple(it.inputTranscription?.text, it.partial, it.inputTranscription?.finished)
        }
      )
      .containsExactly(Triple("what", true, false), Triple("what is", false, true))
      .inOrder()
  }

  @Test
  fun audio_zeroFrames_throws() {
    assertFailsWith<IllegalArgumentException> { LiveScript.builder().audio(frames = 0) }
  }
}
