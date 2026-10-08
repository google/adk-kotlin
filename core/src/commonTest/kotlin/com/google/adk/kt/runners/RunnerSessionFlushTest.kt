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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.summarizer.EventSummarizer
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Node
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Covers when the runner flushes its session service, recorded against appends and emits. */
class RunnerSessionFlushTest {

  private val sessionService = RecordingSessionService()

  @Test
  fun runAsync_finalResponse_flushedAfterAppendAndBeforeEmit() = runBlocking {
    val agent =
      DummyAgent(AGENT) { context ->
        emit(agentEvent(context, content = modelFunctionCall("lookup", id = "call-1")))
        emit(agentEvent(context, content = userFunctionResponse("lookup", id = "call-1")))
        emit(agentEvent(context, content = modelMessage("done")))
      }

    runAndRecordEmits(runner(agent))

    assertEquals(
      listOf(
        "append:user",
        "append:call",
        "emit:call",
        "append:response",
        "emit:response",
        "append:text",
        "flush",
        "emit:text",
        "flush",
      ),
      sessionService.calls,
    )
  }

  @Test
  fun runAsync_postInvocationCompaction_flushedAfterCompactionEvent() = runBlocking {
    val summarizer =
      object : EventSummarizer {
        override suspend fun summarizeEvents(events: List<Event>): Event =
          compactionEvent(startTs = 0L, endTs = 0L)
      }
    val app =
      App(
        appName = APP,
        rootAgent = textAgent(),
        eventsCompactionConfig =
          EventsCompactionConfig(compactionInterval = 1, overlapSize = 0, summarizer = summarizer),
      )

    runAndRecordEmits(InMemoryRunner(app = app, sessionService = sessionService))

    assertEquals(listOf("append:compaction", "flush"), sessionService.calls.takeLast(2))
  }

  @Test
  fun runAsync_beforeRunEarlyExit_flushedBeforeEmit() = runBlocking {
    val plugin =
      object : Plugin {
        override val name: String = "halt"

        override suspend fun beforeRun(
          invocationContext: InvocationContext
        ): CallbackChoice<Unit, Content> = CallbackChoice.Break(modelMessage("halted"))
      }

    runAndRecordEmits(runner(textAgent(), plugins = listOf(plugin)))

    assertEquals(
      listOf("append:user", "append:text", "flush", "emit:text", "flush"),
      sessionService.calls,
    )
  }

  @Test
  fun runAsync_runFails_flushesAndRethrows() = runBlocking {
    val agent =
      DummyAgent(AGENT) { context ->
        emit(agentEvent(context, content = modelFunctionCall("lookup", id = "call-1")))
        throw IllegalStateException("agent failed")
      }

    assertFailsWith<IllegalStateException> { runAndRecordEmits(runner(agent)) }

    assertEquals(listOf("append:user", "append:call", "emit:call", "flush"), sessionService.calls)
  }

  @Test
  fun runAsync_nodeFails_flushesItsErrorEventAndRethrows() = runBlocking {
    assertFailsWith<IllegalStateException> { runAndRecordEmits(runnerWithRootNode(FailingNode())) }

    assertEquals(
      listOf("append:user", "append:error", "flush", "emit:error", "flush"),
      sessionService.calls,
    )
  }

  @Test
  fun runAsync_runAndFlushFail_rethrowsRunFailureWithFlushFailureSuppressed() = runBlocking {
    val agent = DummyAgent(AGENT) { throw IllegalStateException("agent failed") }
    val flushFailure = SessionException("flush failed")
    sessionService.flushFailures[1] = flushFailure

    val thrown = assertFailsWith<IllegalStateException> { runAndRecordEmits(runner(agent)) }

    // Stack-trace recovery may rethrow a copy that carries the original as its cause.
    val suppressed = thrown.suppressedExceptions + thrown.cause?.suppressedExceptions.orEmpty()
    assertTrue(suppressed.any { it.message == flushFailure.message }, "flush failure not attached")
  }

  @Test
  fun runAsync_flushAfterRunFailureCanceled_rethrowsCancellation() = runBlocking {
    val agent = DummyAgent(AGENT) { throw IllegalStateException("agent failed") }
    sessionService.flushFailures[1] = CancellationException("flush canceled")

    assertFailsWith<CancellationException> { runAndRecordEmits(runner(agent)) }

    assertEquals(listOf("append:user", "flush"), sessionService.calls)
  }

  @Test
  fun runAsync_endOfRunFlushFails_throwsAfterTheFinalResponseWithoutARunError() = runBlocking {
    sessionService.flushFailures[2] = SessionException("flush failed")
    val errors = mutableListOf<Throwable>()

    assertFailsWith<SessionException> {
      runAndRecordEmits(runner(textAgent(), plugins = listOf(errorRecorder(errors))))
    }

    assertEquals(
      listOf("append:user", "append:text", "flush", "emit:text", "flush"),
      sessionService.calls,
    )
    assertTrue(errors.isEmpty(), "a failed end-of-run flush is not a run error")
  }

  @Test
  fun runAsync_finalResponseFlushFails_rethrowsItWithoutEmitting() = runBlocking {
    // Both flushes throw the same instance, so the runner must not suppress it into itself.
    val flushFailure = SessionException("flush failed")
    sessionService.flushFailures[1] = flushFailure
    sessionService.flushFailures[2] = flushFailure
    val errors = mutableListOf<Throwable>()

    assertFailsWith<SessionException> {
      runAndRecordEmits(runner(textAgent(), plugins = listOf(errorRecorder(errors))))
    }

    assertEquals(listOf("append:user", "append:text", "flush", "flush"), sessionService.calls)
    assertEquals(listOf(flushFailure.message), errors.map { it.message })
  }

  @Test
  fun runAsync_collectorFails_flushesAndRethrows() = runBlocking {
    val agent =
      DummyAgent(AGENT) { context ->
        emit(agentEvent(context, content = modelFunctionCall("lookup", id = "call-1")))
        emit(agentEvent(context, content = modelMessage("done")))
      }

    assertFailsWith<IllegalStateException> {
      runner(agent).runAsync(USER, SESSION, newMessage = userMessage("hi")).collect {
        throw IllegalStateException("collector failed")
      }
    }

    assertEquals(listOf("append:user", "append:call", "flush"), sessionService.calls)
  }

  @Test
  fun runAsync_runFailsWithAnError_doesNotFlush() = runBlocking {
    val agent = DummyAgent(AGENT) { throw NotImplementedError("agent stub") }

    assertFailsWith<NotImplementedError> { runAndRecordEmits(runner(agent)) }

    assertEquals(listOf("append:user"), sessionService.calls)
  }

  @Test
  fun runAsync_collectorStopsEarly_doesNotFlush() = runBlocking {
    val agent =
      DummyAgent(AGENT) { context ->
        emit(agentEvent(context, content = modelFunctionCall("lookup", id = "call-1")))
        emit(agentEvent(context, content = modelMessage("done")))
      }

    val unused = runner(agent).runAsync(USER, SESSION, newMessage = userMessage("hi")).first()

    assertEquals(listOf("append:user", "append:call"), sessionService.calls)
  }

  @Test
  fun runAsync_resumedInvocationAlreadyFinal_flushesAppendedMessage() = runBlocking {
    val session = sessionService.delegate.createSession(KEY)
    val unusedUser =
      sessionService.delegate.appendEvent(
        session,
        Event(author = Role.USER, invocationId = "inv-1", content = userMessage("hi")),
      )
    val unusedEnd =
      sessionService.delegate.appendEvent(
        session,
        Event(author = AGENT, invocationId = "inv-1", actions = EventActions(endOfAgent = true)),
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = APP,
            rootAgent = textAgent(),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          ),
        sessionService = sessionService,
      )

    val events =
      runner
        .runAsync(USER, SESSION, invocationId = "inv-1", newMessage = userMessage("again"))
        .toList()

    assertTrue(events.isEmpty(), "a finished invocation should not run again")
    assertEquals(listOf("append:user", "flush"), sessionService.calls)
  }

  @Test
  fun rewindAsync_flushesRewindEvent() = runBlocking {
    val runner = runner(textAgent())
    val invocationId =
      runner.runAsync(USER, SESSION, newMessage = userMessage("hi")).toList().first().invocationId
    sessionService.calls.clear()

    runner.rewindAsync(USER, SESSION, rewindBeforeInvocationId = checkNotNull(invocationId))

    assertEquals(listOf("append:rewind", "flush"), sessionService.calls)
  }

  private fun runner(agent: BaseAgent, plugins: List<Plugin> = emptyList()): InMemoryRunner =
    InMemoryRunner(agent = agent, appName = APP, sessionService = sessionService, plugins = plugins)

  @OptIn(ExperimentalWorkflowApi::class)
  private fun runnerWithRootNode(rootNode: Node): InMemoryRunner =
    InMemoryRunner(app = App(appName = APP, rootNode = rootNode), sessionService = sessionService)

  /** A plugin that records each run error in [errors]. */
  private fun errorRecorder(errors: MutableList<Throwable>): Plugin =
    object : Plugin {
      override val name: String = "errors"

      override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
        errors += error
      }
    }

  private suspend fun runAndRecordEmits(runner: InMemoryRunner) {
    runner
      .runAsync(USER, SESSION, newMessage = userMessage("hi"))
      .onEach { sessionService.calls += "emit:${label(it)}" }
      .toList()
  }

  private companion object {
    const val APP = "flush_app"
    const val USER = "user"
    const val SESSION = "session"
    const val AGENT = "agent"
    val KEY = SessionKey(APP, USER, SESSION)

    fun textAgent(): DummyAgent =
      DummyAgent(AGENT) { context -> emit(agentEvent(context, content = modelMessage("done"))) }

    fun agentEvent(context: InvocationContext, content: Content): Event =
      Event(author = AGENT, invocationId = context.invocationId, content = content)

    /** A short, content-free name for [event], so recorded calls stay readable. */
    fun label(event: Event): String =
      when {
        event.actions.compaction != null -> "compaction"
        event.functionCalls().isNotEmpty() -> "call"
        event.functionResponses().isNotEmpty() -> "response"
        event.actions.rewindBeforeInvocationId != null -> "rewind"
        event.errorCode != null -> "error"
        event.author == Role.USER -> "user"
        else -> "text"
      }
  }

  /**
   * A [SessionService] over [InMemorySessionService] that records appends and flushes in [calls],
   * naming a flush of anything but [KEY] by its key, or `flush:all`.
   */
  private class RecordingSessionService(val delegate: SessionService = InMemorySessionService()) :
    SessionService by delegate {
    val calls: MutableList<String> = mutableListOf()

    /** Failures to throw, by the 1-based number of the flush that throws it. */
    val flushFailures: MutableMap<Int, Exception> = mutableMapOf()
    private var flushes = 0

    override suspend fun appendEvent(session: Session, event: Event): Event {
      val appended = delegate.appendEvent(session, event)
      calls += "append:${label(event)}"
      return appended
    }

    override suspend fun flush(key: SessionKey?) {
      calls +=
        when (key) {
          null -> "flush:all"
          KEY -> "flush"
          else -> "flush:$key"
        }
      flushes++
      flushFailures[flushes]?.let { throw it }
    }
  }

  /** A workflow node that fails as soon as it runs. */
  @OptIn(ExperimentalWorkflowApi::class)
  private class FailingNode : Node {
    override val name: String = "failing_node"

    override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
      throw IllegalStateException("node failed")
    }
  }
}
