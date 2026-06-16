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
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAgentTest {

  private val testModel = DummyModel("test-model")

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

    assertEquals(
      listOf(
        // First event is the model response with the function call.
        "test-agent" to
          listOf(
            Part(text = "LLM response with function call"),
            Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"))),
          ),
        // Second event is the function response.
        "test-agent" to
          Part(functionResponse = FunctionResponse("my_function", response = testResponse)),
        // Third event is the final model response.
        "test-agent" to secondText,
      ),
      simplifyEvents(events),
    )
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

    assertEquals(
      listOf(
        // First event is the model response with the function call.
        "test-agent" to
          listOf(
            Part(text = "LLM response with function call"),
            Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"))),
          ),
        // Second event is the function response.
        "test-agent" to
          Part(functionResponse = FunctionResponse("my_function", response = testResponse)),
        // Third event is the final model response.
        "test-agent" to secondText,
      ),
      simplifyEvents(events),
    )
    // The long-running tool's call id is carried on the first event's longRunningToolIds.
    assertEquals(setOf("call_1"), events[0].longRunningToolIds)
    assertTrue(events[0].longRunningToolIds.contains(events[0].functionCalls().firstOrNull()?.id))
  }

  @Test
  fun runAsync_modelThrowsException_processorRecovers() = runTest {
    val failingModel =
      DummyModel("failing-model") { flow { throw RuntimeException("Model failed") } }

    val callback = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Fallback response")))
    }

    val agent =
      LlmAgent(name = "test-agent", model = failingModel, onModelErrorCallbacks = listOf(callback))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(listOf("test-agent" to "Fallback response"), simplifyEvents(events))
  }

  @Test(expected = RuntimeException::class)
  fun runAsync_modelThrowsException_noProcessor_throwsException() = runTest {
    val failingModel =
      DummyModel("failing-model") { flow { throw RuntimeException("Model failed") } }

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
      DummyModel("custom-model") { request ->
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

    assertEquals(listOf("test-agent" to "Final response"), simplifyEvents(events))
    // The end-of-agent marker has no content (so it doesn't appear in simplifyEvents above)
    // but must still be emitted on a resumable invocation.
    assertEquals(2, events.size)
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

  @Test
  fun runAsync_withOutputKey_savesFinalResponseToStateDelta() = runTest {
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Saved output"))),
      )
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertTrue(events[0].isFinalResponse)
    assertEquals("Saved output", events[0].actions.stateDelta["myOutput"])
  }

  @Test
  fun runAsync_withOutputKey_concatenatesMultipleTextParts() = runTest {
    val multiPartContent =
      Content(role = Role.MODEL, parts = listOf(Part(text = "Part 1."), Part(text = " Part 2.")))
    val model =
      DummyModel.createSequential("test-model", listOf(LlmResponse(content = multiPartContent)))
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myMultiPartOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertEquals("Part 1. Part 2.", events[0].actions.stateDelta["myMultiPartOutput"])
  }

  @Test
  fun runAsync_withOutputKey_ignoresThoughtParts() = runTest {
    val mixedContent =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(text = "Saved output"), Part(text = "Ignored thought", thought = true)),
      )
    val model =
      DummyModel.createSequential("test-model", listOf(LlmResponse(content = mixedContent)))
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertEquals("Saved output", events[0].actions.stateDelta["myOutput"])
  }

  @Test
  fun runAsync_withoutOutputKey_leavesStateDeltaEmpty() = runTest {
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Some output"))),
      )
    val agent = LlmAgent(name = "test-agent", model = model) // no outputKey
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertTrue(events[0].actions.stateDelta.isEmpty())
  }

  @Test
  fun runAsync_withOutputKey_doesNotOverwriteStateWhenOnlyThoughtPartsPresent() = runTest {
    val thoughtOnlyContent =
      Content(role = Role.MODEL, parts = listOf(Part(text = "thinking out loud", thought = true)))
    val model =
      DummyModel.createSequential("test-model", listOf(LlmResponse(content = thoughtOnlyContent)))
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertTrue(
      "stateDelta must stay empty when the only text parts are thoughts",
      events[0].actions.stateDelta.isEmpty(),
    )
  }

  @Test
  fun runAsync_withEmptyOutputKey_isTreatedAsUnset() = runTest {
    // Mirrors Python's `if not self.output_key: return` falsy check: an empty string must not
    // become a state-delta key.
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Some output"))),
      )
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertTrue(
      "stateDelta must stay empty when outputKey is the empty string",
      events[0].actions.stateDelta.isEmpty(),
    )
  }

  @Test
  fun runAsync_withOutputKey_writesValueToSessionStateOnAppendEvent() = runTest {
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Saved output"))),
      )
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    // Mirror what the runner does after each emitted event: persist via SessionService.
    for (event in agent.runAsync(context).toList()) {
      val unused = sessionService.appendEvent(session, event)
    }

    assertEquals("Saved output", session.state["myOutput"])
  }

  @Test
  fun runAsync_withTempPrefixOutputKey_writesDeltaButIsNotPersisted() = runTest {
    // The LlmAgent contract is to write to `event.actions.stateDelta` regardless of key prefix;
    // it is the `State.applyDelta` layer that drops `temp:`-prefixed keys before persisting them
    // into the session state. This test pins down both halves of that contract so a future change
    // to either side is caught.
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = modelMessage("Saved output"))),
      )
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "temp:tempKey")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()
    for (event in events) {
      val unused = sessionService.appendEvent(session, event)
    }

    assertEquals(1, events.size)
    // The agent must still publish the value via the event's state delta.
    assertEquals("Saved output", events[0].actions.stateDelta["temp:tempKey"])
    // But State.applyDelta strips temp:-prefixed keys, so the session state must not contain it.
    assertFalse(
      "temp: outputKey must not be persisted to session.state",
      session.state.containsKey("temp:tempKey"),
    )
  }

  @Test
  fun runAsync_withPartialEvent_doesNotWriteStateDelta() = runTest {
    // Sequential model calls: the first returns a partial event; the second returns the final
    // response. Each LlmAgentTurn iteration constructs a fresh EventActions, so the partial
    // event's stateDelta is independent of the final event's and can be asserted on its own.
    //
    // (Aside: within a single `model.generateContent` flow, LlmAgentTurn reuses one base event
    // and emits shallow copies that share an EventActions instance; that path is harmless in
    // practice because SessionService.appendEvent skips partial events, but it is unsuited to
    // asserting per-event stateDelta state here.)
    val model =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = modelMessage("Partial chunk"), partial = true),
          LlmResponse(content = modelMessage("Final response")),
        ),
      )
    val agent = LlmAgent(name = "test-agent", model = model, outputKey = "myOutput")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(2, events.size)
    // Partial chunk: not a final response, no state delta write.
    assertTrue("first event should be partial", events[0].partial)
    assertFalse("first event must not be a final response", events[0].isFinalResponse)
    assertTrue(
      "partial events must not write to stateDelta",
      events[0].actions.stateDelta.isEmpty(),
    )
    // Final response from a separate turn: stateDelta is populated.
    assertFalse("second event should not be partial", events[1].partial)
    assertTrue("second event must be a final response", events[1].isFinalResponse)
    assertEquals("Final response", events[1].actions.stateDelta["myOutput"])
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
