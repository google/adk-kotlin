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
class InMemoryAppLoaderTest {

  @Test
  fun listApps_severalApps_returnsTheirNamesSorted() {
    // Arrange
    val loader = InMemoryAppLoader(App("beta", TestAgent("b")), App("alpha", TestAgent("a")))

    // Act + Assert
    assertThat(loader.listApps()).containsExactly("alpha", "beta").inOrder()
  }

  @Test
  fun loadApp_agentOrWorkflowApp_returnsItUnderItsAppName() {
    // Arrange
    val agentApp = App("support_bot", TestAgent("support.bot"))
    val workflowApp = App(appName = "billing", rootNode = workflow())
    val loader = InMemoryAppLoader(agentApp, workflowApp)

    // Act + Assert
    assertThat(loader.loadApp("support_bot")).isSameInstanceAs(agentApp)
    assertThat(loader.loadApp("billing")).isSameInstanceAs(workflowApp)
    assertThat(loader.loadApp("support.bot")).isNull()
    assertThat(loader.loadApp("missing")).isNull()
  }

  @Test
  fun constructor_duplicateAppNames_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      InMemoryAppLoader(App("dup", TestAgent("a")), App("dup", TestAgent("b")))
    }
  }

  @Test
  fun emptyLoader_servesNoApps() {
    // Arrange
    val loader = InMemoryAppLoader()

    // Act + Assert
    assertThat(loader.listApps()).isEmpty()
    assertThat(loader.loadApp("anything")).isNull()
  }

  private fun workflow() = Workflow(name = "flow", edges = listOf(Edge(Start, TestAgent("step"))))

  private class TestAgent(name: String) : BaseAgent(name = name) {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
  }
}
