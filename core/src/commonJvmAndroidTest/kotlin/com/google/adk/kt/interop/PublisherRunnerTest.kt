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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.workflow.Node
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class PublisherRunnerTest {

  @Test
  fun runAsync_subscriberCancelsAfterTheFirstEvent_runsAfterRunOnce() = runBlocking {
    // A canceled subscription is an early stop, as for ADK Python's sync run().
    val plugin = AfterRunRecorder()

    // awaitFirst() cancels the subscription once the first event arrives.
    val first =
      runnerWith(threeEvents(), plugin).runAsync("user", "s", newMessage = go()).awaitFirst()

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(first.content?.parts?.single()?.text).isEqualTo("e0")
    assertThat(plugin.afterRuns).isEqualTo(1)
    assertThat(plugin.runErrors).isEmpty()
  }

  @Test
  fun runAsync_subscriberCancelsWhileTheAgentWaits_runsAfterRunOnce() = runBlocking {
    // The plugin's own cancellation in afterRun must not reach the subscriber as an error.
    val plugin = AfterRunRecorder(failure = CancellationException("plugin canceled"))
    val agent =
      DummyAgent("emitter") { context ->
        emit(emitterEvent(context, "e0"))
        awaitCancellation()
      }
    val subscriber = RecordingSubscriber()

    runnerWith(agent, plugin).runAsync("user", "s", newMessage = go()).subscribe(subscriber)
    withTimeout(GUARD_TIMEOUT) { subscriber.firstEvent.await() }
    subscriber.subscription.cancel()

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(plugin.afterRuns).isEqualTo(1)
    assertThat(plugin.runErrors).isEmpty()
    assertThat(subscriber.error).isNull()
  }

  @Test
  fun runAsync_afterRunFailsAfterTheSubscriberCancels_notifiesOnRunError() = runBlocking {
    val failure = IllegalStateException("after boom")
    val plugin = AfterRunRecorder(failure = failure)

    val first =
      runnerWith(threeEvents(), plugin).runAsync("user", "s", newMessage = go()).awaitFirst()

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(first.content?.parts?.single()?.text).isEqualTo("e0")
    assertThat(plugin.runErrors).containsExactly(failure)
    assertThat(plugin.afterRuns).isEqualTo(1)
  }

  @Test
  fun runAsync_agentCleanupFailsAfterACancelInOnNext_notifiesOnRunErrorOnce() = runBlocking {
    val cleanupFailure = IllegalStateException("cleanup boom")
    val plugin = AfterRunRecorder()
    val agent =
      DummyAgent("emitter") { context ->
        try {
          emit(emitterEvent(context, "e0"))
          emit(emitterEvent(context, "e1"))
        } finally {
          throw cleanupFailure
        }
      }

    val subscriber = RecordingSubscriber(cancelOnFirstEvent = true)

    runnerWith(agent, plugin).runAsync("user", "s", newMessage = go()).subscribe(subscriber)

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(subscriber.firstEvent.await().content?.parts?.single()?.text).isEqualTo("e0")
    assertThat(plugin.runErrors).containsExactly(cleanupFailure)
    assertThat(plugin.afterRuns).isEqualTo(0)
  }

  @Test
  fun runAsync_agentCleanupFailsWhileTheAgentWaits_notifiesOnRunErrorOnce() = runBlocking {
    val cleanupFailure = IllegalStateException("cleanup boom")
    val plugin = AfterRunRecorder()
    val agent =
      DummyAgent("emitter") { context ->
        try {
          emit(emitterEvent(context, "e0"))
          awaitCancellation()
        } finally {
          throw cleanupFailure
        }
      }
    val subscriber = RecordingSubscriber()

    runnerWith(agent, plugin).runAsync("user", "s", newMessage = go()).subscribe(subscriber)
    withTimeout(GUARD_TIMEOUT) { subscriber.firstEvent.await() }
    subscriber.subscription.cancel()

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(plugin.runErrors).containsExactly(cleanupFailure)
    assertThat(plugin.afterRuns).isEqualTo(0)
  }

  @Test
  fun runAsync_agentCleanupFailsAtTheNextEmit_notifiesOnRunErrorOnce() = runBlocking {
    val cleanupFailure = IllegalStateException("cleanup boom")
    val plugin = AfterRunRecorder()
    val resume = CompletableDeferred<Unit>()
    val agent =
      DummyAgent("emitter") { context ->
        try {
          emit(emitterEvent(context, "e0"))
          // Outlives the cancel, so the next emit fails before it reaches the subscriber.
          withContext(NonCancellable) { resume.await() }
          emit(emitterEvent(context, "e1"))
        } finally {
          throw cleanupFailure
        }
      }
    val subscriber = RecordingSubscriber()

    runnerWith(agent, plugin).runAsync("user", "s", newMessage = go()).subscribe(subscriber)
    withTimeout(GUARD_TIMEOUT) { subscriber.firstEvent.await() }
    subscriber.subscription.cancel()
    resume.complete(Unit)

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(plugin.runErrors).containsExactly(cleanupFailure)
    assertThat(plugin.afterRuns).isEqualTo(0)
  }

  @Test
  fun runAsync_runCanceledInsideTheAgent_skipsItsAfterRun() = runBlocking {
    // Only canceling the subscription is an early stop, not canceling a run nested in the agent.
    val nestedPlugin = AfterRunRecorder()
    val nestedStarted = CompletableDeferred<Unit>()
    val nested = waitingRunner(nestedPlugin, nestedStarted)
    val plugin = AfterRunRecorder()
    val agent =
      DummyAgent("emitter") { context ->
        coroutineScope {
          val nestedRun = launch { nested.runAsync("user", "n", newMessage = go()).collect {} }
          nestedStarted.await()
          nestedRun.cancelAndJoin()
        }
        emit(emitterEvent(context, "e0"))
      }

    val events =
      runnerWith(agent, plugin).runAsync("user", "s", newMessage = go()).asFlow().toList()

    assertThat(events).hasSize(1)
    assertThat(plugin.afterRuns).isEqualTo(1)
    assertThat(nestedPlugin.afterRuns).isEqualTo(0)
    assertThat(plugin.runErrors).isEmpty()
    assertThat(nestedPlugin.runErrors).isEmpty()
  }

  @Test
  fun runAsync_subscriberCancelsDuringANestedRunInACustomAgentRoot_runsTheNestedAfterRun() =
    runBlocking {
      val nestedAfterRuns = nestedAfterRunsAfterASubscriberCancel { plugins, runNested ->
        App(appName = "app", rootAgent = DummyAgent("root") { runNested() }, plugins = plugins)
      }
      assertThat(nestedAfterRuns).isEqualTo(1)
    }

  @Test
  fun runAsync_subscriberCancelsDuringANestedRunInAnLlmAgentRoot_skipsTheNestedAfterRun() =
    runBlocking {
      // As in ADK Python, a run nested in an LlmAgent, ParallelAgent or node root sees a plain
      // cancellation.
      val nestedAfterRuns = nestedAfterRunsAfterASubscriberCancel { plugins, runNested ->
        val root = LlmAgent(name = "root", model = DummyModel("model") { flow { runNested() } })
        App(appName = "app", rootAgent = root, plugins = plugins)
      }
      assertThat(nestedAfterRuns).isEqualTo(0)
    }

  @Test
  fun runAsync_subscriberCancelsDuringANestedRunInANodeRoot_skipsTheNestedAfterRun() = runBlocking {
    val nestedAfterRuns = nestedAfterRunsAfterASubscriberCancel { plugins, runNested ->
      val root =
        object : Node {
          override val name = "root"

          override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { runNested() }
        }
      App(appName = "app", rootNode = root, plugins = plugins)
    }
    assertThat(nestedAfterRuns).isEqualTo(0)
  }

  @Test
  fun runAsync_subscriberCancelsDuringANestedRunInAParallelAgentRoot_skipsTheNestedAfterRun() =
    runBlocking {
      val nestedAfterRuns = nestedAfterRunsAfterASubscriberCancel { plugins, runNested ->
        val root = ParallelAgent(name = "root", subAgents = listOf(DummyAgent("a") { runNested() }))
        App(appName = "app", rootAgent = root, plugins = plugins)
      }
      assertThat(nestedAfterRuns).isEqualTo(0)
    }

  @Test
  fun asyncJavaHelpersAsPublisher_subscriberCancelsAfterTheFirstEvent_runsAfterRunOnce() =
    runBlocking {
      val plugin = AfterRunRecorder()
      val runner =
        InMemoryRunner(App(appName = "app", rootAgent = threeEvents(), plugins = listOf(plugin)))

      val first =
        AsyncJavaHelpers.asPublisher(runner.runAsync("user", "s", newMessage = go())).awaitFirst()

      withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
      assertThat(first.content?.parts?.single()?.text).isEqualTo("e0")
      assertThat(plugin.afterRuns).isEqualTo(1)
    }

  /**
   * Cancels the subscription while [appWith]'s root waits in a nested run; returns its afterRuns.
   */
  private suspend fun nestedAfterRunsAfterASubscriberCancel(
    appWith: (plugins: List<Plugin>, runNested: suspend () -> Unit) -> App
  ): Int {
    val nestedPlugin = AfterRunRecorder()
    val nestedStarted = CompletableDeferred<Unit>()
    val nested = waitingRunner(nestedPlugin, nestedStarted)
    val plugin = AfterRunRecorder()
    val app = appWith(listOf(plugin)) { nested.runAsync("user", "n", newMessage = go()).collect {} }
    val subscriber = RecordingSubscriber()

    PublisherRunner.inMemory(app).runAsync("user", "s", newMessage = go()).subscribe(subscriber)
    withTimeout(GUARD_TIMEOUT) { nestedStarted.await() }
    subscriber.subscription.cancel()

    withTimeout(GUARD_TIMEOUT) { plugin.finished.await() }
    assertThat(plugin.afterRuns).isEqualTo(1)
    return nestedPlugin.afterRuns
  }

  /**
   * Records afterRun and onRunError calls and completes [finished] after the run's last one. Both
   * call yield(), so they fail in a canceled coroutine unless the runner runs them under
   * NonCancellable.
   */
  private class AfterRunRecorder(private val failure: Throwable? = null) : Plugin {
    override val name = "after_run_recorder"
    var afterRuns = 0
    val runErrors = mutableListOf<Throwable>()
    val finished = CompletableDeferred<Unit>()

    override suspend fun afterRun(invocationContext: InvocationContext) {
      yield()
      afterRuns++
      // onRunError never hears of a cancellation, so that run finishes here.
      if (failure == null || failure is CancellationException) finished.complete(Unit)
      if (failure != null) throw failure
    }

    override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
      runErrors += error
      yield()
      finished.complete(Unit)
    }
  }

  /** Requests every event and keeps the subscription, canceling it on the first event if asked. */
  private class RecordingSubscriber(private val cancelOnFirstEvent: Boolean = false) :
    Subscriber<Event> {
    lateinit var subscription: Subscription
    val firstEvent = CompletableDeferred<Event>()
    var error: Throwable? = null

    override fun onSubscribe(s: Subscription) {
      subscription = s
      s.request(Long.MAX_VALUE)
    }

    override fun onNext(event: Event) {
      if (cancelOnFirstEvent) subscription.cancel()
      firstEvent.complete(event)
    }

    override fun onError(t: Throwable) {
      error = t
      firstEvent.completeExceptionally(t)
    }

    override fun onComplete() {}
  }

  /** A runner whose agent completes [started] and then waits until it is canceled. */
  private fun waitingRunner(plugin: Plugin, started: CompletableDeferred<Unit>) =
    InMemoryRunner(
      App(
        appName = "nested",
        rootAgent =
          DummyAgent("nested") {
            started.complete(Unit)
            awaitCancellation()
          },
        plugins = listOf(plugin),
      )
    )

  private fun threeEvents() =
    DummyAgent("emitter") { context -> repeat(3) { i -> emit(emitterEvent(context, "e$i")) } }

  private fun emitterEvent(context: InvocationContext, text: String) =
    Event(invocationId = context.invocationId, author = "emitter", content = modelMessage(text))

  private fun runnerWith(agent: DummyAgent, plugin: Plugin) =
    PublisherRunner.inMemory(App(appName = "app", rootAgent = agent, plugins = listOf(plugin)))

  private fun go() = userMessage("go")

  companion object {
    /** Bounds a wait whose failure mode is a hang. */
    private val GUARD_TIMEOUT = 10.seconds
  }
}
