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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.webserver.loaders

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MultiAppLoaderTest {

  @Test
  fun listAgents_severalApps_returnsTheirNamesSorted() {
    // Arrange
    val loader =
      MultiAppLoader(App("beta", AppLoaderTestAgent("b")), App("alpha", AppLoaderTestAgent("a")))

    // Act + Assert
    assertThat(loader.listAgents()).containsExactly("alpha", "beta").inOrder()
  }

  @Test
  fun loadApp_knownOrUnknownName_returnsTheAppOrNull() {
    // Arrange
    val app = App("alpha", AppLoaderTestAgent("a"))
    val loader = MultiAppLoader(app)

    // Act + Assert
    assertThat(loader.loadApp("alpha")).isSameInstanceAs(app)
    assertThat(loader.loadApp("missing")).isNull()
  }

  @Test
  fun loadAgent_agentOrWorkflowRoot_returnsOnlyTheAgent() {
    // Arrange
    val agent = AppLoaderTestAgent("a")
    val loader =
      MultiAppLoader(App("agent_app", agent), App(appName = "graph_app", rootNode = workflow()))

    // Act + Assert
    assertThat(loader.loadAgent("agent_app")).isSameInstanceAs(agent)
    assertThat(loader.loadAgent("graph_app")).isNull()
  }

  @Test
  fun loadRoot_app_returnsItsRootNodeOrRootAgent() {
    // Arrange
    val agent = AppLoaderTestAgent("a")
    val graph = workflow()
    val loader: AgentLoader =
      MultiAppLoader(App("agent_app", agent), App(appName = "graph_app", rootNode = graph))

    // Act + Assert
    assertThat(loader.loadRoot("agent_app")).isSameInstanceAs(agent)
    assertThat(loader.loadRoot("graph_app")).isSameInstanceAs(graph)
    assertThat(loader.loadRoot("missing")).isNull()
  }

  @Test
  fun constructor_duplicateAppNames_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      MultiAppLoader(App("dup", AppLoaderTestAgent("a")), App("dup", AppLoaderTestAgent("b")))
    }
  }

  private fun workflow() =
    Workflow(name = "flow", edges = listOf(Edge(Start, AppLoaderTestAgent("step"))))

  private class AppLoaderTestAgent(name: String) : BaseAgent(name = name) {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
  }
}
