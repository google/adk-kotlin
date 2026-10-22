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
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

  @Test
  fun onAnswersQueued_callerHasHungUp_neverStartsTheStream(): Unit = runBlocking {
    val queue = LiveRequestQueue().apply { close() }
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    var started = false
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { started = true } }

    try {
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      yield()
    } finally {
      withContext(NonCancellable) { tools.close() }
    }

    assertWithMessage("a stream whose pending answer was dropped should never start")
      .that(started)
      .isFalse()
  }

  @Test
  fun stopAll_streamWhoseCleanupThrows_sendsNoErrorAnswer(): Unit = runBlocking {
    val queue = LiveRequestQueue()
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    val stream = flow<String> { awaitCancellation() }.onCompletion { error("cleanup failed") }
    val tool = DummyTool(name = "monitor") { _, _ -> stream }
    try {
      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onAnswersQueued(answerTo("c1"))
      yield()
    } finally {
      withContext(NonCancellable) { tools.close() }
    }
    queue.close()

    assertWithMessage("a stream that was stopped sent an answer")
      .that(queue.receiveRequest())
      .isNull()
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
}
