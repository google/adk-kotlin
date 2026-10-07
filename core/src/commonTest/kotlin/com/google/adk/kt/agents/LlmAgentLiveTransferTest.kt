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
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.live.FakeLiveModel
import com.google.adk.kt.testing.live.LiveScript
import com.google.adk.kt.testing.live.SentLiveMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    val parentModel: FakeLiveModel,
    val childModel: FakeLiveModel,
  )

  /** The names of the tool answers [model]'s only connection was sent. */
  private fun toolResponseNames(model: FakeLiveModel) =
    model.connections
      .single()
      .sent
      .filterIsInstance<SentLiveMessage.ClientContent>()
      .map { it.content }
      .filter { content ->
        content.parts.isNotEmpty() && content.parts.all { it.functionResponse != null }
      }
      .mapNotNull { it.parts.single().functionResponse?.name }

  /** A parent that immediately hands over, and a child that then calls a tool of its own. */
  private fun handoverTree(): Handover {
    val childModel =
      FakeLiveModel(
        LiveScript.builder()
          .toolCall("get_weather")
          .awaitToolResponse()
          .text("sunny", partial = false)
          .endStream()
          .build()
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
      FakeLiveModel(
        LiveScript.builder()
          .respond(modelTransferToAgentResponse("weather_agent"))
          .endStream()
          .build()
      )
    val parent = LlmAgent(name = "router", model = parentModel, subAgents = listOf(child))
    return Handover(InMemoryRunner(agent = parent), parentModel, childModel)
  }

  @Test
  fun runLive_transfer_runsTheChildOverItsOwnConnection(): Unit = runTest {
    val tree = handoverTree()

    val events = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(
      1,
      tree.childModel.connections.size,
      "the child should open its own live connection",
    )
    assertTrue(
      events.any { it.content?.parts?.any { part -> part.text == "sunny" } == true },
      "the child's turn should reach the caller (${events.size} events)",
    )
    assertEquals(
      1,
      tree.parentModel.connections.size,
      "the parent should not reconnect after handing over",
    )
  }

  @Test
  fun runLive_transfer_doesNotAnswerTheChildsToolCallsOnTheParentsConnection(): Unit = runTest {
    // Transfer is handled around the connection so the child's answer reaches its own model.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    val onParent = toolResponseNames(tree.parentModel)
    assertEquals(
      listOf("get_weather"),
      toolResponseNames(tree.childModel),
      "the child answers its own call",
    )
    assertTrue(
      "get_weather" !in onParent,
      "the child's tool answer must not be sent on the parent's connection too: $onParent",
    )
  }

  @Test
  fun runLive_transfer_closesTheChildsConnectionToo(): Unit = runTest {
    // Asserted, not just counted: a leaked pump coroutine per transfer would go unseen.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertTrue(
      tree.parentModel.connections.single().isClosed,
      "the parent's connection was left open",
    )
    assertTrue(
      tree.childModel.connections.single().isClosed,
      "the child's connection was left open",
    )
  }

  @Test
  fun runLive_transfer_givesTheChildItsOwnPersonaAndTools(): Unit = runTest {
    // Checks the child's request; elsewhere only the resumption handle is read from it.
    val tree = handoverTree()

    val unusedEvents = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    val childRequest = tree.childModel.connectRequests.single()
    val instruction = childRequest.config.systemInstruction?.parts.orEmpty().mapNotNull { it.text }
    assertTrue(
      instruction.any { "Report the weather." in it },
      "the child took over without its own instruction",
    )
    val declared =
      childRequest.config.tools?.flatMap { it.functionDeclarations.orEmpty().map { fn -> fn.name } }
    // Its own tool, plus transfer_to_agent, which lets a child with a parent hand back.
    assertTrue(
      declared.orEmpty().containsAll(listOf("get_weather", "transfer_to_agent")),
      "the child took over without its own tools; it was offered $declared",
    )
  }

  @Test
  fun runLive_transfer_doesNotGiveTheChildTheParentsResumptionHandle(): Unit = runTest {
    // The parent's handle names the parent's session; the child must not resume into it.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(
      null,
      tree.childModel.connectRequests.single().liveConnectConfig.sessionResumption?.handle,
      "the child must open a fresh session",
    )
    assertEquals(1, tree.parentModel.connections.size)
  }

  @Test
  fun runLive_transfer_stillTellsTheModelTheTransferWasDone(): Unit = runTest {
    // The parent's model asked for the transfer, so its function response goes back there.
    val tree = handoverTree()

    val unused = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(listOf("transfer_to_agent"), toolResponseNames(tree.parentModel))
  }

  @Test
  fun runLive_afterALiveTransfer_nextRunContinuesWithTheChild(): Unit = runTest {
    // The events a live transfer stores are what routes the next run to the child.
    val tree = handoverTree()

    val unusedFirst = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()
    val unusedSecond = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(1, tree.parentModel.connections.size, "the next run must not start at the parent")
    assertEquals(2, tree.childModel.connections.size, "the next run should continue with the child")
  }
}
