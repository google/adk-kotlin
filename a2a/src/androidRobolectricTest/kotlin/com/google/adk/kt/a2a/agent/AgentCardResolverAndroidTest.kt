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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.a2aproject.sdk.client.http.AndroidA2AHttpClient
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.TransportProtocol
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the proto-free Android agent-card auto-fetch: [resolveAgentCard] and [AndroidA2AAgent]
 * fetch and parse a card from the standard `/.well-known/agent-card.json` endpoint (served here by
 * [MockWebServer] on the Robolectric runtime).
 */
@RunWith(AndroidJUnit4::class)
class AgentCardResolverAndroidTest {

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
  fun resolveAgentCard_fetchesAndParsesFromWellKnownEndpoint() = runTest {
    val baseUrl = server.url("/").toString()
    server.enqueue(MockResponse().setBody(JsonUtil.toJson(agentCard(baseUrl))))

    val resolved = resolveAgentCard(AndroidA2AHttpClient(), baseUrl)

    assertThat(resolved.name()).isEqualTo("remote-agent")
    assertThat(resolved.description()).isEqualTo("Remote Agent")
    assertThat(recordedPath()).isEqualTo("/.well-known/agent-card.json")
  }

  @Test
  fun androidA2AAgent_autoFetchesCard_andPopulatesDescription() = runTest {
    val baseUrl = server.url("/").toString()
    server.enqueue(MockResponse().setBody(JsonUtil.toJson(agentCard(baseUrl))))

    val agent = AndroidA2AAgent(name = "remote-agent", agentCardUrl = baseUrl)

    assertThat(agent.description).isEqualTo("Remote Agent")
  }

  @Test
  fun resolveAgentCard_httpError_throwsResolutionError() = runTest {
    server.enqueue(MockResponse().setResponseCode(500))
    val baseUrl = server.url("/").toString()

    val e = runCatching { resolveAgentCard(AndroidA2AHttpClient(), baseUrl) }.exceptionOrNull()

    assertThat(e).isInstanceOf(BaseRemoteA2AAgent.AgentCardResolutionError::class.java)
    assertThat(e).hasMessageThat().contains("Failed to fetch agent card")
  }

  @Test
  fun resolveAgentCard_malformedBody_throwsResolutionError() = runTest {
    server.enqueue(MockResponse().setBody("not json"))
    val baseUrl = server.url("/").toString()

    val e = runCatching { resolveAgentCard(AndroidA2AHttpClient(), baseUrl) }.exceptionOrNull()

    assertThat(e).isInstanceOf(BaseRemoteA2AAgent.AgentCardResolutionError::class.java)
    assertThat(e).hasMessageThat().contains("Failed to parse agent card")
  }

  @Test
  fun resolveAgentCard_emptyBody_throwsResolutionError() = runTest {
    // A 200 with an empty body parses to null; must surface as AgentCardResolutionError, not an
    // NPE.
    server.enqueue(MockResponse().setBody(""))
    val baseUrl = server.url("/").toString()

    val e = runCatching { resolveAgentCard(AndroidA2AHttpClient(), baseUrl) }.exceptionOrNull()

    assertThat(e).isInstanceOf(BaseRemoteA2AAgent.AgentCardResolutionError::class.java)
    assertThat(e).hasMessageThat().contains("Empty agent card response")
  }

  @Test
  fun resolveAgentCard_networkFailure_throwsResolutionError() = runTest {
    // Point at a server that is immediately shut down, so the GET fails with an IOException.
    val deadServer = MockWebServer().apply { start() }
    val baseUrl = deadServer.url("/").toString()
    deadServer.shutdown()

    val e = runCatching { resolveAgentCard(AndroidA2AHttpClient(), baseUrl) }.exceptionOrNull()

    assertThat(e).isInstanceOf(BaseRemoteA2AAgent.AgentCardResolutionError::class.java)
    assertThat(e).hasMessageThat().contains("Failed to fetch agent card")
  }

  private fun recordedPath(): String? = server.takeRequest().path
}
