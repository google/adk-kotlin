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
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.awaitToolResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
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
  fun runLive_transferWhileAToolStreams_deliversItsEventsBeforeTheChildsTurn(): Unit = runTest {
    val childModel =
      DummyLiveModel(listOf(Respond(LlmResponse(content = modelMessage("sunny"), partial = false))))
    val monitor =
      DummyTool(name = "monitor") { _, _ ->
        flow {
          emit(Event(content = Content(Role.MODEL, listOf(Part(text = "watching")))))
          // Had the tool outlived the handover, this would reach the caller after the child's turn.
          while (childModel.connections.isEmpty()) delay(10)
          emit(Event(content = Content(Role.MODEL, listOf(Part(text = "still watching")))))
          awaitCancellation()
        }
      }
    val parentModel =
      DummyLiveModel(
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("a tool response") {
            it is ContentInput && it.content.parts.all { p -> p.functionResponse != null }
          },
          Respond(modelTransferToAgentResponse("weather_agent")),
          EndStream,
        )
      )
    val child = LlmAgent(name = "weather_agent", model = childModel)
    val parent =
      LlmAgent(
        name = "router",
        model = parentModel,
        subAgents = listOf(child),
        tools = listOf(monitor),
      )

    val queue = LiveRequestQueue()

    val events =
      InMemoryRunner(agent = parent)
        .runLive("u", "s", queue)
        .onEach { event ->
          // Hangs up shortly after the child speaks, giving a surviving parent tool time to send.
          if (event.content?.parts?.any { it.text == "sunny" } == true) {
            delay(100)
            queue.close()
          }
        }
        .toList()

    val sunny = events.indexOfFirst { event ->
      event.content?.parts?.any { it.text == "sunny" } == true
    }
    val toolEvents = events.indices.filter { events[it].branch == "monitor@c1" }
    assertTrue(toolEvents.isNotEmpty(), "the parent's tool event never reached the caller")
    assertTrue(toolEvents.all { it < sunny }, "a parent's tool event came after the child's turn")
  }

  @Test
  fun runLive_streamingCallInTheSameTurnAsATransfer_neverStarts(): Unit = runTest {
    var started = false
    val monitor =
      DummyTool(name = "monitor") { _, _ ->
        flow<String> {
          started = true
          awaitCancellation()
        }
      }
    val transfer =
      FunctionCall(
        name = TRANSFER_TO_AGENT_TOOL_NAME,
        args = mapOf("agent_name" to "weather_agent"),
        id = "t1",
      )
    val monitorCall = FunctionCall(name = "monitor", args = emptyMap(), id = "c1")
    val calls =
      LlmResponse(
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = monitorCall), Part(functionCall = transfer)),
          )
      )
    val parentModel = DummyLiveModel(listOf(Respond(calls), EndStream))
    val childModel =
      DummyLiveModel(
        listOf(Respond(LlmResponse(content = modelMessage("sunny"), partial = false)), EndStream)
      )
    val child = LlmAgent(name = "weather_agent", model = childModel)
    val parent =
      LlmAgent(
        name = "router",
        model = parentModel,
        subAgents = listOf(child),
        tools = listOf(monitor),
      )

    val unused = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

    assertFalse(started, "a stream answered alongside a transfer should never start")
    assertTrue(
      childModel.connections
        .flatMap { it.sent }
        .none { message ->
          (message as? ContentInput)?.content?.parts?.any { it.functionResponse?.id == "c1" } ==
            true
        },
      "the child heard the never-started stream",
    )
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
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
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
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transfer_closesTheChildsConnectionToo(): Unit = runTest {
    // Each live connection owns a pump coroutine, so an open connection keeps it running.
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
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transfer_givesTheChildItsOwnPersonaAndTools(): Unit = runTest {
    // After a transfer, the child opens its own connection built from its own settings.
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
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_afterALiveTransfer_nextRunContinuesWithTheChild(): Unit = runTest {
    // The events a live transfer stores are what routes the next run to the child.
    val tree = handoverTree()

    val unusedFirst = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()
    val unusedSecond = tree.runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(1, tree.parentModel.connections.size, "the next run must not start at the parent")
    assertEquals(2, tree.childModel.connections.size, "the next run should continue with the child")
    tree.childModel.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_transferWhileAToolStreams_stopsItBeforeTheChildConnects(): Unit = runTest {
    var childConnectionsWhenStopped: Int? = null
    val childModel = DummyLiveModel(listOf(wholeText("sunny"), EndStream))
    val ticker =
      flow {
          while (true) {
            emit("tick")
            delay(50.milliseconds)
          }
        }
        .onCompletion { childConnectionsWhenStopped = childModel.connections.size }
    val model =
      DummyLiveModel(
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          awaitToolResponse(),
          Respond(modelTransferToAgentResponse("weather_agent")),
          EndStream,
        )
      )
    val child = LlmAgent(name = "weather_agent", model = childModel)
    val tools = listOf(DummyTool(name = "monitor") { _, _ -> ticker })
    val parent = LlmAgent(name = "router", model = model, subAgents = listOf(child), tools = tools)
    val unused = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(0, childConnectionsWhenStopped, "the stream should stop before the child connects")
    val heard = childModel.connections.flatMap { it.sent }.filterIsInstance<ContentInput>()
    assertTrue(heard.none { it.content.parts.any { p -> p.functionResponse?.id == "c1" } })
  }
}
