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

@file:OptIn(ExperimentalResumabilityFeature::class)

package com.google.adk.kt.runners

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalResumabilityFeature
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.Tracer

/**
 * An in-memory implementation of a [Runner] that manages the lifecycle of a [BaseAgent] execution.
 *
 * It provides default in-memory implementations for session, artifact, and memory services. It can
 * be constructed either directly from a root agent or from an [App].
 */
open class InMemoryRunner : AbstractRunner {

  /**
   * Creates an [InMemoryRunner] from a root [agent] and default in-memory services.
   *
   * @param tracer Optional telemetry [Tracer] for this runner's runs, for hosts that build a runner
   *   from a bare agent rather than an [App]. When `null`, tracing is a no-op.
   */
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    tracer: Tracer? = null,
  ) : super(
    appName,
    agent,
    sessionService,
    artifactService,
    memoryService,
    PluginManager(),
    tracer = tracer,
  )

  /**
   * Creates an [InMemoryRunner] from an [App], deriving its [App.appName], [App.rootAgent],
   * [App.plugins], and [App.resumabilityConfig].
   *
   * This is the recommended way to configure plugins and resumability.
   *
   * @param skipClosingPlugins See [PluginManager.skipClosingPlugins]. Set to `true` when the
   *   [App.plugins] are shared with another (parent) runner whose lifecycle owns them.
   */
  constructor(
    app: App,
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    skipClosingPlugins: Boolean = false,
  ) : super(app, sessionService, artifactService, memoryService, skipClosingPlugins)

  /**
   * Creates an [InMemoryRunner] with an explicit [pluginManager].
   *
   * @deprecated Configure plugins on the [App] instead.
   */
  @Deprecated(
    "Configure plugins via App.plugins instead, e.g. " +
      "InMemoryRunner(App(appName, agent, plugins = listOf(...))). Passing a PluginManager " +
      "directly to the runner is deprecated.",
    ReplaceWith("InMemoryRunner(App(appName, agent, plugins = pluginManager.plugins))"),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager,
  ) : super(appName, agent, sessionService, artifactService, memoryService, pluginManager)

  /**
   * Creates an [InMemoryRunner] with an explicit [resumabilityConfig].
   *
   * @deprecated Configure resumability on the [App] instead.
   */
  @Deprecated(
    "Configure resumability via App.resumabilityConfig instead, e.g. " +
      "InMemoryRunner(App(appName, agent, resumabilityConfig = ...)). Passing a ResumabilityConfig " +
      "directly to the runner is deprecated.",
    ReplaceWith("InMemoryRunner(App(appName, agent, resumabilityConfig = resumabilityConfig))"),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    resumabilityConfig: ResumabilityConfig,
  ) : super(
    appName,
    agent,
    sessionService,
    artifactService,
    memoryService,
    PluginManager(),
    resumabilityConfig,
  )

  /**
   * Creates an [InMemoryRunner] with both an explicit [pluginManager] and [resumabilityConfig].
   *
   * @deprecated Configure plugins and resumability on the [App] instead.
   */
  @Deprecated(
    "Configure plugins and resumability via App instead, e.g. " +
      "InMemoryRunner(App(appName, agent, plugins = listOf(...), resumabilityConfig = ...)). " +
      "Passing them directly to the runner is deprecated.",
    ReplaceWith(
      "InMemoryRunner(App(appName, agent, plugins = pluginManager.plugins, " +
        "resumabilityConfig = resumabilityConfig))"
    ),
    DeprecationLevel.WARNING,
  )
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager,
    resumabilityConfig: ResumabilityConfig,
  ) : super(
    appName,
    agent,
    sessionService,
    artifactService,
    memoryService,
    pluginManager,
    resumabilityConfig,
  )
}
