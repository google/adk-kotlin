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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.awaitToolResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.wholeText
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Covers handing a live conversation from one agent to another.
 *
 * If a transfer ran inside function-response handling rather than around the connection, the parent
 * would see the child's events and answer the child's tool calls on its own connection too. These
 * tests therefore check what each connection received, not only what the caller saw, and use
 * `runTest` because the handover waits on a one-second delay that virtual time skips.
 */
class LlmAgentLiveTransferTest {

  /** A parent that immediately hands over, and the child it hands over to. */
  private data class Handover(
    val runner: InMemoryRunner,
    val parentModel: DummyLiveModel,
    val childModel: DummyLiveModel,
  )

  /** The names of the tool answers [model]'s only connection was sent. */
  private fun toolResponseNames(model: DummyLiveModel) =
    model.connections
      .single()
      .sent
      .filterIsInstance<ContentInput>()
      .flatMap { it.content.parts }
      .mapNotNull { it.functionResponse?.name }

  /** A parent that immediately hands over, and a child that then calls a tool of its own. */
  private fun handoverTree(): Handover {
    val childModel =
      DummyLiveModel(
        listOf(
          Respond(modelFunctionCallResponse("get_weather")),
          awaitToolResponse(),
          wholeText("sunny"),
          EndStream,
        )
      )
    val child =
      LlmAgent(
        name = "weather_agent",
        model = childModel,
        instruction = Instruction("Report the weather."),
        tools =
          listOf(DummyTool(name = "get_weather", declares = true) { _, _ -> mapOf("tempF" to 72) }),
      )
    val parentModel =
      DummyLiveModel(listOf(Respond(modelTransferToAgentResponse("weather_agent")), EndStream))
    val parent = LlmAgent(name = "router", model = parentModel, subAgents = listOf(child))
    return Handover(InMemoryRunner(agent = parent), parentModel, childModel)
  }

  @Test
  fun runLive_transfer_runsTheChildOverItsOwnConnection(): Unit = runTest {
    val tree = handoverTree()

    val events = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertWithMessage("the child should open its own live connection")
      .that(tree.childModel.connections.size)
      .isEqualTo(1)
    assertWithMessage("the child's turn should reach the caller (${events.size} events)")
      .that(events.any { it.content?.parts?.any { part -> part.text == "sunny" } == true })
      .isTrue()
    assertWithMessage("the parent should not reconnect after handing over")
      .that(tree.parentModel.connections.size)
      .isEqualTo(1)
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transfer_doesNotAnswerTheChildsToolCallsOnTheParentsConnection(): Unit = runTest {
    // Transfer is handled around the connection so the child's answer reaches its own model.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    val onParent = toolResponseNames(tree.parentModel)
    assertWithMessage("the child answers its own call")
      .that(toolResponseNames(tree.childModel))
      .isEqualTo(listOf("get_weather"))
    assertWithMessage(
        "the child's tool answer must not be sent on the parent's connection too: $onParent"
      )
      .that(onParent)
      .doesNotContain("get_weather")
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transfer_closesTheChildsConnectionToo(): Unit = runTest {
    // Each live connection owns a pump coroutine, so an open connection keeps it running.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertWithMessage("the parent's connection was left open")
      .that(tree.parentModel.connections.single().isClosed)
      .isTrue()
    assertWithMessage("the child's connection was left open")
      .that(tree.childModel.connections.single().isClosed)
      .isTrue()
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transfer_givesTheChildItsOwnPersonaAndTools(): Unit = runTest {
    // After a transfer, the child opens its own connection built from its own settings.
    val tree = handoverTree()

    val unusedEvents = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    val childRequest = tree.childModel.connectRequests.single()
    val instruction = childRequest.config.systemInstruction?.parts.orEmpty().mapNotNull { it.text }
    assertWithMessage("the child took over without its own instruction")
      .that(instruction.any { "Report the weather." in it })
      .isTrue()
    val declared =
      childRequest.config.tools?.flatMap { it.functionDeclarations.orEmpty().map { fn -> fn.name } }
    // Its own tool, plus transfer_to_agent, which lets a child with a parent hand back.
    assertWithMessage("the child took over without its own tools")
      .that(declared)
      .containsAtLeast("get_weather", "transfer_to_agent")
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_afterALiveTransfer_nextRunContinuesWithTheChild(): Unit = runTest {
    // The events a live transfer stores are what routes the next run to the child.
    val tree = handoverTree()

    val unusedFirst = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()
    val unusedSecond = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertWithMessage("the next run must not start at the parent")
      .that(tree.parentModel.connections.size)
      .isEqualTo(1)
    assertWithMessage("the next run should continue with the child")
      .that(tree.childModel.connections.size)
      .isEqualTo(2)
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }
}
