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

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRequestQueue
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
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.outputTranscriptionStep
import com.google.adk.kt.testing.simpleAudioTurn
import com.google.adk.kt.testing.storedEvents
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.Node
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    copy(inputAudioTranscription = null, outputAudioTranscription = null)

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
   * Rejects an append or flush that starts while another is still running, as a stale-checking
   * store does.
   */
  private class OverlapRejectingSessionService(
    private val delegate: SessionService = InMemorySessionService()
  ) : SessionService by delegate {
    private var busy = false
    var flushes = 0
      private set

    override suspend fun appendEvent(session: Session, event: Event): Event = exclusively {
      delegate.appendEvent(session, event)
    }

    override suspend fun flush(key: SessionKey?) {
      exclusively { flushes += 1 }
    }

    private suspend fun <T> exclusively(call: suspend () -> T): T {
      check(!busy) { "Stale session: a call overlapped another" }
      busy = true
      try {
        // Holds the call open long enough for an unlocked caller to overlap it.
        delay(5)
        return call()
      } finally {
        busy = false
      }
    }
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

    assertEquals(listOf("echo: one", "echo: two"), events.map { it.content?.parts?.single()?.text })
  }

  @Test
  fun runLive_putsTheQueueOnTheInvocationContext(): Unit = runBlocking {
    // The queue carries the caller's input to the agent; a live run has no initial message.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()
    queue.close()

    runner.runLive("u", "s", queue).toList()

    assertSame(queue, agent.seenContext?.liveRequestQueue)
  }

  @Test
  fun runLive_closingTheQueue_endsTheRun(): Unit = runBlocking {
    // If the run ignored the closed queue, toList() would never return and hang the test.
    val runner = InMemoryRunner(agent = LiveEchoAgent())
    val queue = LiveRequestQueue()
    queue.close()

    assertEquals(emptyList(), runner.runLive("u", "s", queue).toList())
  }

  @Test
  fun runLive_createsTheSessionWhenItDoesNotExist(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)
    val queue = LiveRequestQueue()
    queue.close()

    runner.runLive("u", "brand-new-session", queue).toList()

    assertNotNull(agent.seenContext?.session)
    assertEquals("brand-new-session", agent.seenContext?.session?.key?.id)
  }

  @Test
  fun runLive_noRunConfig_requestsBothTranscriptions(): Unit = runBlocking {
    // As in ADK Python, a run without a config gets RunConfig's defaults, which transcribe both.
    val model = DummyLiveModel(simpleAudioTurn())
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = model))

    val unused = runLiveWith(runner)

    val config = model.connectRequests.single().liveConnectConfig
    assertEquals(AudioTranscriptionConfig(), config.inputAudioTranscription)
    assertEquals(AudioTranscriptionConfig(), config.outputAudioTranscription)
  }

  @Test
  fun runLive_multiAgentRootWithTranscriptionsOff_keepsThemOff(): Unit = runBlocking {
    // As in ADK Python, a multi-agent root only warns; the caller's choice reaches the model.
    val config = liveConnectConfigFor(RunConfig(responseModalities = listOf(Modality.AUDIO)).off())
    assertNull(config.inputAudioTranscription)
    assertNull(config.outputAudioTranscription)
  }

  @Test
  fun runLive_multiAgentRootWithOutputTranscriptionOff_keepsItOff(): Unit = runBlocking {
    val config = liveConnectConfigFor(RunConfig(outputAudioTranscription = null))
    assertEquals(AudioTranscriptionConfig(), config.inputAudioTranscription)
    assertNull(config.outputAudioTranscription)
  }

  @Test
  fun runLive_multiAgentRootWithItsOwnTranscriptionConfig_keepsIt(): Unit = runBlocking {
    val own = AudioTranscriptionConfig(languageCodes = listOf("en-US"))
    val config =
      liveConnectConfigFor(
        RunConfig(
          responseModalities = listOf(Modality.AUDIO),
          inputAudioTranscription = own,
          outputAudioTranscription = own,
        )
      )
    assertEquals(own, config.inputAudioTranscription)
    assertEquals(own, config.outputAudioTranscription)
  }

  @Test
  fun runLive_singleAgentRootWithTranscriptionsOff_keepsThemOff(): Unit = runBlocking {
    val config =
      liveConnectConfigFor(
        RunConfig(responseModalities = listOf(Modality.AUDIO)).off(),
        subAgents = emptyList(),
      )
    assertNull(config.inputAudioTranscription)
    assertNull(config.outputAudioTranscription)
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

    assertEquals(2, model.connections.size)
    assertEquals(
      listOf(userMessage("My name is Ada."), modelMessage("Hi Ada!")),
      model.connections[1].sent.filterIsInstance<List<*>>().singleOrNull(),
      "the second connection should open with the first run's spoken turns as history",
    )
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

    assertEquals(2, streamed.size, "the caller still receives the frames")
    assertEquals(emptyList(), runner.storedEvents(), "video and image must not reach the session")
  }

  @Test
  fun runAsync_leavesTheLiveQueueUnset(): Unit = runBlocking {
    // A turn-based run must not look live to the agent.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent)

    runner.runAsync("u", "s", newMessage = userMessage("hello")).toList()

    assertNull(agent.seenContext?.liveRequestQueue)
  }

  @Test
  fun runLive_doesNotPersistModelAudio(): Unit = runBlocking {
    // Only a saveLiveBlob artifact reference is stored; per-chunk audio would bloat the session.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioContent()))))

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller still receives the audio")
    assertEquals(emptyList(), runner.storedEvents(), "audio must not reach the session")
  }

  @Test
  fun runLive_persistsNonMediaEvents(): Unit = runBlocking {
    // The media rule is narrow: control events and text are the conversation and are kept.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))))

    val unused = runLiveWith(runner)

    assertEquals(listOf("hello"), runner.storedEvents().map { it.content?.parts?.single()?.text })
  }

  @Test
  fun runLive_persistsMediaHeldAsAnArtifactReference(): Unit = runBlocking {
    // An artifact reference has no inline data, so it is stored like any other event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioReference()))))

    val unused = runLiveWith(runner)

    assertEquals(1, runner.storedEvents().size, "an artifact reference is not inline media")
  }

  @Test
  fun runLive_doesNotPersistUppercaseMimeMedia(): Unit = runBlocking {
    // MIME types are case-insensitive; ADK Python lowercases them before this check too.
    val audio = modelMessage(Part(inlineData = Blob(mimeType = "AUDIO/PCM", data = ByteArray(8))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audio))))

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller still receives the audio")
    assertEquals(emptyList(), runner.storedEvents(), "audio must not reach the session")
  }

  @Test
  fun runLive_persistsNonMediaInlineData(): Unit = runBlocking {
    // Only audio, video and image are live media; other inline data is part of the conversation.
    val pdf =
      modelMessage(Part(inlineData = Blob(mimeType = "application/pdf", data = ByteArray(4))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(pdf))))

    val unused = runLiveWith(runner)

    assertEquals(1, runner.storedEvents().size, "non-media inline data is kept")
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

    assertEquals(
      listOf("artifact://live/audio.pcm"),
      runner.storedEvents().map { it.content?.parts?.single()?.fileData?.fileUri },
    )
  }

  @Test
  fun runLive_onEventPluginAddsInlineAudio_doesNotStoreIt(): Unit = runBlocking {
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))),
        plugins = listOf(plugin(rewrite = { it.copy(content = audioContent()) })),
      )

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller still receives the rewritten event")
    assertEquals(emptyList(), runner.storedEvents(), "audio must not reach the session")
  }

  @Test
  fun runLive_beforeRunAnswersWithInlineAudio_emitsItWithoutStoringIt(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent, plugins = listOf(plugin(breakWith = audioContent())))

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller receives the plugin's answer")
    assertEquals(emptyList(), runner.storedEvents(), "audio must not reach the session")
    assertNull(agent.seenContext, "the agent must not run once beforeRun has answered")
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

    assertEquals(1, runner.storedEvents().size, "an artifact reference is not inline media")
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

    assertEquals(1, afterRunCalls)
  }

  @Test
  fun runLive_doesNotPersistPartialEvents(): Unit = runBlocking {
    // Partials are skipped as in the turn-based rule; only the media rule is live-specific.
    val runner =
      InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(modelMessage("hal"), partial = true))))

    val unused = runLiveWith(runner)

    assertEquals(emptyList(), runner.storedEvents(), "a partial is not yet the final text")
  }

  @Test
  fun runAsync_persistsModelAudio(): Unit = runBlocking {
    // The control: the media rule is live-only, so a turn-based run still stores this event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioContent()))))

    runner.runAsync("u", "s", newMessage = userMessage("hello")).toList()

    val model = runner.storedEvents().filter { it.author == "model" }
    assertEquals(1, model.size, "a turn-based run keeps inline media")
  }

  @Test
  fun runLive_agentWithoutLiveSupport_throwsUnsupportedOperation(): Unit = runBlocking {
    // Silently producing nothing would look like a model that never answered.
    val runner = InMemoryRunner(agent = DummyAgent())

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertEquals("DummyAgent does not support live runs", error.message)
  }

  @Test
  fun runLive_modelWithoutLiveSupport_failsOutOfConnect(): Unit = runBlocking {
    // Refused by `Model.connect`'s default; Python likewise reaches `llm.connect` first.
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = turnOnlyModel()))

    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertEquals("live connections are not supported for turn-only-model", error.message)
  }

  @Test
  fun runLive_freshSession_runsTheRoot(): Unit = runBlocking {
    val child = LlmAgent(name = "child", model = turnOnlyModel("child-model"))
    val root =
      LlmAgent(name = "root", model = turnOnlyModel("root-model"), subAgents = listOf(child))
    val runner = InMemoryRunner(agent = root)

    // Each model refuses to connect naming itself, which shows whose turn loop ran.
    val error = assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertEquals("live connections are not supported for root-model", error.message)
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

    assertEquals("live connections are not supported for child-model", error.message)
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
    assertTrue(childEvents.isNotEmpty(), "the routed agent should have run")
    assertEquals(listOf("child"), childEvents.map { it.branch }.distinct())
    val history = childModel.connectRequests.single().contents
    assertTrue(
      history.any { content -> content.parts.any { it.text == "earlier, on my branch" } },
      "the routed agent should see its own branched turn",
    )
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
    assertTrue(childEvents.isNotEmpty(), "the routed agent should have run")
    assertEquals(listOf(null), childEvents.map { it.branch }.distinct())
  }

  @Test
  fun runLive_runnerAndTurnAppends_neverOverlap(): Unit = runBlocking {
    // The session service fails the run if a runner append overlaps a live-turn append.
    val script = listOf(wholeText("one"), wholeText("two"), wholeText("three"), turnCompleteStep())
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = DummyLiveModel(script)),
        sessionService = OverlapRejectingSessionService(),
      )
    val queue = LiveRequestQueue()
    repeat(3) { queue.sendContent(userMessage("hi $it")) }

    val events =
      withTimeout(10.seconds) {
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
      }

    assertTrue(events.any { it.turnComplete })
  }

  @Test
  fun runLive_flushNeverOverlapsAnAppend(): Unit = runBlocking {
    // The flush runs inside the append lock, so the turn cannot store a request meanwhile.
    val script =
      listOf(
        wholeText("one"),
        turnCompleteStep(),
        wholeText("two"),
        turnCompleteStep(),
        wholeText("three"),
        turnCompleteStep(),
      )
    val sessionService = OverlapRejectingSessionService()
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = DummyLiveModel(script)),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    repeat(3) { queue.sendContent(userMessage("hi $it")) }

    var turns = 0
    val unused =
      withTimeout(10.seconds) {
        runner
          .runLive("u", "s", queue)
          .onEach { if (it.turnComplete && ++turns == 3) queue.close() }
          .toList()
      }

    // One flush at each turn's end, then one at the run's end.
    assertEquals(4, sessionService.flushes)
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

    assertEquals(listOf<Throwable>(error), seen)
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

    assertEquals(emptyList(), seen)
  }

  @Test
  fun runLive_runnerWithoutOverride_failsWhenCollectedWithUnsupportedOperation(): Unit =
    runBlocking {
      // Cold like every other live refusal, so it surfaces where the caller collects.
      val runner = TurnOnlyRunner(InMemoryRunner(agent = LiveEchoAgent()))

      val flow = runner.runLive("u", "s", LiveRequestQueue())
      val error = assertFailsWith<UnsupportedOperationException> { flow.toList() }

      assertEquals("TurnOnlyRunner does not support live runs", error.message)
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

    assertEquals("This runner does not support live runs", error.message)
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

    assertEquals(1, runner.storedEvents().count { it.author == "model" })
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

    assertEquals(0, sessionService.appended.count { it.author == "model" })
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

    assertEquals("A live session append did not finish in 10s.", error.message)
    assertEquals(
      listOf("user"),
      sessionService.appended.map { it.author },
      "the live turn's own append of the user's turn should go through",
    )
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_holderIgnoringCancellation_failsTheRunWithItsAppendBound(): Unit = runTest {
    // Storing the second message ignores cancellation for 15 s while the runner waits for the lock
    // with no timeout of its own, so the run fails with the holder's append bound once it lets go.
    val sessionService =
      AppendRecordingSessionService(
        beforeAppend = {
          if (it.content?.parts?.singleOrNull()?.text == "second") {
            withContext(NonCancellable) { delay(15.seconds) }
          }
        }
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
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first"))
    queue.sendContent(userMessage("second"))

    val error =
      assertFailsWith<IllegalStateException> {
        withTimeout(60.seconds) { runner.runLive("u", "s", queue).toList() }
      }

    assertEquals("A live session append did not finish in 10s.", error.message)
    assertEquals(15.seconds.inWholeMilliseconds, testScheduler.currentTime)
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_cancelledWhileAStateDeltaWaitsForTheLock_stopsWaiting(): Unit = runTest {
    // A state-only request's wait for the lock stays cancellable, so ending the run does not hang.
    // runTest's virtual time keeps the 1 s cancel bound below the fake connection's 5 s wait.
    var held: InvocationContext? = null
    val holder =
      object : Plugin {
        override val name = "lock-holder"

        override suspend fun beforeRun(
          invocationContext: InvocationContext
        ): CallbackChoice<Unit, Content> {
          invocationContext.sessionAppendLock.lock()
          held = invocationContext
          return CallbackChoice.Continue(Unit)
        }
      }
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = DummyLiveModel(emptyList())),
        sessionService = sessionService,
        plugins = listOf(holder),
      )
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    val run = launch { runner.runLive("u", "s", queue).toList() }
    runCurrent()
    assertTrue(run.isActive, "the run should be waiting for the held lock")

    try {
      withTimeout(1.seconds) { run.cancelAndJoin() }
    } finally {
      // Frees a run stuck on the lock, so a regression fails here instead of hanging the test.
      checkNotNull(held).sessionAppendLock.unlock()
    }

    assertTrue(sessionService.appended.none { it.actions.stateDelta["k"] == "v" })
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

    assertEquals("A live session flush did not finish in 10s.", error.message)
    // The flush after the failure times out too, and is attached to the failure.
    assertEquals(listOf("A live session flush did not finish in 10s."), suppressedMessages(error))
  }

  @Test
  fun runLive_endFlushHangs_failsAfterTheBound(): Unit = runTest {
    // The run itself completes, so only the end-of-run flush can hang it.
    val sessionService = AppendRecordingSessionService(beforeFlush = { awaitCancellation() })
    val runner = InMemoryRunner(agent = LiveEchoAgent(), sessionService = sessionService)

    val error =
      assertFailsWith<IllegalStateException> { withTimeout(60.seconds) { runLiveWith(runner) } }

    assertEquals("A live session flush did not finish in 10s.", error.message)
  }

  @Test
  fun runLive_slowAppendThenSlowFlush_doesNotFailTheTurn(): Unit = runTest {
    // The append and the flush take the lock separately, so the turn's append can run between.
    val queue = LiveRequestQueue()
    val sessionService =
      AppendRecordingSessionService(
        beforeAppend = {
          if (it.content?.parts?.singleOrNull()?.text == "hello") {
            // The turn now waits for the lock while the runner holds it.
            queue.sendContent(userMessage("second"))
            delay(6.seconds)
          }
        },
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
    queue.sendContent(userMessage("first"))

    val unused =
      withTimeout(60.seconds) {
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
      }

    val storedTexts = sessionService.appended.mapNotNull { it.content?.parts?.singleOrNull()?.text }
    assertTrue("second" in storedTexts, "the turn's append should have gone through")
    model.connections.forEach { it.assertAllWaitsMet() }
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

    assertEquals(listOf("append", "flush", "emit", "flush"), sessionService.calls)
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

    assertEquals(listOf("append", "emit", "append", "flush", "emit", "flush"), sessionService.calls)
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

    assertEquals(listOf("append", "append", "append", "flush", "flush"), calls)
  }

  @Test
  fun runLive_spokenTurn_flushesOnceForTheTurnAndOnceAtTheEnd(): Unit = runBlocking {
    val calls = flushCallsFor(spokenTurn())

    // Usage, both finished transcripts and the turn's end are stored; audio and chunks are not.
    assertEquals(listOf("append", "append", "append", "append", "flush", "flush"), calls)
  }

  @Test
  fun runLive_recordingSavedAfterTheTurnEnd_isFlushedByTheRunEnd(): Unit = runBlocking {
    // A recording is saved in the background after its turn ends, so the next flush covers it.
    val calls = flushCallsFor(spokenTurn(), RunConfig(saveLiveBlob = true))

    assertEquals(listOf("append", "append", "append", "append", "flush", "append", "flush"), calls)
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

    assertEquals(listOf("append", "append", "append", "flush", "flush"), calls)
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
    assertTrue(handover >= 0, "the handover event was not stored")
    val appendCalls = sessionService.calls.withIndex().filter { it.value == "append" }
    assertEquals("flush", sessionService.calls.getOrNull(appendCalls[handover].index + 1))
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

    assertTrue(sessionService.appended.none { it.content?.parts?.firstOrNull()?.text == "stop" })
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

    assertTrue(sessionService.appended.none { it.content?.parts?.firstOrNull()?.text == "stop" })
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

    assertEquals(listOf("flush"), sessionService.calls)
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

    assertEquals(listOf("flush"), sessionService.calls)
    assertEquals(listOf("flush failed"), suppressedMessages(error))
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

    assertEquals(emptyList(), sessionService.calls)
  }

  @Test
  fun runLive_appRootedOnAnAgentAsItsNode_runsThatAgentLive(): Unit = runBlocking {
    // An agent given as the App's root node is still an agent, so it runs live.
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(app = App(appName = "agent_node_app", rootNode = agent))

    val unused = runLiveWith(runner)

    assertNotNull(agent.seenContext, "the agent should have run live")
  }

  @OptIn(ExperimentalWorkflowApi::class)
  @Test
  fun runLive_nodeRootedRunner_isRefused(): Unit = runBlocking {
    var ran = false
    val node =
      object : Node {
        override val name = "solo"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          ran = true
          emit("ran")
        }
      }
    val runner = InMemoryRunner(app = App(appName = "node_app", rootNode = node))

    assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertFalse(ran, "a node-rooted runner must not run its workflow turn-based on a live run")
    assertNull(
      runner.sessionService.getSession(SessionKey(runner.appName, "u", "s")),
      "a refused run must not leave a session behind",
    )
  }

  @Test
  fun runLive_modelWithoutLiveSupport_hasAlreadyCreatedTheSession(): Unit = runBlocking {
    // The session exists before a late refusal; pinned so adding an early check is deliberate.
    val runner = InMemoryRunner(agent = LlmAgent(name = "live_agent", model = turnOnlyModel()))

    assertFailsWith<UnsupportedOperationException> { runLiveWith(runner) }

    assertNotNull(
      runner.sessionService.getSession(SessionKey(runner.appName, "u", "s")),
      "the session is created before the model is asked to connect",
    )
  }
}
