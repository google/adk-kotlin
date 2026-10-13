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
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
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
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Transcription
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

  private fun wholeText(text: String) =
    Respond(LlmResponse(content = modelMessage(text), partial = false))

  private fun turnComplete() = Respond(LlmResponse(turnComplete = true))

  /**
   * One spoken model turn: a transcription, one frame of audio, usage, the finished transcription
   * and the turn boundary, then a resumption handle on the next receive.
   */
  private fun simpleAudioTurn(): List<DummyLiveStep> =
    listOf(
      outputTranscription("hello there", finished = false),
      Respond(
        LlmResponse(
          content =
            modelMessage(
              Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(960)))
            )
        )
      ),
      Respond(
        LlmResponse(
          usageMetadata =
            UsageMetadata(promptTokenCount = 12, candidatesTokenCount = 34, totalTokenCount = 46)
        )
      ),
      outputTranscription("hello there", finished = true),
      turnComplete(),
      Respond(
        LlmResponse(
          liveSessionResumptionUpdate =
            LiveServerSessionResumptionUpdate(newHandle = "handle-after-turn", resumable = true)
        )
      ),
    )

  private fun outputTranscription(text: String, finished: Boolean) =
    Respond(
      LlmResponse(
        outputTranscription = Transcription(text = text, finished = finished),
        partial = !finished,
      )
    )

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

  /** The events a run persisted, as opposed to the ones it streamed to the caller. */
  private suspend fun persistedEvents(runner: InMemoryRunner): List<Event> =
    runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))?.events ?: emptyList()

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
    assertEquals(emptyList(), persistedEvents(runner), "video and image must not reach the session")
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
    assertEquals(emptyList(), persistedEvents(runner), "audio must not reach the session")
  }

  @Test
  fun runLive_persistsNonMediaEvents(): Unit = runBlocking {
    // The media rule is narrow: control events and text are the conversation and are kept.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))))

    val unused = runLiveWith(runner)

    assertEquals(listOf("hello"), persistedEvents(runner).map { it.content?.parts?.single()?.text })
  }

  @Test
  fun runLive_persistsMediaHeldAsAnArtifactReference(): Unit = runBlocking {
    // An artifact reference has no inline data, so it is stored like any other event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioReference()))))

    val unused = runLiveWith(runner)

    assertEquals(1, persistedEvents(runner).size, "an artifact reference is not inline media")
  }

  @Test
  fun runLive_doesNotPersistUppercaseMimeMedia(): Unit = runBlocking {
    // MIME types are case-insensitive; ADK Python lowercases them before this check too.
    val audio = modelMessage(Part(inlineData = Blob(mimeType = "AUDIO/PCM", data = ByteArray(8))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audio))))

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller still receives the audio")
    assertEquals(emptyList(), persistedEvents(runner), "audio must not reach the session")
  }

  @Test
  fun runLive_persistsNonMediaInlineData(): Unit = runBlocking {
    // Only audio, video and image are live media; other inline data is part of the conversation.
    val pdf =
      modelMessage(Part(inlineData = Blob(mimeType = "application/pdf", data = ByteArray(4))))
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(pdf))))

    val unused = runLiveWith(runner)

    assertEquals(1, persistedEvents(runner).size, "non-media inline data is kept")
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
      persistedEvents(runner).map { it.content?.parts?.single()?.fileData?.fileUri },
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
    assertEquals(emptyList(), persistedEvents(runner), "audio must not reach the session")
  }

  @Test
  fun runLive_beforeRunAnswersWithInlineAudio_emitsItWithoutStoringIt(): Unit = runBlocking {
    val agent = LiveEchoAgent()
    val runner = InMemoryRunner(agent = agent, plugins = listOf(plugin(breakWith = audioContent())))

    val streamed = runLiveWith(runner)

    assertEquals(1, streamed.size, "the caller receives the plugin's answer")
    assertEquals(emptyList(), persistedEvents(runner), "audio must not reach the session")
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

    assertEquals(1, persistedEvents(runner).size, "an artifact reference is not inline media")
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

    assertEquals(emptyList(), persistedEvents(runner), "a partial is not yet the final text")
  }

  @Test
  fun runAsync_persistsModelAudio(): Unit = runBlocking {
    // The control: the media rule is live-only, so a turn-based run still stores this event.
    val runner = InMemoryRunner(agent = ScriptedAgent(listOf(modelEvent(audioContent()))))

    runner.runAsync("u", "s", newMessage = userMessage("hello")).toList()

    val model = persistedEvents(runner).filter { it.author == "model" }
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
    val script = listOf(wholeText("one"), wholeText("two"), wholeText("three"), turnComplete())
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
    val script = listOf(wholeText("one"), wholeText("two"), wholeText("three"), turnComplete())
    val sessionService = OverlapRejectingSessionService()
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = DummyLiveModel(script)),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    repeat(3) { queue.sendContent(userMessage("hi $it")) }

    val unused =
      withTimeout(10.seconds) {
        runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()
      }

    // One flush per final response (three texts and the turn boundary), then one at the end.
    assertEquals(5, sessionService.flushes)
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

    assertEquals(1, persistedEvents(runner).count { it.author == "model" })
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
          turnComplete(),
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
  fun runLive_runnerWaitsTooLongForTheAppendLock_failsTheRunAfterTheBound(): Unit = runTest {
    // Storing the second message ignores cancellation, so the turn holds the lock past the bound.
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
          turnComplete(),
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

    assertEquals("A live session append waited over 10s for the session.", error.message)
    model.connections.forEach { it.assertAllWaitsMet() }
  }

  @Test
  fun runLive_flushHangs_failsTheRunAfterTheBound(): Unit = runTest {
    // Every flush hangs, including the one after the failure, and the run still ends.
    val sessionService = AppendRecordingSessionService(beforeFlush = { awaitCancellation() })
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))),
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
          turnComplete(),
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
  fun runLive_finalResponse_isFlushedAfterItsAppendAndBeforeTheCallerSeesIt(): Unit = runBlocking {
    // As in runAsync, so the caller never sees a final response the service has not persisted.
    val sessionService = AppendRecordingSessionService()
    val runner =
      InMemoryRunner(
        agent = ScriptedAgent(listOf(modelEvent(modelMessage("hello")))),
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    queue.close()

    val unused = runner.runLive("u", "s", queue).onEach { sessionService.calls += "emit" }.toList()

    assertEquals(listOf("append", "flush", "emit", "flush"), sessionService.calls)
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
