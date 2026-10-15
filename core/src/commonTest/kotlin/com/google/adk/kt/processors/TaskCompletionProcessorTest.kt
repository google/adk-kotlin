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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.tools.TaskCompletedTool.Companion.TASK_COMPLETED_TOOL_NAME
import kotlin.test.assertContains
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Test

@OptIn(ExperimentalLiveApi::class)
class TaskCompletionProcessorTest {

  private fun offeredToolNames(request: LlmRequest): List<String> =
    request.config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }.map { it.name }

  private fun instructionText(request: LlmRequest): String =
    request.config.systemInstruction?.parts.orEmpty().joinToString { it.text.orEmpty() }

  /** A context that runs [agent] live: one whose run has a live request queue. */
  private fun liveContextFor(agent: LlmAgent) =
    testInvocationContext(agent = agent).apply {
      frameworkData.liveRequestQueue = LiveRequestQueue()
    }

  @Test
  fun processRequest_onALiveSequentialStep_offersTheToolAndInstruction() = runBlocking {
    val step = LlmAgent(name = "step", description = "s", model = DummyModel("m"))
    val unused = SequentialAgent(name = "seq", subAgents = listOf(step))

    val processed = TaskCompletionProcessor().process(liveContextFor(step), LlmRequest())

    assertContains(offeredToolNames(processed), TASK_COMPLETED_TOOL_NAME)
    val instruction = instructionText(processed)
    assertContains(instruction, "call the task_completed function to exit")
    assertContains(instruction, "do not generate any text other than the function call")
  }

  @Test
  fun processRequest_notALiveRun_leavesTheRequestUnchanged() = runBlocking {
    // A step of a SequentialAgent, but turn-based (no live queue): the tool is not offered.
    val step = LlmAgent(name = "step", description = "s", model = DummyModel("m"))
    val unused = SequentialAgent(name = "seq", subAgents = listOf(step))
    val context = testInvocationContext(agent = step)
    val request = LlmRequest()

    val processed = TaskCompletionProcessor().process(context, request)

    assertSame(request, processed)
  }

  @Test
  fun processRequest_parentIsNotASequentialAgent_leavesTheRequestUnchanged() = runBlocking {
    // A live step whose parent is an LlmAgent is reached by transfer, not as a sequential step.
    val step = LlmAgent(name = "step", description = "s", model = DummyModel("m"))
    val unused =
      LlmAgent(
        name = "parent",
        description = "p",
        model = DummyModel("p"),
        subAgents = listOf(step),
      )
    val request = LlmRequest()

    val processed = TaskCompletionProcessor().process(liveContextFor(step), request)

    assertSame(request, processed)
  }

  @Test
  fun processRequest_stepAlreadyHasATaskCompletedTool_doesNotAddASecond() = runBlocking {
    // Dedupe by name, as ADK Python does, so a caller's own task_completed tool is left in place.
    val step =
      LlmAgent(
        name = "step",
        description = "s",
        model = DummyModel("m"),
        tools = listOf(DummyTool(TASK_COMPLETED_TOOL_NAME)),
      )
    val unused = SequentialAgent(name = "seq", subAgents = listOf(step))
    val request = LlmRequest()

    val processed = TaskCompletionProcessor().process(liveContextFor(step), request)

    assertSame(request, processed)
  }
}
