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
import com.google.adk.kt.models.LongRunningReturnsUnitTool
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * End-to-end [Runner] coverage of a long-running tool whose `FunctionTool` subclass is
 * KSP-generated from an `@Tool(isLongRunning = true)` function that returns `Unit`.
 *
 * [LongRunningToolIntegrationTest] pins the same "`Unit` suppresses the function-response event"
 * contract with a hand-written `DummyTool`. This complements it by exercising the KSP
 * `FunctionToolGenerator` path: the generated `execute` returns the `Unit` singleton (`return
 * Unit`), which the framework reads as "no response yet". It lives in the `jvmAndroidKspTest`
 * source set because KMP forbids `common` test sets from referencing per-platform KSP output.
 */
class KspLongRunningToolIntegrationTest {

  @Test
  fun runAsync_kspGeneratedLongRunningToolReturnsUnit_suppressesFunctionResponseAndEndsTurn() =
    runTest {
      val callId = "lr_call_unit_ksp"
      val tool = LongRunningReturnsUnitTool()
      var modelInvocations = 0
      val agent =
        LlmAgent(
          name = "agent",
          model =
            DummyModel("model") {
              modelInvocations++
              flowOf(modelFunctionCallResponse(tool.name, id = callId))
            },
          tools = listOf(tool),
        )
      val runner = InMemoryRunner(agent = agent)

      val events =
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("start")).toList()

      val modelEvent = events.first { event -> event.functionCalls().any { it.id == callId } }
      assertEquals(setOf(callId), modelEvent.longRunningToolIds)
      // The FR event is suppressed: no function-response for the call is emitted.
      assertTrue(
        events.none { event -> event.functionResponses().any { it.id == callId } },
        "a Unit-returning long-running tool must not emit a function-response event",
      )
      // The function-call event is the turn's final response, so the model is not re-invoked.
      assertEquals(1, modelInvocations, "the FC is final, so the model is not re-invoked")
    }
}
