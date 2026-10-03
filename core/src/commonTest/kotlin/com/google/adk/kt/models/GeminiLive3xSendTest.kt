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

import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.ActivityEnd as SdkActivityEnd
import com.google.genai.kotlin.types.ActivityStart as SdkActivityStart
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionResponse as SdkFunctionResponse
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

private const val GEMINI_3X_LIVE = "gemini-3.0-flash-live"
private const val GEMINI_25_LIVE = "gemini-2.5-flash-live"

/** Tests how a live connection's send path differs on Gemini 3.x. */
class GeminiLive3xSendTest {

  /** Records what reached the session, in order, so a test can assert which call was used. */
  private class RecordingSession : LiveSessionHandle {
    val calls = mutableListOf<String>()

    override fun receive(): Flow<SdkLiveServerMessage> = emptyFlow()

    override suspend fun sendClientContent(turns: List<SdkContent>, turnComplete: Boolean) {
      calls.add("clientContent(turnComplete=$turnComplete)")
    }

    override suspend fun sendRealtimeInput(
      audio: SdkBlob?,
      video: SdkBlob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: SdkActivityStart?,
      activityEnd: SdkActivityEnd?,
    ) {
      calls.add("realtimeText($text)")
    }

    override suspend fun sendToolResponse(functionResponses: List<SdkFunctionResponse>) {
      // Names and ids tell responses apart; results are user data, so they are not recorded.
      calls.add(
        "toolResponse(${functionResponses.joinToString { "${it.name}#${it.id ?: "no-id"}" }})"
      )
    }

    override suspend fun closeSession() {
      calls.add("close")
    }
  }

  private fun connectionOn(model: String): Pair<GeminiLiveConnection, RecordingSession> {
    val session = RecordingSession()
    return GeminiLiveConnection(session, modelVersion = model) to session
  }

  @Test
  fun sendHistory_gemini3xEndingWithUser_completesTheTurnWithoutANudge(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_3X_LIVE)

    connection.sendHistory(listOf(userMessage("hello")))

    // The history's turn-complete alone starts the reply; a "." would be a second trigger.
    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendHistory_gemini3xEndingWithFunctionResponse_completesTheTurnWithoutANudge(): Unit =
    runBlocking {
      val (connection, session) = connectionOn(GEMINI_3X_LIVE)
      val response =
        Content(
          role = "user",
          parts =
            listOf(Part(functionResponse = FunctionResponse(name = "GetWeather", id = "call-1"))),
        )

      connection.sendHistory(listOf(response))

      // A function response is user-role, so it completes the turn, with no nudge on 3.x.
      assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
    }

  @Test
  fun sendHistory_olderLiveModel_sendsNoNudge(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_25_LIVE)

    connection.sendHistory(listOf(userMessage("hello")))

    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendHistory_gemini3xNotEndingWithUser_sendsNoNudge(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_3X_LIVE)

    connection.sendHistory(listOf(modelMessage("hi")))

    // The model is not expected to answer here, so nudging it would invent a turn.
    assertThat(session.calls).containsExactly("clientContent(turnComplete=false)")
  }

  @Test
  fun sendContent_gemini3xSingleTextPart_goesAsRealtimeInput(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_3X_LIVE)

    connection.sendContent(userMessage("what is the weather"))

    assertThat(session.calls).containsExactly("realtimeText(what is the weather)")
  }

  @Test
  fun sendContent_olderLiveModelSingleTextPart_goesAsClientContent(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_25_LIVE)

    connection.sendContent(userMessage("what is the weather"))

    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendContent_gemini3xMultiPart_goesAsClientContent(): Unit = runBlocking {
    val (connection, session) = connectionOn(GEMINI_3X_LIVE)

    connection.sendContent(userMessage(Part(text = "one"), Part(text = "two")))

    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendContent_gemini3xToolResponseWithText_stillGoesAsAToolResponse(): Unit = runBlocking {
    // Text on the part would also match the 3.x text branch, so this pins the branch order.
    val (connection, session) = connectionOn(GEMINI_3X_LIVE)

    connection.sendContent(
      Content(
        role = "user",
        parts =
          listOf(
            Part(
              text = "done",
              functionResponse = FunctionResponse(name = "GetWeather", id = "call-1"),
            )
          ),
      )
    )

    // Name and id, not a count: every branch of the send path makes exactly one call.
    assertThat(session.calls).containsExactly("toolResponse(GetWeather#call-1)")
  }

  @Test
  fun sendContent_gemini3xPartialSingleTextPart_goesAsClientContentLeavingTheTurnOpen(): Unit =
    runBlocking {
      val (connection, session) = connectionOn(GEMINI_3X_LIVE)

      connection.sendContent(userMessage("half"), partial = true)

      assertThat(session.calls).containsExactly("clientContent(turnComplete=false)")
    }
}
