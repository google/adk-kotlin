/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the point of seeding a session: a run that follows one picks the conversation up where the
 * seed left off. The route tests show the events are stored and returned; these show the agent
 * actually reads them as history, which is what multi-turn evaluation depends on.
 */
@RunWith(JUnit4::class)
class SessionSeedingTest {

  @Test
  fun runAfterSeeding_agentSeesTheSeededTurnsAsHistory() = testApplication {
    application { adkApiModule(testConfig()) }

    val created =
      client.post("/apps/history-agent/users/u/sessions") {
        jsonBody(
          """
          {"sessionId":"s1","events":[
            {"id":"e1","author":"user",
             "content":{"role":"user","parts":[{"text":"my name is Ada"}]}},
            {"id":"e2","author":"history-agent",
             "content":{"role":"model","parts":[{"text":"hello Ada"}]}}
          ]}
          """
            .trimIndent()
        )
      }
    // Asserted before the run, so a failed seed reads as a failed seed rather than as a history
    // mismatch further down.
    assertThat(created.status).isEqualTo(HttpStatusCode.OK)

    val run =
      client.post("/run") {
        jsonBody(
          """{"appName":"history-agent","userId":"u","sessionId":"s1",""" +
            """"newMessage":{"role":"user","parts":[{"text":"what is my name?"}]}}"""
        )
      }

    assertThat(run.status).isEqualTo(HttpStatusCode.OK)
    // Two seeded turns precede the new message, and the earlier one is readable, so the run is a
    // continuation of the seeded conversation rather than a fresh one.
    assertThat(run.bodyAsText()).contains("historyBefore=2")
    assertThat(run.bodyAsText()).contains("recalled=my name is Ada")
  }

  @Test
  fun runAfterSeedingState_agentSeesTheSeededState() = testApplication {
    // The other half of an eval seed: state the graded run is expected to start from.
    application { adkApiModule(testConfig()) }

    val created =
      client.post("/apps/history-agent/users/u/sessions") {
        jsonBody("""{"sessionId":"s1","state":{"topic":"astronomy","temp:scratch":"dropped"}}""")
      }
    assertThat(created.status).isEqualTo(HttpStatusCode.OK)

    val run =
      client.post("/run") {
        jsonBody(
          """{"appName":"history-agent","userId":"u","sessionId":"s1",""" +
            """"newMessage":{"role":"user","parts":[{"text":"go"}]}}"""
        )
      }

    assertThat(run.bodyAsText()).contains("topic=astronomy")
    assertThat(run.bodyAsText()).contains("scratch=absent")
  }

  private fun testConfig() =
    AdkServerConfig(
      agentLoader = HistoryAgentLoader(),
      sessionService = InMemorySessionService(),
      artifactService = FakeArtifactService(),
      apiServerSpanExporter = ApiServerSpanExporter(),
    )
}

/** Reports what the session already held when the run began, so a test can see the seed. */
private class HistoryAgent :
  BaseAgent(name = "history-agent", description = "Reports the history it was given") {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    // The run's own user message is appended last, before the agent is invoked, so drop it.
    val before = context.session.events.dropLast(1)
    val recalled = before.firstOrNull()?.content?.text().orEmpty()
    val scratch = if (context.session.state.containsKey("temp:scratch")) "present" else "absent"
    emit(
      Event(
        invocationId = context.invocationId,
        author = "history-agent",
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(
                  text =
                    "historyBefore=${before.size};recalled=$recalled;" +
                      "topic=${context.session.state["topic"]};scratch=$scratch"
                )
              ),
          ),
        turnComplete = true,
      )
    )
  }
}

private class HistoryAgentLoader : AgentLoader {
  override fun listAgents() = listOf("history-agent")

  override fun loadAgent(agentName: String) =
    if (agentName == "history-agent") HistoryAgent() else null
}
