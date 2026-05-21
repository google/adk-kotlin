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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.simplifyContent
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for long-running tools through the public Runner. The contract -- documented on
 * [BaseTool.isLongRunning] -- is:
 * - A long-running tool is a regular tool whose [BaseTool.isLongRunning] flag is `true`.
 * - The tool's return value becomes the function-response payload. The framework adds the call's id
 *   to the model event's `longRunningToolIds`.
 * - When the tool returns `Unit` the framework suppresses the function-response event entirely; the
 *   function-call event (which satisfies `Event.isFinalResponse` because `longRunningToolIds` is
 *   populated) becomes the last event of the turn and the agent loop terminates.
 * - The caller resumes by sending a follow-up `runAsync(newMessage = userFunctionResponse(...))`.
 *   The runner routes it to the agent that issued the call and re-invokes the model with the
 *   updated history. The original tool is not re-executed.
 *
 * Python ADK reference (`google/adk/flows/llm_flows/functions.py`): the framework suppresses the FR
 * on any falsy `function_response` (`None`, `{}`, `""`). This Kotlin port narrows that to `Unit`
 * only; empty maps and other falsy values still emit an FR event.
 */
class LongRunningToolIntegrationTest {

  // The four scenarios immediately below cover every combination of `is_resumable` x
  // long-running tool return value (falsy vs truthy dict). Behaviour was verified against the
  // Python ADK by direct source reading and manual exercise; the table summarises the observed
  // contract. `end_of_agent` is short for "the framework emits an event with
  // `actions.endOfAgent = true` for this agent."
  //
  // | # | resumable | tool return       | calls | events         | end_of_agent |
  // | - | --------- | ----------------- | ----- | -------------- | ------------ |
  // | 1 | off       | Unit/None         | 1     | [FC]           | n/a          |
  // | 2 | off       | {status: pending} | 2     | [FC, FR, text] | n/a          |
  // | 3 | on        | Unit/None         | 1     | [FC]           | suppressed   |
  // | 4 | on        | {status: pending} | 1     | [FC, FR]       | suppressed   |
  //
  // For non-resumable mode (1, 2) the framework never emits `end_of_agent` at all -- the marker
  // only exists in resumable mode. For resumable mode (3, 4) the marker is suppressed by the
  // pause gates in `LlmAgent.runAsyncImpl` so the agent state stays live for an eventual resume.

  /**
   * Non-resumable + falsy-returning long-running tool: returning `Unit` (Kotlin's "no response yet"
   * signal) suppresses the FR event. The FC event carries `longRunningToolIds`, so the agent loop
   * terminates after the FC without re-invoking the model. No `endOfAgent` (never emitted in
   * non-resumable mode).
   *
   * Note: in Python ADK this corresponds to returning `None` (or any falsy value); see
   * `functions.py:582` (`if not function_response: return None`). Kotlin narrows the trigger to the
   * `Unit` singleton specifically.
   *
   * Verified against Python ADK (manual verification: `is_resumable=False, returns=None` produces
   * `1 model call, [FC]` events with no `end_of_agent`).
   */
  @Test
  fun runAsync_longRunningToolReturnsUnit_suppressesFunctionResponseAndStops() = runTest {
    val callId = "lr_call_unit"
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") {
            modelInvocations++
            flowOf(modelFunctionCallResponse(TOOL_NAME_1, id = callId))
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                toolInvocations++
                Unit
              },
            )
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()

    val agentEvents = events.filter { it.author == AGENT_NAME }
    assertEquals(1, agentEvents.size, "only the function-call event should be emitted")
    assertEquals(setOf(callId), agentEvents.single().longRunningToolIds)
    assertTrue(agentEvents.single().functionResponses().isEmpty())
    assertEquals(1, modelInvocations)
    assertEquals(1, toolInvocations)
    assertTrue(
      events.none { it.actions.endOfAgent },
      "endOfAgent never emitted in non-resumable mode",
    )
  }

  /**
   * Non-resumable + dict-returning long-running tool: the dict becomes the function-response
   * payload; the model is then re-invoked once with the placeholder in history and acknowledges.
   * Across the turn, the model is invoked twice and the tool is invoked once. No `endOfAgent`
   * (never emitted in non-resumable mode).
   *
   * Verified against Python ADK (manual verification: `is_resumable=False, returns={status:
   * pending}` produces `2 model calls, [FC, FR, text]` events with no `end_of_agent`).
   */
  @Test
  fun runAsync_longRunningToolReturnsDict_propagatesPayloadAndAcknowledges() = runTest {
    val callId = "lr_call_dict"
    val payload = mapOf("status" to "pending")
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      singleCallThenAcknowledgeAgent(
        callId = callId,
        toolPayload = payload,
        onModelInvoke = { modelInvocations++ },
        onToolInvoke = { toolInvocations++ },
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start work"))
        .toList()

    val modelEvent = events.first { event -> event.functionCalls().any { it.id == callId } }
    assertEquals(setOf(callId), modelEvent.longRunningToolIds)
    val functionResponse = events.first { event ->
      event.functionResponses().any { it.id == callId }
    }
    assertEquals(payload, functionResponse.functionResponses().single().response)
    assertEquals("acknowledged", events.last().content?.parts?.singleOrNull()?.text)
    assertEquals(2, modelInvocations)
    assertEquals(1, toolInvocations)
    assertTrue(
      events.none { it.actions.endOfAgent },
      "endOfAgent never emitted in non-resumable mode",
    )
  }

  /**
   * Resumable-mode counterpart of
   * [runAsync_longRunningToolReturnsUnit_suppressesFunctionResponseAndStops]. Behaviour is
   * identical to the non-resumable case for the externally-observable event stream (1 model
   * invocation, FC-only, no FR) because both paths terminate via `isFinalResponse` on the FC event.
   * The internal difference: on a resumable invocation the `endOfAgent` marker would normally fire
   * after the loop, but the `LlmAgent.runAsyncImpl` per-event `shouldPause` check suppresses it so
   * the agent state stays "live" for resumption.
   *
   * Verified against Python ADK (see
   * `tests/unittests/flows/llm_flows/test_functions_long_running.py` counterpart and manual
   * verification: `is_resumable=True, returns=None` produces `1 model call, [FC]` events with no
   * `end_of_agent`).
   */
  @Test
  fun runAsync_resumable_longRunningToolReturnsUnit_suppressesFunctionResponseAndStops() = runTest {
    val callId = "lr_call_unit_resumable"
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") {
            modelInvocations++
            flowOf(modelFunctionCallResponse(TOOL_NAME_1, id = callId))
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                toolInvocations++
                Unit
              },
            )
          ),
      )
    val runner =
      InMemoryRunner(agent = agent, resumabilityConfig = ResumabilityConfig(isResumable = true))

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()

    val agentEvents = events.filter { it.author == AGENT_NAME }
    assertEquals(1, agentEvents.size, "only the function-call event should be emitted")
    assertEquals(setOf(callId), agentEvents.single().longRunningToolIds)
    assertTrue(agentEvents.single().functionResponses().isEmpty())
    assertEquals(1, modelInvocations)
    assertEquals(1, toolInvocations)
    assertTrue(
      events.none { it.actions.endOfAgent },
      "endOfAgent must be suppressed on a long-running pause in a resumable invocation",
    )
  }

  /**
   * Resumable-mode counterpart of
   * [runAsync_longRunningToolReturnsDict_propagatesPayloadAndAcknowledges]. Differs from the
   * non-resumable case: only **1 model invocation** happens (vs 2 in non-resumable mode). The
   * flow's `_run_one_step_async`-equivalent (`LlmAgentTurn.shouldPause`) short-circuits the second
   * step when it sees the long-running FC in `events[-2:]`, so the model is never re-invoked with
   * the pending payload in history. Externally-observable events are `[FC, FR]`; no `endOfAgent`
   * (suppressed for the same reason as scenario 3).
   *
   * Verified against Python ADK (manual verification: `is_resumable=True, returns={status:
   * pending}` produces `1 model call, [FC, FR]` events with no `end_of_agent`).
   */
  @Test
  fun runAsync_resumable_longRunningToolReturnsDict_emitsFunctionResponseAndPauses() = runTest {
    val callId = "lr_call_dict_resumable"
    val payload = mapOf("status" to "pending")
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") {
            modelInvocations++
            flowOf(modelFunctionCallResponse(TOOL_NAME_1, id = callId))
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                toolInvocations++
                payload
              },
            )
          ),
      )
    val runner =
      InMemoryRunner(agent = agent, resumabilityConfig = ResumabilityConfig(isResumable = true))

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()

    val agentEvents = events.filter { it.author == AGENT_NAME }
    val fcEvent = agentEvents.first { it.functionCalls().any { call -> call.id == callId } }
    assertEquals(setOf(callId), fcEvent.longRunningToolIds)
    val frEvent = agentEvents.first { it.functionResponses().any { resp -> resp.id == callId } }
    assertEquals(payload, frEvent.functionResponses().single().response)
    assertEquals(
      1,
      modelInvocations,
      "the flow's pause-check at step 2 prevents the second model invocation",
    )
    assertEquals(1, toolInvocations)
    assertTrue(
      events.none { it.actions.endOfAgent },
      "endOfAgent must be suppressed on a long-running pause in a resumable invocation",
    )
  }

  /**
   * A long-running tool returning a non-dict value (here a `String`) is wrapped in `{"result":
   * ...}` per the Gen-AI specs before being yielded as the function-response payload.
   */
  @Test
  fun runAsync_longRunningToolReturnsString_wrapsInResultMap() = runTest {
    val callId = "lr_call_str"
    var toolInvocations = 0
    val agent =
      singleCallThenAcknowledgeAgent(
        callId = callId,
        toolPayload = "pending",
        onModelInvoke = {},
        onToolInvoke = { toolInvocations++ },
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()

    val functionResponse = events.first { event ->
      event.functionResponses().any { it.id == callId }
    }
    assertEquals(
      mapOf(BaseTool.RESULT_KEY to "pending"),
      functionResponse.functionResponses().single().response,
    )
    assertEquals(1, toolInvocations)
  }

  /**
   * A long-running tool returning an empty map (or any other empty-ish value other than `Unit`) is
   * taken at face value: the framework yields a function-response event with that payload and
   * re-invokes the model. Pins the intentional divergence from Python ADK's truthiness-based
   * suppression (Python would suppress on `{}` too; we do not).
   */
  @Test
  fun runAsync_longRunningToolReturnsEmptyMap_emitsFunctionResponseAndContinues() = runTest {
    val callId = "lr_call_empty_map"
    var modelInvocations = 0
    val agent =
      singleCallThenAcknowledgeAgent(
        callId = callId,
        toolPayload = emptyMap<String, Any>(),
        onModelInvoke = { modelInvocations++ },
        onToolInvoke = {},
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("go"))
        .toList()

    val functionResponse = events.firstOrNull { event ->
      event.functionResponses().any { it.id == callId }
    }
    assertTrue(
      functionResponse != null,
      "an empty-map return must still emit a function-response event",
    )
    assertEquals(2, modelInvocations)
  }

  /**
   * Resume after a `Unit`-suppressed pause: a follow-up `runAsync` carrying a user
   * `FunctionResponse` with the real result causes the model to be re-invoked once with that
   * response in history. The original tool is NOT re-executed.
   *
   * Asserts the full simplified content history seen by the model on resume, so it is visually
   * obvious there are no stale placeholder responses or other artifacts in scope.
   */
  @Test
  fun runAsync_longRunningToolResumeAfterUnitPause_modelHistoryContainsOnlyRealResponse() =
    runTest {
      val callId = "lr_call_resume_unit"
      val realResult = mapOf("result" to 42)
      val capturedRequests = mutableListOf<LlmRequest>()
      var toolInvocations = 0
      val agent =
        unitPausingAgent(
          callId = callId,
          captureRequest = { capturedRequests += it },
          onToolInvoke = { toolInvocations++ },
        )
      val runner = InMemoryRunner(agent = agent)

      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()
      val resumeEvents =
        runner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage =
              userFunctionResponse(name = TOOL_NAME_1, id = callId, response = realResult),
          )
          .toList()

      assertEquals("done", resumeEvents.last().content?.parts?.singleOrNull()?.text)
      assertEquals(1, toolInvocations)
      assertEquals(2, capturedRequests.size)
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult)),
        ),
        capturedRequests.last().simplifiedContents(),
      )
    }

  /**
   * Realistic placeholder-then-resume lifecycle:
   * 1. Long-running tool returns a placeholder payload (`{"status": "working"}`).
   * 2. Framework emits an FC event AND an FR event carrying the placeholder; the model is then
   *    re-invoked with the placeholder in history and responds (turn 1 ends).
   * 3. Caller injects a `userFunctionResponse` with the real result.
   * 4. On the resume turn the model is invoked with the real `FunctionResponse` in history. The
   *    tool is NOT re-executed.
   *
   * Asserts the full simplified content history of every model invocation. Two notable framework
   * behaviours are pinned here:
   * - `HistoryRewriterProcessor.rearrangeEventsForLatestFunctionResponse` merges the user-injected
   *   real FR with the prior placeholder, so the resume turn's history holds a single FR for the
   *   call (the real result), not both.
   * - The same rearrange truncates events that follow the FC, so the model's prior "acknowledged"
   *   reply (emitted in turn 1 after seeing the placeholder) is NOT visible to the model on resume.
   */
  @Test
  fun runAsync_longRunningToolReturnsPlaceholderThenResumes_modelHistoryShowsMergedRealResponse() =
    runTest {
      val callId = "lr_call_resume_placeholder"
      val placeholder = mapOf("status" to "working")
      val realResult = mapOf("result" to "done", "items" to 7)
      val capturedRequests = mutableListOf<LlmRequest>()
      var toolInvocations = 0
      val agent =
        singleCallThenAcknowledgeAgent(
          callId = callId,
          toolPayload = placeholder,
          captureRequest = { capturedRequests += it },
          onToolInvoke = { toolInvocations++ },
        )
      val runner = InMemoryRunner(agent = agent)

      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userFunctionResponse(name = TOOL_NAME_1, id = callId, response = realResult),
        )
        .toList()

      assertEquals(3, capturedRequests.size)
      assertEquals(1, toolInvocations)

      // Turn-1 invocation-1: the model sees only the starting user message.
      assertEquals(listOf(Role.USER to "start"), capturedRequests[0].simplifiedContents())
      // Turn-1 invocation-2: the model sees the FC and the placeholder FR.
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = placeholder)),
        ),
        capturedRequests[1].simplifiedContents(),
      )
      // Resume invocation: the placeholder FR was merged-replaced with the real result, and the
      // model's prior "acknowledged" reply was dropped by
      // `rearrangeEventsForLatestFunctionResponse`.
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult)),
        ),
        capturedRequests[2].simplifiedContents(),
      )
    }

  /**
   * Scenario: the model issues a long-running call and a regular call in parallel. The regular
   * tool's FR is delivered immediately; the long-running one is paused with `Unit`. The model is
   * re-invoked once with the regular FR (and the long-running call still dangling) and
   * acknowledges. Later the caller injects the real FR for the long-running call and the model sees
   * both responses on resume.
   *
   * Verifies that parallel FCs in one turn behave sensibly when one is long-running: the
   * non-long-running response flows through normally, the long-running one pauses via `Unit`, and
   * resume picks the long-running call back up without re-executing either tool.
   */
  @Test
  fun runAsync_longRunningPlusParallelRegularTool_resumeDeliversBothResponses() = runTest {
    val lrCallId = "lr_call_parallel"
    val regCallId = "reg_call_parallel"
    val regResponse = mapOf("ok" to true)
    val realResult = mapOf("result" to 99)
    val capturedRequests = mutableListOf<LlmRequest>()
    var lrInvocations = 0
    var regInvocations = 0
    var modelInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") { request ->
            capturedRequests += request
            modelInvocations++
            flowOf(
              if (modelInvocations == 1)
                modelParallelFunctionCallsResponse(
                  FunctionCall(name = TOOL_NAME_1, id = lrCallId),
                  FunctionCall(name = TOOL_NAME_2, id = regCallId),
                )
              else LlmResponse(content = modelMessage("acknowledged"))
            )
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                lrInvocations++
                Unit
              },
            ),
            DummyTool(
              name = TOOL_NAME_2,
              onRun = { _, _ ->
                regInvocations++
                regResponse
              },
            ),
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    runner
      .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
      .toList()
    runner
      .runAsync(
        userId = USER_ID,
        sessionId = SESSION_ID,
        newMessage = userFunctionResponse(name = TOOL_NAME_1, id = lrCallId, response = realResult),
      )
      .toList()

    // The model is invoked three times total: once with the user prompt, once with the regular
    // tool's response (long-running call still dangling), and once on resume.
    assertEquals(3, capturedRequests.size)
    // Each tool is invoked exactly once.
    assertEquals(1, lrInvocations)
    assertEquals(1, regInvocations)

    // Resume-turn history: both function-responses are visible to the model -- the regular one
    // from turn 1 and the user-injected long-running one. The intermediate "acknowledged" reply
    // is dropped by `rearrangeEventsForLatestFunctionResponse` (which truncates after the FC
    // event). The FRs appear in chronological order: the regular tool's response was emitted in
    // turn 1, the long-running response is merged in at resume time.
    assertEquals(
      listOf(
        Role.USER to "start",
        Role.MODEL to
          listOf(
            Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
            Part(functionCall = FunctionCall(name = TOOL_NAME_2)),
          ),
        Role.USER to
          listOf(
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_2, response = regResponse)),
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult)),
          ),
      ),
      capturedRequests.last().simplifiedContents(),
    )
  }

  /**
   * Scenario: long-running tool pauses with `Unit`. Before the caller injects the real FR they send
   * an unrelated user message; the model handles it as a normal turn. Eventually the caller injects
   * the real FR for the long-running call.
   *
   * Asserts on the simplified history at every model invocation so the history rewrite is visible:
   * - Turn 1 invocation 1: only the starting user message is in scope.
   * - Turn 2 invocation 1 (interleaved): the dangling long-running FC and the new user message are
   *   both in scope. The framework does NOT strip the dangling FC.
   * - Resume: `rearrangeEventsForLatestFunctionResponse` truncates everything between the FC and
   *   the user-injected FR, so the model only sees the original "start work" prompt, the FC, and
   *   the real FR. The intermediate "also do something else" + "sure, doing it" exchange is NOT
   *   visible to the model resuming the long-running call.
   */
  @Test
  fun runAsync_longRunningToolThenInterleavedUserMessage_resumeRoutesCorrectly() = runTest {
    val callId = "lr_call_interleaved"
    val realResult = mapOf("result" to "finally")
    val capturedRequests = mutableListOf<LlmRequest>()
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") { request ->
            capturedRequests += request
            modelInvocations++
            flowOf(
              when (modelInvocations) {
                1 -> modelFunctionCallResponse(TOOL_NAME_1, id = callId)
                2 -> LlmResponse(content = modelMessage("sure, doing it"))
                else -> LlmResponse(content = modelMessage("got the real result"))
              }
            )
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                toolInvocations++
                Unit
              },
            )
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    // Turn 1: kick off the long-running call (Unit-suppressed, loop terminates).
    runner
      .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start work"))
      .toList()
    // Turn 2: unrelated user message handled as a normal turn.
    runner
      .runAsync(
        userId = USER_ID,
        sessionId = SESSION_ID,
        newMessage = userMessage("also do something else"),
      )
      .toList()
    // Turn 3: caller injects the real FR for the long-running call.
    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userFunctionResponse(name = TOOL_NAME_1, id = callId, response = realResult),
        )
        .toList()

    assertEquals("got the real result", resumeEvents.last().content?.parts?.singleOrNull()?.text)
    assertEquals(1, toolInvocations)
    assertEquals(3, capturedRequests.size)

    // Turn 1: model is invoked with only the starting user message.
    assertEquals(listOf(Role.USER to "start work"), capturedRequests[0].simplifiedContents())

    // Turn 2: model is invoked with the dangling long-running FC AND the interleaved user
    // message. The framework does not strip dangling long-running FCs from history.
    assertEquals(
      listOf(
        Role.USER to "start work",
        Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
        Role.USER to "also do something else",
      ),
      capturedRequests[1].simplifiedContents(),
    )

    // Resume: the rearrange-for-latest-FR truncates everything between the FC and the FR, so the
    // model only sees the original "start work" prompt, the FC, and the real FR. The intermediate
    // "also do something else" + "sure, doing it" exchange is NOT visible to the model resuming
    // the long-running call.
    assertEquals(
      listOf(
        Role.USER to "start work",
        Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
        Role.USER to
          Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult)),
      ),
      capturedRequests[2].simplifiedContents(),
    )
  }

  // -- Fixtures ----------------------------------------------------------------------------------

  /**
   * Agent whose model emits a long-running [TOOL_NAME_1] call on its first invocation and the text
   * `"acknowledged"` on every subsequent invocation. The tool is a long-running [DummyTool] that
   * returns [toolPayload] on every call. Optionally records each [LlmRequest] the model receives.
   */
  private fun singleCallThenAcknowledgeAgent(
    callId: String,
    toolPayload: Any,
    onModelInvoke: () -> Unit = {},
    captureRequest: (LlmRequest) -> Unit = {},
    onToolInvoke: () -> Unit = {},
  ): LlmAgent {
    var invocations = 0
    return LlmAgent(
      name = AGENT_NAME,
      model =
        DummyModel("model") { request ->
          captureRequest(request)
          invocations++
          onModelInvoke()
          flowOf(
            if (invocations == 1) modelFunctionCallResponse(TOOL_NAME_1, id = callId)
            else LlmResponse(content = modelMessage("acknowledged"))
          )
        },
      tools =
        listOf(
          DummyTool(
            name = TOOL_NAME_1,
            isLongRunning = true,
            onRun = { _, _ ->
              onToolInvoke()
              toolPayload
            },
          )
        ),
    )
  }

  /**
   * Agent whose model emits a long-running call on its first invocation and the text `"done"` on
   * every subsequent invocation. The tool is a long-running [DummyTool] that returns `Unit` on
   * every call (suppressing the function-response event so the pause turn ends after the
   * function-call). Optionally records each [LlmRequest] the model receives.
   */
  private fun unitPausingAgent(
    callId: String,
    captureRequest: (LlmRequest) -> Unit = {},
    onToolInvoke: () -> Unit = {},
  ): LlmAgent {
    var invocations = 0
    return LlmAgent(
      name = AGENT_NAME,
      model =
        DummyModel("model") { request ->
          captureRequest(request)
          invocations++
          flowOf(
            if (invocations == 1) modelFunctionCallResponse(TOOL_NAME_1, id = callId)
            else LlmResponse(content = modelMessage("done"))
          )
        },
      tools =
        listOf(
          DummyTool(
            name = TOOL_NAME_1,
            isLongRunning = true,
            onRun = { _, _ ->
              onToolInvoke()
              Unit
            },
          )
        ),
    )
  }

  /**
   * Reduces this request's content history to a `(role, simplifiedContent)` list using
   * [simplifyContent], for legible whole-history assertions. Function-call/response ids are
   * stripped by [simplifyContent].
   */
  private fun LlmRequest.simplifiedContents(): List<Pair<String, Any>> = contents.map { content ->
    (content.role ?: "") to simplifyContent(content)
  }

  private companion object {
    const val USER_ID = "u"
    const val SESSION_ID = "s"
    const val AGENT_NAME = "agent"
    const val TOOL_NAME_1 = "tool_1"
    const val TOOL_NAME_2 = "tool_2"
  }
}
