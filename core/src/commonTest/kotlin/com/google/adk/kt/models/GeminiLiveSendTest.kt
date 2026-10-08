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

import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.ActivityEnd as SdkActivityEnd
import com.google.genai.kotlin.types.ActivityStart as SdkActivityStart
import com.google.genai.kotlin.types.Blob as SdkBlob
import com.google.genai.kotlin.types.Content as SdkContent
import com.google.genai.kotlin.types.FunctionResponse as SdkFunctionResponse
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

private const val MODEL = "gemini-live-test"

/** Tests a live connection's send path. */
class GeminiLiveSendTest {

  /** Records what reached the session, in order, so a test can assert which call was used. */
  private class RecordingSession : LiveSessionHandle {
    val calls = mutableListOf<String>()
    val sentTurns = mutableListOf<List<SdkContent>>()

    override fun receive(): Flow<SdkLiveServerMessage> = emptyFlow()

    override suspend fun sendClientContent(turns: List<SdkContent>, turnComplete: Boolean) {
      sentTurns.add(turns)
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
  fun sendHistory_endingWithFunctionResponse_completesTheTurnWithoutANudge(): Unit = runBlocking {
    val (connection, session) = connectionOn(MODEL)
    val response =
      Content(
        role = "user",
        parts =
          listOf(Part(functionResponse = FunctionResponse(name = "GetWeather", id = "call-1"))),
      )

    connection.sendHistory(listOf(response))

    // A function response is user-role, so it completes the turn.
    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendHistory_endingWithCapitalizedUserRole_doesNotCompleteTheTurn(): Unit = runBlocking {
    val (connection, session) = connectionOn(MODEL)

    // Case-sensitive like ADK Python (`role == "user"`): "User" is not "user", so no turn-complete.
    connection.sendHistory(listOf(Content(role = "User", parts = listOf(Part(text = "hello")))))

    assertThat(session.calls).containsExactly("clientContent(turnComplete=false)")
  }

  @Test
  fun sendHistory_dropsAdkLiveArtifactParts_keepsTextAndExternalFileData(): Unit = runBlocking {
    // Parity with ADK Python's `_filter_media_parts`: internal `_adk_live` artifact references
    // (any MIME) are dropped from replayed history, while external fileData is kept.
    val (connection, session) = connectionOn(MODEL)
    val adkLiveUri = "artifact://app/u/s/_adk_live/img.png#1"
    val gcsUri = "gs://b/img.png"
    val mixed =
      Content(
        role = "user",
        parts =
          listOf(
            Part(text = "look at these"),
            Part(fileData = FileData(fileUri = adkLiveUri, mimeType = "image/png")),
            Part(fileData = FileData(fileUri = gcsUri, mimeType = "image/png")),
          ),
      )
    val onlyAdkLive =
      Content(
        role = "user",
        parts = listOf(Part(fileData = FileData(fileUri = adkLiveUri, mimeType = "image/png"))),
      )

    connection.sendHistory(listOf(mixed, onlyAdkLive))

    // `onlyAdkLive` is dropped entirely; `mixed` keeps the text and the gs:// part.
    val turns = session.sentTurns.single()
    assertThat(turns).hasSize(1)
    val parts = turns.single().parts.orEmpty()
    assertThat(parts.mapNotNull { it.text }).containsExactly("look at these")
    assertThat(parts.mapNotNull { it.fileData?.fileUri }).containsExactly(gcsUri)
  }

  @Test
  fun sendContent_multiPart_goesAsClientContent(): Unit = runBlocking {
    val (connection, session) = connectionOn(MODEL)

    connection.sendContent(userMessage(Part(text = "one"), Part(text = "two")))

    assertThat(session.calls).containsExactly("clientContent(turnComplete=true)")
  }

  @Test
  fun sendContent_toolResponseWithText_stillGoesAsAToolResponse(): Unit = runBlocking {
    // A tool response carrying text is still sent as a tool response: pins tool response over
    // client
    // content.
    val (connection, session) = connectionOn(MODEL)

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
}
