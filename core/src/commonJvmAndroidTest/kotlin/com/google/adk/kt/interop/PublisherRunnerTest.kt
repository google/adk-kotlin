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
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.audioFrame
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.outputTranscriptionStep
import com.google.adk.kt.testing.turnCompleteStep
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.testing.wholeText
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Modality
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
   * Records what a Java subscriber would see, on which threads, and in which trace, and can end the
   * run from a signal: closing [queue] on turn complete or cancelling after the first event. With
   * [blockUntil] it blocks in `onNext` until that latch opens, as slow inline work would.
   */
  private class RecordingSubscriber(
    private val queue: LiveRequestQueue? = null,
    private val cancelOnFirstEvent: Boolean = false,
    private val blockUntil: CountDownLatch? = null,
  ) : Subscriber<Event> {
    val events = CopyOnWriteArrayList<Event>()
    val threads = CopyOnWriteArrayList<Thread>()
    val traces = CopyOnWriteArrayList<String?>()
    val firstEvent = CompletableDeferred<Unit>()
    val terminated = CompletableDeferred<Throwable?>()
    private lateinit var subscription: Subscription

    override fun onSubscribe(s: Subscription) {
      subscription = s
      s.request(Long.MAX_VALUE)
    }

    override fun onNext(event: Event) {
      threads.add(Thread.currentThread())
      traces.add(CallerTrace.trace())
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

  /** What the runners from [rewindRecordingRunner] recorded at their last rewind. */
  private val rewoundIn = AtomicReference<String>()

  @After
  fun closeRunners() {
    for (runner in runners) {
      runner.close()
    }
  }

  private fun runnerFor(model: Model, tools: List<BaseTool> = emptyList()): PublisherRunner {
    val runner =
      PublisherRunner.inMemory(LlmAgent(name = "live_agent", model = model, tools = tools))
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

  /**
   * A runner whose model calls the `record` tool once and then answers; [onCall] runs inside both
   * model calls and the tool call.
   */
  private fun toolCallingRunner(onCall: () -> Unit): PublisherRunner {
    val responses =
      listOf(modelFunctionCallResponse("record"), LlmResponse(content = modelMessage("done")))
    val model =
      DummyModel(
        "model",
        responses.map { response ->
          flow {
            onCall()
            emit(response)
          }
        },
      )
    val tool =
      DummyTool(name = "record", declares = true) { _, _ ->
        onCall()
        mapOf("status" to "ok")
      }
    return runnerFor(model, listOf(tool))
  }

  private fun singleThreadDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { Thread(it, name) }.asCoroutineDispatcher()

  /**
   * Counts down [latch] and blocks, as a synchronous Java tool does, until the other parallel call
   * counts it down too. Not suspend, because suspend code should not call a method that throws
   * `InterruptedException`, such as `CountDownLatch.await`.
   */
  private fun waitForTheOtherCall(latch: CountDownLatch): Boolean {
    latch.countDown()
    return latch.await(GUARD_TIMEOUT.inWholeMilliseconds, MILLISECONDS)
  }

  /** This thread's name without the ` @coroutine#N` suffix that coroutine debug mode adds. */
  private fun Thread.baseName(): String = name.substringBefore(" @coroutine#")

  @Test
  fun runAsync_coroutineContextGiven_runsTheModelAndToolsWithIt(): Unit = runBlocking {
    val seen = CopyOnWriteArrayList<String?>()
    val runner = toolCallingRunner { seen += requestId.get() }
    val subscriber = RecordingSubscriber()

    runner
      .runAsync(
        "u",
        "s",
        newMessage = userMessage("hi"),
        coroutineContext = requestId.asContextElement("request-1"),
      )
      .subscribe(subscriber)

    assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    assertThat(seen).containsExactly("request-1", "request-1", "request-1")
  }

  @Test
  fun runAsync_dispatcherGiven_runsTheModelToolsAndOnNextOnIt(): Unit = runBlocking {
    val threads = CopyOnWriteArrayList<String>()
    val runner = toolCallingRunner { threads += Thread.currentThread().baseName() }
    val subscriber = RecordingSubscriber()

    singleThreadDispatcher("adk-run").use { dispatcher ->
      runner
        .runAsync("u", "s", newMessage = userMessage("hi"), coroutineContext = dispatcher)
        .subscribe(subscriber)
      assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    }

    assertThat(threads).containsExactly("adk-run", "adk-run", "adk-run")
    assertThat(subscriber.threads.map { it.baseName() }.toSet()).containsExactly("adk-run")
  }

  @Test
  fun runAsync_noCoroutineContext_runsParallelBlockingToolCallsConcurrently(): Unit = runBlocking {
    val bothArrived = CountDownLatch(2)
    val metTheOtherCall = CopyOnWriteArrayList<Boolean>()
    val tool =
      DummyTool(name = "wait_for_other", declares = true) { _, _ ->
        metTheOtherCall += waitForTheOtherCall(bothArrived)
        mapOf("status" to "ok")
      }
    val model =
      DummyModel.createSequential(
        "model",
        listOf(
          modelParallelFunctionCallsResponse(
            FunctionCall(name = "wait_for_other", args = emptyMap(), id = "call-1"),
            FunctionCall(name = "wait_for_other", args = emptyMap(), id = "call-2"),
          ),
          LlmResponse(content = modelMessage("done")),
        ),
      )
    val runner = runnerFor(model, listOf(tool))
    val subscriber = RecordingSubscriber()

    runner.runAsync("u", "s", newMessage = userMessage("hi")).subscribe(subscriber)

    assertThat(withTimeout(GUARD_TIMEOUT * 2) { subscriber.terminated.await() }).isNull()
    assertThat(metTheOtherCall).containsExactly(true, true)
  }

  @Test
  fun runAsync_fromJava_keepsTheSixArgumentOverload() {
    val method =
      PublisherRunner::class
        .java
        .getMethod(
          "runAsync",
          String::class.java,
          String::class.java,
          String::class.java,
          Content::class.java,
          Map::class.java,
          RunConfig::class.java,
        )

    assertThat(method.returnType).isEqualTo(Publisher::class.java)
  }

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

  @OptIn(ExperimentalWorkflowApi::class)
  @Test
  fun runLive_workflowRoot_deliversItsNodesEventsThenCompletes(): Unit = runBlocking {
    val greet = node<Any?, String>("greet") { _, _ -> "hello" }
    val runner =
      PublisherRunner.inMemory(
        App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, greet))))
      )
    runners += runner
    val subscriber = RecordingSubscriber()

    // The queue stays open: only the workflow's end completes the run.
    runner.runLive("u", "s", LiveRequestQueue()).subscribe(subscriber)

    assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    assertThat(subscriber.events.map { it.output }).contains("hello")
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
    // At most 63 blockers: Dispatchers.IO allows at least 64 threads, so one stays free.
    val blockerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 63)

    whileSubscribersBlock(runner, blockerCount) {
      val queue = LiveRequestQueue()
      try {
        runToCompletion(runner, queue)
      } finally {
        queue.close()
      }
    }
  }

  @Test
  fun runLive_dispatcherGiven_deliversEventsOnIt(): Unit = runBlocking {
    val runner = runnerFor(DummyLiveModel(answersAfterTheUser()))
    val queue = LiveRequestQueue()
    val subscriber = RecordingSubscriber(queue = queue)

    singleThreadDispatcher("adk-live").use { dispatcher ->
      runner.runLive("u", "s", queue, coroutineContext = dispatcher).subscribe(subscriber)
      queue.sendContent(userMessage("hi"))
      assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    }

    assertThat(subscriber.threads.map { it.baseName() }.toSet()).containsExactly("adk-live")
  }

  @Test
  fun runLive_subscribersBlockInOnNext_leaveDispatchersDefaultFree(): Unit = runBlocking {
    val runner = runnerFor(DummyLiveModel(answersAfterTheUser()))

    // One blocker per Default thread: if events arrived on Default, none would be left.
    whileSubscribersBlock(runner, Runtime.getRuntime().availableProcessors()) {
      val ranOnDefault = CompletableDeferred<Unit>()
      Dispatchers.Default.asExecutor().execute { ranOnDefault.complete(Unit) }
      withTimeout(GUARD_TIMEOUT) { ranOnDefault.await() }
    }
  }

  @Test
  fun runAsync_callerHasATraceContext_runsTheModelInIt() {
    val seenByModel = AtomicReference<String>()
    val model =
      DummyModel(
        "model",
        listOf(
          flow {
            seenByModel.set(CallerTrace.traceAndName())
            emit(LlmResponse(content = modelMessage("done")))
          }
        ),
      )
    val runner = runnerFor(model)

    val events =
      CallerTrace.CONTEXT.makeCurrent().use { runner.runAsync("u", "s", null, userMessage("hi")) }
    AsyncJavaHelpers.forEach(events) {}

    assertThat(seenByModel.get()).isEqualTo("caller/null")
  }

  @Test
  fun runLive_callerHasATraceContext_runsAndCallsOnNextInIt(): Unit = runBlocking {
    val ranIn = AtomicReference<String>()
    val engine = InMemoryRunner(LlmAgent(name = "agent", model = DummyModel("model", emptyList())))
    // Captures no context when called, so the run sees only what PublisherRunner gives it.
    val runner =
      PublisherRunner.of(
        object : Runner by engine {
          override fun runLive(
            userId: String,
            sessionId: String,
            liveRequestQueue: LiveRequestQueue,
            runConfig: RunConfig?,
          ): Flow<Event> = flow {
            ranIn.set(CallerTrace.traceAndName())
            emit(Event(author = "agent"))
          }
        }
      )
    runners += runner
    val subscriber = RecordingSubscriber()

    val events =
      CallerTrace.CONTEXT.makeCurrent().use { runner.runLive("u", "s", LiveRequestQueue()) }
    events.subscribe(subscriber)

    assertThat(withTimeout(GUARD_TIMEOUT) { subscriber.terminated.await() }).isNull()
    assertThat(ranIn.get()).isEqualTo("caller/null")
    assertThat(subscriber.traces).containsExactly("caller")
  }

  @Test
  fun rewindAsync_callerHasATraceContext_runsInIt() {
    val runner = rewindRecordingRunner()

    CallerTrace.CONTEXT.makeCurrent().use {
      runner.rewindAsync("u", "s", "invocation").get(5, SECONDS)
    }

    assertThat(rewoundIn.get()).isEqualTo("caller/null")
  }

  @Test
  fun rewindAsync_withCoroutineContext_runsInIt() {
    val runner = rewindRecordingRunner()

    runner.rewindAsync("u", "s", "invocation", CoroutineName("call")).get(5, SECONDS)

    assertThat(rewoundIn.get()).isEqualTo("null/call")
  }

  /** A runner whose rewind records its trace value and coroutine name as `trace/name`. */
  private fun rewindRecordingRunner(): PublisherRunner {
    val engine = InMemoryRunner(LlmAgent(name = "agent", model = DummyModel("model", emptyList())))
    val runner =
      PublisherRunner.of(
        object : Runner by engine {
          override suspend fun rewindAsync(
            userId: String,
            sessionId: String,
            rewindBeforeInvocationId: String,
          ) {
            rewoundIn.set(CallerTrace.traceAndName())
          }
        }
      )
    runners += runner
    return runner
  }

  /**
   * Runs [block] while [count] subscribers of [runner] are blocked in `onNext`, then releases them
   * and waits for their runs to end, so [closeRunners] never closes a runner with runs in flight.
   */
  private suspend fun whileSubscribersBlock(
    runner: PublisherRunner,
    count: Int,
    block: suspend () -> Unit,
  ) {
    val release = CountDownLatch(1)
    val queues = mutableListOf<LiveRequestQueue>()
    val blockers = mutableListOf<RecordingSubscriber>()
    try {
      for (i in 1..count) {
        val queue = LiveRequestQueue()
        val blocker = RecordingSubscriber(blockUntil = release)
        queues += queue
        runner.runLive("u", "blocker-$i", queue).subscribe(blocker)
        blockers += blocker
        queue.sendContent(userMessage("hi"))
      }
      for (blocker in blockers) {
        withTimeout(GUARD_TIMEOUT) { blocker.firstEvent.await() }
      }

      block()
    } finally {
      release.countDown()
      for (queue in queues) {
        queue.close()
      }
      // NonCancellable: the waits must run even when the test body failed or was cancelled.
      withContext(NonCancellable) {
        withTimeout(GUARD_TIMEOUT) {
          for (blocker in blockers) {
            blocker.terminated.await()
          }
        }
      }
    }
  }

  companion object {
    /** Bounds a wait whose failure mode is a hang. */
    private val GUARD_TIMEOUT = 10.seconds

    /** Stands in for MDC: thread-local state a caller carries into a run through its context. */
    private val requestId = ThreadLocal<String?>()
  }
}
