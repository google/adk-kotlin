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

/** An [AppLoader] serving a fixed set of apps, each under its [App.appName]. */
class MultiAppLoader(vararg apps: App) : AppLoader {
  private val appsByName: Map<String, App> = apps.associateBy(App::appName)

  init {
    require(appsByName.size == apps.size) { "App names must be unique." }
  }

  override fun listAgents(): List<String> = appsByName.keys.sorted()

  override fun loadAgent(agentName: String): BaseAgent? = appsByName[agentName]?.root as? BaseAgent

  override fun loadApp(appName: String): App? = appsByName[appName]
}
