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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionNotFoundException
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.outputTranscriptionStep
import com.google.adk.kt.testing.simpleAudioTurn
import com.google.adk.kt.testing.storedEvents
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.RetryConfig
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.parallelNode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.genai.kotlin.GenAiApiException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Covers the live entry point on the runner and the agent contract underneath it. */
class RunLiveTest {

  /** Records the context it was handed, and emits one event per queued request it observes. */
  private class LiveEchoAgent : BaseAgent("live-echo", "test agent") {
    var seenContext: InvocationContext? = null
      private set

    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}

    override fun runLiveImpl(context: InvocationContext): Flow<Event> = flow {
      seenContext = context
      val queue = context.liveRequestQueue ?: return@flow
      while (true) {
        val request = queue.receiveRequest() ?: break
        val text = (request.input as? ContentInput)?.content?.parts?.firstOrNull()?.text ?: continue
        emit(
          Event(
            invocationId = context.invocationId,
            author = "model",
            content = modelMessage("echo: $text"),
          )
        )
      }
    }
  }

  /** Fails its live run with a cancellation of its own, which is not a failure of the run. */
  private class CancellingAgent : BaseAgent("cancelling", "test agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}

    override fun runLiveImpl(context: InvocationContext): Flow<Event> = flow {
      throw CancellationException("stopped by the agent")
    }
  }

  /** A [Runner] that leaves [Runner.runLive] to the interface default. */
  private class TurnOnlyRunner(private val delegate: InMemoryRunner) : Runner {
    override val appName = delegate.appName
    override val agent = delegate.agent
    override val sessionService = delegate.sessionService
    override val artifactService = delegate.artifactService
    override val memoryService = delegate.memoryService
    override val pluginManager = delegate.pluginManager
    override val resumabilityConfig = delegate.resumabilityConfig

    @Suppress("AvoidNullableCollections") // Nullability is fixed by the overridden signature.
    override fun runAsync(
      userId: String,
      sessionId: String,
      invocationId: String?,
      newMessage: Content?,
      stateDelta: Map<String, Any>?,
      runConfig: RunConfig?,
    ): Flow<Event> =
      delegate.runAsync(userId, sessionId, invocationId, newMessage, stateDelta, runConfig)

    override fun run(
      userId: String,
      sessionId: String,
      newMessage: Content,
      runConfig: RunConfig?,
    ): Iterator<Event> = delegate.run(userId, sessionId, newMessage, runConfig)

    override fun close() {
      delegate.close()
    }
  }

  /** A model that does not override `Model.connect`, so connecting fails naming [name]. */
  private fun turnOnlyModel(name: String = "turn-only-model") = DummyModel(name)

  /** A plugin that answers before the run with [breakWith], if set, and rewrites each event. */
  private fun plugin(
    breakWith: Content? = null,
    recordRunError: (Throwable) -> Unit = {},
    recordAfterRun: () -> Unit = {},
    rewrite: (Event) -> Event = { it },
  ): Plugin =
    object : Plugin {
      override val name = "test-plugin"

      override suspend fun beforeRun(
        invocationContext: InvocationContext
      ): CallbackChoice<Unit, Content> =
        breakWith?.let { CallbackChoice.Break(it) } ?: CallbackChoice.Continue(Unit)

      override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event =
        rewrite(event)

      override suspend fun afterRun(invocationContext: InvocationContext) {
        recordAfterRun()
      }

      override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
        recordRunError(error)
      }
    }

  private fun audioReference() =
    modelMessage(
      Part(fileData = FileData(fileUri = "artifact://live/audio.pcm", mimeType = "audio/pcm"))
    )

  /** Replaces an event's inline audio with an artifact reference, as a scrubbing plugin might. */
  private fun withAudioAsReference(event: Event): Event =
    if (event.content?.parts?.any { it.inlineData != null } == true) {
      event.copy(content = audioReference())
    } else {
      event
    }

  /** Emits a fixed list of events, so a test can choose exactly what a run produces. */
  private class ScriptedAgent(private val events: List<Event>) :
    BaseAgent("scripted", "test agent") {
    private fun replayEvents(context: InvocationContext): Flow<Event> = flow {
      for (event in events) emit(event.copy(invocationId = context.invocationId))
    }

    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = replayEvents(context)

    override fun runLiveImpl(context: InvocationContext): Flow<Event> = replayEvents(context)
  }

  private fun modelEvent(content: Content, partial: Boolean = false) =
    Event(author = "model", content = content, partial = partial)

  private fun audioContent() =
    Content(
      role = "model",
      parts =
        listOf(Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(8)))),
    )

  /**
   * The messages of the [IllegalStateException]s suppressed in [error] or its causes. Coroutines
   * may rethrow a copy that holds the original as its cause, or attach a debug trace of their own.
   */
  private fun suppressedMessages(error: Throwable): List<String?> =
    generateSequence(error) { it.cause }
      .flatMap { it.suppressedExceptions }
      .filterIsInstance<IllegalStateException>()
      .map { it.message }
      .toList()

  /** Runs a live turn to completion, returning what the caller saw streamed. */
  private suspend fun runLiveWith(
    runner: InMemoryRunner,
    runConfig: RunConfig? = null,
  ): List<Event> {
    val queue = LiveRequestQueue()
    queue.close()
    return runner.runLive("u", "s", queue, runConfig).toList()
  }

  private fun RunConfig.off() =
    copy(live = live.copy(inputAudioTranscription = null, outputAudioTranscription = null))

  /** Runs a live turn under [runConfig] on a root with [subAgents], returning its live config. */
  private suspend fun liveConnectConfigFor(
    runConfig: RunConfig,
    subAgents: List<BaseAgent> =
      listOf(LlmAgent(name = "child", model = turnOnlyModel("child-model"))),
  ): LiveConnectConfig {
    val model = DummyLiveModel(simpleAudioTurn())
    val root = LlmAgent(name = "root", model = model, subAgents = subAgents)
    val unused = runLiveWith(InMemoryRunner(agent = root), runConfig)
    return model.connectRequests.single().liveConnectConfig
  }

  /**
   * Records each event the runner asks to append once [beforeAppend] returns, before the delegate
   * can filter it out. Also records every append and flush in call order in `calls`. A flush throws
   * [flushFailure] when one is set.
   */
  private class AppendRecordingSessionService(
    private val beforeAppend: suspend (Event) -> Unit = {},
    private val flushFailure: Exception? = null,
    private val beforeFlush: suspend () -> Unit = {},
    private val delegate: SessionService = InMemorySessionService(),
  ) : SessionService by delegate {
    val appended = mutableListOf<Event>()
    val calls = mutableListOf<String>()

    override suspend fun appendEvent(session: Session, event: Event): Event {
      beforeAppend(event)
      appended += event
      calls += "append"
      return delegate.appendEvent(session, event)
    }

    override suspend fun flush(key: SessionKey?) {
      calls += "flush"
      beforeFlush()
      if (flushFailure != null) throw flushFailure
    }
  }

  @Test
  fun runLive_deliversQueuedRequestsToTheAgent(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()

    queue.sendContent(userMessage("one"))
    queue.sendContent(userMessage("two"))
    queue.close()

    val events = runner.runLive("u", "s", queue).toList()

    assertThat(events.map { it.content?.parts?.single()?.text })
      .isEqualTo(listOf("echo: one", "echo: two"))
  }

  @Test
  fun runLive_putsTheQueueOnTheInvocationContext(): Unit = runBlocking {
    // The queue carries the caller's input to the agent; a live run has no initial message.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()
    queue.close()

    runner.runLive("u", "s", queue).toList()

    assertThat(agent.seenContext?.liveRequestQueue).isSameInstanceAs(queue)
  }

  @Test
  fun runLive_closingTheQueue_endsTheRun(): Unit = runBlocking {
    // If the run ignored the closed queue, toList() would never return and hang the test.
    val runner = InMemoryRunner(agent = LiveEchoAgent())
    val queue = LiveRequestQueue()
    queue.close()

    assertThat(runner.runLive("u", "s", queue).toList()).isEmpty()
  }

  @Test
  fun runLive_createsTheSessionWhenItDoesNotExist(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()
    queue.close()

    runner.runLive("u", "brand-new-session", queue).toList()

    assertThat(agent.seenContext?.session).isNotNull()
    assertThat(agent.seenContext?.session?.key?.id).isEqualTo("brand-new-session")
  }

  @Test
  fun runLive_autoCreateSessionOff_missingSession_throwsWithoutRunningOrCreatingIt(): Unit =
    runBlocking {
      val agent = LiveEchoAgent()
      val runner = InMemoryRunner(agent = agent, autoCreateSession = false)
      val queue = LiveRequestQueue()
      queue.close()

      assertFailsWith<SessionNotFoundException> { runner.runLive("u", "missing", queue).toList() }

      assertThat(agent.seenContext).isNull()
      assertThat(runner.sessionService.getSession(SessionKey(runner.appName, "u", "missing")))
        .isNull()
    }

  @Test
  fun runLive_autoCreateSessionOff_existingSession_runs(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent, autoCreateSession = false)
    val unused = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("one"))
    queue.close()

    val events = runner.runLive("u", "s", queue).toList()

    assertThat(events.map { it.content?.parts?.single()?.text }).containsExactly("echo: one")
  }

  @Test
  fun runLive_noRunConfig_requestsBothTranscriptions(): Unit = runBlocking {
    // As in ADK Python, a run without a config gets RunConfig's defaults, which transcribe both.
    val model = DummyLiveModel(simpleAudioTurn())
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))

    val unused = runLiveWith(runner)

    val config = model.connectRequests.single().liveConnectConfig
    assertThat(config.inputAudioTranscription).isEqualTo(AudioTranscriptionConfig())
    assertThat(config.outputAudioTranscription).isEqualTo(AudioTranscriptionConfig())
  }

  @Test
  fun runLive_multiAgentRootWithTranscriptionsOff_keepsThemOff(): Unit = runBlocking {
    // As in ADK Python, a multi-agent root only warns; the caller's choice reaches the model.
    val config =
      liveConnectConfigFor(
        RunConfig(live = LiveRunConfig(responseModalities = listOf(Modality.AUDIO))).off()
      )
    assertThat(config.inputAudioTranscription).isNull()
    assertThat(config.outputAudioTranscription).isNull()
  }

  @Test
  fun runLive_multiAgentRootWithOutputTranscriptionOff_keepsItOff(): Unit = runBlocking {
    val config =
      liveConnectConfigFor(RunConfig(live = LiveRunConfig(outputAudioTranscription = null)))
    assertThat(config.inputAudioTranscription).isEqualTo(AudioTranscriptionConfig())
    assertThat(config.outputAudioTranscription).isNull()
  }

  @Test
  fun runLive_multiAgentRootWithItsOwnTranscriptionConfig_keepsIt(): Unit = runBlocking {
    val own = AudioTranscriptionConfig(languageCodes = listOf("en-US"))
    val config =
      liveConnectConfigFor(
        RunConfig(
          live =
            LiveRunConfig(
              responseModalities = listOf(Modality.AUDIO),
              inputAudioTranscription = own,
              outputAudioTranscription = own,
            )
        )
      )
    assertThat(config.inputAudioTranscription).isEqualTo(own)
    assertThat(config.outputAudioTranscription).isEqualTo(own)
  }

  @Test
  fun runLive_singleAgentRootWithTranscriptionsOff_keepsThemOff(): Unit = runBlocking {
    val config =
      liveConnectConfigFor(
        RunConfig(live = LiveRunConfig(responseModalities = listOf(Modality.AUDIO))).off(),
        subAgents = emptyList(),
      )
    assertThat(config.inputAudioTranscription).isNull()
    assertThat(config.outputAudioTranscription).isNull()
  }

  @Test
  fun runLive_newConnection_replaysStoredTranscriptions(): Unit = runBlocking {
    // Only transcriptions record a spoken turn, so a new connection must get them as history.
    val model =
      DummyLiveModel(
        listOf(
          Respond(inputTranscription("My name is Ada.", finished = true)),
          outputTranscriptionStep("Hi Ada!", finished = true),
          turnCompleteStep(),
        )
      )
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))

    repeat(2) {
      val queue = LiveRequestQueue()
      val unused =
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
    }

    assertThat(model.connections.size).isEqualTo(2)
    assertWithMessage(
        "the second connection should open with the first run's spoken turns as history"
      )
      .that(model.connections[1].sent.filterIsInstance<List<*>>().singleOrNull())
      .isEqualTo(listOf(userMessage("My name is Ada."), modelMessage("Hi Ada!")))
  }

  @Test
  fun runLive_doesNotPersistVideoOrImageFrames(): Unit = runBlocking {
    val video =
      Content(
        role = "model",
        parts = listOf(Part(inlineData = Blob(mimeType = "video/mp4", data = ByteArray(8)))),
      )
    val image =
      Content(
        role = "model",
        parts = listOf(Part(inlineData = Blob(mimeType = "image/jpeg", data = ByteArray(8)))),
      )
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(video), modelEvent(image))))

    val streamed = runLiveWith(runner)

    assertWithMessage("the caller still receives the frames").that(streamed.size).isEqualTo(2)
    assertWithMessage("video and image must not reach the session")
      .that(runner.storedEvents())
      .isEmpty()
  }

  @Test
  fun runAsync_leavesTheLiveQueueUnset(): Unit = runBlocking {
    // A turn-based run must not look live to the agent.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)

    runner.runAsync("u", "s", newMessage = userMessage("hello")).toList()

    assertThat(agent.seenContext?.liveRequestQueue).isNull()
  }

  @Test
  fun runLive_doesNotPersistModelAudio(): Unit = runBlocking {
    // Only a saveLiveBlob artifact reference is stored; per-chunk audio would bloat the session.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioContent()))))

    val streamed = runLiveWith(runner)

    assertWithMessage("the caller still receives the audio").that(streamed.size).isEqualTo(1)
    assertWithMessage("audio must not reach the session").that(runner.storedEvents()).isEmpty()
  }

  @Test
  fun runLive_persistsNonMediaEvents(): Unit = runBlocking {
    // The media rule is narrow: control events and text are the conversation and are kept.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))))

    val unused = runLiveWith(runner)

    assertThat(runner.storedEvents().map { it.content?.parts?.single()?.text })
      .isEqualTo(listOf("hello"))
  }

  @Test
  fun runLive_persistsMediaHeldAsAnArtifactReference(): Unit = runBlocking {
    // An artifact reference has no inline data, so it is stored like any other event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioReference()))))

    val unused = runLiveWith(runner)

    assertWithMessage("an artifact reference is not inline media")
      .that(runner.storedEvents().size)
      .isEqualTo(1)
  }

  @Test
  fun runLive_doesNotPersistUppercaseMimeMedia(): Unit = runBlocking {
    // MIME types are case-insensitive; ADK Python lowercases them before this check too.
    val audio = modelMessage(Part(inlineData = Blob(mimeType = "AUDIO/PCM", data = ByteArray(8))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audio))))

    val streamed = runLiveWith(runner)

    assertWithMessage("the caller still receives the audio").that(streamed.size).isEqualTo(1)
    assertWithMessage("audio must not reach the session").that(runner.storedEvents()).isEmpty()
  }

  @Test
  fun runLive_persistsNonMediaInlineData(): Unit = runBlocking {
    // Only audio, video and image are live media; other inline data is part of the conversation.
    val pdf =
      modelMessage(Part(inlineData = Blob(mimeType = "application/pdf", data = ByteArray(4))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(pdf))))

    val unused = runLiveWith(runner)

    assertWithMessage("non-media inline data is kept").that(runner.storedEvents().size).isEqualTo(1)
  }

  @Test
  fun runLive_onEventPluginSwapsInlineAudioForAReference_storesTheReference(): Unit = runBlocking {
    // Storage is decided on the event the plugins return, as in ADK Python.
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(audioContent()))),
        plugins = listOf(plugin(rewrite = ::withAudioAsReference)),
      )

    val unused = runLiveWith(runner)

    assertThat(runner.storedEvents().map { it.content?.parts?.single()?.fileData?.fileUri })
      .isEqualTo(listOf("artifact://live/audio.pcm"))
  }

  @Test
  fun runLive_onEventPluginAddsInlineAudio_doesNotStoreIt(): Unit = runBlocking {
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))),
        plugins = listOf(plugin(rewrite = { it.copy(content = audioContent()) })),
      )

    val streamed = runLiveWith(runner)

    assertWithMessage("the caller still receives the rewritten event")
      .that(streamed.size)
      .isEqualTo(1)
    assertWithMessage("audio must not reach the session").that(runner.storedEvents()).isEmpty()
  }

  @Test
  fun runLive_beforeRunAnswersWithInlineAudio_emitsItWithoutStoringIt(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent, plugins = listOf(plugin(breakWith = audioContent())))

    val streamed = runLiveWith(runner)

    assertWithMessage("the caller receives the plugin's answer").that(streamed.size).isEqualTo(1)
    assertWithMessage("audio must not reach the session").that(runner.storedEvents()).isEmpty()
    assertWithMessage("the agent must not run once beforeRun has answered")
      .that(agent.seenContext)
      .isNull()
  }

  @Test
  fun runLive_beforeRunAnswerRewrittenToAReference_storesTheReference(): Unit = runBlocking {
    // The early exit goes through onEvent too, so its storage follows the rewritten event.
    val runner =
      InMemoryRunner(
        agent = LiveEchoAgent(),
        plugins = listOf(plugin(breakWith = audioContent(), rewrite = ::withAudioAsReference)),
      )

    val unused = runLiveWith(runner)

    assertWithMessage("an artifact reference is not inline media")
      .that(runner.storedEvents().size)
      .isEqualTo(1)
  }

  @Test
  fun runLive_completes_runsAfterRunPlugins(): Unit = runBlocking {
    var afterRunCalls = 0
    val runner =
      InMemoryRunner(
        agent = LiveEchoAgent(),
        plugins = listOf(plugin(recordAfterRun = { afterRunCalls++ })),
      )

    val unused = runLiveWith(runner)

    assertThat(afterRunCalls).isEqualTo(1)
  }

  @Test
  fun runLive_callerTakesTheFirstEvent_runsAfterRunOnce(): Unit = runBlocking {
    var afterRunCalls = 0
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("a")), modelEvent(modelMessage("b")))),
        plugins = listOf(plugin(recordAfterRun = { afterRunCalls++ })),
      )
    val queue = LiveRequestQueue()
    queue.close()

    val first = runner.runLive("u", "s", queue).first()

    assertThat(first.content?.parts?.single()?.text).isEqualTo("a")
    assertThat(afterRunCalls).isEqualTo(1)
  }

  @Test
  fun runLive_doesNotPersistPartialEvents(): Unit = runBlocking {
    // Partials are skipped as in the turn-based rule; only the media rule is live-specific.
    val runner =
      InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(modelMessage("hal"), partial = true))))

    val unused = runLiveWith(runner)

    assertWithMessage("a partial is not yet the final text").that(runner.storedEvents()).isEmpty()
  }

  @Test
  fun runAsync_persistsModelAudio(): Unit = runBlocking {
    // The control: the media rule is live-only, so a turn-based run still stores this event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioContent()))))

    runner.runAsync("u", "s", newMessage = userMessage("hello")).toList()

    val model = runner.storedEvents().filter { it.author == "model" }
    assertWithMessage("a turn-based run keeps inline media").that(model.size).isEqualTo(1)
  }

  @Test
  fun runLive_agentWithoutLiveSupport_throwsUnsupportedOperation(): Unit = runBlocking {
    // Silently producing nothing would look like a model that never answered.
    val runner = InMemoryRunner(agent = DummyAgent())

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(error).hasMessageThat().isEqualTo("DummyAgent does not support live runs")
  }

  @Test
  fun runLive_modelWithoutLiveSupport_failsOutOfConnect(): Unit = runBlocking {
    // Refused by `Model.connect`'s default; Python likewise reaches `llm.connect` first.
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = turnOnlyModel()))

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(error)
      .hasMessageThat()
      .isEqualTo("live connections are not supported for turn-only-model")
  }

  @Test
  fun runLive_freshSession_runsTheRoot(): Unit = runBlocking {
    val child = LlmAgent(name = "child", model = turnOnlyModel("child-model"))
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)

    // Each model refuses to connect naming itself, which shows whose turn loop ran.
    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(error)
      .hasMessageThat()
      .isEqualTo("live connections are not supported for root-model")
  }

  @Test
  fun runLive_sessionWhoseLastAgentIsASubAgent_runsThatAgent(): Unit = runBlocking {
    val child = LlmAgent(name = "child", model = turnOnlyModel("child-model"))
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)
    val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    val unused =
      runner.sessionService.appendEvent(
        session,
        Event(author = "child", content = modelMessage("hi")),
      )

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(error)
      .hasMessageThat()
      .isEqualTo("live connections are not supported for child-model")
  }

  @Test
  fun runLive_routedSubAgent_resumesTheBranchItLastRanOn(): Unit = runBlocking {
    // As in ADK Python's live path, the routed agent's events carry the branch it last ran on.
    val childModel = DummyLiveModel(simpleAudioTurn())
    val child = LlmAgent(name = "child", model = childModel)
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)
    val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    val unusedAppended =
      runner.sessionService.appendEvent(
        session,
        Event(author = "child", branch = "child", content = modelMessage("earlier, on my branch")),
      )

    val events = runLiveWith(runner)

    val childEvents = events.filter { it.author == "child" }
    assertWithMessage("the routed agent should have run").that(childEvents).isNotEmpty()
    assertThat(childEvents.map { it.branch }.distinct()).isEqualTo(listOf("child"))
    val history = childModel.connectRequests.single().contents
    assertWithMessage("the routed agent should see its own branched turn")
      .that(history.any { content -> content.parts.any { it.text == "earlier, on my branch" } })
      .isTrue()
  }

  @Test
  fun runLive_routedSubAgentWhoseLastBranchIsAToolMessage_restoresNoBranch(): Unit = runBlocking {
    // A streaming tool's message under the agent's name is not the agent's own branch.
    val childModel = DummyLiveModel(simpleAudioTurn())
    val child = LlmAgent(name = "child", model = childModel)
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)
    val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    for (event in
      listOf(
        Event(
          author = "child",
          content = modelMessage(Part(functionCall = FunctionCall(name = "monitor", id = "c1"))),
        ),
        Event(author = "child", branch = "monitor@c1", content = userMessage("watching")),
      )) {
      val unused = runner.sessionService.appendEvent(session, event)
    }

    val events = runLiveWith(runner)

    val childEvents = events.filter { it.author == "child" }
    assertWithMessage("the routed agent should have run").that(childEvents).isNotEmpty()
    assertThat(childEvents.map { it.branch }.distinct()).containsExactly(null)
    val history = childModel.connectRequests.single().contents
    assertWithMessage("the tool's message should stay out of the routed agent's history")
      .that(history.flatMap { it.parts }.mapNotNull { it.text })
      .doesNotContain("watching")
  }

  @Test
  fun runLive_routedSubAgentWhoseBranchedTurnWasRewound_restoresNoBranch(): Unit = runBlocking {
    // As in ADK Python, the branch comes from history with rewinds applied.
    val child = LlmAgent(name = "child", model = DummyLiveModel(simpleAudioTurn()))
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)
    val sessionService = runner.sessionService
    val session = sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    for (event in
      listOf(
        Event(invocationId = "inv-1", author = "child", content = modelMessage("plain")),
        Event(
          invocationId = "inv-2",
          author = "child",
          branch = "child",
          content = modelMessage("rewound"),
        ),
        Event(
          invocationId = "inv-3",
          author = "user",
          actions = EventActions(rewindBeforeInvocationId = "inv-2"),
        ),
      )) {
      val unusedAppended = sessionService.appendEvent(session, event)
    }

    val events = runLiveWith(runner)

    val childEvents = events.filter { it.author == "child" }
    assertWithMessage("the routed agent should have run").that(childEvents).isNotEmpty()
    assertThat(childEvents.map { it.branch }.distinct()).isEqualTo(listOf(null))
  }

  @Test
  fun runLive_stateDeltaStoredDuringASlowRunnerAppend_storesEveryEventOnce(): Unit = runTest {
    // The live turn stores a state change while the runner is still storing the reply.
    val queue = LiveRequestQueue()
    val sessionService =
      AppendRecordingSessionService(
        beforeAppend = {
          if (it.content?.parts?.singleOrNull()?.text == "hello") {
            queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
            delay(6.seconds)
          }
        },
        // Each call stays within its own bound, though the append and flush together pass it.
        beforeFlush = { delay(6.seconds) },
      )
    val model =
      DummyLiveModel(
        listOf(
          AwaitSent("the first user turn") { it is ContentInput },
          wholeText("hello"),
          turnCompleteStep(),
        )
      )
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = model),
        sessionService = sessionService,
      )

    queue.use {
      queue.sendContent(userMessage("first"))
      val unused =
        withTimeout(60.seconds) {
          runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
        }
    }

    // Each event is stored once: the text, the state change, or the turn end it carries.
    assertThat(
        runner.storedEvents().map {
          Triple(
            it.content?.parts?.singleOrNull()?.text,
            it.actions.stateDelta["k"],
            it.turnComplete,
          )
        }
      )
      .containsExactly(
        Triple("first", null, false),
        Triple("hello", null, false),
        Triple(null, "v", false),
        Triple(null, null, true),
      )
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_runFails_notifiesOnRunErrorPlugins(): Unit = runBlocking {
    val seen = mutableListOf<Throwable>()
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = turnOnlyModel()),
        plugins = listOf(plugin(recordRunError = { seen += it })),
      )

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(seen).isEqualTo(listOf<Throwable>(error))
  }

  @Test
  fun runLive_agentThrowsCancellation_doesNotNotifyOnRunErrorPlugins(): Unit = runBlocking {
    // A cancellation is not a failure of the run, as in runAsync.
    val seen = mutableListOf<Throwable>()
    val runner =
      InMemoryRunner(
        agent = CancellingAgent(),
        plugins = listOf(plugin(recordRunError = { seen += it })),
      )

    assertFailsWith<CancellationException> { runLiveWith(runner) }

    assertThat(seen).isEmpty()
  }

  @Test
  fun runLive_runnerWithoutOverride_failsWhenCollectedWithUnsupportedOperation(): Unit =
    runBlocking {
      // Cold like every other live refusal, so it surfaces where the caller collects.
      val runner = TurnOnlyRunner(InMemoryRunner(agent = LiveEchoAgent()))

      val flow = runner.runLive("u", "s", LiveRequestQueue())
      val error = assertFailsWith<UnsupportedOperationException> { flow.toList() }

      assertThat(error).hasMessageThat().isEqualTo("TurnOnlyRunner does not support live runs")
    }

  @Test
  fun runLive_anonymousRunnerWithoutOverride_saysThisRunner(): Unit = runBlocking {
    // An anonymous class has no simple name to put in the message.
    val runner =
      object : Runner by InMemoryRunner(agent = LiveEchoAgent()) {
        override fun runLive(
          userId: String,
          sessionId: String,
          liveRequestQueue: LiveRequestQueue,
          runConfig: RunConfig?,
        ): Flow<Event> = super<Runner>.runLive(userId, sessionId, liveRequestQueue, runConfig)
      }

    val error =
      assertFailsWith<UnsupportedOperationException> {
        runner.runLive("u", "s", LiveRequestQueue()).toList()
      }

    assertThat(error).hasMessageThat().isEqualTo("This runner does not support live runs")
  }

  @Test
  fun runAsync_onEventPluginClearsPartial_storesTheRewrittenEvent(): Unit = runBlocking {
    // Storage is decided on the event the plugins return, as in ADK Python.
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("chunk"), partial = true))),
        plugins = listOf(plugin(rewrite = { it.copy(partial = false) })),
      )

    val unused = runner.runAsync("u", "s", newMessage = userMessage("go")).toList()

    assertThat(runner.storedEvents().count { it.author == "model" }).isEqualTo(1)
  }

  @Test
  fun runAsync_onEventPluginMarksAFinalEventPartial_doesNotStoreIt(): Unit = runBlocking {
    // Recorded at the service boundary, since the service itself also skips a partial event.
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("whole")))),
        sessionService = sessionService,
        plugins = listOf(plugin(rewrite = { it.copy(partial = true) })),
      )

    val unused = runner.runAsync("u", "s", newMessage = userMessage("go")).toList()

    assertThat(sessionService.appended.count { it.author == "model" }).isEqualTo(0)
  }

  @Test
  fun runLive_runnerAppendHangs_failsTheRunAfterTheBound(): Unit = runTest {
    // Bounded like the live turn's own appends, so a stuck session service cannot stall the run.
    val sessionService =
      AppendRecordingSessionService(
        beforeAppend = { if (it.content?.role == "model") awaitCancellation() }
      )
    val model =
      DummyLiveModel(
        listOf(
          AwaitSent("the user's turn") { it is ContentInput },
          wholeText("hello"),
          turnCompleteStep(),
        )
      )
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = model),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))

    val error =
      assertFailsWith<IllegalStateException> {
        withTimeout(60.seconds) { runner.runLive("u", "s", queue).toList() }
      }

    assertThat(error).hasMessageThat().isEqualTo("A live session append did not finish in 10s.")
    assertWithMessage("the live turn's own append of the user's turn should go through")
      .that(sessionService.appended.map { it.author })
      .isEqualTo(listOf("user"))
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_flushHangs_failsTheRunAfterTheBound(): Unit = runTest {
    // Every flush hangs, the turn end's and the one after the failure, and the run still ends.
    val sessionService = AppendRecordingSessionService(beforeFlush = { awaitCancellation() })
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(Event(author = "model", turnComplete = true))),
        sessionService = sessionService,
      )

    val error =
      assertFailsWith<IllegalStateException> { withTimeout(60.seconds) { runLiveWith(runner) } }

    assertThat(error).hasMessageThat().isEqualTo("A live session flush did not finish in 10s.")
    // The flush after the failure times out too, and is attached to the failure.
    assertThat(suppressedMessages(error))
      .isEqualTo(listOf("A live session flush did not finish in 10s."))
  }

  @Test
  fun runLive_endFlushHangs_failsAfterTheBound(): Unit = runTest {
    // The run itself completes, so only the end-of-run flush can hang it.
    val sessionService = AppendRecordingSessionService(beforeFlush = { awaitCancellation() })
    val runner = InMemoryRunner(agent = LiveEchoAgent(), sessionService = sessionService)

    val error =
      assertFailsWith<IllegalStateException> { withTimeout(60.seconds) { runLiveWith(runner) } }

    assertThat(error).hasMessageThat().isEqualTo("A live session flush did not finish in 10s.")
  }

  @Test
  fun runLive_turnEnd_isFlushedAfterItsAppendAndBeforeTheCallerSeesIt(): Unit = runBlocking {
    // The turn is persisted before the caller learns it ended.
    val sessionService = AppendRecordingSessionService()
    val turnEnd = Event(author = "model", turnComplete = true)
    val runner =
      InMemoryRunner(agent = ScriptedAgent(listOf(turnEnd)), sessionService = sessionService)
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).onEach { sessionService.calls += "emit" }.toList()

    assertThat(sessionService.calls).isEqualTo(listOf("append", "flush", "emit", "flush"))
  }

  @Test
  fun runLive_turnEndCarryingInlineMedia_isNotStoredButStillFlushesTheTurn(): Unit = runBlocking {
    // A plugin put audio on the turn end: that event is not stored, but the turn still flushes.
    val sessionService = AppendRecordingSessionService()
    val turn =
      listOf(modelEvent(modelMessage("hello")), Event(author = "model", turnComplete = true))
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(turn),
        sessionService = sessionService,
        plugins =
          listOf(
            plugin(rewrite = { if (it.turnComplete) it.copy(content = audioContent()) else it })
          ),
      )
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).onEach { sessionService.calls += "emit" }.toList()

    assertThat(sessionService.calls).isEqualTo(listOf("append", "emit", "flush", "emit", "flush"))
  }

  @Test
  fun runLive_eventWithinATurn_isFlushedWithTheTurnEnd(): Unit = runBlocking {
    // Flushed once per turn, unlike runAsync's once per final response.
    val sessionService = AppendRecordingSessionService()
    val turn =
      listOf(modelEvent(modelMessage("hello")), Event(author = "model", turnComplete = true))
    val runner = InMemoryRunner(agent = ScriptedAgent(turn), sessionService = sessionService)
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).onEach { sessionService.calls += "emit" }.toList()

    assertThat(sessionService.calls)
      .isEqualTo(listOf("append", "emit", "append", "flush", "emit", "flush"))
  }

  @Test
  fun runLive_geminiTextTurn_flushesOnceForTheTurnAndOnceAtTheEnd(): Unit = runBlocking {
    // Gemini streams text as partial chunks, then sends the whole text, usage and the turn's end.
    val calls =
      flushCallsFor(
        listOf(
          partialText("one "),
          partialText("two"),
          usage(),
          wholeText("one two"),
          turnCompleteStep(),
        )
      )

    assertThat(calls).isEqualTo(listOf("append", "append", "append", "flush", "flush"))
  }

  @Test
  fun runLive_spokenTurn_flushesOnceForTheTurnAndOnceAtTheEnd(): Unit = runBlocking {
    val calls = flushCallsFor(spokenTurn())

    // Usage, both finished transcripts and the turn's end are stored; audio and chunks are not.
    assertThat(calls).isEqualTo(listOf("append", "append", "append", "append", "flush", "flush"))
  }

  @Test
  fun runLive_recordingSavedAfterTheTurnEnd_isFlushedByTheRunEnd(): Unit = runBlocking {
    // A recording is saved in the background after its turn ends, so the next flush covers it.
    val calls = flushCallsFor(spokenTurn(), RunConfig(live = LiveRunConfig(saveLiveBlob = true)))

    assertThat(calls)
      .isEqualTo(listOf("append", "append", "append", "append", "flush", "append", "flush"))
  }

  @Test
  fun runLive_interruptedTurn_flushesOnceForTheTurnAndOnceAtTheEnd(): Unit = runBlocking {
    // An interruption is not the turn's end; the turn-complete that follows it is.
    val interruptedText = Respond(LlmResponse(content = modelMessage("one "), interrupted = true))
    val calls =
      flushCallsFor(
        listOf(
          partialText("one "),
          interruptedText,
          Respond(LlmResponse(interrupted = true)),
          turnCompleteStep(),
        )
      )

    assertThat(calls).isEqualTo(listOf("append", "append", "append", "flush", "flush"))
  }

  @Test
  fun runLive_transfer_flushesTheSourceAgentsTurnAtTheHandover(): Unit = runBlocking {
    // A handover ends the source agent's turn without a turn-complete.
    val sessionService = AppendRecordingSessionService()
    val child =
      LlmAgent(name = "child", model = DummyLiveModel(listOf(wholeText("hi"), turnCompleteStep())))
    val parent =
      LlmAgent(
        name = "parent",
        model =
          DummyLiveModel(
            listOf(Respond(modelTransferToAgentResponse("child")), DummyLiveStep.EndStream)
          ),
        subAgents = listOf(child),
      )
    val runner = InMemoryRunner(agent = parent, sessionService = sessionService)
    val queue = LiveRequestQueue()

    val unused =
      withTimeout(10.seconds) {
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
      }

    assertFlushedRightAfter(sessionService) { it.actions.transferToAgent != null }
  }

  @Test
  fun runLive_completedStep_flushesTheStepAtTheHandover(): Unit = runBlocking {
    // A completed sequential step hands over to the next step without a turn-complete.
    val sessionService = AppendRecordingSessionService()
    val taskCompleted =
      Respond(
        LlmResponse(
          content = modelMessage(Part(functionCall = FunctionCall("task_completed", id = "tc")))
        )
      )
    val step1 =
      LlmAgent(
        name = "step1",
        description = "A sequential step.",
        model = DummyLiveModel(listOf(taskCompleted, DummyLiveStep.EndStream)),
      )
    val step2 =
      LlmAgent(
        name = "step2",
        description = "A sequential step.",
        model = DummyLiveModel(listOf(wholeText("hi"), turnCompleteStep())),
      )
    val sequence = SequentialAgent(name = "sequence", subAgents = listOf(step1, step2))
    val runner = InMemoryRunner(agent = sequence, sessionService = sessionService)
    val queue = LiveRequestQueue()

    val unused =
      withTimeout(10.seconds) {
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
      }

    assertFlushedRightAfter(sessionService) { event ->
      event.functionResponses().any { it.name == "task_completed" }
    }
  }

  /**
   * Asserts that the first stored event matching [isHandover] is flushed before the next append.
   */
  private fun assertFlushedRightAfter(
    sessionService: AppendRecordingSessionService,
    isHandover: (Event) -> Boolean,
  ) {
    val handover = sessionService.appended.indexOfFirst(isHandover)
    assertWithMessage("the handover event was not stored").that(handover).isAtLeast(0)
    val appendCalls = sessionService.calls.withIndex().filter { it.value == "append" }
    assertThat(sessionService.calls.getOrNull(appendCalls[handover].index + 1)).isEqualTo("flush")
  }

  @Test
  fun runAsync_onEventPluginMarksTheEarlyExitEventPartial_doesNotStoreIt(): Unit = runBlocking {
    // A partial event is never stored, whichever path produced it, as in ADK Python.
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = DummyAgent("root"),
        sessionService = sessionService,
        plugins =
          listOf(plugin(breakWith = modelMessage("stop"), rewrite = { it.copy(partial = true) })),
      )

    val unused = runner.runAsync("u", "s", newMessage = userMessage("hi")).toList()

    assertThat(sessionService.appended.none { it.content?.parts?.firstOrNull()?.text == "stop" })
      .isTrue()
  }

  @Test
  fun runLive_onEventPluginMarksTheEarlyExitEventPartial_doesNotStoreIt(): Unit = runBlocking {
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = LiveEchoAgent(),
        sessionService = sessionService,
        plugins =
          listOf(plugin(breakWith = modelMessage("stop"), rewrite = { it.copy(partial = true) })),
      )
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(sessionService.appended.none { it.content?.parts?.firstOrNull()?.text == "stop" })
      .isTrue()
  }

  /** Runs [script] live until its turn ends and returns the session's append and flush calls. */
  private suspend fun flushCallsFor(
    script: List<DummyLiveStep>,
    runConfig: RunConfig? = null,
  ): List<String> {
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = DummyLiveModel(script)),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    val unused =
      withTimeout(10.seconds) {
        runner
          .runLive("u", "s", queue, runConfig ?: RunConfig())
          .onEach { if (it.turnComplete) queue.close() }
          .toList()
      }
    return sessionService.calls
  }

  private fun partialText(text: String) =
    Respond(LlmResponse(content = modelMessage(text), partial = true))

  private fun usage() =
    Respond(
      LlmResponse(
        usageMetadata =
          UsageMetadata(promptTokenCount = 1, candidatesTokenCount = 2, totalTokenCount = 3)
      )
    )

  /** A spoken turn as Gemini sends it: transcription chunks, audio, usage and finished copies. */
  private fun spokenTurn(): List<DummyLiveStep> {
    return listOf(
      Respond(inputTranscription("hi", finished = false)),
      outputTranscriptionStep("hel", finished = false),
      audioFrame(),
      outputTranscriptionStep("lo", finished = false),
      audioFrame(),
      usage(),
      Respond(inputTranscription("hi", finished = true)),
      outputTranscriptionStep("hello", finished = true),
      turnCompleteStep(),
    )
  }

  @Test
  fun runLive_completes_flushesAtTheEnd(): Unit = runBlocking {
    val sessionService = AppendRecordingSessionService()
    val runner = InMemoryRunner(agent = LiveEchoAgent(), sessionService = sessionService)

    val unused = runLiveWith(runner)

    assertThat(sessionService.calls).isEqualTo(listOf("flush"))
  }

  @Test
  fun runLive_runFails_flushesAndRethrowsWithTheFlushFailureSuppressed(): Unit = runBlocking {
    val flushFailure = IllegalStateException("flush failed")
    val sessionService = AppendRecordingSessionService(flushFailure = flushFailure)
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = turnOnlyModel()),
        sessionService = sessionService,
      )

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertThat(sessionService.calls).isEqualTo(listOf("flush"))
    assertThat(suppressedMessages(error)).isEqualTo(listOf("flush failed"))
  }

  @Test
  fun runLive_callerStopsCollecting_skipsTheEndFlush(): Unit = runBlocking {
    // A canceled run stops; whatever it left pending is for the service's owner to flush.
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("chunk"), partial = true))),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).first()

    assertThat(sessionService.calls).isEmpty()
  }

  @Test
  fun runLive_appRootedOnAnAgentAsItsNode_runsThatAgentLive(): Unit = runBlocking {
    // An agent given as the App's root node is still an agent, so it runs live.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(app = App(appName = "agent_node_app", rootNode = agent))

    val unused = runLiveWith(runner)

    assertWithMessage("the agent should have run live").that(agent.seenContext).isNotNull()
  }

  /** Records its input and the context it ran on, writes a state key, and emits [output]. */
  private class RecordingNode(override val name: String, private val output: Any?) : Node {
    var seenInput: Any? = null
      private set

    var seenContext: Context? = null
      private set

    override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
      seenInput = nodeInput
      seenContext = context
      context.updateState(name, "ran")
      emit(output)
    }
  }

  /** A runner whose app is rooted on a workflow that runs [nodes] one after another. */
  private fun workflowRunner(
    vararg nodes: Node,
    plugins: List<Plugin> = emptyList(),
    sessionService: SessionService = InMemorySessionService(),
    config: NodeConfig = NodeConfig(),
  ) =
    InMemoryRunner(
      app =
        App(
          appName = "workflow_app",
          rootNode =
            Workflow(
              name = "wf",
              edges = (listOf<Node>(Start) + nodes).zipWithNext { from, to -> Edge(from, to) },
              config = config,
            ),
          plugins = plugins,
        ),
      sessionService = sessionService,
    )

  /** Runs [runner] live on a [queue] the caller never closes, so only the root's end stops it. */
  private suspend fun runLiveUntilTheRootEnds(
    runner: InMemoryRunner,
    queue: LiveRequestQueue = LiveRequestQueue(),
  ): List<Event> = withTimeout(5.seconds) { runner.runLive("u", "s", queue).toList() }

  @Test
  fun runLive_callerTakesAWorkflowRootsFirstOutput_runsAfterRunOnce(): Unit = runBlocking {
    var afterRuns = 0
    val runErrors = mutableListOf<Throwable>()
    val runner =
      workflowRunner(
        RecordingNode("first", "A"),
        RecordingNode("second", "B"),
        plugins =
          listOf(plugin(recordRunError = { runErrors += it }, recordAfterRun = { afterRuns++ })),
      )

    // The caller stops at the first node's output, as ADK Python's early close does.
    val first =
      withTimeout(5.seconds) {
        runner.runLive("u", "s", LiveRequestQueue()).first { it.output != null }
      }

    assertThat(first.output).isEqualTo("A")
    assertWithMessage("stopping early is not a run error").that(runErrors).isEmpty()
    assertThat(afterRuns).isEqualTo(1)
  }

  @Test
  fun runLive_workflowRoot_runsToItsEndWithoutTheQueueClosing(): Unit = runBlocking {
    var afterRuns = 0
    val first = RecordingNode("first", "A")
    val second = RecordingNode("second", "B")
    val runner =
      workflowRunner(first, second, plugins = listOf(plugin(recordAfterRun = { afterRuns++ })))
    val queue = LiveRequestQueue()

    val events = runLiveUntilTheRootEnds(runner, queue)

    assertThat(queue.isClosed).isFalse()
    assertWithMessage("a live run has no message to give the first node")
      .that(first.seenInput)
      .isNull()
    assertThat(second.seenInput).isEqualTo("A")
    assertThat(events.mapNotNull { it.output }).containsExactly("A", "B").inOrder()
    val stored = runner.storedEvents()
    assertThat(stored.mapNotNull { it.nodeInfo?.path })
      .containsExactly("wf@1/first@1", "wf@1/second@1")
      .inOrder()
    assertThat(stored.mapNotNull { it.actions.stateDelta["second"] }).containsExactly("ran")
    assertThat(afterRuns).isEqualTo(1)
  }

  @Test
  fun runLive_workflowRoot_nestedAndDispatchedNodesShareTheCallersQueue(): Unit = runBlocking {
    val nested = RecordingNode("nested", "N")
    val dispatched = RecordingNode("dispatched", "D")
    val dispatcher =
      node<Any?, Any?>("dispatcher", rerunOnResume = true) { context, _ ->
        context.runNode(dispatched)
      }
    val inner = Workflow(name = "inner", edges = listOf(Edge(Start, nested)))
    val queue = LiveRequestQueue()

    val unused = runLiveUntilTheRootEnds(workflowRunner(inner, dispatcher), queue)

    assertThat(nested.seenContext?.invocationContext?.liveRequestQueue).isSameInstanceAs(queue)
    assertThat(dispatched.seenContext?.invocationContext?.liveRequestQueue).isSameInstanceAs(queue)
  }

  @Test
  fun runLive_workflowRootWhoseBeforeRunAnswers_skipsTheWorkflowAndRunsAfterRun(): Unit =
    runBlocking {
      var afterRuns = 0
      val node = RecordingNode("node", "never")
      val answer = plugin(breakWith = modelMessage("early"), recordAfterRun = { afterRuns++ })
      val runner = workflowRunner(node, plugins = listOf(answer))

      val events = runLiveUntilTheRootEnds(runner)

      assertWithMessage("the workflow must not run").that(node.seenContext).isNull()
      assertThat(events.map { it.contentText() }).containsExactly("early")
      assertThat(runner.storedEvents().map { it.contentText() }).containsExactly("early")
      assertThat(afterRuns).isEqualTo(1)
    }

  @Test
  fun runLive_workflowRootsEarlyAnswerWithAnImage_isNotStoredAsANodeEvent(): Unit = runBlocking {
    val image = Part(inlineData = Blob(mimeType = "image/png", data = ByteArray(4)))
    val early = Content(role = "model", parts = listOf(Part(text = "early"), image))
    val runner =
      workflowRunner(RecordingNode("node", "never"), plugins = listOf(plugin(breakWith = early)))

    val events = runLiveUntilTheRootEnds(runner)

    assertThat(events.map { it.contentText() }).containsExactly("early")
    // A plugin's early answer is not a node's event, so the agent rule drops it whole.
    assertThat(runner.storedEvents()).isEmpty()
  }

  @Test
  fun runLive_nodeRootFails_notifiesOnRunErrorAndFlushesTheSession(): Unit = runBlocking {
    val errors = mutableListOf<Throwable>()
    val sessionService = AppendRecordingSessionService()
    val failing = node<Any?, Unit>("failing") { _, _ -> throw IllegalStateException("node failed") }
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "node_app",
            rootNode = failing,
            plugins = listOf(plugin(recordRunError = { errors += it })),
          ),
        sessionService = sessionService,
      )

    val error = assertFailsWith<IllegalStateException> { runLiveUntilTheRootEnds(runner) }

    assertThat(error).hasMessageThat().isEqualTo("node failed")
    assertThat(errors.map { it.message }).containsExactly("node failed")
    assertThat(sessionService.appended.single().errorCode).isEqualTo("IllegalStateException")
    assertThat(sessionService.calls).containsExactly("append", "flush").inOrder()
  }

  /** A node that emits [events] and nothing else. */
  private fun emitting(vararg events: Event): Node =
    object : Node {
      override val name = "speaker"

      override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flowOf(*events)
    }

  @Test
  fun runLive_workflowNodes_storeTheSameEventsAsRunAsync(): Unit = runBlocking {
    val image = Part(inlineData = Blob(mimeType = "image/png", data = ByteArray(4)))
    val state = EventActions(stateDelta = mutableMapOf<String, Any>("spoken" to "yes"))
    val speaker =
      emitting(
        Event(author = "", content = audioContent()),
        Event(author = "", content = audioContent(), actions = state),
        Event(author = "", content = modelMessage("Spoken.")),
        Event(
          author = "",
          content = Content(role = "model", parts = listOf(image)),
          turnComplete = true,
        ),
      )
    val live = workflowRunner(speaker)
    val turnBased = workflowRunner(speaker)

    val unused = runLiveUntilTheRootEnds(live)
    runTurnBased(turnBased)

    val expected = turnBased.storedEvents().filter { it.author != "user" }.map { it.shared() }
    assertThat(live.storedEvents().map { it.shared() })
      .containsExactlyElementsIn(expected)
      .inOrder()
    val lastBlob = live.storedEvents().last().content?.parts?.single()?.inlineData
    assertThat(lastBlob?.mimeType).isEqualTo("image/png")
  }

  /** What a stored node event holds, apart from the ids and times of the run that stored it. */
  private fun Event.shared() =
    listOf(author, content, actions.stateDelta, turnComplete, nodeInfo?.path)

  @Test
  fun runLive_workflowNodeAudioEndingATurn_storesItAndFlushesTheTurn(): Unit = runBlocking {
    val sessionService = AppendRecordingSessionService()

    val audio = Event(author = "", content = audioContent(), turnComplete = true)

    val events =
      withTimeout(5.seconds) {
        workflowRunner(emitting(audio), sessionService = sessionService)
          .runLive("u", "s", LiveRequestQueue())
          .onEach { sessionService.calls += "emit" }
          .toList()
      }

    assertThat(sessionService.appended).containsExactly(events.single())
    // The turn is flushed before its event reaches the caller; the last flush ends the run.
    assertThat(sessionService.calls).containsExactly("append", "flush", "emit", "flush").inOrder()
  }

  @Test
  fun runLive_agentMediaEventCarryingAStateChange_isNotStored(): Unit = runBlocking {
    // An agent's media event is dropped whole, even when the App's root node is the agent.
    val state = EventActions(stateDelta = mutableMapOf<String, Any>("spoken" to "yes"))
    fun agent() = ScriptedAgent(listOf(modelEvent(audioContent()).copy(actions = state)))
    val asAgent = InMemoryRunner(agent = agent())
    val asNode = InMemoryRunner(app = App(appName = "agent_node_app", rootNode = agent()))

    val unusedAgent = runLiveWith(asAgent)
    val unusedNode = runLiveWith(asNode)

    assertThat(asAgent.storedEvents()).isEmpty()
    assertThat(asNode.storedEvents()).isEmpty()
  }

  @Test
  fun runLive_workflowSingleTurnAgentNode_runsTurnBasedOnItsInput(): Unit = runBlocking {
    val requests = mutableListOf<LlmRequest>()
    val summarizer =
      LlmAgent(
        name = "summarizer",
        model =
          DummyModel("summarizer") { request ->
            requests += request
            flowOf(LlmResponse(content = modelMessage("summary")))
          },
      )
    val capture = RecordingNode("capture", "done")
    val runner = workflowRunner(RecordingNode("prep", "initial text"), summarizer, capture)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("unread"))

    val unused = runLiveUntilTheRootEnds(runner, queue)
    queue.close()

    // The model has no live connection, so a live attempt would have failed the run.
    assertThat(requests.single().contents.last().text()).isEqualTo("initial text")
    assertThat(capture.seenInput).isEqualTo("summary")
    val unread = queue.receiveRequest()?.input as? ContentInput
    assertThat(unread?.content?.text()).isEqualTo("unread")
  }

  @Test
  fun runLive_workflowSingleTurnAgentNode_asksForToolConfirmationAsItDoesTurnBased(): Unit =
    runBlocking {
      var executions = 0
      val secureTool =
        object : FunctionTool("secure", "Needs the user's approval.", requiresConfirmation = true) {
          override fun declaration() = null

          override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
            executions++
            return "ok"
          }
        }
      val agent =
        LlmAgent(
          name = "agent",
          model =
            DummyModel("model") { flowOf(modelFunctionCallResponse("secure", id = "call_1")) },
          tools = listOf(secureTool),
        )
      val runner = workflowRunner(agent)

      val events = runLiveUntilTheRootEnds(runner)

      assertThat(executions).isEqualTo(0)
      val request =
        events
          .flatMap { it.functionCalls() }
          .single { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
      assertWithMessage("the confirmation request was not stored")
        .that(
          runner.storedEvents().any { event -> event.functionCalls().any { it.id == request.id } }
        )
        .isTrue()
    }

  /**
   * A model that answers both turn-based calls and live connections with [response], counting them.
   */
  private class EitherWayModel(override val name: String, private val response: LlmResponse) :
    Model {
    private val live = DummyLiveModel.perConnection(List(4) { listOf(Respond(response)) }, name)
    var calls = 0
      private set

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
      calls++
      return flowOf(response)
    }

    override suspend fun connect(request: LlmRequest): LiveConnection {
      calls++
      return live.connect(request)
    }
  }

  private val finished = modelFunctionCallResponse("finish_task", mapOf("result" to "done"))

  /** A task agent over [taskSubAgents] on [model], which finishes at once with "done". */
  private fun taskAgent(
    name: String,
    vararg taskSubAgents: LlmAgent,
    model: Model = EitherWayModel(name, finished),
  ) =
    LlmAgent(name = name, model = model, subAgents = taskSubAgents.toList(), mode = AgentMode.TASK)

  /** Asserts that running [runner] live fails, before the session exists, naming [agentName]. */
  private suspend fun assertFailsBeforeRunningLive(runner: InMemoryRunner, agentName: String) {
    val failure = assertFailsWith<UnsupportedOperationException> { runLiveUntilTheRootEnds(runner) }

    assertThat(failure).hasMessageThat().contains("'$agentName' cannot run live yet")
    assertThat(runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))).isNull()
  }

  /** Runs [runner] turn-based on one message and returns its events. */
  @CanIgnoreReturnValue
  private suspend fun runTurnBased(runner: InMemoryRunner): List<Event> =
    runner.runAsync("u", "s", newMessage = userMessage("go")).toList()

  /** A live-only model that finishes a task at once with "done", so a turn-based call fails. */
  private fun liveTaskModel(name: String) = DummyLiveModel(listOf(Respond(finished)), name)

  @Test
  fun runLive_workflowTaskAgentNode_runsItLive(): Unit = runBlocking {
    val model = liveTaskModel("writer")

    val events = runLiveUntilTheRootEnds(workflowRunner(taskAgent("writer", model = model)))

    assertThat(events.mapNotNull { it.output }).containsExactly(mapOf("result" to "done"))
    assertThat(model.connections).hasSize(1)
  }

  @Test
  fun runLive_workflowTaskAgentNodeThatDelegatesToTasks_failsGivingTheDelegationReason(): Unit =
    runBlocking {
      val failure =
        assertFailsWith<UnsupportedOperationException> {
          runLiveUntilTheRootEnds(workflowRunner(booker()))
        }

      assertThat(failure)
        .hasMessageThat()
        .isEqualTo("Task agent 'booker' cannot run live yet: it delegates to task sub-agents.")
    }

  @Test
  fun runAsync_workflowTaskAgentNode_runsTurnBased(): Unit = runBlocking {
    val events = runTurnBased(workflowRunner(taskAgent("writer")))

    assertThat(events.mapNotNull { it.output }).containsExactly(mapOf("result" to "done"))
  }

  @Test
  fun runLive_taskAgentNodeInANestedWorkflowsParallelNode_runsItLive(): Unit = runBlocking {
    val model = liveTaskModel("writer")
    val inner =
      Workflow("inner", listOf(Edge(Start, parallelNode(taskAgent("writer", model = model)))))

    val events = runLiveUntilTheRootEnds(workflowRunner(inner))

    assertThat(events.mapNotNull { it.output }).contains(listOf(mapOf("result" to "done")))
    assertThat(model.connections).hasSize(1)
  }

  @Test
  fun runLive_delegatingTaskInANestedWorkflowsParallelNode_failsBeforeRunning(): Unit =
    runBlocking {
      val inner = Workflow("inner", listOf(Edge(Start, parallelNode(booker()))))

      assertFailsBeforeRunningLive(workflowRunner(inner), "booker")
    }

  @Test
  fun runLive_runNodeStartsAWorkflowWithATaskAgentNode_runsTheTaskLive(): Unit = runBlocking {
    val model = liveTaskModel("writer")
    val inner = Workflow("inner", listOf(Edge(Start, taskAgent("writer", model = model))))

    val events = runLiveUntilTheRootEnds(starterRunner(inner))

    assertThat(events.mapNotNull { it.output }).contains(mapOf("result" to "done"))
    assertThat(model.connections).hasSize(1)
  }

  @Test
  fun runLive_runNodeStartsAWorkflowWithADelegatingTask_failsBeforeItsNodesRun(): Unit =
    runBlocking {
      val prep = RecordingNode("prep", "ready")
      val inner = Workflow("inner", listOf(Edge(Start, prep), Edge(prep, booker())))

      val failure =
        assertFailsWith<UnsupportedOperationException> {
          runLiveUntilTheRootEnds(starterRunner(inner))
        }

      assertThat(failure)
        .hasMessageThat()
        .isEqualTo("Task agent 'booker' cannot run live yet: it delegates to task sub-agents.")
      assertThat(prep.seenContext).isNull()
    }

  @Test
  fun runLive_chatNodeWhoseTaskDelegatesToTasks_failsBeforeRunning(): Unit = runBlocking {
    val planner = LlmAgent(name = "planner", model = turnOnlyModel(), subAgents = listOf(booker()))

    assertFailsBeforeRunningLive(workflowRunner(planner), "booker")
  }

  @Test
  fun runAsync_chatNodeWhoseTaskDelegatesToTasks_runsTurnBased(): Unit = runBlocking {
    val model = DummyModel("planner") { flowOf(LlmResponse(content = modelMessage("hi"))) }
    val planner = LlmAgent(name = "planner", model = model, subAgents = listOf(booker()))

    val events = runTurnBased(workflowRunner(planner))

    assertThat(events.map { it.contentText() }).contains("hi")
  }

  @Test
  fun runLive_chatNodeWithATaskSubAgent_runs(): Unit = runBlocking {
    val reply = LlmResponse(content = modelMessage("hi"), turnComplete = true)
    val planner =
      LlmAgent(
        name = "planner",
        model = EitherWayModel("planner", reply),
        subAgents = listOf(taskAgent("writer")),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))

    val events =
      withTimeout(5.seconds) {
        workflowRunner(planner)
          .runLive("u", "s", queue)
          .onEach { if (it.contentText() == "hi") queue.close() }
          .toList()
      }

    assertThat(events.map { it.contentText() }).contains("hi")
  }

  @Test
  fun runLive_runNodeStartsATaskThatDelegatesToTasks_failsBeforeTheTaskRuns(): Unit = runBlocking {
    var modelCalls = 0
    val model =
      DummyModel("booker") {
        modelCalls++
        flowOf(finished)
      }
    val booker = taskAgent("booker", taskAgent("payer"), model = model)

    val failure =
      assertFailsWith<UnsupportedOperationException> {
        runLiveUntilTheRootEnds(starterRunner(booker))
      }

    assertThat(failure)
      .hasMessageThat()
      .isEqualTo("Task agent 'booker' cannot run live yet: it delegates to task sub-agents.")
    assertThat(modelCalls).isEqualTo(0)
  }

  @Test
  fun runAsync_runNodeStartsATaskThatDelegatesToTasks_runsTurnBased(): Unit = runBlocking {
    val events = runTurnBased(starterRunner(booker()))

    assertThat(events.mapNotNull { it.output }).contains(mapOf("result" to "done"))
  }

  @Test
  fun runLive_runNodeStartsATask_runsIt(): Unit = runBlocking {
    val events = runLiveUntilTheRootEnds(starterRunner(taskAgent("writer")))

    assertThat(events.mapNotNull { it.output }).contains(mapOf("result" to "done"))
  }

  @Test
  fun runLive_workflowChatNodeTransfers_failsBeforeTheTargetRuns(): Unit = runBlocking {
    var billingCalls = 0
    val billing =
      DummyModel("billing") {
        billingCalls++
        flowOf(paid)
      }

    assertTransferFailsLive(workflowRunner(coordinator(billing)))
    assertThat(billingCalls).isEqualTo(0)
  }

  @Test
  fun runLive_workflowChatNodeTransfersUnderARetryConfig_stillFailsBeforeTheTargetRuns(): Unit =
    runBlocking {
      var billingCalls = 0
      val billing =
        DummyModel("billing") {
          billingCalls++
          flowOf(paid)
        }
      val coordinatorModel = transferringModel()
      val retrying = NodeConfig(retryConfig = RetryConfig(maxAttempts = 2))

      assertTransferFailsLive(
        workflowRunner(coordinator(billing, coordinatorModel), config = retrying)
      )
      assertThat(coordinatorModel.calls).isEqualTo(1)
      assertThat(billingCalls).isEqualTo(0)
    }

  @Test
  fun runAsync_workflowChatNodeTransfers_runsTheTargetTurnBased(): Unit = runBlocking {
    val events = runTurnBased(workflowRunner(coordinator(DummyModel("billing") { flowOf(paid) })))

    assertThat(events.map { it.contentText() }).contains("paid")
  }

  @Test
  fun runLive_workflowSingleTurnNodeTransfers_passesTheTargetsReplyToTheNextNode(): Unit =
    runBlocking {
      val coordinator =
        LlmAgent(
          name = "coordinator",
          model = DummyModel("coordinator") { flowOf(modelTransferToAgentResponse("billing")) },
          mode = AgentMode.SINGLE_TURN,
          subAgents =
            listOf(LlmAgent(name = "billing", model = DummyModel("billing") { flowOf(paid) })),
        )
      val prep = node<Any?, String>("prep") { _, _ -> "question" }
      var captured: Any? = null
      val capture = node<Any?, String>("capture") { _, input -> "done".also { captured = input } }

      val unused = runLiveUntilTheRootEnds(workflowRunner(prep, coordinator, capture))

      // Neither model has a live connection, so the target ran turn-based to reply.
      assertThat(captured).isEqualTo("paid")
    }

  @Test
  fun runLive_singleTurnNodesTargetHandedBackToByItsTask_staysTurnBased(): Unit = runBlocking {
    var taskCalls = 0
    val task =
      LlmAgent(
        name = "t",
        description = "Does t.",
        mode = AgentMode.TASK,
        model =
          DummyModel("t") {
            flowOf(if (taskCalls++ == 0) modelTransferToAgentResponse("planner") else finished)
          },
      )
    var plannerCalls = 0
    val planner =
      LlmAgent(
        name = "planner",
        mode = AgentMode.CHAT,
        subAgents = listOf(task),
        model =
          DummyModel("planner") {
            flowOf(
              if (plannerCalls++ == 0) {
                modelFunctionCallResponse("t", mapOf("request" to "go"), id = "d1")
              } else {
                LlmResponse(content = modelMessage("done"))
              }
            )
          },
      )
    val coordinator =
      LlmAgent(
        name = "coordinator",
        model = DummyModel("coordinator") { flowOf(modelTransferToAgentResponse("planner")) },
        mode = AgentMode.SINGLE_TURN,
        subAgents = listOf(planner),
      )

    val events = runLiveUntilTheRootEnds(workflowRunner(coordinator))

    // No model has a live connection, so a live attempt anywhere would have failed the run.
    assertThat(events.map { it.contentText() }).contains("done")
  }

  @Test
  fun runLive_singleTurnNodeHandsToAnAgentAboveTheRun_runsItTurnBasedUnderItsOwnRunId(): Unit =
    runBlocking {
      val asker =
        LlmAgent(
          name = "asker",
          description = "Asks.",
          mode = AgentMode.SINGLE_TURN,
          model = DummyModel("asker") { flowOf(modelTransferToAgentResponse("lead")) },
        )
      val unusedLead =
        LlmAgent(
          name = "lead",
          model = DummyModel("lead") { flowOf(paid) },
          subAgents = listOf(asker),
        )
      val first = node<Any?, Any?>("first", rerunOnResume = true) { c, _ -> c.runNode(asker) }
      val second = node<Any?, Any?>("second", rerunOnResume = true) { c, _ -> c.runNode(asker) }
      val runner = workflowRunner(first, second)

      val unused = runLiveUntilTheRootEnds(runner)

      // lead's model has no live connection, so a live hop would have failed the run.
      val leadPaths =
        runner.storedEvents().filter { it.author == "lead" }.mapNotNull { it.nodeInfo?.path }
      assertThat(leadPaths.toSet()).hasSize(2)
    }

  @Test
  fun runLive_runNodeStartsASingleTurnNodeThatTransfers_runsTheTargetOnceTurnBased(): Unit =
    runBlocking {
      var billingCalls = 0
      val coordinator =
        LlmAgent(
          name = "coordinator",
          model = DummyModel("coordinator") { flowOf(modelTransferToAgentResponse("billing")) },
          mode = AgentMode.SINGLE_TURN,
          subAgents =
            listOf(
              LlmAgent(
                name = "billing",
                model =
                  DummyModel("billing") {
                    billingCalls++
                    flowOf(paid)
                  },
              )
            ),
        )
      var returned: Any? = null
      val starter =
        node<Any?, Any?>("starter", rerunOnResume = true) { c, _ ->
          c.runNode(coordinator).also { returned = it }
        }

      val events = runLiveUntilTheRootEnds(workflowRunner(starter))

      // billing has no live connection, so a live run would have failed it.
      assertThat(events.map { it.contentText() }).contains("paid")
      assertThat(billingCalls).isEqualTo(1)
      assertThat(returned).isEqualTo("paid")
    }

  @Test
  fun runLive_workflowParallelNodesAgentTransfers_failsBeforeTheTargetRuns(): Unit = runBlocking {
    var billingCalls = 0
    val billing =
      DummyModel("billing") {
        billingCalls++
        flowOf(paid)
      }

    assertTransferFailsLive(workflowRunner(parallelNode(coordinator(billing))))
    assertTransferFailsLive(workflowRunner(parallelNode(parallelNode(coordinator(billing)))))
    assertThat(billingCalls).isEqualTo(0)
  }

  @Test
  fun runAsync_workflowParallelNodesAgentTransfers_runsTheTargetTurnBased(): Unit = runBlocking {
    var billingCalls = 0
    val billing =
      DummyModel("billing") {
        billingCalls++
        flowOf(paid)
      }

    val unused = runTurnBased(workflowRunner(parallelNode(coordinator(billing))))

    assertThat(billingCalls).isEqualTo(1)
  }

  @Test
  fun runLive_runNodeStartsAChatThatTransfers_followsTheTransfer(): Unit = runBlocking {
    val billing =
      EitherWayModel("billing", LlmResponse(content = modelMessage("paid"), turnComplete = true))
    val queue = LiveRequestQueue()

    val events =
      withTimeout(5.seconds) {
        starterRunner(coordinator(billing))
          .runLive("u", "s", queue)
          .onEach { if (it.contentText() == "paid") queue.close() }
          .toList()
      }

    assertThat(events.map { it.contentText() }).contains("paid")
    assertThat(billing.calls).isEqualTo(1)
  }

  @Test
  fun runLive_rootParallelChatTransfers_followsTheTransfer(): Unit = runBlocking {
    val billing =
      EitherWayModel("billing", LlmResponse(content = modelMessage("paid"), turnComplete = true))
    val queue = LiveRequestQueue()
    val runner =
      InMemoryRunner(app = App(appName = "app", rootNode = parallelNode(coordinator(billing))))

    val events =
      withTimeout(5.seconds) {
        runner
          .runLive("u", "s", queue)
          .onEach { if (it.contentText() == "paid") queue.close() }
          .toList()
      }

    assertThat(events.map { it.contentText() }).contains("paid")
    assertThat(billing.calls).isEqualTo(1)
  }

  @Test
  fun runLive_runNodeStartsAParallelChatThatTransfers_followsTheTransfer(): Unit = runBlocking {
    val billing =
      EitherWayModel("billing", LlmResponse(content = modelMessage("paid"), turnComplete = true))
    val queue = LiveRequestQueue()

    val events =
      withTimeout(5.seconds) {
        starterRunner(parallelNode(coordinator(billing)))
          .runLive("u", "s", queue)
          .onEach { if (it.contentText() == "paid") queue.close() }
          .toList()
      }

    assertThat(events.map { it.contentText() }).contains("paid")
    assertThat(billing.calls).isEqualTo(1)
  }

  @Test
  fun runLive_runNodeStartsAParallelNodeOfATask_runsItLive(): Unit = runBlocking {
    val model = liveTaskModel("writer")

    val events =
      runLiveUntilTheRootEnds(starterRunner(parallelNode(taskAgent("writer", model = model))))

    assertThat(events.mapNotNull { it.output }).contains(listOf(mapOf("result" to "done")))
    assertThat(model.connections).hasSize(1)
  }

  @Test
  fun runLive_runNodeStartsAParallelNodeOfAChatWithADelegatingTask_failsBeforeItConnects(): Unit =
    runBlocking {
      val model = DummyLiveModel(listOf(Respond(LlmResponse(content = modelMessage("hi")))))
      val planner = LlmAgent(name = "planner", model = model, subAgents = listOf(booker()))

      val failure =
        assertFailsWith<UnsupportedOperationException> {
          runLiveUntilTheRootEnds(starterRunner(parallelNode(planner)))
        }

      assertThat(failure)
        .hasMessageThat()
        .isEqualTo("Task agent 'booker' cannot run live yet: it delegates to task sub-agents.")
      assertThat(model.connections).isEmpty()
    }

  @Test
  fun runAsync_runNodeStartsAParallelNodeOfATask_runsTurnBased(): Unit = runBlocking {
    val events = runTurnBased(starterRunner(parallelNode(taskAgent("writer"))))

    assertThat(events.mapNotNull { it.output }).contains(listOf(mapOf("result" to "done")))
  }

  @Test
  fun runLive_workflowAgentNodeFailsAfterAskingToTransfer_failsWithItsOwnError(): Unit =
    runBlocking {
      val failing =
        object : BaseAgent("failing", "fails") {
          override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
            emit(Event(author = name, actions = EventActions(transferToAgent = "billing")))
            throw IllegalStateException("node failed")
          }

          override fun runLiveImpl(context: InvocationContext): Flow<Event> = runAsyncImpl(context)
        }

      val failure =
        assertFailsWith<IllegalStateException> { runLiveUntilTheRootEnds(workflowRunner(failing)) }

      assertThat(failure).hasMessageThat().isEqualTo("node failed")
    }

  @Test
  fun runLive_workflowAgentNodePausesAfterAskingToTransfer_pausesInsteadOfFailing(): Unit =
    runBlocking {
      val asking =
        ScriptedAgent(
          listOf(
            Event(
              author = "scripted",
              content = modelFunctionCall("approve", id = "c1"),
              longRunningToolIds = setOf("c1"),
              actions = EventActions(transferToAgent = "billing"),
            )
          )
        )

      val events = runLiveUntilTheRootEnds(workflowRunner(asking))

      assertThat(events.flatMap { it.longRunningToolIds }).containsExactly("c1")
    }

  @Test
  fun runLive_workflowFunctionNodeAsksToTransfer_failsAsATurnBasedRunDoes(): Unit = runBlocking {
    val transferring =
      node<Any?, Any?>("transferring") { context, _ ->
        context.actions.transferToAgent = "billing"
        null
      }

    val failure =
      assertFailsWith<IllegalArgumentException> {
        runLiveUntilTheRootEnds(workflowRunner(transferring))
      }

    assertThat(failure).hasMessageThat().isEqualTo("Only agents can request an agent transfer.")
  }

  @Test
  fun runLive_pluginRewritesALiveTasksAudio_storesTheRewrite(): Unit = runBlocking {
    val audio = Part(inlineData = Blob(mimeType = "audio/pcm", data = ByteArray(4)))
    val finish = Part(functionCall = FunctionCall("finish_task", mapOf("result" to "done")))
    val spoken = LlmResponse(content = modelMessage(audio, finish))
    val scrubbing =
      object : Plugin {
        override val name = "scrubbing"

        override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event =
          withAudioAsReference(event)
      }
    val runner =
      workflowRunner(
        starterNode(taskAgent("writer", model = EitherWayModel("writer", spoken))),
        plugins = listOf(scrubbing),
      )

    val unused = runLiveUntilTheRootEnds(runner)

    assertThat(runner.storedEvents().map { it.content }).contains(audioReference())
  }

  private val paid = LlmResponse(content = modelMessage("paid"))

  /** Asserts that running [runner] live fails at the coordinator's transfer. */
  private suspend fun assertTransferFailsLive(runner: InMemoryRunner) {
    val failure = assertFailsWith<UnsupportedOperationException> { runLiveUntilTheRootEnds(runner) }

    assertThat(failure)
      .hasMessageThat()
      .isEqualTo("Agent node 'coordinator' cannot transfer in a live run yet.")
  }

  /** A task agent that delegates to the task agent `payer`. */
  private fun booker() = taskAgent("booker", taskAgent("payer"))

  /** A runner whose workflow's function node starts [node] with runNode and returns its output. */
  private fun starterRunner(node: Node) = workflowRunner(starterNode(node))

  /** A function node that starts [node] with runNode and returns its output. */
  private fun starterNode(node: Node): Node =
    node<Any?, Any?>("starter", rerunOnResume = true) { context, _ -> context.runNode(node) }

  /** A model that answers with a transfer to `billing`. */
  private fun transferringModel() =
    EitherWayModel("coordinator", modelTransferToAgentResponse("billing"))

  /** A chat agent on [model] that transfers to its sub-agent `billing`, on [billingModel]. */
  private fun coordinator(billingModel: Model, model: Model = transferringModel()) =
    LlmAgent(
      name = "coordinator",
      model = model,
      subAgents = listOf(LlmAgent(name = "billing", model = billingModel)),
    )

  @Test
  fun runLive_modelWithoutLiveSupport_hasAlreadyCreatedTheSession(): Unit = runBlocking {
    // The session exists before a late refusal; pinned so adding an early check is deliberate.
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = turnOnlyModel()))

    assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertWithMessage("the session is created before the model is asked to connect")
      .that(runner.sessionService.getSession(SessionKey(runner.appName, "u", "s")))
      .isNotNull()
  }

  @Test
  fun runLive_streamingToolEmitsAfterItsConnectionEnded_deliversTheEventBeforeTheRunEnds(): Unit =
    runBlocking {
      val closing = CompletableDeferred<Unit>()
      val tool =
        DummyTool(name = "monitor") { _, _ ->
          flow {
            closing.await()
            emit(Event(content = modelMessage("bye")))
            awaitCancellation()
          }
        }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer") { it.answers("c1", pendingAnswer) },
          DummyLiveStep.EndStream,
        )
      val model = DummyLiveModel(script)
      // The tool emits while the run closes the ended connection, after the turn stopped reading.
      val closingModel =
        object : Model by model {
          override suspend fun connect(request: LlmRequest): LiveConnection {
            val connection = model.connect(request)
            return object : LiveConnection by connection {
              override suspend fun closeSession() {
                closing.complete(Unit)
                yield()
                connection.closeSession()
              }
            }
          }
        }
      val agent = LlmAgent(name = "live_agent", model = closingModel, tools = listOf(tool))

      val events =
        InMemoryRunner(agent = agent).runLive("u", "s", LiveRequestQueue(), RunConfig()).toList()

      assertThat(events.count { it.branch == "monitor@c1" }).isEqualTo(1)
    }

  @Test
  fun runLive_streamingToolEmitsAnEventThenAValue_sendsTheValueOnceTheCallerHasTheEvent(): Unit =
    runBlocking {
      val value = mapOf("result" to "300")
      val tool =
        DummyTool(name = "monitor") { _, _ ->
          flowOf(Event(content = modelMessage("watching")), "300")
        }
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the value") { it.answers("c1", value) },
          Respond(LlmResponse(turnComplete = true)),
        )
      val model = DummyLiveModel(script)
      val agent = LlmAgent(name = "live_agent", model = model, tools = listOf(tool))
      val queue = LiveRequestQueue()
      var sentWhileHandling: Boolean? = null

      val unused =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig())
          .onEach { event ->
            if (event.branch == "monitor@c1") {
              // Gives a tool that runs ahead time to send its value before the caller is done.
              delay(50.milliseconds)
              sentWhileHandling =
                model.connections.flatMap { it.sent }.any { it.answers("c1", value) }
            }
            if (event.turnComplete) queue.close()
          }
          .toList()

      assertThat(sentWhileHandling).isFalse()
    }

  @Test
  fun runLive_dropWhileAToolEventWaitsForTheCaller_deliversItOnceOnTheNextConnection(): Unit =
    runBlocking {
      val value = mapOf("result" to "300")
      val toolGo = CompletableDeferred<Unit>()
      val first =
        listOf(
          resumptionHandle("h1"),
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the pending answer") { it.answers("c1", pendingAnswer) },
          Respond(LlmResponse(content = modelMessage("ok"))),
          AwaitSent("the caller's note") { it.isText("noted") },
        )
      val second =
        listOf(
          AwaitSent("the value") { it.answers("c1", value) },
          Respond(LlmResponse(turnComplete = true)),
        )
      // The note's write drops the connection while the caller still holds the model's "ok".
      val model =
        DummyLiveModel.perConnection(listOf(first, second), onSend = dropOnWriteOf("noted"))
      val agent =
        LlmAgent(name = "live_agent", model = model, tools = listOf(watchingTool(toolGo, "300")))
      val queue = LiveRequestQueue()
      var connectionsAtDelivery = 0
      var valueSentBeforeDelivery: Boolean? = null

      val events =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig())
          .onEach { event ->
            if (event.content?.parts?.any { it.text == "ok" } == true) {
              // The tool's event now waits for the caller, who is still busy with this one.
              toolGo.complete(Unit)
              queue.sendContent(userMessage("noted"))
              delay(100.milliseconds)
            }
            if (event.branch == "monitor@c1") {
              connectionsAtDelivery = model.connections.size
              valueSentBeforeDelivery =
                model.connections.flatMap { it.sent }.any { it.answers("c1", value) }
            }
            if (event.turnComplete) queue.close()
          }
          .toList()

      assertThat(events.count { it.branch == "monitor@c1" }).isEqualTo(1)
      assertThat(connectionsAtDelivery).isEqualTo(2)
      assertThat(valueSentBeforeDelivery).isFalse()
    }

  @Test
  fun runLive_answerBehindAToolEventTheCallerHolds_doesNotHoldTheNextTypedTurn(): Unit =
    runBlocking {
      val toolGo = CompletableDeferred<Unit>()
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          // The caller's activity signal, sent while it holds the tool's event, prompts an answer.
          AwaitSent("the activity start") { it == RealtimeInput.ActivityStart },
          Respond(LlmResponse(content = modelMessage(FIRST_ANSWER))),
          AwaitSent("the typed turn") { it.isText(SECOND_TURN) },
          Respond(LlmResponse(content = modelMessage(SECOND_ANSWER), turnComplete = true)),
        )
      val model = DummyLiveModel(script)
      val agent =
        LlmAgent(name = "live_agent", model = model, tools = listOf(watchingTool(toolGo, "300")))
      val queue = LiveRequestQueue()
      var sentWhileHandling: Boolean? = null

      val events =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig())
          .onEach { event ->
            if (event.content?.parts?.any { it.functionCall != null } == true) toolGo.complete(Unit)
            if (event.branch == "monitor@c1") {
              queue.sendActivityStart()
              // Lets the model's answer arrive; it now waits behind this event.
              delay(50.milliseconds)
              sentWhileHandling = sendNextAndWait(queue, model)
            }
            if (event.turnComplete) queue.close()
          }
          .toList()

      assertThat(sentWhileHandling).isTrue()
      assertThat(events.mapNotNull { it.content?.parts?.firstOrNull()?.text })
        .containsAtLeast("watching", FIRST_ANSWER, SECOND_ANSWER)
        .inOrder()
    }

  @Test
  fun runLive_queuedTypedTurnsAfterAToolEvent_storeEachAnswerBeforeTheNextTurn(): Unit =
    runBlocking {
      val toolGo = CompletableDeferred<Unit>()
      val script =
        listOf(
          Respond(modelFunctionCallResponse("monitor", id = "c1")),
          AwaitSent("the first turn") { it.isText(FIRST_TURN) },
          Respond(LlmResponse(content = modelMessage(FIRST_ANSWER), turnComplete = true)),
          AwaitSent("the second turn") { it.isText(SECOND_TURN) },
          Respond(LlmResponse(content = modelMessage(SECOND_ANSWER), turnComplete = true)),
        )
      val agent =
        LlmAgent(
          name = "live_agent",
          model = DummyLiveModel(script),
          tools = listOf(watchingTool(toolGo, "300")),
        )
      val runner = InMemoryRunner(agent = agent)
      val queue = LiveRequestQueue()

      val unused =
        runner
          .runLive("u", "s", queue, RunConfig())
          .onEach { event ->
            if (event.content?.parts?.any { it.functionCall != null } == true) toolGo.complete(Unit)
            // The tool's event has been handed over, so the turns queued now keep their order.
            if (event.branch == "monitor@c1") {
              queue.sendContent(userMessage(FIRST_TURN))
              queue.sendContent(userMessage(SECOND_TURN))
            }
            if (event.hasText(SECOND_ANSWER)) queue.close()
          }
          .toList()

      assertThat(runner.storedTexts())
        .containsAtLeast(FIRST_TURN, FIRST_ANSWER, SECOND_TURN, SECOND_ANSWER)
        .inOrder()
    }

  @Test
  fun runLive_typedTurnSentWhileAnAnswerIsBeingStored_doesNotWaitForTheStore(): Unit = runBlocking {
    val model = twoTurnModel()
    val queue = LiveRequestQueue()
    var sentDuringStore: Boolean? = null
    // Sends the next turn from onEvent, which the runner runs before it stores the answer.
    val slowStore =
      object : Plugin {
        override val name = "slow-store"

        override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
          if (event.hasText(FIRST_ANSWER)) sentDuringStore = sendNextAndWait(queue, model)
          return event
        }
      }
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = model),
        plugins = listOf(slowStore),
      )
    queue.sendContent(userMessage(FIRST_TURN))

    val unused =
      runner
        .runLive("u", "s", queue, RunConfig())
        .onEach { if (it.hasText(SECOND_ANSWER)) queue.close() }
        .toList()

    assertThat(sentDuringStore).isTrue()
  }

  @Test
  fun runLive_queuedTypedTurns_storeEachAnswerBeforeTheNextTurn(): Unit = runBlocking {
    val queue = LiveRequestQueue()
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = twoTurnModel()))
    queue.sendContent(userMessage(FIRST_TURN))
    queue.sendContent(userMessage(SECOND_TURN))

    val unused =
      runner
        .runLive("u", "s", queue, RunConfig())
        .onEach { if (it.hasText(SECOND_ANSWER)) queue.close() }
        .toList()

    assertThat(runner.storedTexts())
      .containsExactly(FIRST_TURN, FIRST_ANSWER, SECOND_TURN, SECOND_ANSWER)
      .inOrder()
  }

  @Test
  fun runLive_slowCallerOnAStoredAnswer_doesNotHoldTheNextTypedTurn(): Unit = runBlocking {
    val model = twoTurnModel()
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage(FIRST_TURN))
    var sentWhileHandling: Boolean? = null

    val unused =
      runner
        .runLive("u", "s", queue, RunConfig())
        .onEach { event ->
          if (event.hasText(FIRST_ANSWER)) sentWhileHandling = sendNextAndWait(queue, model)
          if (event.hasText(SECOND_ANSWER)) queue.close()
        }
        .toList()

    assertThat(sentWhileHandling).isTrue()
    assertThat(runner.storedTexts())
      .containsExactly(FIRST_TURN, FIRST_ANSWER, SECOND_TURN, SECOND_ANSWER)
      .inOrder()
  }

  @Test
  fun runLive_stopRightAfterAnAnswer_storesTheTypedTurnQueuedBehindIt(): Unit = runBlocking {
    // The model answers on connect, so the queued turn waits until that answer is taken.
    val model =
      DummyLiveModel(
        listOf(Respond(LlmResponse(content = modelMessage(FIRST_ANSWER), turnComplete = true)))
      )
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage(FIRST_TURN))

    val unused = runner.runLive("u", "s", queue, RunConfig()).first { it.hasText(FIRST_ANSWER) }

    assertThat(runner.storedTexts()).containsExactly(FIRST_ANSWER, FIRST_TURN).inOrder()
    assertWithMessage("a stopped run does not send the turn")
      .that(model.connections.single().sent)
      .isEmpty()
  }

  @Test
  fun runLive_typedTurnFailsToStoreAfterAStop_callerStillGetsTheAnswer(): Unit = runBlocking {
    val model =
      DummyLiveModel(
        listOf(Respond(LlmResponse(content = modelMessage(FIRST_ANSWER), turnComplete = true)))
      )
    val storeTried = CompletableDeferred<Unit>()
    val failingStore =
      AppendRecordingSessionService(
        beforeAppend = {
          if (it.hasText(FIRST_TURN)) {
            storeTried.complete(Unit)
            error("store unavailable")
          }
        }
      )
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = model),
        sessionService = failingStore,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage(FIRST_TURN))

    // The failed store is only logged, so it does not replace the stop the caller asked for.
    val answer = runner.runLive("u", "s", queue, RunConfig()).first { it.hasText(FIRST_ANSWER) }

    assertThat(answer.content?.parts?.single()?.text).isEqualTo(FIRST_ANSWER)
    assertWithMessage("the stop tried to store the turn").that(storeTried.isCompleted).isTrue()
  }

  @Test
  fun runLive_stopRightAfterAnAnswer_storesTheQueuedTurnWithItsStateDelta(): Unit = runBlocking {
    val model =
      DummyLiveModel(
        listOf(Respond(LlmResponse(content = modelMessage(FIRST_ANSWER), turnComplete = true)))
      )
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))
    val queue = LiveRequestQueue()
    queue.send(
      LiveRequest(input = ContentInput(userMessage(FIRST_TURN)), stateDelta = mapOf("k" to "v"))
    )

    val unused = runner.runLive("u", "s", queue, RunConfig()).first { it.hasText(FIRST_ANSWER) }

    // One event carries both the turn and its state change.
    val turn = runner.storedEvents().filter { it.hasText(FIRST_TURN) }
    assertThat(turn.map { it.actions.stateDelta }).containsExactly(mapOf("k" to "v"))
    assertThat(runner.storedEvents().count { it.actions.stateDelta.isNotEmpty() }).isEqualTo(1)
  }

  @Test
  fun runLive_streamingToolEmitsAnEvent_leavesTheAgentsOutputKeyUnset(): Unit = runBlocking {
    val tool =
      DummyTool(name = "monitor") { _, _ ->
        flowOf(Event(content = modelMessage("watching")), "300")
      }
    val script =
      listOf(
        Respond(modelFunctionCallResponse("monitor", id = "c1")),
        AwaitSent("the value") { it.answers("c1", mapOf("result" to "300")) },
        Respond(LlmResponse(turnComplete = true)),
      )
    val agent =
      LlmAgent(
        name = "live_agent",
        model = DummyLiveModel(script),
        tools = listOf(tool),
        outputKey = "out",
      )
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()

    val events =
      runner
        .runLive("u", "s", queue, RunConfig())
        .onEach { if (it.turnComplete) queue.close() }
        .toList()

    assertThat(events.count { it.branch == "monitor@c1" }).isEqualTo(1)
    val session = runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))
    assertWithMessage("a tool's message to the caller is not the agent's output")
      .that(session?.state?.get("out"))
      .isNull()
  }

  companion object {
    private const val FIRST_TURN = "hi"
    private const val FIRST_ANSWER = "first"
    private const val SECOND_TURN = "and then?"
    private const val SECOND_ANSWER = "second"

    private val pendingAnswer =
      mapOf("status" to "The function is running asynchronously and the results are pending.")

    private fun resumptionHandle(handle: String) =
      Respond(
        LlmResponse(
          liveSessionResumptionUpdate =
            LiveServerSessionResumptionUpdate(newHandle = handle, resumable = true)
        )
      )

    /** Once [start] completes, sends the caller "watching", then [value] to the model. */
    private fun watchingTool(start: CompletableDeferred<Unit>, value: String) =
      DummyTool(name = "monitor") { _, _ ->
        flow {
          start.await()
          emit(Event(content = modelMessage("watching")))
          emit(value)
          awaitCancellation()
        }
      }

    /** Drops the first connection when the caller's [text] is written to it. */
    private fun dropOnWriteOf(text: String): suspend (Int, Any) -> Unit = { connection, message ->
      if (connection == 0 && message.isText(text)) {
        throw GenAiApiException(1006, "ConnectionClosed", "socket closed")
      }
    }

    /** Answers [FIRST_TURN] with [FIRST_ANSWER], then [SECOND_TURN] with [SECOND_ANSWER]. */
    private fun twoTurnModel() =
      DummyLiveModel(
        listOf(
          AwaitSent("the first turn") { it.isText(FIRST_TURN) },
          Respond(LlmResponse(content = modelMessage(FIRST_ANSWER), turnComplete = true)),
          AwaitSent("the second turn") { it.isText(SECOND_TURN) },
          Respond(LlmResponse(content = modelMessage(SECOND_ANSWER), turnComplete = true)),
        )
      )

    private fun Event.hasText(text: String): Boolean =
      content?.parts?.any { it.text == text } == true

    /** The texts of the events [this] runner stored, in order. */
    private suspend fun InMemoryRunner.storedTexts(): List<String> =
      storedEvents().mapNotNull { it.content?.parts?.firstOrNull()?.text }

    /** Sends [SECOND_TURN] and returns whether it reaches [model] before this call returns. */
    private suspend fun sendNextAndWait(queue: LiveRequestQueue, model: DummyLiveModel): Boolean {
      queue.sendContent(userMessage(SECOND_TURN))
      return withTimeoutOrNull(5.seconds) {
        while (model.connections.flatMap { it.sent }.none { it.isText(SECOND_TURN) }) {
          delay(10.milliseconds)
        }
      } != null
    }

    private fun Any.isText(text: String): Boolean =
      this is ContentInput && content.parts.any { it.text == text }

    private fun Any.answers(callId: String, response: Map<String, Any?>): Boolean =
      this is ContentInput &&
        content.parts.any { part ->
          part.functionResponse?.id == callId && part.functionResponse.response == response
        }
  }
}
