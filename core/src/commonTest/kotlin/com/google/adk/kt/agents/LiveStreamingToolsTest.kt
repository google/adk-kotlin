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
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Test

class LiveStreamingToolsTest {

  /** A tool context for call [callId], on a throwaway invocation. */
  private fun streamingToolContext(callId: String) =
    ToolContext(
      invocationContext =
        InvocationContext(
          session = Session(key = SessionKey("app", "user", "s")),
          agent = LlmAgent(name = "agent", model = DummyModel("model")),
        ),
      functionCallId = callId,
    )

  /** The content a live run queues to answer call [callId] to `monitor`. */
  private fun answerTo(callId: String) =
    Content(
      Role.USER,
      listOf(
        Part(
          functionResponse =
            FunctionResponse(name = "monitor", id = callId, response = mapOf("status" to "x"))
        )
      ),
    )

  /** Starts [tool]'s stream for [callId], as a run does once the pending answer is queued. */
  private suspend fun LiveStreamingTools.startStream(tool: DummyTool, callId: String) {
    val unused = run(tool, streamingToolContext(callId), emptyMap())
    onAnswersQueued(answerTo(callId))
  }

  @Test
  fun onAnswersQueued_mayNotStart_neverStartsTheStream() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      var started = false
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { started = true } }

      val closed: List<Content>
      try {
        val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
        tools.onAnswersQueued(answerTo("c1"), mayStart = false)
        yield()
      } finally {
        closed = withContext(NonCancellable) { tools.close() }
      }

      assertWithMessage("a stream answered alongside a handover should never start")
        .that(started)
        .isFalse()
      assertThat(closed.flatMap { it.parts }.map { it.functionResponse?.id }).containsExactly("c1")
    }
  }

  @Test
  fun onAnswersQueued_callerHasHungUp_neverStartsTheStream(): Unit = runBlocking {
    val queue = LiveRequestQueue().apply { close() }
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    var started = false
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { started = true } }

    val closed: List<Content>
    try {
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      yield()
    } finally {
      closed = withContext(NonCancellable) { tools.close() }
    }

    assertWithMessage("a stream whose pending answer was dropped should never start")
      .that(started)
      .isFalse()
    // Answered as pending by `run`, so `close` still returns a final answer though it never ran.
    assertThat(closed.flatMap { it.parts }.map { it.functionResponse?.id }).containsExactly("c1")
  }

  @Test
  fun stopRunning_stopsTheStream_stopAllStillReturnsItsFinalAnswer() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))

      tools.stopRunning()

      val stopped = tools.stopAll().flatMap { it.parts }.map { it.functionResponse?.id }
      val closed = tools.close()

      assertThat(stopped).containsExactly("c1")
      assertThat(closed).isEmpty()
    }
  }

  @Test
  fun onBeforeConnect_cancelsAStreamThatNeverStarted_closeStillReturnsItsFinalAnswer() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }

      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onBeforeConnect()

      assertThat(tools.close().flatMap { it.parts }.map { it.functionResponse?.id })
        .containsExactly("c1")
    }
  }

  @Test
  fun close_afterARefusedRepeat_returnsAFinalAnswerForTheRepeatToo() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))

      val repeat = tools.run(tool, streamingToolContext("c2"), emptyMap())

      val stopped = tools.close().flatMap { it.parts }.map { it.functionResponse?.id }

      assertThat((repeat as Map<*, *>)["status"].toString()).contains("already running")
      assertThat(stopped).containsExactly("c1", "c2")
    }
  }

  @Test
  fun close_sameCallIdOwedTwice_returnsOneFinalAnswer() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onBeforeConnect()
      // Re-running the same id after onBeforeConnect owes it twice, but close answers each id once.
      val again = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))

      val stopped = tools.close().flatMap { it.parts }.map { it.functionResponse?.id }

      assertThat((again as Map<*, *>)["status"].toString()).contains("pending")
      assertThat(stopped).containsExactly("c1")
    }
  }

  @Test
  fun close_repeatOfAReplacedCall_returnsAFinalAnswerForTheRepeat() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      val repeat = tools.run(tool, streamingToolContext("c2"), emptyMap())

      // c1 again with other arguments replaces the first, so it must inherit c2's final answer.
      val replacement = tools.run(tool, streamingToolContext("c1"), mapOf("symbol" to "silver"))
      tools.onAnswersQueued(answerTo("c1"))

      val stopped = tools.close().flatMap { it.parts }.map { it.functionResponse?.id }

      assertThat((repeat as Map<*, *>)["status"].toString()).contains("already running")
      assertThat((replacement as Map<*, *>)["status"].toString()).contains("pending")
      assertThat(stopped).containsExactly("c1", "c2")
    }
  }

  @Test
  fun close_streamThatEndedOnItsOwn_returnsAFinishedAnswerForItAndItsRepeat() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val release = CompletableDeferred<Unit>()
      val tool =
        DummyTool(name = "ticker") { _, _ ->
          flow {
            release.await()
            emit("tick")
          }
        }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      val repeat = tools.run(tool, streamingToolContext("c2"), emptyMap())

      release.complete(Unit)
      yield()
      val closed = tools.close().flatMap { it.parts }.mapNotNull { it.functionResponse }

      assertThat((repeat as Map<*, *>)["status"].toString()).contains("already running")
      val finished = mapOf("status" to "ticker finished streaming; no more updates will arrive.")
      assertThat(closed.associate { it.id to it.response })
        .containsExactly("c1", finished, "c2", finished)
    }
  }

  @Test
  fun close_streamThatFailed_returnsAnErrorAnswerWithoutTheErrorText() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "ticker") { _, _ -> flow<String> { error("secret detail") } }
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))

      yield()
      val closed = tools.close().flatMap { it.parts }.mapNotNull { it.functionResponse }

      assertThat(closed.map { it.id to it.response })
        .containsExactly(
          "c1" to
            mapOf(
              "status" to "ticker stopped streaming after an error; no more updates will arrive."
            )
        )
    }
  }

  @Test
  fun stopAll_streamWhoseCleanupThrows_sendsNoErrorAnswer(): Unit = runBlocking {
    val queue = LiveRequestQueue()
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    val stream = flow<String> { awaitCancellation() }.onCompletion { error("cleanup failed") }
    val tool = DummyTool(name = "monitor") { _, _ -> stream }
    val closed: List<Content>
    try {
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      yield()
    } finally {
      closed = withContext(NonCancellable) { tools.close() }
    }
    queue.close()

    assertWithMessage("a stream that was stopped sent an answer")
      .that(queue.receiveRequest())
      .isNull()
    // Its stop still records a final answer; only the queue must stay empty.
    assertThat(closed.flatMap { it.parts }.map { it.functionResponse?.id }).containsExactly("c1")
  }

  @Test
  fun stopAll_valuesAlreadyQueued_areNeverReceived(): Unit = runBlocking {
    val queue = LiveRequestQueue()
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    val queued = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()
    // One stream is still running at the stop, and one has already ended.
    val running =
      DummyTool(name = "monitor") { _, _ ->
        flow {
          emit("tick")
          queued.complete(Unit)
          awaitCancellation()
        }
      }
    val finite =
      DummyTool(name = "counter") { _, _ ->
        flow { emit("last") }.onCompletion { ended.complete(Unit) }
      }
    try {
      for ((tool, id) in listOf(running to "c1", finite to "c2")) {
        val unused = tools.run(tool, streamingToolContext(id), emptyMap())
        tools.onAnswersQueued(answerTo(id))
      }
      queued.await()
      ended.await()
    } finally {
      withContext(NonCancellable) { tools.close() }
    }
    queue.close()

    assertWithMessage("a stopped stream's queued value was received")
      .that(queue.receiveRequest())
      .isNull()
  }

  @Test
  fun stopAll_streamRunning_leavesNoStreamToStop() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val stop = DummyTool(name = "stop_streaming") { _, _ -> "ran" }
      val stopped: List<Content>
      val closed: List<Content>

      val answer =
        try {
          tools.startStream(tool, "c1")
          stopped = tools.stopAll()
          tools.run(stop, streamingToolContext("s1"), mapOf("function_name" to "monitor"))
        } finally {
          closed = withContext(NonCancellable) { tools.close() }
        }

      assertThat(stopped).hasSize(1)
      assertThat(answer)
        .isEqualTo(mapOf("status" to "No active streaming function named monitor found"))
      assertThat(closed).isEmpty()
    }
  }

  @Test
  fun requests_inputClosedBeforeCollection_completesEmpty() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      tools.closeInput()
      try {
        assertThat(tools.requests().toList()).isEmpty()
      } finally {
        withContext(NonCancellable) { tools.close() }
      }
    }
  }

  @Test
  fun run_stopStreaming_stopsOnlyTheNamedTool() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val monitor = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
      val news = DummyTool(name = "news") { _, _ -> flow<String> { awaitCancellation() } }
      val stop = DummyTool(name = "stop_streaming") { _, _ -> "ran" }
      val closed: List<Content>

      val answer =
        try {
          tools.startStream(monitor, "c1")
          tools.startStream(news, "n1")
          tools.run(stop, streamingToolContext("s1"), mapOf("function_name" to "monitor"))
        } finally {
          closed = withContext(NonCancellable) { tools.close() }
        }

      assertThat(answer)
        .isEqualTo(mapOf("status" to "Successfully stopped streaming function monitor"))
      // Only news is still streaming when the run ends.
      assertThat(closed.flatMap { it.parts }.map { it.functionResponse?.id }).containsExactly("n1")
    }
  }

  @Test
  fun run_stopStreamingCancelledWhileItWaits_endsAtOnce() {
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val started = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      // Outlasts the one-second stop timeout, so the stop is still waiting when its caller cancels.
      val stubborn =
        DummyTool(name = "monitor") { _, _ ->
          flow<String> {
            try {
              started.complete(Unit)
              awaitCancellation()
            } finally {
              cancelled.complete(Unit)
              withContext(NonCancellable) { delay(1500.milliseconds) }
            }
          }
        }
      val stop = DummyTool(name = "stop_streaming") { _, _ -> "ran" }

      val joined =
        try {
          tools.startStream(stubborn, "c1")
          started.await()
          val stopCall = async {
            tools.run(stop, streamingToolContext("s1"), mapOf("function_name" to "monitor"))
          }
          cancelled.await()
          stopCall.cancel()
          withTimeoutOrNull(500.milliseconds) { stopCall.join() }
        } finally {
          withContext(NonCancellable) { tools.close() }
        }

      assertWithMessage("the stop should end once its caller is cancelled").that(joined).isNotNull()
    }
  }
}
