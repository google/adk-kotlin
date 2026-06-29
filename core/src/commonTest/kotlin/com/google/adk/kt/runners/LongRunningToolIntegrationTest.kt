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
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.summarizer.EventSummarizer
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.compactionEvent
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
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
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
 *   to the model event's `longRunningToolIds`. A `Unit` return is coerced to an empty Map so the
 *   wire form is clean; the FR event is still emitted (matching Java's `LongRunningFunctionTool`
 *   semantics).
 * - The caller resumes by sending a follow-up `runAsync(newMessage = userFunctionResponse(...))`.
 *   The runner routes it to the agent that issued the call and re-invokes the model with the
 *   updated history. The original tool is not re-executed.
 */
class LongRunningToolIntegrationTest {

  // The four scenarios immediately below cover every combination of `is_resumable` x
  // long-running tool return value (empty vs non-empty dict). `Unit` and an empty Map both
  // produce the same observable behaviour as any other empty-dict return (matching Java's
  // `LongRunningFunctionTool` semantics). `end_of_agent` is short for "the
  // framework emits an event with `actions.endOfAgent = true` for this agent."
  //
  // | # | resumable | tool return       | calls | events         | end_of_agent |
  // | - | --------- | ----------------- | ----- | -------------- | ------------ |
  // | 1 | off       | Unit/{}           | 2     | [FC, FR, text] | n/a          |
  // | 2 | off       | {status: pending} | 2     | [FC, FR, text] | n/a          |
  // | 3 | on        | Unit/{}           | 1     | [FC, FR]       | suppressed   |
  // | 4 | on        | {status: pending} | 1     | [FC, FR]       | suppressed   |
  //
  // For non-resumable mode (1, 2) the framework never emits `end_of_agent` at all -- the marker
  // only exists in resumable mode. For resumable mode (3, 4) the marker is suppressed by the
  // pause gates in `LlmAgent.runAsyncImpl` so the agent state stays live for an eventual resume.

  /**
   * Non-resumable + `Unit`-returning long-running tool: `Unit` is coerced to `{}` and emitted as
   * the function-response payload (matching Java's `LongRunningFunctionTool` semantics). The model
   * is then re-invoked once with the empty placeholder in history and acknowledges. Across the
   * turn, the model is invoked twice and the tool is invoked once. No `endOfAgent` (never emitted
   * in non-resumable mode).
   */
  @Test
  fun runAsync_longRunningToolReturnsUnit_emitsEmptyFunctionResponseAndAcknowledges() = runTest {
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
    val functionResponse = events.first { event ->
      event.functionResponses().any { it.id == callId }
    }
    assertEquals(emptyMap<String, Any>(), functionResponse.functionResponses().single().response)
    assertEquals("acknowledged", events.last().content?.parts?.singleOrNull()?.text)
    assertEquals(2, modelInvocations)
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
   * [runAsync_longRunningToolReturnsUnit_emitsEmptyFunctionResponseAndAcknowledges]. `Unit` is
   * coerced to `{}` and emitted as the FR payload (matching Java's `LongRunningFunctionTool`
   * semantics). Externally-observable events are `[FC, FR]`; the model is invoked only once because
   * `LlmAgentTurn.shouldPause` short-circuits the second step when it sees the long-running FC in
   * `events[-2:]`. `endOfAgent` is suppressed by the resumable pause gates so the agent state stays
   * "live" for an eventual resume.
   */
  @Test
  fun runAsync_resumable_longRunningToolReturnsUnit_emitsEmptyFunctionResponseAndPauses() =
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
      val frEvent = agentEvents.first { it.functionResponses().any { resp -> resp.id == callId } }
      assertEquals(emptyMap<String, Any>(), frEvent.functionResponses().single().response)
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
   * A long-running tool returning an empty map emits an FR event with that payload (matching Java's
   * `LongRunningFunctionTool` semantics). The framework re-invokes the model with the empty
   * placeholder in history and acknowledges.
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
   * Scenario: the model issues a long-running call and a regular call in parallel. Both FRs are
   * delivered immediately (the long-running tool's `Unit` is coerced to `{}`). The model is
   * re-invoked once with both FRs and acknowledges. Later the caller injects the real FR for the
   * long-running call; on resume the model sees the real result (the prior `{}` placeholder is
   * merged-replaced by `rearrangeEventsForLatestFunctionResponse`).
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
   * Resuming a paused long-running tool call must still work after event compaction has summarized
   * the window that contains that call.
   *
   * Scenario: a resumable app with sliding-window compaction. A long-running tool call pauses
   * (invocation 2), which crosses the compaction interval, so post-invocation compaction summarizes
   * a window that includes the long-running `FunctionCall`/placeholder-`FunctionResponse` pair. On
   * resume with the real `FunctionResponse`, the framework must still deliver it and let the model
   * produce its final reply.
   *
   * TODO(b/528182891): this currently fails, so the test is [Ignore]d. The compactor's
   *   "self-contained prefix" guard only protects open function calls and open HITL confirmations;
   *   a long-running call is already *balanced* by its placeholder response, so it gets summarized
   *   (its `longRunningToolIds` is never treated as an open obligation -- see
   *   [com.google.adk.kt.summarizer.SlidingWindowEventCompactor]). On resume,
   *   [com.google.adk.kt.processors.HistoryRewriterProcessor] then drops the original
   *   `FunctionCall` with the summarized window, so `rearrangeEventsForLatestFunctionResponse`
   *   cannot match the resumed response and throws. Re-enable this test once the compactor treats
   *   `longRunningToolIds` as open obligations (the long-running analog of the existing HITL
   *   guard).
   */
  @Ignore
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
