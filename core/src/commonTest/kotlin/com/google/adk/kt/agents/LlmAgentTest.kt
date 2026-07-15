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

package com.google.adk.kt.agents

import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
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
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  fun init_defaultMaxSteps_isNull() {
    val agent = LlmAgent(name = "test_agent", model = testModel)

    assertNull(agent.maxSteps)
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
  fun runAsync_withMaxSteps_stopsAfterMaxSteps() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(
          Part(text = "LLM response with function call"),
          Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"), id = "call_1")),
        ),
      )

    // The model always asks to call the tool again; only the maxSteps cap stops the loop. The third
    // response (a final text response) must never be requested.
    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = modelMessage("This should never be returned.")),
        ),
      )

    val testResponse = mapOf("response" to "response for my_function")
    val testTool = DummyTool("my_function", onRun = { _, _ -> testResponse })

    val agent =
      LlmAgent(name = "test-agent", model = testModel, tools = listOf(testTool), maxSteps = 2)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    val functionCallPair =
      "test-agent" to
        listOf(
          Part(text = "LLM response with function call"),
          Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"))),
        )
    val functionResponsePair =
      "test-agent" to
        Part(functionResponse = FunctionResponse("my_function", response = testResponse))

    // Exactly two steps run (each: model call + tool response); execution stops at the cap before
    // the unreachable final response.
    assertEquals(
      listOf(functionCallPair, functionResponsePair, functionCallPair, functionResponsePair),
      simplifyEvents(events),
    )
  }

  @Test
  fun runAsync_withMaxStepsNotReached_runsToFinalResponse() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(
          Part(text = "LLM response with function call"),
          Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"), id = "call_1")),
        ),
      )
    val finalText = "LLM response after function response"

    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = modelMessage(finalText)),
        ),
      )

    val testResponse = mapOf("response" to "response for my_function")
    val testTool = DummyTool("my_function", onRun = { _, _ -> testResponse })

    // A generous cap that is never hit: the agent stops on its own final response.
    val agent =
      LlmAgent(name = "test-agent", model = testModel, tools = listOf(testTool), maxSteps = 5)
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(
      listOf(
        "test-agent" to
          listOf(
            Part(text = "LLM response with function call"),
            Part(functionCall = FunctionCall("my_function", mapOf("arg1" to "value1"))),
          ),
        "test-agent" to
          Part(functionResponse = FunctionResponse("my_function", response = testResponse)),
        "test-agent" to finalText,
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
    // Pre-populate the session with a user message, a long-running function-call event, and the
    // tool's empty placeholder function-response -- this is what the runner would have written
    // before re-entering `runAsyncImpl` on resume. Including the FR avoids the "resumption from
    // unresolved function-call" branch in `LlmAgentTurn.execute` so the test exercises the
    // pause-gate-from-history path directly.
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
    session.events.add(
      Event(
        id = Uuid.random(),
        invocationId = invocationId,
        author = "test-agent",
        branch = context.branch,
        content =
          Content(
            role = Role.USER,
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(name = "do_work", id = callId, response = emptyMap())
                )
              ),
          ),
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

  @Test
  fun runAsync_withOutputSchema_savesValidatedMapToState() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )
    val model =
      DummyModel.createSequential(
        "gemini-2.0-flash",
        listOf(LlmResponse(content = modelMessage("""{"answer": "42"}"""))),
      )
    val agent =
      LlmAgent(name = "test-agent", model = model, outputSchema = schema, outputKey = "result")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    // The validated JSON is stored as a parsed map (mirrors Python/Java which save the parsed
    // structure rather than the raw string).
    assertEquals(mapOf("answer" to "42"), events[0].actions.stateDelta["result"])
  }

  @Test
  fun runAsync_withOutputSchema_invalidJson_savesRawText() = runBlocking {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))
    val model =
      DummyModel.createSequential(
        "gemini-2.0-flash",
        listOf(LlmResponse(content = modelMessage("not json"))),
      )
    val agent =
      LlmAgent(name = "test-agent", model = model, outputSchema = schema, outputKey = "result")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    // When the output cannot be parsed/validated, the raw text is stored instead.
    assertEquals("not json", events[0].actions.stateDelta["result"])
  }

  @Test
  fun runAsync_withOutputSchema_validJsonNotMatchingSchema_savesRawText() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )
    // Valid JSON, but it does not satisfy the schema (it lacks the required "answer" field and
    // carries an unexpected one). This exercises the schema-mismatch branch of
    // maybeSaveOutputToState, which falls back to storing the raw text (distinct from the
    // parse-failure branch above).
    val rawOutput = """{"unexpected": "value"}"""
    val model =
      DummyModel.createSequential(
        "gemini-2.0-flash",
        listOf(LlmResponse(content = modelMessage(rawOutput))),
      )
    val agent =
      LlmAgent(name = "test-agent", model = model, outputSchema = schema, outputKey = "result")
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    assertEquals(1, events.size)
    assertEquals(rawOutput, events[0].actions.stateDelta["result"])
  }

  @Test
  fun runAsync_withOutputSchemaAndTools_gemini2_usesSetModelResponseWorkaround() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("answer" to Schema(type = Type.STRING)),
        required = listOf("answer"),
      )
    // The model returns its final answer by calling the set_model_response tool (the workaround for
    // Gemini 2.x, which cannot use a response schema together with tools).
    val setResponseCall =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(
              functionCall =
                FunctionCall("set_model_response", mapOf("answer" to "42"), id = "call_1")
            )
          ),
      )
    val model =
      DummyModel.createSequential(
        "gemini-2.0-flash",
        listOf(LlmResponse(content = setResponseCall)),
      )
    val agent =
      LlmAgent(
        name = "test-agent",
        model = model,
        tools = listOf(DummyTool("my_tool")),
        outputSchema = schema,
        outputKey = "result",
      )
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // Events: the set_model_response function call, its function response, and the synthetic final
    // model response carrying the structured JSON.
    assertEquals(3, events.size)
    val finalEvent = events.last()
    assertTrue(finalEvent.isFinalResponse)
    assertEquals("""{"answer":"42"}""", finalEvent.content?.parts?.firstOrNull()?.text)
    assertEquals(mapOf("answer" to "42"), finalEvent.actions.stateDelta["result"])
  }

  @Test
  fun runAsync_withOutputSchemaAndTools_gemini2_invalidArgs_failsInvocation() =
    runBlocking<Unit> {
      val schema =
        Schema(
          type = Type.OBJECT,
          properties = mapOf("answer" to Schema(type = Type.STRING)),
          required = listOf("answer"),
        )
      // The model calls set_model_response with args that violate the schema (missing the required
      // "answer"). Unlike the best-effort direct-schema path, the workaround validates strictly, so
      // the tool throws and—absent any onToolError/onModelError recovery—the failure propagates out
      // of
      // the invocation rather than being saved as raw text.
      val setResponseCall =
        Content(
          role = Role.MODEL,
          parts =
            listOf(
              Part(
                functionCall =
                  FunctionCall("set_model_response", mapOf("wrong" to "value"), id = "call_1")
              )
            ),
        )
      val model =
        DummyModel.createSequential(
          "gemini-2.0-flash",
          listOf(LlmResponse(content = setResponseCall)),
        )
      val agent =
        LlmAgent(
          name = "test-agent",
          model = model,
          tools = listOf(DummyTool("my_tool")),
          outputSchema = schema,
          outputKey = "result",
        )
      val sessionService = InMemorySessionService()
      val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
      val context = InvocationContext(agent = agent, session = session, runConfig = null)

      assertFailsWith<IllegalArgumentException> { agent.runAsync(context).toList() }
    }

  /**
   * Tool sets `toolContext.actions.endOfAgent = true` mid-invocation: the per-step loop in
   * [LlmAgent.executeTurns] must honor it and stop after the current step, mirroring Java ADK's
   * `BaseLlmFlow.run` loop that breaks on `getLast(eventList).actions().endInvocation()`. This
   * exercises the Java-tool path described in b/522621203 (a Java tool calling
   * `toolContext.actions().setEndInvocation(true)`, which sets `EventActions.endOfAgent` in the
   * common data model).
   */
  @Test
  fun runAsync_toolSetsEndOfAgent_stopsLoopAfterStep() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(
          Part(text = "calling tool"),
          Part(functionCall = FunctionCall("stop_tool", mapOf("arg1" to "v"), id = "call_1")),
        ),
      )

    // Second LLM response must never be requested: the tool ends the invocation after step 1.
    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = modelMessage("This should never be returned.")),
        ),
      )

    val stopTool =
      DummyTool(
        name = "stop_tool",
        onRun = { ctx, _ ->
          ctx.actions.endOfAgent = true
          mapOf("status" to "stopped")
        },
      )

    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(stopTool))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // Exactly one step ran: the model call + the function response. No second model call.
    assertEquals(2, events.size)
    val responsePart = events[1].content?.parts?.firstOrNull()
    assertEquals("stop_tool", responsePart?.functionResponse?.name)
    assertEquals(mapOf("status" to "stopped"), responsePart?.functionResponse?.response)
    assertTrue(
      "loop must honor tool-set endOfAgent on the last event of the step",
      events.last().actions.endOfAgent,
    )
  }

  /**
   * Tool calls the new `toolContext.endInvocation()` helper: same outcome as setting
   * `actions.endOfAgent = true`, via the context-flag path (parity with Python ADK's
   * `tool_context._invocation_context.end_invocation = True`).
   */
  @Test
  fun runAsync_toolCallsEndInvocation_stopsLoopAfterStep() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(Part(functionCall = FunctionCall("stop_tool", emptyMap(), id = "call_1"))),
      )

    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = modelMessage("Unreachable.")),
        ),
      )

    val stopTool =
      DummyTool(
        name = "stop_tool",
        onRun = { ctx, _ ->
          ctx.endInvocation()
          mapOf("status" to "stopped")
        },
      )

    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(stopTool))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // Exactly one step ran (no second model call); behavioral evidence that the loop honored
    // the context-level end-of-invocation flag. (The flag itself lives on the per-agent context
    // created by `BaseAgent.runAsync(parentContext).forAgent(this)`, which is a data-class copy
    // of the test's outer `context`, so asserting it directly on `context` is not meaningful.)
    assertEquals(2, events.size)
  }

  /**
   * A model/agent callback ends the invocation via the new `CallbackContext.endInvocation()`
   * helper. Without this helper, callbacks defined outside the `com.google.adk.kt` module cannot
   * reach `CallbackContext.invocationContext` (it is `internal`) and therefore cannot terminate the
   * invocation -- the gap called out in b/522621203.
   */
  @Test
  fun runAsync_beforeModelCallback_endsInvocation_stopsLoop() = runTest {
    // Model returns a (non-final) function call, so without endInvocation() the loop would run a
    // second step. The before-model callback ends the invocation, so the loop stops after step 1.
    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(
            content =
              Content(
                "model",
                listOf(Part(functionCall = FunctionCall("noop_tool", emptyMap(), id = "call_1"))),
              )
          ),
          LlmResponse(content = modelMessage("This should never be returned.")),
        ),
      )
    val noopTool = DummyTool(name = "noop_tool", onRun = { _, _ -> mapOf("ok" to true) })

    val callback = BeforeModelCallback { ctx, request ->
      ctx.endInvocation()
      CallbackChoice.Continue(request)
    }

    val agent =
      LlmAgent(
        name = "test-agent",
        model = testModel,
        tools = listOf(noopTool),
        beforeModelCallbacks = listOf(callback),
      )
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // Exactly one step ran (model call + function response); no second model call.
    assertEquals(2, events.size)
    assertFalse(
      events.any {
        it.content?.parts?.any { p -> p.text == "This should never be returned." } == true
      }
    )
  }

  /**
   * AfterToolCallback receives a [ToolContext] (not a [CallbackContext]); it must be able to end
   * the invocation via [ToolContext.endInvocation], symmetric with the BeforeModelCallback path.
   */
  @Test
  fun runAsync_afterToolCallback_endsInvocation_stopsLoop() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(Part(functionCall = FunctionCall("my_function", emptyMap(), id = "call_1"))),
      )

    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(
          LlmResponse(content = contentWithFunctionCall),
          LlmResponse(content = modelMessage("Unreachable.")),
        ),
      )

    val testTool = DummyTool("my_function", onRun = { _, _ -> mapOf("ok" to true) })
    val callback = AfterToolCallback { ctx, _, _, result ->
      ctx.endInvocation()
      result
    }

    val agent =
      LlmAgent(
        name = "test-agent",
        model = testModel,
        tools = listOf(testTool),
        afterToolCallbacks = listOf(callback),
      )
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context = InvocationContext(agent = agent, session = session, runConfig = null)

    val events = agent.runAsync(context).toList()

    // First step ran (model call + function response); the loop did not continue to a second
    // model call -- behavioral evidence that the after-tool callback's `endInvocation()`
    // stopped the loop.
    assertEquals(2, events.size)
  }

  /**
   * In a resumable invocation, a tool that sets `actions.endOfAgent = true` produces a
   * function-response event whose `endOfAgent` flag is persisted to session history. On a
   * subsequent resume, [InvocationContext.populateInvocationAgentStates] walks the history and
   * marks this agent as ended (`endOfAgents[author] = true`), which causes
   * `AbstractRunner.runAsync` (line 122) to short-circuit a new invocation that resolves to the
   * same agent.
   *
   * This is intentional but diverges from Python ADK's context-flag path: Python's
   * `invocation_context.end_invocation = True` only stops the in-progress invocation and does NOT
   * touch any event's `end_of_agent` field, so it leaves no resume-time footprint. Kotlin tools
   * that want the Python-compatible "stop just this invocation" behavior should use
   * [ToolContext.endInvocation] (which sets the context flag) instead of `actions.endOfAgent =
   * true`. This test pins both halves of that contract so a future change to either side is caught.
   */
  @Test
  fun runAsync_resumableContext_toolSetsEndOfAgent_marksAgentEndedOnResume() = runTest {
    val contentWithFunctionCall =
      Content(
        "model",
        listOf(Part(functionCall = FunctionCall("stop_tool", emptyMap(), id = "call_1"))),
      )
    val testModel =
      DummyModel.createSequential(
        "test-model",
        listOf(LlmResponse(content = contentWithFunctionCall)),
      )
    val stopTool =
      DummyTool(
        name = "stop_tool",
        onRun = { ctx, _ ->
          ctx.actions.endOfAgent = true
          mapOf("status" to "stopped")
        },
      )
    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(stopTool))
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    val context =
      InvocationContext(
        agent = agent,
        session = session,
        runConfig = null,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        sessionService = sessionService,
        invocationId = "inv-1",
      )

    // First invocation: tool sets endOfAgent on the function-response event. Persist each event
    // so the session reflects what a real runner would have written.
    val events = mutableListOf<Event>()
    agent.runAsync(context).collect { event ->
      val unused = sessionService.appendEvent(context.session, event)
      events.add(event)
    }

    // The function-response event carries endOfAgent=true (the tool's mutation propagated to the
    // event via `buildResponseEvent` which assigns `event.actions = toolContext.actions`).
    val toolResponseEvent = events.firstOrNull {
      it.actions.endOfAgent && it.functionResponses().isNotEmpty()
    }
    assertNotNull(
      "expected at least one persisted function-response event with endOfAgent=true",
      toolResponseEvent,
    )

    // Now simulate a fresh resume: a new InvocationContext over the same session.
    val resumeContext =
      InvocationContext(
        agent = agent,
        session = session,
        runConfig = null,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
        sessionService = sessionService,
        invocationId = "inv-1",
      )
    resumeContext.populateInvocationAgentStates()

    // The history's endOfAgent flag from the prior tool call marks this agent as ended -- the
    // signal that `AbstractRunner.runAsync:122` reads to short-circuit a new invocation for this
    // agent. This is the divergence from Python's context-flag path (which leaves no footprint).
    assertEquals(true, resumeContext.endOfAgents["test-agent"])
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
