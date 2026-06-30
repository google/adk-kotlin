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

package com.google.adk.kt.a2a.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.a2a.android.AndroidA2AAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.userMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TaskStatus
import org.a2aproject.sdk.spec.TextPart
import org.a2aproject.sdk.spec.TransportProtocol
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) counterpart of `A2AAgentAndroidTest`: builds an Android A2A agent via
 * [AndroidA2AAgent] and runs a round-trip against an in-process [MockWebServer], exercising the
 * proto-free Android transport.
 *
 * The MockWebServer returns a real JSON-RPC response built with the SDK's own
 * [JsonUtil] + [SendMessageResponse], and the transport parses it back with [JsonUtil], so this
 * exercises the actual proto-free A2A serialization on a real Android runtime in both directions.
 */
@RunWith(AndroidJUnit4::class)
class A2AAgentInstrumentationTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun runAsync_androidHttpClient_realRoundTrip_emitsAgentEventAndSendsRequest() = runTest {
    val agentReply = "Hello from the Android A2A agent!"

    // Build a real JSON-RPC `message/send` response with the SDK's own proto-free serialization:
    // a completed Task whose status message carries the agent text. Using a completed Task (rather
    // than a bare Message) lets the agent's non-streaming flow recognise the turn as terminal.
    val agentMessage =
      Message.builder()
        .messageId("agent-message-1")
        .role(Message.Role.ROLE_AGENT)
        .parts(listOf(TextPart(agentReply)))
        .build()
    val responseTask =
      Task.builder()
        .id("android-task-1")
        .contextId("android-context-1")
        .status(TaskStatus(TaskState.TASK_STATE_COMPLETED, agentMessage, null))
        .build()
    val responseBody = JsonUtil.toJson(SendMessageResponse("2.0", "1", responseTask, null))
    server.enqueue(MockResponse().setBody(responseBody))
    val serverUrl = server.url("/a2a").toString()

    val agentCard =
      AgentCard.builder()
        .name("remote-agent")
        .description("Remote Agent")
        .url(serverUrl)
        .version("1.0.0")
        .defaultInputModes(listOf("text"))
        .defaultOutputModes(listOf("text"))
        .skills(listOf())
        .supportedInterfaces(
          listOf(AgentInterface(TransportProtocol.JSONRPC.asString(), serverUrl))
        )
        .capabilities(AgentCapabilities.builder().streaming(false).build())
        .build()

    val agent = AndroidA2AAgent(name = "remote-agent", agentCard = agentCard)

    val session =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-1"),
        events =
          mutableListOf(
            Event(invocationId = "invocation-0", author = "user", content = userMessage("hello"))
          ),
      )
    val context = InvocationContext(agent = DummyAgent(), session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // The agent emitted an ADK Event carrying the agent text from the real round-trip.
    val emittedTexts = events.mapNotNull { it.content?.parts?.firstOrNull()?.text }
    assertThat(emittedTexts).contains(agentReply)

    // The user message actually traversed AndroidA2AHttpClient and reached the server.
    val recorded = server.takeRecordedRequestOrFail()
    assertThat(recorded.path).isEqualTo("/a2a")
    assertThat(recorded.method).isEqualTo("POST")
    assertThat(recorded.body.readUtf8()).contains("hello")
  }

  @Test
  fun runAsync_autoFetchCard_realRoundTrip_fetchesCardThenSendsMessage() = runTest {
    val agentReply = "Hello from the auto-fetched Android A2A agent!"
    val serverUrl = server.url("/a2a").toString()

    val agentCard =
      AgentCard.builder()
        .name("remote-agent")
        .description("Remote Agent")
        .url(serverUrl)
        .version("1.0.0")
        .defaultInputModes(listOf("text"))
        .defaultOutputModes(listOf("text"))
        .skills(listOf())
        .supportedInterfaces(
          listOf(AgentInterface(TransportProtocol.JSONRPC.asString(), serverUrl))
        )
        .capabilities(AgentCapabilities.builder().streaming(false).build())
        .build()
    // 1) Served for the auto-fetch GET of `/.well-known/agent-card.json`.
    server.enqueue(MockResponse().setBody(JsonUtil.toJson(agentCard)))

    val agentMessage =
      Message.builder()
        .messageId("agent-message-1")
        .role(Message.Role.ROLE_AGENT)
        .parts(listOf(TextPart(agentReply)))
        .build()
    val responseTask =
      Task.builder()
        .id("android-task-1")
        .contextId("android-context-1")
        .status(TaskStatus(TaskState.TASK_STATE_COMPLETED, agentMessage, null))
        .build()
    // 2) Served for the `message/send` POST.
    server.enqueue(
      MockResponse().setBody(JsonUtil.toJson(SendMessageResponse("2.0", "1", responseTask, null)))
    )

    // No card supplied: the agent auto-fetches it from the well-known endpoint on-device.
    val agent = AndroidA2AAgent(name = "remote-agent", agentCardUrl = server.url("/").toString())

    val session =
      Session(
        key = SessionKey(appName = "demo", userId = "user", id = "session-1"),
        events =
          mutableListOf(
            Event(invocationId = "invocation-0", author = "user", content = userMessage("hello"))
          ),
      )
    val context = InvocationContext(agent = DummyAgent(), session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    val emittedTexts = events.mapNotNull { it.content?.parts?.firstOrNull()?.text }
    assertThat(emittedTexts).contains(agentReply)

    // The card was fetched from the well-known endpoint first, then the message was sent.
    val cardRequest = server.takeRecordedRequestOrFail()
    assertThat(cardRequest.path).isEqualTo("/.well-known/agent-card.json")
    assertThat(cardRequest.method).isEqualTo("GET")
    val sendRequest = server.takeRecordedRequestOrFail()
    assertThat(sendRequest.path).isEqualTo("/a2a")
    assertThat(sendRequest.method).isEqualTo("POST")
    assertThat(sendRequest.body.readUtf8()).contains("hello")
  }
}

private fun MockWebServer.takeRecordedRequestOrFail(): RecordedRequest =
  try {
    takeRequest()
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw AssertionError("Interrupted while waiting for the recorded request", e)
  }
