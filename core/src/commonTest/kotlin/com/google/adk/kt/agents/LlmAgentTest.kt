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

package com.google.adk.kt.agents

import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAgentTest {

  private val testModel =
    object : Model {
      override val name = "test-model"

      override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
        flow {}
    }

  @Test
  fun init_withRequiredParams_succeeds() {
    val agent = LlmAgent(name = "test_agent", model = testModel)

    assertEquals("test_agent", agent.name)
    assertEquals(testModel, agent.model)
    assertTrue(agent.tools.isEmpty())
  }

  @Test
  fun init_defaultIncludeContents_isDefault() {
    val agent = LlmAgent(name = "test_agent", model = testModel)

    assertEquals(IncludeContents.DEFAULT, agent.includeContents)
  }

  @Test
  fun runAsync_withFunctionCall_emitsCorrectEvents() = runTest {
    val firstContent =
      Content(
        "model",
        listOf(
          Part(text = "LLM response with function call"),
          Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"), id = "call_1")),
        ),
      )
    val secondText = "LLM response after function response"

    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = firstContent), LlmResponse(content = modelMessage(secondText))),
      )

    val testResponse = mapOf("response" to "response for my_function")
    val testTool = DummyTool("my_function", onRun = { _, _ -> testResponse })

    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(testTool))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(3, events.size)

    // First event is the model response with the function call
    assertEquals("test-agent", events[0].author)
    assertEquals(firstContent.parts[0].text, events[0].content?.parts?.get(0)?.text)
    assertEquals("my_function", events[0].content?.parts?.get(1)?.functionCall?.name)

    // Second event is the function response
    assertEquals("test-agent", events[1].author)
    assertEquals("my_function", events[1].content?.parts?.get(0)?.functionResponse?.name)
    assertEquals(testResponse, events[1].content?.parts?.get(0)?.functionResponse?.response)

    // Third event is the final model response
    assertEquals("test-agent", events[2].author)
    assertEquals(secondText, events[2].content?.parts?.get(0)?.text)
  }

  @Test
  fun runAsync_longRunningFunctionCall_returnsToolIds() = runTest {
    val firstContent =
      Content(
        "model",
        listOf(
          Part(text = "LLM response with function call"),
          Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"), id = "call_1")),
        ),
      )
    val secondText = "LLM response after function response"

    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = firstContent), LlmResponse(content = modelMessage(secondText))),
      )

    val testResponse = mapOf("response" to "response for my_function")
    val testTool = DummyTool("my_function", isLongRunning = true, onRun = { _, _ -> testResponse })

    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(testTool))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(3, events.size)

    // First event has the longRunningToolIds set to the function call's id
    assertEquals("test-agent", events[0].author)
    assertEquals(firstContent.parts[0].text, events[0].content?.parts?.get(0)?.text)
    assertEquals(setOf("call_1"), events[0].longRunningToolIds)
    assertTrue(events[0].longRunningToolIds.contains(events[0].functionCalls().firstOrNull()?.id))

    // Second event is the function response
    assertEquals("test-agent", events[1].author)
    assertEquals("my_function", events[1].content?.parts?.get(0)?.functionResponse?.name)

    // Third event is the final model response
    assertEquals("test-agent", events[2].author)
    assertEquals(secondText, events[2].content?.parts?.get(0)?.text)
  }

  @Test
  fun runAsync_modelThrowsException_processorRecovers() = runTest {
    val failingModel =
      object : Model {
        override val name = "failing-model"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            throw RuntimeException("Model failed")
          }
      }

    val callback = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Fallback response")))
    }

    val agent =
      LlmAgent(name = "test-agent", model = failingModel, onModelErrorCallbacks = listOf(callback))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertEquals("test-agent", events[0].author)
    assertEquals("Fallback response", events[0].content?.parts?.get(0)?.text)
  }

  @Test(expected = RuntimeException::class)
  fun runAsync_modelThrowsException_noProcessor_throwsException() = runTest {
    val failingModel =
      object : Model {
        override val name = "failing-model"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            throw RuntimeException("Model failed")
          }
      }

    val agent = LlmAgent(name = "test-agent", model = failingModel) // No processors
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    agent.runAsync(context).toList()
  }

  @Test
  fun runAsync_preparesRequestWithAllProcessorSteps() = runTest {
    var capturedRequest: LlmRequest? = null
    val customModel =
      object : Model {
        override val name = "custom-model"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            capturedRequest = request
            emit(LlmResponse(content = modelMessage("Response")))
          }
      }

    val agent =
      LlmAgent(
        name = "test-agent",
        model = customModel,
        staticInstruction = Content(parts = listOf(Part(text = "Static Instruction"))),
      )

    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    // Add a user event to session history to verify history/contents processing
    session.events.add(
      Event(
        id = Uuid.random(),
        invocationId = "test-invocation",
        author = Role.USER,
        content = userMessage("Hello"),
      )
    )

    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    agent.runAsync(context).toList()

    val req = capturedRequest
    assertNotNull(req)

    // 1. Verifies BasicRequestProcessor has run
    assertEquals(customModel, req!!.model)

    // 2. Verifies InstructionsProcessor has run
    val systemInstruction = req.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("Static Instruction", systemInstruction!!.parts.firstOrNull()?.text)

    // 3. Verifies ContentsProcessor has run
    assertEquals(1, req.contents.size)
    assertEquals("Hello", req.contents.first().parts.firstOrNull()?.text)
  }

  @Test
  fun runAsync_resumingWithSubAgent_callsSubAgentDirectly() = runTest {
    val subAgent =
      DummyAgent("sub-agent", onRunAsync = { emit(createEvent("sub-agent", "msg-from-sub")) })
    val agent = LlmAgent(name = "test-agent", subAgents = listOf(subAgent), model = testModel)

    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))

    val context =
      InvocationContext(
        agent = agent,
        session = session,
        runConfig = null,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )

    // Mock state to indicate transfer to sub-agent
    context.agentStates["test-agent"] = TypedData.MapValue(emptyMap())

    // Set up history so that getSubagentToResume returns the sub-agent.
    val event =
      Event(
        id = Uuid.random(),
        invocationId = context.invocationId,
        author = "test-agent",
        branch = context.branch,
        actions = EventActions(transferToAgent = "sub-agent"),
      )
    session.events.add(event)

    val events = agent.runAsync(context).toList()

    val subAgentEvents = events.filter { it.author == "sub-agent" }
    assertEquals(1, subAgentEvents.size)
    assertEquals("msg-from-sub", subAgentEvents[0].content?.parts?.get(0)?.text)

    val endStateEvents = events.filter { it.actions.endOfAgent }
    assertEquals(1, endStateEvents.size)
    assertEquals("test-agent", endStateEvents[0].author)
  }

  @Test
  fun runAsync_resumableContext_emitsEndOfAgent() = runTest {
    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Final response"))),
      )

    val agent = LlmAgent(name = "test-agent", model = testModel)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context =
      InvocationContext(
        agent = agent,
        session = session,
        runConfig = null,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )

    val events = agent.runAsync(context).toList()

    assertEquals(2, events.size)
    assertEquals("Final response", events[0].content?.parts?.get(0)?.text)
    assertTrue(events[1].actions.endOfAgent)
    assertEquals("test-agent", events[1].author)
  }

  /**
   * On a resumable invocation that paused on a long-running tool call, no event with
   * `actions.endOfAgent = true` is emitted -- the agent state stays live so a follow-up call can
   * resume it. The gate inspects the last two events of the current invocation/branch and skips
   * `endOfAgent` emission when any of them satisfies `shouldPauseInvocation`. Mirrors Python ADK
   * `agents/llm_agent.py:498-505`.
   */
  @Test
  fun runAsync_resumableContext_longRunningPause_suppressesEndOfAgent() = runTest {
    val callId = "long-running-call-id"
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel.createSequential("test-model", listOf(LlmResponse())),
        tools = listOf(DummyTool(name = "do_work", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val invocationId = "test-invocation"
    val context =
      InvocationContext(
        agent = agent,
        session = session,
        runConfig = null,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        invocationId = invocationId,
      )
    // Pre-populate the session with a user message and a long-running function-call event from a
    // prior turn -- this is what the runner would have written before re-entering `runAsyncImpl`.
    session.events.add(
      Event(
        id = Uuid.random(),
        invocationId = invocationId,
        author = "user",
        branch = context.branch,
        content = userMessage("start"),
      )
    )
    session.events.add(
      Event(
        id = Uuid.random(),
        invocationId = invocationId,
        author = "test-agent",
        branch = context.branch,
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "do_work", id = callId))),
          ),
        longRunningToolIds = setOf(callId),
      )
    )

    val events = agent.runAsync(context).toList()

    assertTrue(
      "endOfAgent must be suppressed when the invocation is paused on a long-running call",
      events.none { it.actions.endOfAgent },
    )
  }

  private fun createEvent(author: String, text: String): Event {
    return Event(
      id = Uuid.random(),
      invocationId = "test-invocation",
      author = author,
      content = Content(parts = listOf(Part(text = text))),
    )
  }
}
