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

package com.google.adk.kt.runners

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.processors.HistoryRewriterProcessor
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.summarizer.EventSummarizer
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.eventWithFunctionCall
import com.google.adk.kt.testing.eventWithFunctionResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.simplifyContent
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end tests for long-running tools through the public Runner. The contract -- documented on
 * [BaseTool.isLongRunning] -- is:
 * - A long-running tool is a regular tool whose [BaseTool.isLongRunning] flag is `true`.
 * - The tool's return value becomes the function-response payload. The framework adds the call's id
 *   to the model event's `longRunningToolIds`. Returning `Unit` means "no response yet": the FR
 *   event is suppressed so the FC event (the turn's final response) ends the turn. A non-`Unit`
 *   return -- including an empty Map -- is emitted as the FR payload. (The `Unit` suppression
 *   aligns with Python's intent, where a long-running tool that yields no response suppresses the
 *   FR; Java instead always emits an empty `{}`.)
 * - The caller resumes by sending a follow-up `runAsync(newMessage = userFunctionResponse(...))`.
 *   The runner routes it to the agent that issued the call and re-invokes the model with the
 *   updated history. The original tool is not re-executed.
 */
class LongRunningToolIntegrationTest {

  // The scenarios immediately below cover every combination of `is_resumable` x long-running tool
  // return value. `Unit` ("no response yet") suppresses the FR event, so the turn ends on the FC
  // event alone; a non-`Unit` return -- an empty Map or a dict -- is emitted as the FR payload.
  // (The `Unit` suppression aligns with Python's intent; Java always emits an empty `{}`.)
  // `end_of_agent` is short for "the framework emits an event with `actions.endOfAgent = true`."
  //
  // | # | resumable | tool return       | calls | events         | end_of_agent |
  // | - | --------- | ----------------- | ----- | -------------- | ------------ |
  // | 1 | off       | Unit              | 1     | [FC]           | n/a          |
  // | 2 | off       | {} (empty map)    | 2     | [FC, FR, text] | n/a          |
  // | 3 | off       | {status: pending} | 2     | [FC, FR, text] | n/a          |
  // | 4 | on        | Unit              | 1     | [FC]           | suppressed   |
  // | 5 | on        | {status: pending} | 1     | [FC, FR]       | suppressed   |
  //
  // A `Unit` return (1, 4) suppresses the FR: the FC event carries `longRunningToolIds` and is
  // therefore `isFinalResponse`, so the turn ends without re-invoking the model -- this is what
  // stops a HITL tool (e.g. request_input) from looping in non-resumable mode. For non-resumable
  // mode (1-3) the framework never emits `end_of_agent`. For resumable mode (4, 5) the marker is
  // suppressed by the pause gates in `LlmAgent.runAsyncImpl` so the agent state stays live for an
  // eventual resume.

  /**
   * Non-resumable + `Unit`-returning long-running tool: `Unit` means "no response yet", so the
   * framework suppresses the function-response event. The function-call event carries
   * `longRunningToolIds` and is therefore the turn's final response, so the model is NOT re-invoked
   * -- the turn ends after `[FC]`. Across the turn the model is invoked once and the tool once.
   * This is the behaviour that stops a HITL tool (e.g. request_input) from looping. No `endOfAgent`
   * (never emitted in non-resumable mode).
   */
  @Test
  fun runAsync_longRunningToolReturnsUnit_suppressesFunctionResponseAndEndsTurn() = runTest {
    val callId = "lr_call_unit"
    var modelInvocations = 0
    var toolInvocations = 0
    val agent =
      singleCallThenAcknowledgeAgent(
        callId = callId,
        toolPayload = Unit,
        onModelInvoke = { modelInvocations++ },
        onToolInvoke = { toolInvocations++ },
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
        .toList()

    val modelEvent = events.first { event -> event.functionCalls().any { it.id == callId } }
    assertEquals(setOf(callId), modelEvent.longRunningToolIds)
    // The FR event is suppressed: no function-response for the call is emitted.
    assertTrue(
      events.none { event -> event.functionResponses().any { it.id == callId } },
      "a Unit-returning long-running tool must not emit a function-response event",
    )
    // The model is not re-invoked, so no acknowledgement text is produced.
    assertTrue(events.none { it.content?.parts?.any { p -> p.text == "acknowledged" } == true })
    assertEquals(1, modelInvocations, "the FC is final, so the model is not re-invoked")
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
   * [runAsync_longRunningToolReturnsUnit_suppressesFunctionResponseAndEndsTurn]. `Unit` suppresses
   * the FR event, so externally-observable events are just `[FC]`; the model is invoked only once.
   * `endOfAgent` is suppressed by the resumable pause gates so the agent state stays "live" for an
   * eventual resume.
   */
  @Test
  fun runAsync_resumable_longRunningToolReturnsUnit_suppressesFunctionResponseAndPauses() =
    runTest {
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
      val fcEvent = agentEvents.first { it.functionCalls().any { call -> call.id == callId } }
      assertEquals(setOf(callId), fcEvent.longRunningToolIds)
      // The FR event is suppressed for a `Unit` return.
      assertTrue(
        agentEvents.none { it.functionResponses().any { resp -> resp.id == callId } },
        "a Unit-returning long-running tool must not emit a function-response event",
      )
      assertEquals(
        1,
        modelInvocations,
        "the long-running FC is final, so the model is not re-invoked",
      )
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
   * A long-running tool returning an empty map (a non-`Unit` value) emits an FR event with that
   * payload -- unlike a `Unit` return, which is suppressed. The framework re-invokes the model with
   * the empty placeholder in history and acknowledges.
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
    assertTrue(functionResponse != null, "an empty-map return must emit a function-response event")
    assertEquals(emptyMap<String, Any>(), functionResponse.functionResponses().single().response)
    assertEquals(2, modelInvocations)
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
   * Scenario: the model issues a long-running call and a regular call in parallel. The long-running
   * tool returns an empty-map placeholder (a non-`Unit` return, so its FR IS emitted), so both FRs
   * are delivered immediately. The model is re-invoked once with both FRs and acknowledges. Later
   * the caller injects the real FR for the long-running call; on resume the model sees the real
   * result (the prior `{}` placeholder is merged-replaced by
   * `rearrangeEventsForLatestFunctionResponse`).
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
              if (modelInvocations == 1) {
                modelParallelFunctionCallsResponse(
                  FunctionCall(name = TOOL_NAME_1, id = lrCallId),
                  FunctionCall(name = TOOL_NAME_2, id = regCallId),
                )
              } else {
                LlmResponse(content = modelMessage("acknowledged"))
              }
            )
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                lrInvocations++
                emptyMap<String, Any>()
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

    // Resume-turn history: both function-responses are visible to the model. The long-running
    // tool's initial `{}` placeholder (emitted in turn 1 alongside the regular tool's FR) was
    // merged-replaced with the user-injected real result by
    // `rearrangeEventsForLatestFunctionResponse`, preserving the original chronological order
    // (long-running first because both FRs were emitted together in turn 1). The intermediate
    // "acknowledged" reply is dropped by the same rearrange (which truncates after the FC event).
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
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult)),
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_2, response = regResponse)),
          ),
      ),
      capturedRequests.last().simplifiedContents(),
    )
  }

  /**
   * Replays the same long-running call id multiple times. Each replay must produce the canonical
   * `[user, model_fc, fr(latest)]` shape (matching Java's `asyncFunction_handlesPendingAndResults`
   * and Python's `test_async_function`). Guards
   * `HistoryRewriterProcessor.rearrangeEventsForLatestFunctionResponse` against drifting from the
   * `[user, model_fc, tool_fr]` shape when out-of-band long-running results are replayed: the
   * intermediate model "acknowledged" text and prior placeholder/real FRs must all be collapsed so
   * the model sees only the latest real response. The tool itself is invoked exactly once across
   * all replays.
   */
  @Test
  fun runAsync_longRunningToolReplayedMultipleTimes_eachReplayProducesUserModelFcFrShape() =
    runTest {
      val callId = "lr_call_multi_replay"
      val placeholder = mapOf("status" to "pending")
      val stillWaiting = mapOf("status" to "still waiting")
      val finalResult = mapOf("result" to 2)
      val followUp = mapOf("result" to 3)
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
          newMessage =
            userFunctionResponse(name = TOOL_NAME_1, id = callId, response = stillWaiting),
        )
        .toList()
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userFunctionResponse(name = TOOL_NAME_1, id = callId, response = finalResult),
        )
        .toList()
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userFunctionResponse(name = TOOL_NAME_1, id = callId, response = followUp),
        )
        .toList()

      // Model is invoked: initial user prompt, after the placeholder FR, and once per replay (3).
      assertEquals(5, capturedRequests.size)
      // The tool is invoked exactly once -- replayed responses must not re-execute it.
      assertEquals(1, toolInvocations)

      // After each replay, the model sees the canonical `[user, model_fc, fr(latest)]` shape.
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = stillWaiting)),
        ),
        capturedRequests[2].simplifiedContents(),
      )
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = finalResult)),
        ),
        capturedRequests[3].simplifiedContents(),
      )
      assertEquals(
        listOf(
          Role.USER to "start",
          Role.MODEL to Part(functionCall = FunctionCall(name = TOOL_NAME_1)),
          Role.USER to
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = followUp)),
        ),
        capturedRequests[4].simplifiedContents(),
      )
    }

  /**
   * Two long-running calls issued in parallel; the user replays a real `FunctionResponse` for only
   * one of them. The next model invocation must preserve the canonical `[user, model(fc1, fc2),
   * fr(...)]` shape -- both FCs intact in a single model event -- with the replayed FR carrying the
   * real result for the replayed id and the original placeholder for the other. Neither
   * long-running tool is re-invoked.
   */
  @Test
  fun runAsync_parallelLongRunningTools_partialReplay_preservesBothFunctionCalls() = runTest {
    val lrCallId1 = "lr_call_parallel_1"
    val lrCallId2 = "lr_call_parallel_2"
    val placeholder1 = mapOf("status" to "pending_1")
    val placeholder2 = mapOf("status" to "pending_2")
    val realResult1 = mapOf("result" to "done_1")
    val capturedRequests = mutableListOf<LlmRequest>()
    var lr1Invocations = 0
    var lr2Invocations = 0
    var modelInvocations = 0
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel("model") { request ->
            capturedRequests += request
            modelInvocations++
            flowOf(
              if (modelInvocations == 1) {
                modelParallelFunctionCallsResponse(
                  FunctionCall(name = TOOL_NAME_1, id = lrCallId1),
                  FunctionCall(name = TOOL_NAME_2, id = lrCallId2),
                )
              } else {
                LlmResponse(content = modelMessage("acknowledged"))
              }
            )
          },
        tools =
          listOf(
            DummyTool(
              name = TOOL_NAME_1,
              isLongRunning = true,
              onRun = { _, _ ->
                lr1Invocations++
                placeholder1
              },
            ),
            DummyTool(
              name = TOOL_NAME_2,
              isLongRunning = true,
              onRun = { _, _ ->
                lr2Invocations++
                placeholder2
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
        newMessage =
          userFunctionResponse(name = TOOL_NAME_1, id = lrCallId1, response = realResult1),
      )
      .toList()

    // Each long-running tool is invoked exactly once; partial replay must not re-execute.
    assertEquals(1, lr1Invocations)
    assertEquals(1, lr2Invocations)

    // Resume-turn history: the parallel FC event is preserved verbatim, and the replayed FR for
    // call-1 is merged with the original placeholder for call-2 (same canonical
    // `[user, model(fc1, fc2), fr(fr1_real, fr2_placeholder)]` shape that Java/Python produce).
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
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_1, response = realResult1)),
            Part(functionResponse = FunctionResponse(name = TOOL_NAME_2, response = placeholder2)),
          ),
      ),
      capturedRequests.last().simplifiedContents(),
    )
  }

  @Test
  fun rearrangeEventsForLatestFunctionResponse_penultimatePartiallyMatchesSplitIds_returnsUnchanged() {
    // idA is in the penultimate FC event, idB in an earlier FC event; the latest FR answers both.
    // Pre-CL: throws IllegalStateException. With the fix: returned unchanged (Python/Java parity).
    val events =
      listOf(
        functionCallEvent("toolB" to "idB"),
        functionCallEvent("toolA" to "idA"),
        Event(
          author = "testAgent",
          content =
            Content(
              role = "function",
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(name = "toolA", id = "idA", response = mapOf("r" to 1))
                  ),
                  Part(
                    functionResponse =
                      FunctionResponse(name = "toolB", id = "idB", response = mapOf("r" to 2))
                  ),
                ),
            ),
        ),
      )
    assertEquals(
      events,
      HistoryRewriterProcessor().rearrangeEventsForLatestFunctionResponse(events),
    )
  }

  private fun functionCallEvent(vararg calls: Pair<String, String>) =
    Event(
      author = "testAgent",
      content =
        Content(
          role = "model",
          parts =
            calls.map { (name, id) ->
              Part(functionCall = FunctionCall(name = name, args = emptyMap(), id = id))
            },
        ),
    )

  /**
   * Resuming a paused long-running tool call still works after event compaction has summarized the
   * window that contains that call.
   *
   * Scenario: a resumable app with sliding-window compaction. A long-running tool call pauses
   * (invocation 2), which crosses the compaction interval, so post-invocation compaction summarizes
   * a window that includes the long-running `FunctionCall`/placeholder-`FunctionResponse` pair. On
   * resume with the real `FunctionResponse`, the framework must still deliver it and let the model
   * produce its final reply.
   *
   * The compactor summarizes the long-running call (its placeholder response balances it, so it is
   * not treated as an open obligation). On resume,
   * [com.google.adk.kt.processors.HistoryRewriterProcessor] restores the compacted call from the
   * pre-compaction events so `rearrangeEventsForLatestFunctionResponse` can match the resumed
   * response instead of throwing.
   */
  @Test
  fun resume_afterCompactionSummarizedPausedLongRunningCall_resumesCleanly() = runTest {
    val callId = "lr_call_compacted"
    val placeholder = mapOf("status" to "working")
    val realResult = mapOf("result" to "done")
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              // Invocation 1: a normal chat turn (no tools).
              LlmResponse(content = modelMessage("hello")),
              // Invocation 2: issue the long-running tool call that pauses.
              modelFunctionCallResponse(TOOL_NAME_1, id = callId),
              // Resume turn: summarize the delivered real result into a final reply.
              LlmResponse(content = modelMessage("resumed")),
            ),
          ),
        tools =
          listOf(
            DummyTool(name = TOOL_NAME_1, isLongRunning = true, onRun = { _, _ -> placeholder })
          ),
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "test_app",
            rootAgent = agent,
            resumabilityConfig = ResumabilityConfig(isResumable = true),
            // Compact every 2 invocations; the summarizer covers the whole window it is given.
            eventsCompactionConfig =
              EventsCompactionConfig(
                compactionInterval = 2,
                overlapSize = 0,
                summarizer = WindowCoveringSummarizer,
              ),
          )
      )

    // Invocation 1: a plain turn. Invocation 2: the long-running call pauses, and post-invocation
    // compaction then fires over invocations 1+2 -- summarizing away the long-running call.
    runner
      .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("hi"))
      .toList()
    runner
      .runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage("start"))
      .toList()

    // Sanity: the long-running call/response were captured in a compaction summary.
    val session =
      assertNotNull(
        runner.sessionService.getSession(SessionKey(runner.appName, USER_ID, SESSION_ID))
      )
    assertTrue(
      session.events.any { it.actions.compaction != null },
      "compaction must have fired over the paused long-running invocation",
    )
    assertTrue(
      session.events.any { event -> event.functionCalls().any { it.id == callId } },
      "raw long-running FunctionCall must still be in the log (compaction only appends a summary)",
    )

    // Resume with the real result. Despite the long-running call having been compacted, the resume
    // must deliver the real response and let the model produce its final reply.
    val resumeEvents =
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = SESSION_ID,
          newMessage = userFunctionResponse(name = TOOL_NAME_1, id = callId, response = realResult),
        )
        .toList()

    val finalText =
      resumeEvents
        .lastOrNull {
          it.author == AGENT_NAME && it.content?.parts?.any { p -> p.text != null } == true
        }
        ?.content
        ?.parts
        ?.singleOrNull()
        ?.text
    assertEquals("resumed", finalText)
  }

  /**
   * Recovery restores a whole parallel call event (and its compacted sibling response) when only
   * one of the calls is resumed.
   *
   * The model issues a long-running call (`lr-1`) and a regular call (`reg-1`) together. Both
   * calls, `reg-1`'s response, and `lr-1`'s placeholder are summarized away; only `lr-1`'s real
   * result survives (delivered on resume). [HistoryRewriterProcessor] must re-inject the entire
   * call event (both calls) and restore `reg-1`'s response, so neither call is left unpaired -- and
   * it must keep the freshest `lr-1` response (the real result, not the compacted placeholder).
   */
  @Test
  fun rewrite_recoversCompactedParallelLongRunningCall_reinjectsSiblingResponse() {
    val parallelCall =
      Event(
        author = "model",
        invocationId = "inv2",
        timestamp = 2L,
        longRunningToolIds = setOf("lr-1"),
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = "lr_tool", id = "lr-1", args = emptyMap())),
                Part(
                  functionCall = FunctionCall(name = "reg_tool", id = "reg-1", args = emptyMap())
                ),
              ),
          ),
      )
    val placeholderLr =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 3L,
        name = "lr_tool",
        callId = "lr-1",
        response = mapOf("status" to "pending"),
      )
    val regResponse =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 4L,
        name = "reg_tool",
        callId = "reg-1",
        response = mapOf("result" to "ok"),
      )
    // Summarizes the invocation window up to and including reg-1's response and lr-1's placeholder.
    val compaction = compactionEvent(startTs = 1L, endTs = 4L, timestamp = 4L)
    // The real lr-1 result arrives on resume, outside the compacted range.
    val resumeLr =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 5L,
        name = "lr_tool",
        callId = "lr-1",
        response = mapOf("result" to "done"),
      )

    val contents =
      HistoryRewriterProcessor()
        .rewrite(
          events = listOf(parallelCall, placeholderLr, regResponse, compaction, resumeLr),
          agentName = "model",
          currentBranch = null,
        )

    assertEquals(3, contents.size)

    // [0] the compaction summary.
    assertEquals(Role.MODEL, contents[0].role)
    assertEquals("summary", contents[0].parts.single().text?.trim())

    // [1] the recovered call event, both calls preserved in order.
    assertEquals(Role.MODEL, contents[1].role)
    assertEquals(
      listOf("lr-1" to "lr_tool", "reg-1" to "reg_tool"),
      contents[1].parts.map { it.functionCall?.id to it.functionCall?.name },
    )

    // [2] the merged responses.
    assertEquals(Role.USER, contents[2].role)
    assertEquals(
      listOf(
        Triple("reg-1", "reg_tool", mapOf("result" to "ok")),
        Triple("lr-1", "lr_tool", mapOf("result" to "done")),
      ),
      contents[2].parts.map {
        Triple(it.functionResponse?.id, it.functionResponse?.name, it.functionResponse?.response)
      },
    )
  }

  /**
   * Recovery handles two long-running calls plus a regular call issued in one parallel event.
   *
   * Both long-running calls (`lr-1`, `lr-2`) resume; `reg-1`'s response and both placeholders are
   * compacted. The shared call event must be re-injected exactly once (not once per resumed call),
   * `reg-1`'s compacted response must be restored, and each long-running call must keep its own
   * freshest result.
   */
  @Test
  fun rewrite_recoversTwoCompactedLongRunningCalls_reinjectsSharedCallEventOnce() {
    val parallelCall =
      Event(
        author = "model",
        invocationId = "inv2",
        timestamp = 2L,
        longRunningToolIds = setOf("lr-1", "lr-2"),
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(
                  functionCall = FunctionCall(name = "lr_tool_1", id = "lr-1", args = emptyMap())
                ),
                Part(
                  functionCall = FunctionCall(name = "lr_tool_2", id = "lr-2", args = emptyMap())
                ),
                Part(
                  functionCall = FunctionCall(name = "reg_tool", id = "reg-1", args = emptyMap())
                ),
              ),
          ),
      )
    val placeholderLr1 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 3L,
        name = "lr_tool_1",
        callId = "lr-1",
        response = mapOf("status" to "pending"),
      )
    val placeholderLr2 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 4L,
        name = "lr_tool_2",
        callId = "lr-2",
        response = mapOf("status" to "pending"),
      )
    val regResponse =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 5L,
        name = "reg_tool",
        callId = "reg-1",
        response = mapOf("result" to "ok"),
      )
    // Summarizes the window through reg-1's response and both placeholders.
    val compaction = compactionEvent(startTs = 1L, endTs = 5L, timestamp = 5L)
    // Both long-running results arrive on resume, outside the compacted range.
    val resumeLr1 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 6L,
        name = "lr_tool_1",
        callId = "lr-1",
        response = mapOf("result" to "done-1"),
      )
    val resumeLr2 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 7L,
        name = "lr_tool_2",
        callId = "lr-2",
        response = mapOf("result" to "done-2"),
      )

    val contents =
      HistoryRewriterProcessor()
        .rewrite(
          events =
            listOf(
              parallelCall,
              placeholderLr1,
              placeholderLr2,
              regResponse,
              compaction,
              resumeLr1,
              resumeLr2,
            ),
          agentName = "model",
          currentBranch = null,
        )

    // [summary, shared call event, merged responses] -- one call content proves single
    // re-injection.
    assertEquals(3, contents.size)
    assertEquals(
      setOf("lr-1", "lr-2", "reg-1"),
      contents[1].parts.mapNotNull { it.functionCall?.id }.toSet(),
    )
    val responses = contents[2].parts.mapNotNull { it.functionResponse }
    assertEquals(setOf("lr-1", "lr-2", "reg-1"), responses.mapNotNull { it.id }.toSet())
    assertEquals(mapOf("result" to "done-1"), responses.single { it.id == "lr-1" }.response)
    assertEquals(mapOf("result" to "done-2"), responses.single { it.id == "lr-2" }.response)
    assertEquals(mapOf("result" to "ok"), responses.single { it.id == "reg-1" }.response)
  }

  /**
   * Recovery restores compacted calls that span two different invocations.
   *
   * Invocation 1 issues a parallel call (`reg-1` + long-running `lr-1`); invocation 2 issues a
   * separate long-running call (`lr-2`). Both call events, `reg-1`'s response, and both
   * placeholders are compacted; later both long-running calls resume. Each compacted call event
   * must be recovered independently (the parallel one with `reg-1`'s restored response, the
   * standalone one on its own), so the assembled prompt pairs every call with its freshest
   * response.
   */
  @Test
  fun rewrite_recoversCompactedCallsAcrossInvocations_reinjectsEachCallEvent() {
    // inv1: parallel (normal reg-1 + long-running lr-1).
    val parallelCall =
      Event(
        author = "model",
        invocationId = "inv1",
        timestamp = 1L,
        longRunningToolIds = setOf("lr-1"),
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(
                  functionCall = FunctionCall(name = "reg_tool", id = "reg-1", args = emptyMap())
                ),
                Part(
                  functionCall = FunctionCall(name = "lr_tool_1", id = "lr-1", args = emptyMap())
                ),
              ),
          ),
      )
    val placeholderLr1 =
      eventWithFunctionResponse(
        invocationId = "inv1",
        timestamp = 2L,
        name = "lr_tool_1",
        callId = "lr-1",
        response = mapOf("status" to "pending"),
      )
    val regResponse =
      eventWithFunctionResponse(
        invocationId = "inv1",
        timestamp = 3L,
        name = "reg_tool",
        callId = "reg-1",
        response = mapOf("result" to "ok"),
      )
    // inv2: a separate long-running call.
    val lrCall2 =
      eventWithFunctionCall(
        invocationId = "inv2",
        timestamp = 4L,
        callName = "lr_tool_2",
        callId = "lr-2",
        longRunning = true,
      )
    val placeholderLr2 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 5L,
        name = "lr_tool_2",
        callId = "lr-2",
        response = mapOf("status" to "pending"),
      )
    // Summarizes both invocations (both call events, reg-1's response, both placeholders).
    val compaction = compactionEvent(startTs = 1L, endTs = 5L, timestamp = 5L)
    // Both long-running calls resume, outside the compacted range.
    val resumeLr1 =
      eventWithFunctionResponse(
        invocationId = "inv1",
        timestamp = 6L,
        name = "lr_tool_1",
        callId = "lr-1",
        response = mapOf("result" to "done-1"),
      )
    val resumeLr2 =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 7L,
        name = "lr_tool_2",
        callId = "lr-2",
        response = mapOf("result" to "done-2"),
      )

    val contents =
      HistoryRewriterProcessor()
        .rewrite(
          events =
            listOf(
              parallelCall,
              placeholderLr1,
              regResponse,
              lrCall2,
              placeholderLr2,
              compaction,
              resumeLr1,
              resumeLr2,
            ),
          agentName = "model",
          currentBranch = null,
        )

    // Each compacted call event is recovered independently, giving one [call, responses] pair per
    // invocation after the summary:
    //  [0] model : "summary"
    //  [1] model : functionCall(reg-1), functionCall(lr-1)      (recovered inv1 parallel call)
    //  [2] user  : functionResponse(reg-1 -> ok), functionResponse(lr-1 -> done-1)
    //  [3] model : functionCall(lr-2)                           (recovered inv2 standalone call)
    //  [4] user  : functionResponse(lr-2 -> done-2)
    assertEquals(5, contents.size)

    assertEquals(Role.MODEL, contents[0].role)
    assertEquals("summary", contents[0].parts.single().text?.trim())

    assertEquals(Role.MODEL, contents[1].role)
    assertEquals(
      listOf("reg-1" to "reg_tool", "lr-1" to "lr_tool_1"),
      contents[1].parts.map { it.functionCall?.id to it.functionCall?.name },
    )

    assertEquals(Role.USER, contents[2].role)
    assertEquals(
      listOf(
        Triple("reg-1", "reg_tool", mapOf("result" to "ok")),
        Triple("lr-1", "lr_tool_1", mapOf("result" to "done-1")),
      ),
      contents[2].parts.map {
        Triple(it.functionResponse?.id, it.functionResponse?.name, it.functionResponse?.response)
      },
    )

    assertEquals(Role.MODEL, contents[3].role)
    assertEquals(
      listOf("lr-2" to "lr_tool_2"),
      contents[3].parts.map { it.functionCall?.id to it.functionCall?.name },
    )

    assertEquals(Role.USER, contents[4].role)
    assertEquals(
      listOf(Triple("lr-2", "lr_tool_2", mapOf("result" to "done-2"))),
      contents[4].parts.map {
        Triple(it.functionResponse?.id, it.functionResponse?.name, it.functionResponse?.response)
      },
    )
  }

  /**
   * Recovery must not swallow a genuinely unmatched response: an orphaned response whose call is
   * *not* long-running is left unrecovered, so `rearrangeEventsForLatestFunctionResponse` still
   * throws.
   */
  @Test
  fun rewrite_orphanedNonLongRunningResponse_stillThrows() {
    val regCall =
      eventWithFunctionCall(
        invocationId = "inv2",
        timestamp = 2L,
        callName = "reg_tool",
        callId = "reg-1",
        longRunning = false,
      )
    // Summarizes away the call event (its later response survives outside the range).
    val compaction = compactionEvent(startTs = 1L, endTs = 2L, timestamp = 3L)
    val orphanedResponse =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 4L,
        name = "reg_tool",
        callId = "reg-1",
        response = mapOf("result" to "done"),
      )

    val error =
      assertFailsWith<IllegalStateException> {
        HistoryRewriterProcessor()
          .rewrite(
            events = listOf(regCall, compaction, orphanedResponse),
            agentName = "model",
            currentBranch = null,
          )
      }
    assertTrue(error.message?.contains("reg-1") == true, "unexpected message: ${error.message}")
  }

  /**
   * Recovery must not swallow a response whose call is absent from the pre-compaction events: with
   * no originating call to restore, the unmatched response still surfaces as a throw.
   */
  @Test
  fun rewrite_orphanedResponseWithNoSourceCall_stillThrows() {
    val compaction = compactionEvent(startTs = 1L, endTs = 5L, timestamp = 5L)
    val orphanedResponse =
      eventWithFunctionResponse(
        invocationId = "inv2",
        timestamp = 6L,
        name = "ghost_tool",
        callId = "ghost-1",
        response = mapOf("result" to "done"),
      )

    val error =
      assertFailsWith<IllegalStateException> {
        HistoryRewriterProcessor()
          .rewrite(
            events = listOf(compaction, orphanedResponse),
            agentName = "model",
            currentBranch = null,
          )
      }
    assertTrue(error.message?.contains("ghost-1") == true, "unexpected message: ${error.message}")
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
   * Reduces this request's content history to a `(role, simplifiedContent)` list using
   * [simplifyContent], for legible whole-history assertions. Function-call/response ids are
   * stripped by [simplifyContent].
   */
  private fun LlmRequest.simplifiedContents(): List<Pair<String, Any>> = contents.map { content ->
    (content.role ?: "") to simplifyContent(content)
  }

  /**
   * An [EventSummarizer] that returns a compaction summary spanning the full window it is handed,
   * so every event in the window (including a long-running call/response pair) is dropped from the
   * rebuilt prompt and replaced by the summary.
   */
  private object WindowCoveringSummarizer : EventSummarizer {
    override suspend fun summarizeEvents(events: List<Event>): Event? =
      if (events.isEmpty()) null
      else compactionEvent(startTs = events.first().timestamp, endTs = events.last().timestamp)
  }

  private companion object {
    const val USER_ID = "u"
    const val SESSION_ID = "s"
    const val AGENT_NAME = "agent"
    const val TOOL_NAME_1 = "tool_1"
    const val TOOL_NAME_2 = "tool_2"
  }
}
