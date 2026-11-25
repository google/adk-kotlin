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
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.awaitToolResponse
import com.google.adk.kt.testing.connectionDrop
import com.google.adk.kt.testing.goAway
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.nonBlockingTool
import com.google.adk.kt.testing.storedEvents
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Transcription
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Covers handing a live conversation from one agent to another.
 *
 * If a transfer ran inside function-response handling rather than around the connection, the parent
 * would see the child's events and answer the child's tool calls on its own connection too. These
 * tests therefore check what each connection received, not only what the caller saw; tests using
 * `runTest` skip the handover's one-second delay in virtual time.
 */
class LlmAgentLiveTransferTest {

  /** Screening the heard input "hi" suspends between queuing a turn's answers and its handover. */
  private val slowScreening = BeforeModelCallback { _, request ->
    if (request.contents.any { c -> c.parts.any { it.text == "hi" } }) delay(100.milliseconds)
    CallbackChoice.Continue(request)
  }

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
  fun runLive_transferWhileAToolStreams_recordsTheParentsStreamAsStopped(): Unit = runTest {
    val stopped =
      mapOf(
        "status" to
          "monitor stopped streaming when its live session ended; no more updates will arrive."
      )
    val childModel =
      DummyLiveModel(
        listOf(Respond(LlmResponse(content = modelMessage("sunny"), partial = false)), EndStream)
      )
    val monitor = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
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
    val runner = InMemoryRunner(agent = parent)

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    parentModel.connections.forEach { it.assertAllWaitsMet() }
    val records =
      runner.sessionService
        .getSession(SessionKey(runner.appName, "u", "s"))
        ?.events
        .orEmpty()
        .filter { event ->
          event.functionResponses().any { it.id == "c1" && it.response == stopped }
        }
    assertThat(records.map { it.author }).containsExactly("router")
  }

  @Test
  fun runLive_transferWithToolEventsStillQueued_storesThemBeforeTheStoppedAnswers(): Unit =
    runTest {
      val childModel =
        DummyLiveModel(
          listOf(Respond(LlmResponse(content = modelMessage("sunny"), partial = false)))
        )
      val monitor =
        DummyTool(name = "monitor") { _, args ->
          flow {
            emit(Event(content = Content(Role.MODEL, listOf(Part(text = "watching ${args["n"]}")))))
            awaitCancellation()
          }
        }
      val calls =
        (1..6).map { FunctionCall(name = "monitor", args = mapOf("n" to it), id = "c$it") }
      val parentModel =
        DummyLiveModel(
          listOf(
            Respond(
              LlmResponse(content = Content(Role.MODEL, calls.map { Part(functionCall = it) }))
            ),
            AwaitSent("the pending answers") {
              it is ContentInput &&
                it.content.parts.count { part -> part.functionResponse != null } == 6
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
      val runner = InMemoryRunner(agent = parent)

      // A slow caller leaves tool events queued when the transfer stops the streams.
      val unused =
        runner
          .runLive("u", "s", queue)
          .onEach { event ->
            if (event.branch?.startsWith("monitor@") == true) delay(3000.milliseconds)
            if (event.content?.parts?.any { it.text == "sunny" } == true) queue.close()
          }
          .toList()

      val events =
        checkNotNull(runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))).events
      val toolEventsAt = events.indices.filter { events[it].branch?.startsWith("monitor@") == true }
      val stopped =
        mapOf(
          "status" to
            "monitor stopped streaming when its live session ended; no more updates will arrive."
        )
      val stoppedAnswersAt =
        events.indices.filter { index ->
          events[index].functionResponses().any { it.response == stopped }
        }
      assertThat(toolEventsAt).hasSize(6)
      assertThat(stoppedAnswersAt).hasSize(6)
      assertThat(toolEventsAt.max()).isLessThan(stoppedAnswersAt.min())
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
      inputTranscription("hi", finished = true)
        .copy(
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
        beforeModelCallbacks = listOf(slowScreening),
      )

    val unused = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

    assertWithMessage("a stream answered alongside a transfer should never start")
      .that(started)
      .isFalse()
    val heard =
      childModel.connections
        .flatMap { it.sent }
        .filterIsInstance<ContentInput>()
        .filter { input -> input.content.parts.any { it.functionResponse?.id == "c1" } }
    assertWithMessage("the child heard the never-started stream").that(heard).isEmpty()
  }

  @Test
  fun runLive_backgroundCallAnswersWhileARestartedSessionTransfers_streamNeverStarts() {
    runBlocking(BackgroundToolCalls) {
      // Called before the restart; answers with a stream once the new session transfers.
      val transferSeen = CompletableDeferred<Unit>()
      var newsStarted = false
      val news =
        nonBlockingTool("news") {
          transferSeen.await()
          flow<String> {
            newsStarted = true
            awaitCancellation()
          }
        }
      val transferAfterSpeech =
        inputTranscription("hi", finished = true)
          .copy(content = modelTransferToAgentResponse("weather_agent").content)
      // A go-away with no handle restarts the turn on a new session.
      val parentModel =
        DummyLiveModel.perConnection(
          listOf(
            listOf(Respond(modelFunctionCallResponse("news", id = "c1")), Respond(goAway())),
            listOf(Respond(transferAfterSpeech), EndStream),
          )
        )
      val child =
        LlmAgent(
          name = "weather_agent",
          model = DummyLiveModel(listOf(wholeText("sunny"), EndStream)),
        )
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(news),
          beforeModelCallbacks = listOf(slowScreening),
        )

      val events =
        InMemoryRunner(agent = parent)
          .runLive("u", "s", LiveRequestQueue())
          .onEach { if (it.actions.transferToAgent != null) transferSeen.complete(Unit) }
          .toList()

      val transferAt = events.indexOfFirst { it.actions.transferToAgent != null }
      val answerAt = events.indexOfFirst { e -> e.functionResponses().any { it.id == "c1" } }
      assertWithMessage("the background answer should arrive during the handover")
        .that(answerAt)
        .isGreaterThan(transferAt)
      assertWithMessage("a stream answered while the restarted session hands over should not start")
        .that(newsStarted)
        .isFalse()
    }
  }

  @Test
  fun runLive_transferWithToolEventsStillQueued_deliversThemBeforeTheChildsTurn(): Unit = runTest {
    val childModel =
      DummyLiveModel(listOf(Respond(LlmResponse(content = modelMessage("sunny"), partial = false))))
    val monitor =
      DummyTool(name = "monitor") { _, args ->
        flow {
          emit(Event(content = Content(Role.MODEL, listOf(Part(text = "watching ${args["n"]}")))))
          awaitCancellation()
        }
      }
    // Distinct arguments, so no call counts as a repeat of another.
    val calls =
      (1..6).map { n -> FunctionCall(name = "monitor", args = mapOf("n" to n), id = "c$n") }
    val parentModel =
      DummyLiveModel(
        listOf(
          Respond(
            LlmResponse(content = Content(Role.MODEL, calls.map { Part(functionCall = it) }))
          ),
          AwaitSent("the pending answers") {
            it is ContentInput && it.content.parts.count { p -> p.functionResponse != null } == 6
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
          // A slow caller leaves tool events still queued when the transfer starts.
          if (event.branch?.startsWith("monitor@") == true) delay(3000.milliseconds)
          if (event.content?.parts?.any { it.text == "sunny" } == true) queue.close()
        }
        .toList()

    val sunny = events.indexOfFirst { e -> e.content?.parts?.any { it.text == "sunny" } == true }
    val toolEvents = events.indices.filter { events[it].branch?.startsWith("monitor@") == true }
    assertWithMessage("every tool event should reach the caller").that(toolEvents).hasSize(6)
    assertWithMessage("a parent's tool event came after the child's turn")
      .that(toolEvents.max())
      .isLessThan(sunny)
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

    assertWithMessage("the stream should stop before the child connects")
      .that(childConnectionsWhenStopped)
      .isEqualTo(0)
    val heard = childModel.connections.flatMap { it.sent }.filterIsInstance<ContentInput>()
    assertWithMessage("the child should get no answer for the parent's call")
      .that(heard.none { it.content.parts.any { p -> p.functionResponse?.id == "c1" } })
      .isTrue()
  }

  @Test
  fun runLive_transferWhileBackgroundCallsRun_answersEachCallOnce() {
    runBlocking(BackgroundToolCalls) {
      val firstHandled = CompletableDeferred<Unit>()
      val tools =
        listOf(
          nonBlockingTool("first") { "done" },
          nonBlockingTool("plain") {
            firstHandled.await()
            "done"
          },
          nonBlockingTool("ticker") {
            firstHandled.await()
            flow<String> { awaitCancellation() }
          },
          nonBlockingTool("hang") { awaitCancellation() },
          nonBlockingTool("held") { flow<String> { awaitCancellation() } },
        )
      // Stops held after its stream is registered but before its pending answer is stored.
      val hold = AfterToolCallback { _, tool, _, result ->
        if (tool.name == "held") awaitCancellation()
        result
      }
      val calls = tools.map { Part(functionCall = FunctionCall(it.name, id = it.name)) }
      val parentModel =
        DummyLiveModel(
          listOf(
            Respond(LlmResponse(content = Content(Role.MODEL, calls))),
            Respond(modelTransferToAgentResponse("weather_agent")),
            EndStream,
          )
        )
      val child =
        LlmAgent(
          name = "weather_agent",
          model = DummyLiveModel(listOf(wholeText("sunny"), EndStream)),
        )
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = tools,
          afterToolCallbacks = listOf(hold),
        )
      val runner = InMemoryRunner(agent = parent)

      // A slow caller leaves the later answers queued when the transfer stops the calls.
      val unused =
        runner
          .runLive("u", "s", LiveRequestQueue())
          .onEach { event ->
            if (event.functionResponses().any { it.id == "first" }) {
              firstHandled.complete(Unit)
              delay(300.milliseconds)
            }
          }
          .toList()

      // Each call is answered once: with its own answer, its stream's final one, or a stopped one.
      val names = tools.map { it.name }
      val answers =
        runner.storedEvents().flatMap { it.functionResponses() }.filter { it.id in names }
      assertThat(answers.map { it.id to it.response.values.single() })
        .containsExactly(
          "first" to "done",
          "plain" to "done",
          "ticker" to "The function is running asynchronously and the results are pending.",
          "ticker" to
            "ticker stopped streaming when its live session ended; no more updates will arrive.",
          "hang" to "hang was stopped when its live session ended; its result will not arrive.",
          "held" to "held was stopped when its live session ended; its result will not arrive.",
        )
    }
  }

  /**
   * An agent named [name] that says "sunny" over its own connection, and that connection's model.
   */
  private fun talkingChild(name: String): Pair<LlmAgent, DummyLiveModel> {
    val model = DummyLiveModel(listOf(wholeText("sunny"), EndStream))
    return LlmAgent(name = name, model = model) to model
  }

  @Test
  fun runLive_backgroundAnswerTransfers_handsOverToTheTarget() {
    runTest(BackgroundToolCalls) {
      val (child, childModel) = talkingChild("weather_agent")
      val parentModel =
        DummyLiveModel(
          listOf(
            Respond(modelFunctionCallResponse("route", id = "r1")),
            awaitToolResponse(),
            // Read only by a parent that kept talking after the transfer.
            wholeText("late"),
            EndStream,
          )
        )
      val route = nonBlockingTool("route", transferTo = "weather_agent") { "routed" }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )
      val runner = InMemoryRunner(agent = parent)

      val events = runner.runLive("u", "s", LiveRequestQueue()).toList()

      assertThat(childModel.connections).hasSize(1)
      assertThat(events.filter { it.author == "weather_agent" }.map { it.contentText() })
        .contains("sunny")
      assertThat(events.map { it.contentText() }).doesNotContain("late")
      // The transfer's answer still reached the parent's model before it handed over.
      assertThat(toolResponseNames(parentModel)).containsExactly("route")
      // Its write ended the wait for it, which would otherwise last five seconds.
      assertThat(testScheduler.currentTime).isLessThan(5_000L)
      val transfers = runner.storedEvents().filter { it.actions.transferToAgent != null }
      assertThat(transfers.flatMap { e -> e.functionResponses().map { it.id } })
        .containsExactly("r1")
    }
  }

  @Test
  fun runLive_backgroundTransferWhoseAnswerWriteDrops_handsOverWithoutWaiting() {
    runTest(BackgroundToolCalls) {
      val (child, childModel) = talkingChild("weather_agent")
      val parentModel =
        DummyLiveModel.perConnection(
          listOf(
            listOf(Respond(modelFunctionCallResponse("route", id = "r1")), awaitToolResponse())
          ),
          onSend = { _, message ->
            val parts = (message as? ContentInput)?.content?.parts.orEmpty()
            if (parts.any { it.functionResponse != null }) throw connectionDrop()
          },
        )
      val route = nonBlockingTool("route", transferTo = "weather_agent") { "routed" }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )

      val unused = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

      assertThat(parentModel.connections).hasSize(1)
      assertThat(childModel.connections).hasSize(1)
      // Nothing waits for a write that can no longer happen.
      assertThat(testScheduler.currentTime).isLessThan(5_000L)
    }
  }

  @Test
  fun runLive_backgroundTransferBehindADroppedWrite_handsOverWithoutWaiting() {
    runTest(BackgroundToolCalls) {
      val (child, childModel) = talkingChild("weather_agent")
      val queue = LiveRequestQueue()
      val parentModel =
        DummyLiveModel.perConnection(
          listOf(
            listOf(Respond(modelFunctionCallResponse("route", id = "r1")), awaitToolResponse())
          ),
          onSend = { _, message ->
            val parts = (message as? ContentInput)?.content?.parts.orEmpty()
            if (parts.any { it.text == "hold on" }) {
              delay(100.milliseconds)
              throw connectionDrop()
            }
          },
        )
      val route =
        nonBlockingTool("route", transferTo = "weather_agent") {
          // Queued first, so the answer waits behind a write that then drops the connection.
          queue.sendContent(Content(Role.USER, listOf(Part(text = "hold on"))))
          "routed"
        }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )

      val unused = InMemoryRunner(agent = parent).runLive("u", "s", queue).toList()

      assertThat(parentModel.connections).hasSize(1)
      assertThat(childModel.connections).hasSize(1)
      // The parent never wrote the answer, and the child must not get it instead.
      assertThat(toolResponseNames(childModel)).isEmpty()
      // The drop ended the wait for the answer's write, which would otherwise last five seconds.
      assertThat(testScheduler.currentTime).isLessThan(5_000L)
    }
  }

  @Test
  fun runLive_backgroundTransferBehindASlowWrite_stillSendsItsAnswer() {
    runTest(BackgroundToolCalls) {
      val (child, childModel) = talkingChild("weather_agent")
      val queue = LiveRequestQueue()
      val parentModel =
        DummyLiveModel.perConnection(
          listOf(
            listOf(Respond(modelFunctionCallResponse("route", id = "r1")), awaitToolResponse())
          ),
          onSend = { _, message ->
            val parts = (message as? ContentInput)?.content?.parts.orEmpty()
            if (parts.any { it.text == "hold on" }) delay(100.milliseconds)
          },
        )
      val route =
        nonBlockingTool("route", transferTo = "weather_agent") {
          // Queued first, so the answer is still queued when the other calls are stopped.
          queue.sendContent(Content(Role.USER, listOf(Part(text = "hold on"))))
          "routed"
        }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )

      val unused = InMemoryRunner(agent = parent).runLive("u", "s", queue).toList()

      assertThat(childModel.connections).hasSize(1)
      assertThat(toolResponseNames(parentModel)).containsExactly("route")
      assertThat(toolResponseNames(childModel)).isEmpty()
      assertThat(testScheduler.currentTime).isLessThan(5_000L)
    }
  }

  @Test
  fun runLive_backgroundTransferWhileTheModelKeepsTalking_stillSendsItsAnswer() {
    runTest(BackgroundToolCalls) {
      val (child, childModel) = talkingChild("weather_agent")
      val queue = LiveRequestQueue()
      val parentModel =
        DummyLiveModel.perConnection(
          listOf(
            listOf(
              Respond(modelFunctionCallResponse("route", id = "r1")),
              AwaitSent("hold on") { message ->
                (message as? ContentInput)?.content?.parts.orEmpty().any { it.text == "hold on" }
              },
              wholeText("late"),
              EndStream,
            )
          ),
          onSend = { _, message ->
            val parts = (message as? ContentInput)?.content?.parts.orEmpty()
            if (parts.any { it.text == "hold on" }) delay(100.milliseconds)
          },
        )
      val route =
        nonBlockingTool("route", transferTo = "weather_agent") {
          queue.sendContent(Content(Role.USER, listOf(Part(text = "hold on"))))
          "routed"
        }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )

      // Holding the answer's event lets the reading end on its own, so the stop after it runs.
      val unused =
        InMemoryRunner(agent = parent)
          .runLive("u", "s", queue)
          .onEach { event ->
            if (event.functionResponses().any { it.id == "r1" }) delay(10.milliseconds)
          }
          .toList()

      assertThat(childModel.connections).hasSize(1)
      assertThat(toolResponseNames(parentModel)).containsExactly("route")
      assertThat(testScheduler.currentTime).isLessThan(5_000L)
    }
  }

  @Test
  fun runLive_backgroundTransferDuringAGoAwayDrain_handsOverWithoutWaiting() {
    runTest(BackgroundToolCalls) {
      // The drain stopped the parent's writes, so its answer goes out on neither connection.
      val (child, childModel) = talkingChild("weather_agent")
      val parentModel =
        DummyLiveModel(
          listOf(
            Respond(modelFunctionCallResponse("route", id = "r1")),
            Respond(LlmResponse(content = modelMessage("checking"))),
            Respond(goAway(timeLeft = 5.seconds)),
            awaitToolResponse(),
          )
        )
      val route =
        nonBlockingTool("route", transferTo = "weather_agent") {
          delay(100.milliseconds)
          "routed"
        }
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(child),
          tools = listOf(route),
        )

      val events = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

      assertThat(parentModel.connections).hasSize(1)
      assertThat(toolResponseNames(parentModel)).isEmpty()
      assertThat(childModel.connections).hasSize(1)
      assertThat(toolResponseNames(childModel)).isEmpty()
      assertThat(events.filter { it.author == "weather_agent" }.map { it.contentText() })
        .contains("sunny")
      // The handover went at the answer, with no wait or pause inside the drain's four seconds.
      assertThat(testScheduler.currentTime).isLessThan(1_000L)
    }
  }

  @Test
  fun runLive_twoBackgroundAnswersTransfer_handsOverToTheFirstOnly() {
    runTest(BackgroundToolCalls) {
      val (weather, weatherModel) = talkingChild("weather_agent")
      val (news, newsModel) = talkingChild("news_agent")
      val tools =
        listOf(
          nonBlockingTool("route_weather", transferTo = "weather_agent") { "routed" },
          nonBlockingTool("route_news", transferTo = "news_agent") { "routed" },
        )
      val calls = tools.map { Part(functionCall = FunctionCall(it.name, id = it.name)) }
      val parentModel =
        DummyLiveModel(
          listOf(
            Respond(LlmResponse(content = Content(Role.MODEL, calls))),
            awaitToolResponse(),
            EndStream,
          )
        )
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(weather, news),
          tools = tools,
        )
      val runner = InMemoryRunner(agent = parent)

      val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

      assertThat(weatherModel.connections).hasSize(1)
      assertThat(newsModel.connections).isEmpty()
      // The losing answer is kept, without a transfer that never happened, and no model hears it.
      val losing =
        runner.storedEvents().single { e -> e.functionResponses().any { it.id == "route_news" } }
      assertThat(losing.actions.transferToAgent).isNull()
      assertThat(losing.functionResponses().single().response)
        .isEqualTo(mapOf("result" to "routed"))
      assertThat(toolResponseNames(parentModel)).containsExactly("route_weather")
    }
  }

  @Test
  fun runLive_backgroundAnswerTransfersAfterTheModelsTransfer_keepsTheModelsTarget() {
    runTest(BackgroundToolCalls) {
      val (weather, weatherModel) = talkingChild("weather_agent")
      val (news, newsModel) = talkingChild("news_agent")
      val modelTransferSeen = CompletableDeferred<Unit>()
      val route =
        nonBlockingTool("route", transferTo = "news_agent") {
          modelTransferSeen.await()
          "routed"
        }
      val calls =
        Content(
          Role.MODEL,
          listOf(
            Part(functionCall = FunctionCall("route", id = "r1")),
            Part(
              functionCall =
                FunctionCall(
                  TRANSFER_TO_AGENT_TOOL_NAME,
                  mapOf("agent_name" to "weather_agent"),
                  id = "t1",
                )
            ),
          ),
        )
      val parentModel = DummyLiveModel(listOf(Respond(LlmResponse(content = calls)), EndStream))
      val parent =
        LlmAgent(
          name = "router",
          model = parentModel,
          subAgents = listOf(weather, news),
          tools = listOf(route),
        )
      val runner = InMemoryRunner(agent = parent)

      val unused =
        runner
          .runLive("u", "s", LiveRequestQueue())
          .onEach { event ->
            if (event.functionResponses().any { it.id == "t1" }) modelTransferSeen.complete(Unit)
          }
          .toList()

      assertThat(weatherModel.connections).hasSize(1)
      assertThat(newsModel.connections).isEmpty()
      val losing = runner.storedEvents().single { e -> e.functionResponses().any { it.id == "r1" } }
      assertThat(losing.actions.transferToAgent).isNull()
      assertThat(losing.functionResponses().single().response)
        .isEqualTo(mapOf("result" to "routed"))
      assertThat(toolResponseNames(parentModel)).containsExactly(TRANSFER_TO_AGENT_TOOL_NAME)
    }
  }

  @Test
  fun runLive_callbackTransferBeforeAToolTransfer_handsOverToTheFirst(): Unit = runTest {
    val (weather, weatherModel) = talkingChild("weather_agent")
    val (news, newsModel) = talkingChild("news_agent")
    // Screening the speech beside the call hands over on the call's event, before its answer.
    val handOff = AfterModelCallback { context, response ->
      context.actions.transferToAgent = "weather_agent"
      response
    }
    val speakingCall =
      modelTransferToAgentResponse("news_agent")
        .copy(outputTranscription = Transcription(text = "Transferring.", finished = false))
    val parentModel = DummyLiveModel(listOf(Respond(speakingCall), EndStream))
    val parent =
      LlmAgent(
        name = "router",
        model = parentModel,
        subAgents = listOf(weather, news),
        afterModelCallbacks = listOf(handOff),
      )
    val runner = InMemoryRunner(agent = parent)

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertThat(weatherModel.connections).hasSize(1)
    assertThat(newsModel.connections).isEmpty()
    val transfers = runner.storedEvents().mapNotNull { it.actions.transferToAgent }
    assertThat(transfers).containsExactly("weather_agent")
    // The losing transfer's answer reaches no model.
    assertThat(toolResponseNames(parentModel)).isEmpty()
  }
}
