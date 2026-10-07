/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.loaders

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.workflow.Node

/** What the routes serve, read from whichever loader the server was configured with. */
internal interface ServedApps {
  /** The names `/list-apps` returns. */
  fun listApps(): List<String>

  /** The root the app [appName] runs, or null if there is no such app. */
  fun loadRoot(appName: String): Node?

  /** A runner for [appName] with [plugins] after the app's own, or null if there is no such app. */
  fun runnerOrNull(
    appName: String,
    sessionService: SessionService,
    artifactService: ArtifactService,
    plugins: List<Plugin>,
  ): InMemoryRunner?
}

/** Serves an [AppLoader]'s apps, each under the requested name. */
internal class AppLoaderApps(private val loader: AppLoader) : ServedApps {
  override fun listApps(): List<String> = loader.listApps()

  override fun loadRoot(appName: String): Node? = loader.loadApp(appName)?.root

  override fun runnerOrNull(
    appName: String,
    sessionService: SessionService,
    artifactService: ArtifactService,
    plugins: List<Plugin>,
  ): InMemoryRunner? {
    val app = loader.loadApp(appName) ?: return null
    return InMemoryRunner(
      app = app.copy(appName = appName, plugins = app.plugins + plugins),
      sessionService = sessionService,
      artifactService = artifactService,
    )
  }

  private val App.root: Node
    get() = rootNode ?: rootAgent
}

/** Serves a deprecated [AgentLoader]'s agents as released: without an [App], under any name. */
@Suppress("DEPRECATION") // AgentLoader stays until 2.0.
internal class AgentLoaderApps(private val loader: AgentLoader) : ServedApps {
  override fun listApps(): List<String> = loader.listAgents()

  override fun loadRoot(appName: String): BaseAgent? = loader.loadAgent(appName)

  override fun runnerOrNull(
    appName: String,
    sessionService: SessionService,
    artifactService: ArtifactService,
    plugins: List<Plugin>,
  ): InMemoryRunner? {
    val agent = loader.loadAgent(appName) ?: return null
    return InMemoryRunner(
      agent = agent,
      appName = appName,
      sessionService = sessionService,
      artifactService = artifactService,
      plugins = plugins,
    )
  }
}
