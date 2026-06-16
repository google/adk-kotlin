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

/**
 * An in-memory implementation of a [Runner] that manages the lifecycle of a [BaseAgent] execution.
 *
 * It provides default in-memory implementations for session, artifact, and memory services. It can
 * be constructed either directly from a root agent or from an [App].
 */
open class InMemoryRunner : AbstractRunner {

  /** Creates an [InMemoryRunner] from a root [agent] and default in-memory services. */
  constructor(
    agent: BaseAgent,
    appName: String = "InMemoryRunner",
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager = PluginManager(),
    resumabilityConfig: ResumabilityConfig = ResumabilityConfig(),
  ) : super(
    appName,
    agent,
    sessionService,
    artifactService,
    memoryService,
    pluginManager,
    resumabilityConfig,
  )

  /** Creates an [InMemoryRunner] from an [App], taking its [App.appName] and [App.rootAgent]. */
  constructor(
    app: App,
    sessionService: SessionService = InMemorySessionService(),
    artifactService: ArtifactService? = InMemoryArtifactService(),
    memoryService: MemoryService? = InMemoryMemoryService(),
    pluginManager: PluginManager = PluginManager(),
    resumabilityConfig: ResumabilityConfig = ResumabilityConfig(),
  ) : super(app, sessionService, artifactService, memoryService, pluginManager, resumabilityConfig)
}
