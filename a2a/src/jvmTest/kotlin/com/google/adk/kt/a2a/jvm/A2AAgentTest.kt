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

package com.google.adk.kt.a2a.jvm

import com.google.adk.kt.a2a.agent.BaseRemoteA2AAgent.AgentCardResolutionError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.TransportProtocol
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Verifies the JVM agent-card auto-fetch: [A2AAgent] fetches and parses a card from the standard
 * `/.well-known/agent-card.json` endpoint (served here by [MockWebServer]).
 */
@RunWith(JUnit4::class)
class A2AAgentTest {

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

  private fun agentCard(url: String): AgentCard =
    AgentCard.builder()
      .name("remote-agent")
      .description("Remote Agent")
      .url(url)
      .version("1.0.0")
      .defaultInputModes(listOf("text"))
      .defaultOutputModes(listOf("text"))
      .skills(listOf())
      .supportedInterfaces(listOf(AgentInterface(TransportProtocol.JSONRPC.asString(), url)))
      .capabilities(AgentCapabilities.builder().streaming(false).build())
      .build()

  @Test
  fun a2aAgent_autoFetchesCard_andPopulatesDescription() = runTest {
    val baseUrl = server.url("/").toString()
    server.enqueue(MockResponse().setBody(JsonUtil.toJson(agentCard(baseUrl))))

    val agent = A2AAgent(name = "remote-agent", agentCardUrl = baseUrl)

    assertThat(agent.description).isEqualTo("Remote Agent")
    assertThat(recordedPath()).isEqualTo("/.well-known/agent-card.json")
  }

  @Test
  fun a2aAgent_explicitDescription_overridesCardDescription() {
    // The card's description is "Remote Agent"; an explicit description should win.
    val agent =
      A2AAgent(
        name = "remote-agent",
        agentCard = agentCard(server.url("/").toString()),
        description = "Custom Description",
      )

    assertThat(agent.description).isEqualTo("Custom Description")
  }

  @Test
  fun a2aAgent_httpError_throwsResolutionError() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))

    val error =
      runCatching { A2AAgent(name = "remote-agent", agentCardUrl = server.url("/").toString()) }
        .exceptionOrNull()

    assertThat(error).isInstanceOf(AgentCardResolutionError::class.java)
    assertThat(error).hasMessageThat().contains("HTTP 404")
  }

  @Test
  fun a2aAgent_malformedCard_throwsResolutionError() = runTest {
    server.enqueue(MockResponse().setBody("{ not json"))

    val error =
      runCatching { A2AAgent(name = "remote-agent", agentCardUrl = server.url("/").toString()) }
        .exceptionOrNull()

    assertThat(error).isInstanceOf(AgentCardResolutionError::class.java)
    assertThat(error).hasMessageThat().contains("parse")
  }

  @Test
  fun a2aAgent_fetchError_throwsResolutionError() = runTest {
    val url = server.url("/").toString()
    server.shutdown()

    val error =
      runCatching { A2AAgent(name = "remote-agent", agentCardUrl = url) }.exceptionOrNull()

    assertThat(error).isInstanceOf(AgentCardResolutionError::class.java)
    assertThat(error).hasMessageThat().contains("fetch")
  }

  @Test
  fun a2aAgent_fullWellKnownUrl_notDoubleAppended() = runTest {
    server.enqueue(MockResponse().setBody(JsonUtil.toJson(agentCard(server.url("/").toString()))))

    val unusedAgent =
      A2AAgent(
        name = "remote-agent",
        agentCardUrl = server.url("/.well-known/agent-card.json").toString(),
      )

    assertThat(recordedPath()).isEqualTo("/.well-known/agent-card.json")
  }

  @Test
  fun a2aAgent_cardMissingRequiredField_throwsResolutionError() = runTest {
    // A syntactically valid card JSON with the required `capabilities` field removed. Confirms a
    // missing required field (SDK record ctor's checkNotNullParam) surfaces as
    // AgentCardResolutionError rather than a raw exception.
    val cardMissingCapabilities =
      JsonUtil.toJson(agentCard(server.url("/").toString()))
        .replace(Regex("\"capabilities\":\\{[^}]*\\}"), "")
        .replace(",,", ",")
        .replace("{,", "{")
        .replace(",}", "}")
    server.enqueue(MockResponse().setBody(cardMissingCapabilities))

    val error =
      runCatching { A2AAgent(name = "remote-agent", agentCardUrl = server.url("/").toString()) }
        .exceptionOrNull()

    assertThat(error).isInstanceOf(AgentCardResolutionError::class.java)
  }

  @Test
  fun a2aAgent_emptyCard_throwsResolutionError() = runTest {
    // An empty body parses to a null card. We assert only the exception type, not the message: an
    // empty body either yields a null card (the "Empty…" guard) or a parse exception, and which one
    // depends on the Gson version, so both must map to AgentCardResolutionError.
    server.enqueue(MockResponse().setBody(""))

    val error =
      runCatching { A2AAgent(name = "remote-agent", agentCardUrl = server.url("/").toString()) }
        .exceptionOrNull()

    assertThat(error).isInstanceOf(AgentCardResolutionError::class.java)
  }

  private fun recordedPath(): String? = server.takeRequest().path
}
