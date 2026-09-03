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

package com.google.adk.kt.interop

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.types.Content
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.reactive.asPublisher
import org.reactivestreams.Publisher

/**
 * Java-friendly wrapper around a [Runner]. A run is exposed as a Reactive Streams [Publisher] of
 * events, so Java callers never touch `Flow`; collect it with `AsyncJavaHelpers.collect`/`forEach`
 * or any Reactive Streams consumer. Session management is not a runner concern -- reach the session
 * service via `asKotlinRunner().sessionService` (or wrap it in a `BaseFutureSessionService`).
 */
@AdkJavaInteropApi
class PublisherRunner private constructor(private val delegate: Runner) : AutoCloseable {

  // Bridges the engine's `suspend` rewind to a CompletableFuture; cancelled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped engine runner, for code that has dropped into Kotlin. */
  fun asKotlinRunner(): Runner = delegate

  val appName: String
    get() = delegate.appName

  /**
   * Runs the agent, streaming its events. Set [invocationId] to resume an interrupted invocation.
   */
  @JvmOverloads
  fun runAsync(
    userId: String,
    sessionId: String,
    invocationId: String? = null,
    newMessage: Content? = null,
    stateDelta: Map<String, Any>? = null,
    runConfig: RunConfig? = null,
  ): Publisher<Event> =
    delegate
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        invocationId = invocationId,
        newMessage = newMessage,
        stateDelta = stateDelta,
        runConfig = runConfig,
      )
      .asPublisher()

  /**
   * Rewinds the session to before [rewindBeforeInvocationId]; see [Runner.rewindAsync]. The
   * returned future completes once the rewind has been applied.
   */
  fun rewindAsync(
    userId: String,
    sessionId: String,
    rewindBeforeInvocationId: String,
  ): CompletableFuture<Void?> = scope.future {
    delegate.rewindAsync(userId, sessionId, rewindBeforeInvocationId)
    null
  }

  override fun close() {
    scope.cancel()
    delegate.close()
  }

  companion object {
    /** Wraps an existing engine [Runner]. */
    @JvmStatic fun of(runner: Runner): PublisherRunner = PublisherRunner(runner)

    /** An in-memory runner over [agent], with in-memory session, artifact and memory services. */
    @JvmStatic
    @JvmOverloads
    fun inMemory(
      agent: BaseAgent,
      appName: String = "InMemoryRunner",
      plugins: List<Plugin> = emptyList(),
    ): PublisherRunner =
      PublisherRunner(InMemoryRunner(agent = agent, appName = appName, plugins = plugins))

    /** An in-memory runner over an [App], the way to configure plugins and resumability. */
    @JvmStatic fun inMemory(app: App): PublisherRunner = PublisherRunner(InMemoryRunner(app))
  }
}
