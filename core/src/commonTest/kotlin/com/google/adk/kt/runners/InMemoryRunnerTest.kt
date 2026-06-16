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
@file:OptIn(ExperimentalResumabilityFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InMemoryRunnerTest {

  private val dummyAgent = DummyAgent(name = "dummy-agent")

  @Test
  fun runAsync_constructedFromApp_usesAppNameAndRootAgent() = runTest {
    val app =
      App(
        appName = "dummy_app",
        rootAgent =
          DummyAgent(
            name = "dummy_agent",
            onRunAsync = { emit(Event(author = Role.MODEL, content = modelMessage("hello"))) },
          ),
      )
    val runner = InMemoryRunner(app = app)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertThat(runner.appName).isEqualTo("dummy_app")
    assertThat(events.map { it.content?.parts?.firstOrNull()?.text }).containsExactly("hello")
    val sessionEvents =
      runner.sessionService.getSession(SessionKey("dummy_app", "user1", "session1"))!!.events
    assertThat(sessionEvents.map { it.author }).containsExactly(Role.USER, Role.MODEL).inOrder()
  }

  @Test
  fun runAsync_withoutFunctionResponse_usesProvidedInvocationId() = runTest {
    val runner = InMemoryRunner(agent = dummyAgent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          invocationId = "custom-inv-999",
          newMessage = userMessage("Hello"),
        )
        .toList()

    assertThat(events.size).isEqualTo(0)
    assertThat(
        runner.sessionService
          .getSession(SessionKey(runner.appName, "user1", "session1"))!!
          .events
          .last()
          .invocationId
      )
      .isEqualTo("custom-inv-999")
  }

  @Test
  fun runAsync_withFunctionResponse_copiesBranchFromFunctionCall() = runTest {
    val runner = InMemoryRunner(agent = dummyAgent)
    val session =
      runner.sessionService.createSession(SessionKey(runner.appName, "user1", "session1"), State())

    val unused =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "test-inv",
          author = dummyAgent.name,
          branch = "my_special_branch",
          content =
            Content(
              role = Role.MODEL,
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(name = "test_func", args = emptyMap(), id = "call_abc")
                  )
                ),
            ),
        ),
      )

    runner
      .runAsync(
        userId = "user1",
        sessionId = "session1",
        invocationId = "test-inv",
        newMessage =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = "test_func",
                      response = mapOf("result" to "ok"),
                      id = "call_abc",
                    )
                )
              ),
          ),
      )
      .toList()

    assertThat(
        runner.sessionService
          .getSession(SessionKey(runner.appName, "user1", "session1"))!!
          .events
          .last { it.author == "user" }
          .branch
      )
      .isEqualTo("my_special_branch")
  }

  @Test
  fun runAsync_withRunConfigCustomMetadata_appliesToAllEvents() = runTest {
    val runner =
      InMemoryRunner(
        agent =
          DummyAgent(
            name = "test-agent",
            onRunAsync = { emit(Event(author = Role.MODEL, content = modelMessage("hello"))) },
          )
      )

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(customMetadata = mapOf("testKey" to "testValue")),
        )
        .toList()

    assertThat(events.size).isEqualTo(1)
    assertThat(events[0].customMetadata?.get("testKey")).isEqualTo("testValue")

    val allSessionEvents =
      runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))!!.events
    assertThat(allSessionEvents.size).isEqualTo(2)
    assertThat(allSessionEvents[0].author).isEqualTo(Role.USER)
    assertThat(allSessionEvents[0].customMetadata?.get("testKey")).isEqualTo("testValue")
    assertThat(allSessionEvents[1].author).isEqualTo(Role.MODEL)
    assertThat(allSessionEvents[1].customMetadata?.get("testKey")).isEqualTo("testValue")
  }

  @Test
  fun runAsync_withRunConfigCustomMetadata_existingMetadataTakesPrecedence() = runTest {
    val runner =
      InMemoryRunner(
        agent =
          DummyAgent(
            name = "test-agent",
            onRunAsync = {
              emit(
                Event(
                  author = Role.MODEL,
                  content = modelMessage("hello"),
                  customMetadata = mapOf("sharedKey" to "agentValue"),
                )
              )
            },
          )
      )

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(customMetadata = mapOf("sharedKey" to "configValue")),
        )
        .toList()

    assertThat(events.size).isEqualTo(1)
    assertThat(events[0].customMetadata).isEqualTo(mapOf("sharedKey" to "agentValue"))

    val allSessionEvents =
      runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))!!.events
    assertThat(allSessionEvents.size).isEqualTo(2)
    assertThat(allSessionEvents[0].customMetadata).isEqualTo(mapOf("sharedKey" to "configValue"))
    assertThat(allSessionEvents[1].customMetadata).isEqualTo(mapOf("sharedKey" to "agentValue"))
  }

  @Test
  fun runAsync_withLlmAgentAndProviderInstruction_interpolatesState() = runTest {
    var capturedSystemInstruction: String? = null
    val runner =
      InMemoryRunner(
        agent =
          LlmAgent(
            name = "test-agent",
            model =
              DummyModel(name = "test-model") { request ->
                capturedSystemInstruction =
                  request.config.systemInstruction?.parts?.firstOrNull()?.text
                flowOf(LlmResponse(content = modelMessage("OK")))
              },
            instruction = Instruction("Hello {user_name}!"),
          )
      )
    val unused =
      runner.sessionService.createSession(
        SessionKey(runner.appName, "user1", "session1"),
        State().apply { this["user_name"] = "Alice" },
      )

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
      .toList()

    assertThat(capturedSystemInstruction).isEqualTo("Hello Alice!")
  }

  @Test
  fun applyStateDelta_mergesStateDeltaIntoEventActions() = runTest {
    val runner = InMemoryRunner(agent = dummyAgent)
    val event = Event(author = Role.USER, content = userMessage("hello"))
    val stateDelta = mapOf("key1" to "value1", "key2" to 42)

    runner.applyStateDelta(event, stateDelta)

    assertThat(event.actions.stateDelta).isEqualTo(stateDelta)
  }

  @Test
  fun runAsync_withResumability_restoresAgentState() = runTest {
    val testAgent = DummyAgent { context ->
      val state = context.agentStates["test-agent"]
      emit(
        Event(
          author = "test-agent",
          content = Content(parts = listOf(Part(text = "State is $state"))),
        )
      )
    }

    val runner =
      InMemoryRunner(agent = testAgent, resumabilityConfig = ResumabilityConfig(isResumable = true))
    val session =
      runner.sessionService.createSession(SessionKey(runner.appName, "user1", "session1"), State())

    // Append initial user message
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "test-inv",
          author = "user",
          content = Content(parts = listOf(Part(text = "hi"))),
        ),
      )

    // Append event with agent state
    val unused2 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "test-inv",
          author = "test-agent",
          actions = EventActions(agentState = TypedData.StringValue("saved_state")),
          content = Content(parts = listOf(Part(text = "previous response"))),
        ),
      )

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          invocationId = "test-inv",
          newMessage = null,
        )
        .toList()

    assertThat(simplifyEvents(events))
      .containsExactly("test-agent" to "State is StringValue(value=saved_state)")
  }

  @Test
  fun runAsync_withResumability_andNewMessage_handlesNewUserContent() = runTest {
    val testAgent = DummyAgent(name = "test-agent")
    val runner =
      InMemoryRunner(agent = testAgent, resumabilityConfig = ResumabilityConfig(isResumable = true))
    val session =
      runner.sessionService.createSession(SessionKey(runner.appName, "user1", "session1"), State())

    // Append initial user message to make it look like a resumed session
    val unused1 =
      runner.sessionService.appendEvent(
        session,
        Event(
          invocationId = "test-inv",
          author = "user",
          content = Content(parts = listOf(Part(text = "hi"))),
        ),
      )

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          invocationId = "test-inv",
          newMessage = userMessage("New message"),
        )
        .toList()

    val allSessionEvents =
      runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))!!.events

    assertThat(allSessionEvents.size).isEqualTo(2)
    assertThat(allSessionEvents.last().content?.parts?.get(0)?.text).isEqualTo("New message")
  }
}
