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

import com.google.adk.kt.workflow.Node

/**
 * An [AgentLoader] that can also serve a root that is not an agent, such as a workflow graph.
 *
 * The server runs a root that is not an agent inside an app, so its name must also be a valid app
 * name.
 */
interface NodeLoader : AgentLoader {

  /**
   * Loads the root [Node] for the specified agent name: an agent, or another node such as a
   * workflow.
   *
   * @param agentName The name of the agent or node to load.
   * @return The root with the given name, or null if it is not found.
   */
  fun loadNode(agentName: String): Node?
}

/** Returns the root [Node] served as [agentName] by this loader, or null if not found. */
internal fun AgentLoader.loadRoot(agentName: String): Node? =
  when (this) {
    is AppLoader -> loadApp(agentName)?.root
    is NodeLoader -> loadNode(agentName)
    else -> loadAgent(agentName)
  }
