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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalAuthApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.testing.DummyMemoryService
import com.google.adk.kt.testing.signInReplyStateKey
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.CaptureInput
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Emitter
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.NodeExecutionException
import com.google.adk.kt.workflow.NodeTimeoutException
import com.google.adk.kt.workflow.OutputAssigner
import com.google.adk.kt.workflow.RequestInput
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.StubNode
import com.google.adk.kt.workflow.ThrowingNode
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.runWorkflow
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Publisher

/** Publishes [future]'s value and unwraps its failure, as Reactor's `Mono.fromFuture` does. */
private fun fromFuture(future: CompletableFuture<Any?>): Publisher<Any> =
  AsyncJavaHelpers.asPublisher(flow { future.await()?.let { emit(it) } })

/**
 * Publishes [future]'s value and passes its failure on unchanged, as RxJava's
 * `Maybe.fromCompletionStage` does.
 */
private fun fromCompletionStage(future: CompletableFuture<Any?>): Publisher<Any> =
  AsyncJavaHelpers.asPublisher(
    flow {
      val value = suspendCancellableCoroutine { continuation ->
        future.whenComplete { result, error ->
          if (error == null) {
            continuation.resume(result)
          } else {
            continuation.resumeWithException(error)
          }
        }
      }
      value?.let { emit(it) }
    }
  )

/**
 * Publishes [future]'s value through a blocking `get()`, which wraps any failure in an
 * `ExecutionException` just as Reactor's `Mono.fromCallable(future::get)` does.
 */
@Suppress("GlobalCoroutineDispatchers")
private fun fromBlockingGet(future: CompletableFuture<Any?>): Publisher<Any> =
  AsyncJavaHelpers.asPublisher(
    flow { withContext(Dispatchers.IO) { future.get(5, TimeUnit.SECONDS) }?.let { emit(it) } }
  )

/** Runs [dispatch] on a view of its context and publishes the future's result with [publish]. */
private class JavaDispatcher(
  name: String,
  config: NodeConfig = NodeConfig(),
  rerunOnResume: Boolean = true,
  private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
  private val publish: (CompletableFuture<Any?>) -> Publisher<Any> = ::fromFuture,
  private val dispatch: (InteropContextView) -> CompletableFuture<Any?>,
) : BasePublisherNode(name, rerunOnResume = rerunOnResume, config = config) {
  override fun runNodeJava(context: Context, nodeInput: Any?): Publisher<Any> =
    publish(dispatch(InteropContextView.of(context, coroutineContext)))
}

/** Counts its runs and produces no output. */
private class SilentNode(override val name: String) : Node {
  var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { runs++ }
}

/**
 * Records where it runs once it has suspended, then outputs that as `<coroutine name>@<thread>`.
 */
private class WhereNode(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    delay(10.milliseconds)
    // Coroutine debug mode appends " @<coroutine>" to the thread name.
    val thread = Thread.currentThread().name.substringBefore(" @")
    emit("${currentCoroutineContext()[CoroutineName]?.name}@$thread")
  }
}

/** Outputs the dispatcher it runs on. */
private class DispatcherNode(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(currentCoroutineContext()[ContinuationInterceptor].toString())
  }
}

/** Completes [started], then suspends until cancelled, which completes [cancelled]. */
private class HangingNode(override val name: String) : Node {
  val started = CompletableDeferred<Unit>()
  val cancelled = CompletableDeferred<Unit>()

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    started.complete(Unit)
    try {
      awaitCancellation()
    } finally {
      cancelled.complete(Unit)
    }
  }
}

/** Suspends in [addSessionToMemory] until cancelled, and then completes [cancelled]. */
private class HangingMemoryService : MemoryService by DummyMemoryService() {
  val cancelled = CompletableDeferred<Unit>()

  override suspend fun addSessionToMemory(session: Session) {
    try {
      awaitCancellation()
    } finally {
      cancelled.complete(Unit)
    }
  }
}

class InteropContextViewTest {

  @Test
  fun runNode_childOutputIsPublishedAsTheCallersOutput() {
    // Arrange
    val caller = JavaDispatcher("caller") { view -> view.runNode(CaptureInput("child"), "in") }

    // Act
    val outputs =
      runWorkflow(workflowOf(caller))
        .filter { it.output != null }
        .map { it.nodeInfo?.path to it.output }

    // Assert
    val childOutput = mapOf("received" to "in")
    assertEquals(
      listOf("wf@1/caller@1/child@1" to childOutput, "wf@1/caller@1" to childOutput),
      outputs,
    )
  }

  @Test
  fun runNode_childWithoutOutput_completesWithNull() {
    // Arrange
    val child = SilentNode("child")
    val caller = JavaDispatcher("caller") { view -> view.runNode(child) }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    assertEquals(1, child.runs)
    assertTrue(events.none { it.output != null })
  }

  @Test
  fun runNode_runIdAndUseSubBranch_reachTheChildsRun() {
    // Arrange
    val config = RunNodeConfig(runId = "custom", useSubBranch = true)
    val caller =
      JavaDispatcher("caller") { view -> view.runNode(CaptureInput("child"), "in", config) }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/caller@1/child@custom" }
    assertEquals(mapOf("received" to "in"), childEvent.output)
    assertEquals("child@custom", childEvent.branch)
  }

  @Test
  fun runNode_overrideBranchAndIsolationScope_reachTheChildsRun() {
    // Arrange
    val config = RunNodeConfig(overrideBranch = "elsewhere", overrideIsolationScope = "scope")
    val caller =
      JavaDispatcher("caller") { view -> view.runNode(Emitter("child", "C"), null, config) }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/caller@1/child@1" }
    assertEquals("elsewhere", childEvent.branch)
    assertEquals("scope", childEvent.isolationScope)
  }

  @Test
  fun runNode_useAsOutput_makesTheChildsOutputTheCallers() {
    // Arrange: the caller also publishes the child's output, which the delegation drops.
    val caller =
      JavaDispatcher("caller") { view ->
        view.runNode(Emitter("child", "C"), null, RunNodeConfig(useAsOutput = true))
      }
    val after = CaptureInput("after")

    // Act
    val events = runWorkflow(workflowOf(caller, after))

    // Assert
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null })
    assertEquals(
      mapOf("received" to "C"),
      events.single { it.nodeInfo?.path == "wf@1/after@1" }.output,
    )
  }

  @Test
  fun runNode_raiseOnWait_endsTheCallersRunWhenTheChildLeavesNoOutput() {
    // Arrange: without raiseOnWait, a null result would let the caller publish its fallback.
    val config = RunNodeConfig(raiseOnWait = true)
    val child = OutputAssigner("child", waitForOutput = true)
    val caller =
      JavaDispatcher("caller") { view ->
        view.runNode(child, null, config).thenApply { it ?: "fallback" }
      }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    assertTrue(events.none { it.output != null })
  }

  @Test
  fun runNode_runsOnDispatchersIoByDefault() {
    // Arrange
    val caller = JavaDispatcher("caller") { view -> view.runNode(DispatcherNode("child")) }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    assertEquals(
      "Dispatchers.IO",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun coroutineContext_givenToOf_reachesTheCall() {
    // Arrange
    val executor = Executors.newSingleThreadExecutor { Thread(it, "java-thread") }
    val events =
      executor.asCoroutineDispatcher().use { dispatcher ->
        val caller =
          JavaDispatcher("caller", coroutineContext = CoroutineName("java-call") + dispatcher) {
            view ->
            view.runNode(WhereNode("child"))
          }

        // Act
        runWorkflow(workflowOf(caller))
      }

    // Assert
    assertEquals(
      "java-call@java-thread",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_unawaitedCalls_takeRunIdsInCallOrder() {
    // Arrange: a held single-thread executor that runs queued calls newest first.
    val held = CountDownLatch(1)
    val executor = newestFirstExecutor()
    executor.execute { held.await() }
    val events =
      executor.asCoroutineDispatcher().use { dispatcher ->
        val caller =
          JavaDispatcher("caller", coroutineContext = dispatcher) { view ->
            val first = view.runNode(CaptureInput("child"), "x")
            val second = view.runNode(CaptureInput("child"), "y")
            held.countDown()
            first.thenCombine(second) { _, _ -> null }
          }

        // Act
        runWorkflow(workflowOf(caller))
      }

    // Assert
    assertEquals(
      mapOf<String?, Any?>(
        "wf@1/caller@1/child@1" to mapOf("received" to "x"),
        "wf@1/caller@1/child@2" to mapOf("received" to "y"),
      ),
      events
        .filter { it.output != null && it.nodeInfo?.path != "wf@1/caller@1" }
        .associate { it.nodeInfo?.path to it.output },
    )
  }

  @Test
  fun runNode_callerWithoutRerunOnResume_failsTheRun() {
    // Arrange
    val caller =
      JavaDispatcher("caller", rerunOnResume = false) { view ->
        view.runNode(StubNode("child", "C"))
      }

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflowOf(caller)) }

    // Assert
    assertContains(error.message!!, "must set rerunOnResume = true")
  }

  @Test
  fun runNode_childFails_failsTheRunWithTheChildsError() {
    // Arrange
    val child = ThrowingNode("child") { NodeExecutionException("child boom") }
    val caller = JavaDispatcher("caller") { view -> view.runNode(child) }
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking {
          workflowOf(caller).runAsync(testInvocationContext()).collect { events.add(it) }
        }
      }

    // Assert: the failure crosses the future unchanged, so only the child reports it.
    assertContains(error.message!!, "child boom")
    assertEquals(
      listOf("wf@1/caller@1/child@1"),
      events.filter { it.errorCode != null }.map { it.nodeInfo?.path },
    )
  }

  @Test
  fun runNode_childAsksForInput_callerWaitsThenPublishesTheAnswer() {
    // Arrange
    val child = Emitter("child", RequestInput(interruptId = "ask", message = "need input"))
    val caller = JavaDispatcher("caller") { view -> view.runNode(child) }
    val runner = resumableRunner(caller, CaptureInput("reply"))

    // Act
    val asked = runner.turn(userMessage("go"))
    val answered = runner.turn(answerTo("ask"))

    // Assert
    assertTrue(asked.any { event -> event.functionCalls().any { it.id == "ask" } })
    assertTrue(asked.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(
      mapOf("received" to mapOf("v" to 1L)),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun runNode_pauseWrappedByAComposedFuture_stillMakesTheCallerWait() {
    // Arrange: thenApply wraps the pause in a CompletionException, which RxJava passes on as is.
    val child = Emitter("child", RequestInput(interruptId = "ask", message = "need input"))
    val caller =
      JavaDispatcher("caller", publish = ::fromCompletionStage) { view ->
        view.runNode(child).thenApply { it }
      }
    val runner = resumableRunner(caller, CaptureInput("reply"))

    // Act
    val asked = runner.turn(userMessage("go"))
    val answered = runner.turn(answerTo("ask"))

    // Assert
    assertTrue(asked.none { it.errorCode != null })
    assertEquals(
      mapOf("received" to mapOf("v" to 1L)),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun runNode_pauseWrappedInAnExecutionException_stillMakesTheCallerWait() {
    // Arrange
    val child = Emitter("child", RequestInput(interruptId = "ask", message = "need input"))
    val caller =
      JavaDispatcher("caller", publish = ::fromBlockingGet) { view -> view.runNode(child) }
    val runner = resumableRunner(caller, CaptureInput("reply"))

    // Act
    val asked = runner.turn(userMessage("go"))
    val answered = runner.turn(answerTo("ask"))

    // Assert
    assertTrue(asked.none { it.errorCode != null })
    assertEquals(
      mapOf("received" to mapOf("v" to 1L)),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun runNode_childFailureWrappedByAComposedFuture_failsTheRunWithTheChildsError() {
    // Arrange
    val child = ThrowingNode("child") { NodeExecutionException("child boom") }
    val caller =
      JavaDispatcher("caller", publish = ::fromCompletionStage) { view ->
        view.runNode(child).thenApply { it }
      }
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking {
          workflowOf(caller).runAsync(testInvocationContext()).collect { events.add(it) }
        }
      }

    // Assert: unwrapped, the failure stays the child's alone.
    assertContains(error.message!!, "child boom")
    assertEquals(
      listOf("wf@1/caller@1/child@1"),
      events.filter { it.errorCode != null }.map { it.nodeInfo?.path },
    )
  }

  @Test
  fun composedFutureFailure_failsTheCallerWithTheWrappedCause() {
    // Arrange: thenApply wraps the IOException in a CompletionException that RxJava passes on.
    val caller =
      JavaDispatcher("caller", publish = ::fromCompletionStage) { view ->
        view.getEvents().thenApply { throw IOException("disk full") }
      }
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IOException> {
        runBlocking {
          workflowOf(caller).runAsync(testInvocationContext()).collect { events.add(it) }
        }
      }

    // Assert: retry policies and the error event see the IOException, not its wrapper.
    assertEquals("disk full", error.message)
    assertEquals(listOf("IOException"), events.mapNotNull { it.errorCode })
  }

  @Test
  fun composedFutureCancellation_failsTheCallerInsteadOfCancellingIt() {
    // Arrange: thenApply wraps the cancellation in a CompletionException.
    val caller =
      JavaDispatcher("caller", publish = ::fromCompletionStage) { _ ->
        CompletableFuture<Any?>().apply { cancel(false) }.thenApply { it }
      }
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<CompletionException> {
        runBlocking {
          workflowOf(caller).runAsync(testInvocationContext()).collect { events.add(it) }
        }
      }

    // Assert: the node fails with an error event instead of ending as canceled.
    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CancellationException })
    assertEquals(listOf("CompletionException"), events.mapNotNull { it.errorCode })
  }

  @Test
  fun cancelledFuture_failsTheCallerInsteadOfCancellingIt() {
    // Arrange: Mono.fromFuture rethrows the cancellation unwrapped.
    val caller = JavaDispatcher("caller") { _ -> CompletableFuture<Any?>().apply { cancel(false) } }
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<CompletionException> {
        runBlocking {
          workflowOf(caller).runAsync(testInvocationContext()).collect { events.add(it) }
        }
      }

    // Assert: the node fails with an error event instead of ending as canceled.
    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CancellationException })
    assertEquals(listOf("CompletionException"), events.mapNotNull { it.errorCode })
  }

  @Test
  fun runNode_callerTimesOut_cancelsTheChild() {
    // Arrange
    val child = HangingNode("child")
    val caller =
      JavaDispatcher("caller", NodeConfig(timeout = 500.milliseconds)) { view ->
        view.runNode(child)
      }

    // Act
    assertFailsWith<NodeTimeoutException> { runWorkflow(workflowOf(caller)) }

    // Assert
    assertTrue(child.started.isCompleted)
    runBlocking { withTimeout(5.seconds) { child.cancelled.await() } }
  }

  @Test
  fun runNode_callerTimesOutWhileAComposedFutureWaits_cancelsTheChild() {
    // Arrange: fromCompletionStage only detaches, and thenApply hides the child's future anyway.
    val child = HangingNode("child")
    val caller =
      JavaDispatcher(
        "caller",
        NodeConfig(timeout = 500.milliseconds),
        publish = ::fromCompletionStage,
      ) { view ->
        view.runNode(child).thenApply { it }
      }

    // Act
    assertFailsWith<NodeTimeoutException> { runWorkflow(workflowOf(caller)) }

    // Assert
    assertTrue(child.started.isCompleted)
    runBlocking { withTimeout(5.seconds) { child.cancelled.await() } }
  }

  @Test
  fun runNode_childStillRunningWhenTheCallerFinishes_isCancelled() {
    // Arrange: the caller publishes without waiting for the child.
    val child = HangingNode("child")
    lateinit var childCall: CompletableFuture<Any?>
    val caller =
      JavaDispatcher("caller") { view ->
        childCall = view.runNode(child)
        CompletableFuture.completedFuture("done")
      }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    assertEquals(
      "done",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
    runBlocking { withTimeout(5.seconds) { child.cancelled.await() } }
    assertTrue(childCall.isCancelled)
  }

  @Test
  fun runNode_afterTheCallersRunEnded_doesNotStartTheChild() {
    // Arrange
    lateinit var lateView: InteropContextView
    val caller =
      JavaDispatcher("caller") { view ->
        lateView = view
        CompletableFuture.completedFuture("done")
      }
    val events = runWorkflow(workflowOf(caller))
    val child = HangingNode("child")

    // Act
    val call = lateView.runNode(child)

    // Assert
    assertEquals(
      "done",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
    assertTrue(call.isCancelled)
    assertFalse(child.started.isCompleted)
  }

  @Test
  fun jobGivenToOf_cancelsTheCall(): Unit = runBlocking {
    // Arrange
    val memoryService = HangingMemoryService()
    val job = Job()
    val view =
      InteropContextView.of(Context(testInvocationContext(memoryService = memoryService)), job)
    val call = view.addSessionToMemory()

    // Act
    job.cancel()

    // Assert
    withTimeout(5.seconds) { memoryService.cancelled.await() }
    assertFailsWith<CancellationException> { call.await() }
  }

  @Test
  fun failedCall_leavesTheJobGivenToOfActive() = runBlocking {
    // Arrange: without an artifact service, saving fails.
    val job = Job()
    val view = InteropContextView.of(Context(testInvocationContext()), job)

    // Act
    val result = view.saveArtifact("notes.txt", Part(text = "hello"))

    // Assert
    assertFailsWith<IllegalStateException> { result.await() }
    assertTrue(job.isActive)
  }

  @Test
  fun failedCall_leavesTheRunsOtherCallsRunning() {
    // Arrange: without an artifact service, the save fails before the child starts.
    val caller =
      JavaDispatcher("caller") { view ->
        val failedSave = view.saveArtifact("notes.txt", Part(text = "hello")).exceptionally { -1 }
        view.runNode(CaptureInput("child"), "in").thenCombine(failedSave) { output, _ -> output }
      }

    // Act
    val events = runWorkflow(workflowOf(caller))

    // Assert
    assertEquals(
      mapOf("received" to "in"),
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun artifactCalls_reachTheArtifactService() = runBlocking {
    // Arrange
    val context = Context(testInvocationContext(artifactService = InMemoryArtifactService()))
    val view = InteropContextView.of(context)

    // Act
    val version = view.saveArtifact("notes.txt", Part(text = "hello")).await()
    val names = view.listArtifacts().await()
    val loaded = view.loadArtifact("notes.txt").await()

    // Assert
    assertEquals(0, version)
    assertEquals(listOf("notes.txt"), names)
    assertEquals("hello", loaded?.text)
    assertEquals(mapOf("notes.txt" to 0), context.actions.artifactDelta.toMap())
  }

  @Test
  fun loadArtifact_withVersion_loadsThatVersion() = runBlocking {
    // Arrange
    val view =
      InteropContextView.of(
        Context(testInvocationContext(artifactService = InMemoryArtifactService()))
      )
    val firstVersion = view.saveArtifact("notes.txt", Part(text = "first")).await()
    val latestVersion = view.saveArtifact("notes.txt", Part(text = "second")).await()

    // Act
    val loaded = view.loadArtifact("notes.txt", firstVersion).await()

    // Assert
    assertTrue(latestVersion > firstVersion)
    assertEquals("first", loaded?.text)
  }

  @Test
  fun saveArtifact_withoutArtifactService_failsTheFuture() {
    // Arrange
    val view = InteropContextView.of(Context(testInvocationContext()))

    // Act
    val result = view.saveArtifact("notes.txt", Part(text = "hello"))

    // Assert
    val error = assertFailsWith<IllegalStateException> { runBlocking { result.await() } }
    assertContains(error.message!!, "artifactService not configured")
  }

  @Test
  fun memoryCalls_reachTheMemoryService() = runBlocking {
    // Arrange
    val memoryService = DummyMemoryService()
    val view = InteropContextView.of(Context(testInvocationContext(memoryService = memoryService)))
    val event = Event(id = "e1", content = Content.fromText(Role.USER, "hi"))
    val eventsMetadata = mapOf("source" to "test")
    val memoryMetadata = mapOf("kind" to "fact")

    // Act
    view.addSessionToMemory().await()
    view.addEventsToMemory(listOf(event), eventsMetadata).await()
    view
      .addMemory(listOf(MemoryEntry(content = event.content!!, id = "m1")), memoryMetadata)
      .await()

    // Assert
    assertEquals(listOf("test_session_id"), memoryService.addedSessions.map { it.key.id })
    val eventsCall = memoryService.addedEvents.single()
    assertEquals(listOf("e1"), eventsCall.events.map { it.id })
    assertEquals("test_session_id", eventsCall.sessionId)
    assertEquals(eventsMetadata, eventsCall.customMetadata)
    val memoryCall = memoryService.addedMemories.single()
    assertEquals(listOf("m1"), memoryCall.memories.map { it.id })
    assertEquals(memoryMetadata, memoryCall.customMetadata)
  }

  @Test
  fun getEvents_returnsTheSessionsEvents() = runBlocking {
    // Arrange
    val event = Event(id = "e1", content = Content.fromText(Role.USER, "hi"))
    val session = testSession().copy(events = mutableListOf(event))
    val view = InteropContextView.of(Context(testInvocationContext(session = session)))

    // Act
    val events = view.getEvents().await()

    // Assert
    assertEquals(listOf("e1"), events.map { it.id })
  }

  @Test
  fun getEvents_forwardsBothFilters() = runBlocking {
    // Arrange: swapped flags would keep e2 and drop e3 instead.
    val current = Event(id = "e1", invocationId = "test-invocation-id", author = "agent")
    val earlier = Event(id = "e2", invocationId = "earlier", author = "agent")
    val elsewhere =
      Event(id = "e3", invocationId = "test-invocation-id", author = "agent", branch = "other")
    val session = testSession().copy(events = mutableListOf(current, earlier, elsewhere))
    val view = InteropContextView.of(Context(testInvocationContext(session = session)))

    // Act
    val events = view.getEvents(currentInvocation = true, currentBranch = false).await()

    // Assert
    assertEquals(listOf("e1", "e3"), events.map { it.id })
  }

  @Test
  fun getAuthResponse_completesWithTheCredentialOnceTheAppSendsItBack() = runBlocking {
    // Arrange
    val authConfig =
      AuthConfig(
        AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
        AuthCredential.ApiKey(Secret("sk-123")),
      )
    val context = Context(testInvocationContext())
    val view = InteropContextView.of(context)

    // Act
    val beforeReply = view.getAuthResponse(authConfig).await()
    context.updateState(authConfig.signInReplyStateKey, "sk-reply")
    val afterReply = view.getAuthResponse(authConfig).await()

    // Assert
    assertNull(beforeReply)
    assertEquals(AuthCredential.ApiKey(Secret("sk-reply")), afterReply)
  }

  @Test
  fun runNodeConfigToBuilder_keepsEveryProperty() {
    val config =
      RunNodeConfig(
        runId = "run",
        useAsOutput = true,
        useSubBranch = true,
        overrideBranch = "branch",
        overrideIsolationScope = "scope",
        raiseOnWait = true,
      )

    assertEquals(config.copy(), config.toBuilder().build())
  }

  @Test
  fun runNodeConfigBuilder_defaultsMatchTheConstructor() {
    assertEquals(RunNodeConfig(), RunNodeConfig.builder().build())
  }
}

/** A workflow named `wf` that runs [nodes] one after another. */
private fun workflowOf(vararg nodes: Node): Workflow =
  Workflow(
    name = "wf",
    edges = (listOf<Node>(Start) + nodes).zipWithNext { from, to -> Edge(from, to) },
  )

/** A runner for a resumable app whose root is [workflowOf] over [nodes]. */
private fun resumableRunner(vararg nodes: Node): InMemoryRunner =
  InMemoryRunner(
    App(
      appName = "app",
      rootNode = workflowOf(*nodes),
      resumabilityConfig = ResumabilityConfig(isResumable = true),
    )
  )

/** The user's answer to the [RequestInput] with [interruptId]. */
private fun answerTo(interruptId: String): Content =
  userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, interruptId, mapOf("v" to 1L))

/** A single-thread executor that runs queued tasks newest first to expose order dependence. */
private fun newestFirstExecutor(): ThreadPoolExecutor =
  ThreadPoolExecutor(
    1,
    1,
    0,
    TimeUnit.MILLISECONDS,
    object : LinkedBlockingDeque<Runnable>() {
      override fun offer(task: Runnable): Boolean = offerFirst(task)
    },
  )

private fun InMemoryRunner.turn(message: Content): List<Event> = runBlocking {
  runAsync(userId = "user", sessionId = "session", newMessage = message).toList()
}
