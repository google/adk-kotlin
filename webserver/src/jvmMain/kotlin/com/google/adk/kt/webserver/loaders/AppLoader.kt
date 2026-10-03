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

import com.google.adk.kt.apps.App
import com.google.adk.kt.workflow.Node

/**
 * An [AgentLoader] that serves whole [App]s, so app settings such as [App.resumabilityConfig] and
 * [App.plugins] apply when the server runs them.
 *
 * The server runs a loaded app under the name it was requested by, which must therefore be a valid
 * app name.
 */
interface AppLoader : AgentLoader {

  /**
   * Loads the app for the specified name.
   *
   * @param appName The name of the app to load.
   * @return The app with the given name, or null if it is not found.
   */
  fun loadApp(appName: String): App?
}

/** The node the app runs: its root node when it has one, else its root agent. */
internal val App.root: Node
  get() = rootNode ?: rootAgent
