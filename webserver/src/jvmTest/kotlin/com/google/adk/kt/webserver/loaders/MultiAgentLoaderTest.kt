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
class MultiAgentLoaderTest {

  @Test
  fun listAgents_returnsEveryNameSorted() {
    val loader = MultiAgentLoader(FakeAgent("beta"), FakeAgent("alpha"))

    assertThat(loader.listAgents()).containsExactly("alpha", "beta").inOrder()
  }

  @Test
  fun loadAgent_returnsTheAgentUnderItsOwnName() {
    val alpha = FakeAgent("alpha")
    val loader = MultiAgentLoader(alpha, FakeAgent("beta"))

    assertThat(loader.loadAgent("alpha")).isSameInstanceAs(alpha)
  }

  @Test
  fun loadAgent_returnsNullForAnUnknownName() {
    val loader = MultiAgentLoader(FakeAgent("alpha"))

    assertThat(loader.loadAgent("missing")).isNull()
  }

  @Test
  fun constructor_rejectsDuplicateNames() {
    assertThrows(IllegalArgumentException::class.java) {
      MultiAgentLoader(FakeAgent("dup"), FakeAgent("dup"))
    }
  }

  @Test
  fun emptyLoader_servesNoAgents() {
    val loader = MultiAgentLoader()

    assertThat(loader.listAgents()).isEmpty()
    assertThat(loader.loadAgent("anything")).isNull()
  }

  @Test
  fun loadNode_agentOrWorkflowName_returnsThatRoot() {
    // Arrange
    val alpha = FakeAgent("alpha")
    val workflow = Workflow(name = "flow", edges = listOf(Edge(Start, FakeAgent("step"))))
    val loader = MultiAgentLoader(alpha, workflow)

    // Act + Assert
    assertThat(loader.listAgents()).containsExactly("alpha", "flow").inOrder()
    assertThat(loader.loadNode("alpha")).isSameInstanceAs(alpha)
    assertThat(loader.loadNode("flow")).isSameInstanceAs(workflow)
    assertThat(loader.loadNode("missing")).isNull()
  }

  @Test
  fun loadAgent_workflowRoot_returnsNull() {
    // Arrange
    val loader = MultiAgentLoader(Workflow(name = "flow"))

    // Act + Assert
    assertThat(loader.loadAgent("flow")).isNull()
  }

  @Test
  fun singleAgentLoader_workflowRoot_servesItOnlyAsANode() {
    // Arrange
    val workflow = Workflow(name = "flow")
    val loader = SingleAgentLoader(workflow)

    // Act + Assert
    assertThat(loader.listAgents()).containsExactly("flow")
    assertThat(loader.loadNode("flow")).isSameInstanceAs(workflow)
    assertThat(loader.loadAgent("flow")).isNull()
    assertThat(loader.loadNode("other")).isNull()
  }

  @Test
  fun loadRoot_agentOnlyLoader_fallsBackToLoadAgent() {
    // Arrange
    val alpha = FakeAgent("alpha")
    val loader =
      object : AgentLoader {
        override fun listAgents() = listOf("alpha")

        override fun loadAgent(agentName: String) = alpha.takeIf { agentName == "alpha" }
      }

    // Act + Assert
    assertThat(loader.loadRoot("alpha")).isSameInstanceAs(alpha)
    assertThat(loader.loadRoot("missing")).isNull()
  }

  @Test
  fun loadRoot_nodeLoader_returnsItsNode() {
    // Arrange
    val workflow = Workflow(name = "flow")
    val loader: AgentLoader = MultiAgentLoader(workflow)

    // Act + Assert
    assertThat(loader.loadRoot("flow")).isSameInstanceAs(workflow)
  }

  private class FakeAgent(name: String) : BaseAgent(name = name, description = "test agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
  }
}
