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

@file:OptIn(AdkJavaInteropApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class BasePublisherAgentTest {

  /** Publishes events from its sub-agents sequentially, as a Java orchestrator would. */
  private class SequencingAgent(name: String, subAgents: List<BaseAgent>) :
    BasePublisherAgent(name, subAgents = subAgents) {
    override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> =
      AsyncJavaHelpers.asPublisher(
        flow { for (subAgent in subAgents) emitAll(subAgent.runAsync(context)) }
      )
  }

  @Test
  fun subAgentPublishedAfterAnother_seesItsOutputKey(): Unit = runBlocking {
    // Arrange
    var seen: Any? = null
    val writer =
      LlmAgent(
        name = "writer",
        model = DummyModel("writer-model") { flowOf(LlmResponse(content = modelMessage("draft"))) },
        outputKey = "draft",
      )
    val reader =
      DummyAgent("reader", onRunAsync = { context -> seen = context.session.state["draft"] })
    val runner = InMemoryRunner(agent = SequencingAgent("sequencer", listOf(writer, reader)))

    // Act
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert
    assertEquals("draft", seen)
  }

  @Test
  fun stepAfterAStateChangingEvent_seesTheChange(): Unit = runBlocking {
    // Arrange
    var seen: Any? = null
    val agent =
      object : BasePublisherAgent("planner") {
        override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> =
          AsyncJavaHelpers.asPublisher(
            flow {
              emit(
                Event(
                  author = name,
                  actions = EventActions(stateDelta = mutableMapOf("plan" to "check invoices")),
                )
              )
              seen = context.session.state["plan"]
            }
          )
      }
    val runner = InMemoryRunner(agent = agent)

    // Act
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert
    assertEquals("check invoices", seen)
  }

  @Test
  fun publisherThatIgnoresDemand_deliversEveryEvent(): Unit = runBlocking {
    // Arrange: a publisher that sends every event on subscribe without waiting for requests.
    val agent =
      object : BasePublisherAgent("eager") {
        override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> =
          Publisher { subscriber ->
            subscriber.onSubscribe(
              object : Subscription {
                override fun request(n: Long) {}

                override fun cancel() {}
              }
            )
            repeat(100) { subscriber.onNext(Event(author = name)) }
            subscriber.onComplete()
          }
      }
    val runner = InMemoryRunner(agent = agent)

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert
    assertEquals(List(100) { "eager" }, events.map { it.author })
  }

  @Test
  fun publisherThatEmitsWhileSubscribing_deliversTheEvent(): Unit = runBlocking {
    // Arrange: a publisher that emits before subscribe returns, failing if nothing was requested.
    val agent =
      object : BasePublisherAgent("immediate") {
        override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> =
          Publisher { subscriber ->
            var requested = 0L
            subscriber.onSubscribe(
              object : Subscription {
                override fun request(n: Long) {
                  requested += n
                }

                override fun cancel() {}
              }
            )
            if (requested > 0) {
              subscriber.onNext(Event(author = name))
              subscriber.onComplete()
            } else {
              subscriber.onError(IllegalStateException("nothing requested"))
            }
          }
      }
    val runner = InMemoryRunner(agent = agent)

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert
    assertEquals(listOf("immediate"), events.map { it.author })
  }

  @Test
  fun collectionStoppedEarly_cancelsTheSubscription(): Unit = runBlocking {
    // Arrange: a publisher that sends one event per request and never completes.
    var cancelled = false
    val agent =
      object : BasePublisherAgent("endless") {
        override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> =
          Publisher { subscriber ->
            subscriber.onSubscribe(
              object : Subscription {
                override fun request(n: Long) {
                  repeat(n.toInt()) { subscriber.onNext(Event(author = name)) }
                }

                override fun cancel() {
                  cancelled = true
                }
              }
            )
          }
      }
    val runner = InMemoryRunner(agent = agent)

    // Act
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).first()

    // Assert
    assertTrue(cancelled)
  }

  @Test
  fun runCancelledBeforeOnSubscribe_cancelsTheLateSubscription(): Unit = runBlocking {
    // Arrange: a publisher that signals onSubscribe only after the run is cancelled.
    val subscriber = CompletableDeferred<Subscriber<in Event>>()
    var cancelled = false
    val agent =
      object : BasePublisherAgent("late") {
        override fun runAsyncImplJava(context: InvocationContext): Publisher<Event> = Publisher {
          subscriber.complete(it)
        }
      }
    val runner = InMemoryRunner(agent = agent)
    val run = launch {
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
    }

    // Act
    val late = subscriber.await()
    run.cancelAndJoin()
    late.onSubscribe(
      object : Subscription {
        override fun request(n: Long) {}

        override fun cancel() {
          cancelled = true
        }
      }
    )

    // Assert
    assertTrue(cancelled)
  }
}
