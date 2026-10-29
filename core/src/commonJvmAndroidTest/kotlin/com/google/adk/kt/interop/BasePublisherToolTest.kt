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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalLiveApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Behavior
import com.google.adk.kt.types.FunctionDeclaration
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class BasePublisherToolTest {

  private fun answers(callId: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
    message is ContentInput &&
      message.content.parts.any {
        it.functionResponse?.id == callId && it.functionResponse.response == response
      }
  }

  /** The n of a user's "ping n" message, or null. */
  private fun pingNumber(message: Any?): Int? =
    (message as? ContentInput)
      ?.content
      ?.parts
      ?.firstNotNullOfOrNull { it.text }
      ?.removePrefix("ping ")
      ?.toIntOrNull()

  /** A Java-style tool that publishes [items]. */
  private fun publishingTool(vararg items: String) =
    object : BasePublisherTool(TOOL_NAME, "Streams prices.") {
      override fun declaration(): FunctionDeclaration = FunctionDeclaration(name, description)

      override fun runPublisher(context: ToolContext, args: Map<String, Any?>): Publisher<String> =
        flowOf(*items).asPublisher()
    }

  @Test
  fun run_underALiveRun_isDeclaredNonBlockingAndStreamsEachValue() {
    runBlocking {
      val script =
        listOf(
          Respond(modelFunctionCallResponse(TOOL_NAME, id = CALL_ID)),
          AwaitSent("the pending answer", answers(CALL_ID, PENDING)),
          AwaitSent("the first price", answers(CALL_ID, mapOf("result" to "300"))),
          AwaitSent("the second price", answers(CALL_ID, mapOf("result" to "400"))),
          Respond(LlmResponse(turnComplete = true)),
        )
      val model = DummyLiveModel(script)
      val agent =
        LlmAgent(name = "agent", model = model, tools = listOf(publishingTool("300", "400")))
      val queue = LiveRequestQueue()

      val unused =
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
          .onEach { if (it.turnComplete) queue.close() }
          .toList()

      model.connections.single().assertAllWaitsMet()
      val declarations =
        model.connectRequests.single().config.tools.orEmpty().flatMap {
          it.functionDeclarations.orEmpty()
        }
      assertThat(declarations.single().behavior).isEqualTo(Behavior.NON_BLOCKING)
    }
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun isStreaming_anyPublisherTool_isTrue() {
    assertThat(publishingTool("300").isStreaming).isTrue()
  }

  @Test
  fun run_outsideALiveRun_fails() {
    runBlocking {
      val model =
        DummyModel.createSequential(
          "model",
          listOf(modelFunctionCallResponse(TOOL_NAME, id = CALL_ID)),
        )
      val agent = LlmAgent(name = "agent", model = model, tools = listOf(publishingTool("300")))

      val error =
        assertFailsWith<IllegalStateException> {
          InMemoryRunner(agent = agent)
            .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("run"))
            .toList()
        }

      assertThat(error)
        .hasMessageThat()
        .isEqualTo("Only a connected live run can stream a tool's Flow; return a value instead.")
    }
  }

  @Test
  fun run_subscriberBlockingOnTheLiveInput_doesNotHoldBackTheSends() {
    // The n of the first "ping n" the subscriber gets; its onNext blocks until the test ends.
    val blockedOn = AtomicInteger()
    val release = CountDownLatch(1)
    val error = AtomicReference<Throwable>()
    val subscriber =
      object : Subscriber<LiveRequest> {
        override fun onSubscribe(subscription: Subscription) {
          subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(request: LiveRequest) {
          val ping = pingNumber(request.input) ?: return
          blockedOn.compareAndSet(0, ping)
          // Bounded, so a stalled send loop fails the script's wait instead of hanging the test.
          release.await(10, TimeUnit.SECONDS)
        }

        override fun onError(t: Throwable) {
          error.set(t)
        }

        override fun onComplete() {}
      }
    val listener =
      object : BasePublisherTool("listen", "Listens to the user with a subscriber that blocks.") {
        override fun declaration(): FunctionDeclaration = FunctionDeclaration(name, description)

        override fun runPublisher(
          context: ToolContext,
          args: Map<String, Any?>,
        ): Publisher<String> =
          // Subscribes from the returned stream, the only place the live input is set.
          flow<String> {
              AsyncJavaHelpers.asPublisher(context.liveRequests).subscribe(subscriber)
              awaitCancellation()
            }
            .asPublisher()
      }
    val script =
      listOf(
        Respond(modelFunctionCallResponse("listen", id = CALL_ID)),
        // Only the blocked ping or a later one: an earlier one may be checked after the block.
        AwaitSent("a ping sent while the subscriber blocks") { message ->
          // A failed subscription never blocks, so report its error instead of the wait's timeout.
          error.get()?.let { throw AssertionError("The live input publisher failed", it) }
          val sent = pingNumber(message)
          sent != null && blockedOn.get() in 1..sent
        },
        Respond(LlmResponse(turnComplete = true)),
      )

    try {
      // Multi-threaded, so a blocked sender cannot also stall the script's timeout.
      runBlocking(Dispatchers.Default) {
        val model = DummyLiveModel(script)
        val agent = LlmAgent(name = "agent", model = model, tools = listOf(listener))
        val queue = LiveRequestQueue()
        // The tool starts listening asynchronously, so the user pings until it hears one.
        val pinger = launch {
          var n = 0
          while (true) {
            queue.sendContent(userMessage("ping ${++n}"))
            delay(20.milliseconds)
          }
        }
        try {
          val unused =
            InMemoryRunner(agent = agent)
              .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
              .onEach { if (it.turnComplete) queue.close() }
              .toList()
          assertThat(error.get()).isNull()
          model.connections.single().assertAllWaitsMet()
        } finally {
          pinger.cancel()
        }
      }
    } finally {
      release.countDown()
    }
  }

  companion object {
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")

    /** The name of the publishing tool the scripted model calls. */
    private const val TOOL_NAME = "prices"

    /** The id the scripted model gives that call. */
    private const val CALL_ID = "c1"
  }
}
