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
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * An in-memory implementation of a [Runner] that manages the lifecycle of a [BaseAgent] execution.
 *
 * It provides default in-memory implementations for session, artifact, and memory services. It can
 * be constructed either directly from a root agent or from an [App].
 */
open class InMemoryRunner : AbstractRunner {

  /**
   * Creates an [InMemoryRunner] from a root [agent], its [plugins], and default in-memory services.
   *
   * [appName] is used as given, whereas the [App]-based constructor validates it against the
   * [App.appName] grammar. Resumability, compaction and context caching are configurable only
   * through an [App].
   *
   * @param autoCreateSession Whether a run creates a session that does not exist yet; when false,
   *   it throws [SessionNotFoundException][com.google.adk.kt.sessions.SessionNotFoundException]
   *   instead.
   */
  @JvmOverloads
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    plugins: List<Plugin> = emptyList(),
    autoCreateSession: Boolean = true,
  ) : super(
    appName,
    agent,
    sessionService,
    artifactService,
    memoryService,
    PluginManager(plugins),
    autoCreateSession = autoCreateSession,
  )

  /**
   * Creates an [InMemoryRunner] from an [App], deriving its [App.appName], [App.rootAgent],
   * [App.plugins], and [App.resumabilityConfig].
   *
   * This is the recommended way to configure plugins and resumability.
   *
   * @param skipClosingPlugins See [PluginManager.skipClosingPlugins]. Set to `true` when the
   *   [App.plugins] are shared with another (parent) runner whose lifecycle owns them.
   * @param autoCreateSession Whether a run creates a session that does not exist yet; when false,
   *   it throws [SessionNotFoundException][com.google.adk.kt.sessions.SessionNotFoundException]
   *   instead.
   */
  @JvmOverloads
  constructor(
    app: App,
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    skipClosingPlugins: Boolean = false,
    autoCreateSession: Boolean = true,
  ) : super(
    app,
    sessionService,
    artifactService,
    memoryService,
    skipClosingPlugins,
    autoCreateSession,
  )

  /**
   * Fluent builder for [InMemoryRunner], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var agent: BaseAgent? = null
    private var app: App? = null
    private var appName: String? = null
    private var sessionService: SessionService = InMemorySessionService()
    private var artifactService: ArtifactService? = InMemoryArtifactService()
    private var memoryService: MemoryService? = InMemoryMemoryService()
    private var plugins: List<Plugin>? = null
    private var skipClosingPlugins: Boolean? = null
    private var autoCreateSession: Boolean = true

    fun agent(agent: BaseAgent): Builder = apply { this.agent = agent }

    fun app(app: App): Builder = apply { this.app = app }

    /** Applies only when building from an agent; an App provides its own name. */
    fun appName(appName: String): Builder = apply { this.appName = appName }

    fun sessionService(sessionService: SessionService): Builder = apply {
      this.sessionService = sessionService
    }

    fun artifactService(artifactService: ArtifactService?): Builder = apply {
      this.artifactService = artifactService
    }

    fun memoryService(memoryService: MemoryService?): Builder = apply {
      this.memoryService = memoryService
    }

    /** Applies only when building from an agent; an App carries its own plugins. */
    fun plugins(plugins: List<Plugin>): Builder = apply { this.plugins = plugins }

    /** Applies only when building from an App. */
    fun skipClosingPlugins(skipClosingPlugins: Boolean): Builder = apply {
      this.skipClosingPlugins = skipClosingPlugins
    }

    fun autoCreateSession(autoCreateSession: Boolean): Builder = apply {
      this.autoCreateSession = autoCreateSession
    }

    fun build(): InMemoryRunner {
      val agent = agent
      val app = app
      check((agent == null) != (app == null)) {
        "InMemoryRunner.Builder requires exactly one of agent or app to be set."
      }
      return if (app != null) {
        check(appName == null) {
          "appName applies only when building from an agent; an App provides its own name."
        }
        check(plugins == null) {
          "plugins apply only when building from an agent; an App carries its own plugins."
        }
        InMemoryRunner(
          app = app,
          sessionService = sessionService,
          artifactService = artifactService,
          memoryService = memoryService,
          skipClosingPlugins = skipClosingPlugins ?: false,
          autoCreateSession = autoCreateSession,
        )
      } else {
        check(skipClosingPlugins == null) {
          "skipClosingPlugins applies only when building from an App."
        }
        InMemoryRunner(
          agent = agent!!,
          appName = appName ?: "InMemoryRunner",
          sessionService = sessionService,
          artifactService = artifactService,
          memoryService = memoryService,
          plugins = plugins ?: emptyList(),
          autoCreateSession = autoCreateSession,
        )
      }
    }
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
