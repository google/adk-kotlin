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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.testing.DummyLiveConnection
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.genai.kotlin.GenAiApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Covers the `send_data` span.
 *
 * A live run makes no `call_llm` calls, so without this span the one request it does send, the
 * conversation so far handed over at connect, appears in no trace at all. Tests that reconnect use
 * `runTest` so the reconnect backoff runs on virtual time.
 */
class LlmAgentLiveTelemetryTest {

  private val tracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    Telemetry.setTracerForTest(tracer)
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
    TelemetryConfig.captureMessageContent = false
  }

  /**
   * Opens one [DummyLiveConnection] per `connect`, playing [scripts] in order, so a test can script
   * a drop followed by a resume. Closes [queue] when the last connection opens so the run then
   * ends.
   */
  private class ScriptPerConnectionModel(
    private val queue: LiveRequestQueue,
    private val scripts: List<List<DummyLiveStep>>,
  ) : Model {
    override val name = "script-per-connection"
    var connects = 0
      private set

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
      throw AssertionError("a live run never calls generateContent")
    }

    override suspend fun connect(request: LlmRequest): LiveConnection {
      val script = scripts[connects++]
      if (connects == scripts.size) queue.close()
      return DummyLiveConnection(script)
    }
  }

  /** Runs a live turn against a session already holding [history]. */
  private suspend fun runWithHistory(
    history: Content?,
    model: Model = DummyLiveModel(listOf(EndStream)),
    queue: LiveRequestQueue = LiveRequestQueue().also { it.close() },
    runConfig: RunConfig? = null,
  ) {
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))
    if (history != null) {
      val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
      val unused =
        runner.sessionService.appendEvent(session, Event(author = "user", content = history))
    }
    val unusedEvents = runner.runLive("u", "s", queue, runConfig).toList()
  }

  private fun sendDataSpans() = tracer.recordedSpans.filter { it.name == "send_data" }

  private fun sendDataSpan() = sendDataSpans().singleOrNull()

  private fun sentData() =
    sendDataSpan()?.attributes?.get(TelemetryAttributes.GCP_VERTEX_AGENT_DATA) as? String

  private fun drop() = GenAiApiException(1006, "ConnectionClosed", "socket closed")

  @Test
  fun runLive_recordsASendDataSpanForTheSeededHistory(): Unit = runBlocking {
    runWithHistory(userMessage("earlier turn"))

    val span = sendDataSpan()
    assertNotNull(span, "expected a send_data span, saw ${tracer.recordedSpans.map { it.name }}")
    assertNotNull(
      span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID],
      "the span must name the invocation it belongs to",
    )
    assertNotNull(
      span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID],
      "the span must carry an event id, as the turn-based spans do",
    )
    assertTrue(span.isEnded, "the span must be closed even though nothing failed")
  }

  @Test
  fun runLive_withNothingToSeed_recordsNoSendDataSpan(): Unit = runBlocking {
    // Nothing was sent, so a span saying data was sent would be a lie.
    runWithHistory(history = null)

    assertEquals(null, sendDataSpan(), "no history means no send")
  }

  @Test
  fun runLive_withoutContentCapture_omitsTheConversation(): Unit = runBlocking {
    // The default. The attribute is still present, because the Dev UI parses it unconditionally.
    TelemetryConfig.captureMessageContent = false

    runWithHistory(userMessage("something private"))

    val data = sentData()
    assertEquals("{}", data, "the conversation must not reach a span unless capture is on")
  }

  @Test
  fun runLive_withContentCapture_recordsTheConversation(): Unit = runBlocking {
    TelemetryConfig.captureMessageContent = true

    runWithHistory(userMessage("what is the weather"))

    val data = sentData()
    assertNotNull(data)
    assertTrue(
      "what is the weather" in data,
      "expected the seeded turn in ${data.length} characters",
    )
  }

  @Test
  fun runLive_withContentCapture_leavesAudioOutOfTheSpan(): Unit = runBlocking {
    // Capture governs text, not raw media, so a live history's audio stays out of spans.
    TelemetryConfig.captureMessageContent = true
    val audio =
      Content(
        role = "user",
        parts =
          listOf(
            Part(text = "spoken"),
            Part(inlineData = Blob(mimeType = "audio/pcm", data = ByteArray(64) { 7 })),
          ),
      )

    runWithHistory(audio)

    val data = sentData()
    assertNotNull(data)
    assertTrue("spoken" in data, "the text should still be recorded (${data.length} characters)")
    assertTrue(
      "inlineData" !in data,
      "inline media must not reach the span (${data.length} characters)",
    )
  }

  @Test
  fun runLive_withContentCapture_summarizesInlineMedia(): Unit = runBlocking {
    TelemetryConfig.captureMessageContent = true
    val audio =
      Content(
        role = "user",
        parts = listOf(Part(inlineData = Blob(mimeType = "audio/pcm", data = ByteArray(64) { 7 }))),
      )

    runWithHistory(audio)

    val data = sentData()
    assertNotNull(data)
    assertTrue(
      "<inline_data: audio/pcm, 64 bytes>" in data,
      "expected a media summary in ${data.length} characters",
    )
  }

  @Test
  fun runLive_recordsNoInvocationSpan(): Unit = runBlocking {
    // ADK Python opens an invocation span only for turn-based runs; the agent's span still records.
    runWithHistory(history = null)

    val names = tracer.recordedSpans.map { it.name }
    assertTrue("invoke_agent live_agent" in names, "expected the agent's span, saw ${names.size}")
    assertTrue("invocation" !in names, "a live run must not open an invocation span")
  }

  @Test
  fun runLive_resumingFromTheCallersHandle_recordsNoSendDataSpan(): Unit = runBlocking {
    // The server already holds the history behind the handle, so nothing is sent, as in ADK Python.
    val model = DummyLiveModel(listOf(EndStream))

    runWithHistory(
      userMessage("earlier turn"),
      model = model,
      runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-0")),
    )

    assertEquals(1, model.connections.size)
    assertEquals(null, sendDataSpan(), "a resumed session sends no history")
  }

  @Test
  fun runLive_dropAfterAHandleUpdate_recordsOneSendDataSpanAcrossBothConnections(): Unit = runTest {
    // Only the first connection sends the history; the resume reuses the server's copy.
    val queue = LiveRequestQueue()
    val model =
      ScriptPerConnectionModel(
        queue,
        listOf(
          listOf(
            Respond(
              LlmResponse(
                liveSessionResumptionUpdate =
                  LiveServerSessionResumptionUpdate(newHandle = "h-1", resumable = true)
              )
            ),
            Fail(drop()),
          ),
          listOf(EndStream),
        ),
      )

    runWithHistory(userMessage("earlier turn"), model, queue)

    assertEquals(2, model.connects)
    assertEquals(1, sendDataSpans().size)
  }

  @Test
  fun runLive_freshRestartAfterAGoAway_recordsASecondSpanWithItsOwnEventId(): Unit = runTest {
    // A handle-less go-away restarts the session, which resends the history, as in ADK Python.
    val queue = LiveRequestQueue()
    val model =
      ScriptPerConnectionModel(
        queue,
        listOf(
          listOf(
            Respond(LlmResponse(content = modelMessage("hi"), partial = false)),
            Respond(LlmResponse(turnComplete = true)),
            Respond(LlmResponse(goAway = LiveServerGoAway(timeLeft = null))),
          ),
          listOf(EndStream),
        ),
      )

    runWithHistory(userMessage("earlier turn"), model, queue)

    val eventIds =
      sendDataSpans().map { it.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] }
    assertEquals(2, model.connects)
    assertEquals(2, eventIds.size, "the restarted session sends the history again")
    assertEquals(2, eventIds.distinct().size, "each session gets its own event id")
  }
}
