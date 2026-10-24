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

package com.google.adk.kt.interop

import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.outputTranscriptionStep
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.types.Modality
import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class PublisherRunnerTest {

  /**
   * Records what a Java subscriber would see, and on which threads, and can end the run from a
   * signal: closing [queue] on turn complete or cancelling after the first event. With [blockUntil]
   * it blocks in `onNext` until that latch opens, as slow inline work would.
   */
  private class RecordingSubscriber(
    private val queue: LiveRequestQueue? = null,
    private val cancelOnFirstEvent: Boolean = false,
    private val blockUntil: CountDownLatch? = null,
  ) : Subscriber<Event> {
    val events = CopyOnWriteArrayList<Event>()
    val threads = CopyOnWriteArrayList<Thread>()
    val firstEvent = CompletableDeferred<Unit>()
    val terminated = CompletableDeferred<Throwable?>()
    private lateinit var subscription: Subscription

    override fun onSubscribe(s: Subscription) {
      subscription = s
      s.request(Long.MAX_VALUE)
    }

    override fun onNext(event: Event) {
      threads.add(Thread.currentThread())
      events.add(event)
      firstEvent.complete(Unit)
      blockUntil?.await()
      if (cancelOnFirstEvent) subscription.cancel()
      if (event.turnComplete) queue?.close()
    }

    override fun onError(t: Throwable) {
      terminated.complete(t)
    }

    override fun onComplete() {
      terminated.complete(null)
    }
  }

  private val runners = CopyOnWriteArrayList<PublisherRunner>()

  @After
  fun closeRunners() {
    for (runner in runners) {
      runner.close()
    }
  }

  private fun runnerFor(model: DummyLiveModel): PublisherRunner {
    val runner = PublisherRunner.inMemory(LlmAgent(name = "live_agent", model = model))
    runners += runner
    return runner
  }

  private fun answersAfterTheUser(): List<DummyLiveStep> =
    listOf(
      AwaitSent("the user's turn") { it is ContentInput },
      outputTranscriptionStep("hello there", finished = true),
      audioFrame(),
      audioFrame(),
      turnCompleteStep(),
    )

  /** The events the Kotlin `Runner.runLive` emits for [script], closing its queue on turn end. */
  private suspend fun kotlinRunLiveEvents(script: List<DummyLiveStep>): List<Event> {
    val runner = runnerFor(DummyLiveModel(script))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    return runner
      .asKotlinRunner()
      .runLive(userId = "u", sessionId = "s", liveRequestQueue = queue)
      .onEach { if (it.turnComplete) queue.close() }
      .toList()
  }

  /**
   * Sends one user turn on [queue] and waits for the run to complete; the subscriber closes [queue]
   * when the turn ends.
   */
  @CanIgnoreReturnValue
  private suspend fun runToCompletion(
    runner: PublisherRunner,
    queue: LiveRequestQueue = LiveRequestQueue(),
    runConfig: RunConfig? = null,
  ): RecordingSubscriber {
    val subscriber = RecordingSubscriber(queue = queue)
    runner.runLive("u", "s", queue, runConfig).subscribe(subscriber)
    queue.sendContent(userMessage("hi"))
    assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    return subscriber
  }

  /** [event] without the fields that differ between two runs of the same script. */
  private fun withoutRunIds(event: Event): Event =
    event.copy(id = "", invocationId = null, timestamp = 0)

  @Test
  fun runLive_notSubscribed_opensNoConnection(): Unit = runBlocking {
    val model = DummyLiveModel(answersAfterTheUser())
    val runner = runnerFor(model)
    val unused = runner.runLive("u", "s", LiveRequestQueue())

    // A full run here gives the unsubscribed run above time to connect, if it wrongly would.
    runToCompletion(runner)

    assertThat(model.connections).hasSize(1)
  }

  @Test
  fun runLive_queueClosed_completesWithTheEventsOfTheKotlinRun(): Unit = runBlocking {
    val model = DummyLiveModel(answersAfterTheUser())
    val runner = runnerFor(model)

    val subscriber = runToCompletion(runner)

    assertThat(subscriber.events.mapNotNull { it.outputTranscription?.text })
      .containsExactly("hello there")
    assertThat(
        subscriber.events.count { it.content?.parts.orEmpty().any { p -> p.inlineData != null } }
      )
      .isEqualTo(2)
    assertThat(subscriber.events.last().turnComplete).isTrue()
    assertThat(subscriber.events.map(::withoutRunIds))
      .containsExactlyElementsIn(kotlinRunLiveEvents(answersAfterTheUser()).map(::withoutRunIds))
      .inOrder()
    assertThat(model.connections.single().isClosed).isTrue()
  }

  @Test
  fun runLive_runConfigGiven_appliesItToTheConnection(): Unit = runBlocking {
    val model = DummyLiveModel(answersAfterTheUser())
    val runner = runnerFor(model)
    val runConfig =
      RunConfig(
        live =
          LiveRunConfig(responseModalities = listOf(Modality.TEXT), outputAudioTranscription = null)
      )

    runToCompletion(runner, runConfig = runConfig)

    val config = model.connectRequests.single().liveConnectConfig
    assertThat(config.responseModalities).containsExactly(Modality.TEXT)
    assertThat(config.outputAudioTranscription).isNull()
  }

  @Test
  fun runLive_fromJava_hasAThreeArgumentOverload() {
    val method =
      PublisherRunner::class
        .java
        .getMethod("runLive", String::class.java, String::class.java, LiveRequestQueue::class.java)

    assertThat(method.returnType).isEqualTo(Publisher::class.java)
  }

  @Test
  fun runLive_subscriptionCancelled_closesTheConnectionAndLeavesTheQueueOpen(): Unit = runBlocking {
    val model = DummyLiveModel(answersAfterTheUser())
    val runner = runnerFor(model)
    val queue = LiveRequestQueue()
    val cancelling = RecordingSubscriber(cancelOnFirstEvent = true)

    runner.runLive("u", "s", queue).subscribe(cancelling)
    queue.sendContent(userMessage("first"))
    withTimeout(GUARD_TIMEOUT) { cancelling.firstEvent.await() }

    // The connection closes on another thread after the cancel.
    withTimeout(GUARD_TIMEOUT) {
      while (!model.connections.single().isClosed) delay(10.milliseconds)
    }
    // Still open: a second run on the same queue receives what is sent after the cancel.
    val second = RecordingSubscriber(queue = queue)
    runner.runLive("u", "s", queue).subscribe(second)
    queue.sendContent(userMessage("second"))
    assertThat(withTimeout(GUARD_TIMEOUT) { second.terminated.await() }).isNull()
    assertThat(model.connections[1].sent).contains(ContentInput(userMessage("second")))
  }

  @Test
  fun runLive_modelFailsAfterAnEvent_deliversTheEventThenOnErrorWithTheCause(): Unit = runBlocking {
    val cause = IllegalStateException("scripted failure")
    val runner = runnerFor(DummyLiveModel(listOf(wholeText("before the failure"), Fail(cause))))
    val subscriber = RecordingSubscriber()

    runner.runLive("u", "s", LiveRequestQueue()).subscribe(subscriber)

    val error = withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }
    assertThat(subscriber.events.single().content?.parts?.single()?.text)
      .isEqualTo("before the failure")
    // Not isSameInstanceAs: with assertions on, coroutine debug mode delivers a copy of the cause.
    assertThat(error).isInstanceOf(IllegalStateException::class.java)
    assertThat(error).hasMessageThat().isEqualTo(cause.message)
  }

  @Test
  fun runLive_eventsArrive_offTheThreadThatSendsToTheQueue(): Unit = runBlocking {
    val runner = runnerFor(DummyLiveModel(answersAfterTheUser()))
    val sender = Thread.currentThread()

    // The script answers only once the user's turn arrives, so this thread's send resumes it.
    val subscriber = runToCompletion(runner)

    assertThat(subscriber.threads).isNotEmpty()
    assertThat(subscriber.threads).doesNotContain(sender)
  }

  @Test
  fun runLive_otherSubscribersBlockInOnNext_stillDeliversEvents(): Unit = runBlocking {
    val runner = runnerFor(DummyLiveModel(answersAfterTheUser()))
    val release = CountDownLatch(1)
    val queues = mutableListOf<LiveRequestQueue>()
    // Blocks every Default thread on fewer than 64 cores; Dispatchers.IO allows at least 64.
    val blockerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 63)
    try {
      val blockers =
        (1..blockerCount).map { i ->
          val queue = LiveRequestQueue().also { queues += it }
          RecordingSubscriber(blockUntil = release).also {
            runner.runLive("u", "blocker-$i", queue).subscribe(it)
            queue.sendContent(userMessage("hi"))
          }
        }
      for (blocker in blockers) {
        withTimeout(GUARD_TIMEOUT) { blocker.firstEvent.await() }
      }

      val victimQueue = LiveRequestQueue().also { queues += it }
      val victim = RecordingSubscriber()
      runner.runLive("u", "victim", victimQueue).subscribe(victim)
      victimQueue.sendContent(userMessage("hi"))

      withTimeout(GUARD_TIMEOUT) { victim.firstEvent.await() }
    } finally {
      release.countDown()
      for (queue in queues) {
        queue.close()
      }
    }
  }

  companion object {
    /** Bounds a wait whose failure mode is a hang. */
    private val GUARD_TIMEOUT = 10.seconds
  }
}
