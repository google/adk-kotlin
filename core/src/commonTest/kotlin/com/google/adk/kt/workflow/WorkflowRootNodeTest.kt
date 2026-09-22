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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** A graph, rather than an agent tree, as an application's entry point. */
class WorkflowRootNodeTest {

  @Test
  fun aWorkflowRunsAsAnApplicationRootNode() {
    // Arrange
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))))
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Act
    val events = runBlocking {
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = Content.fromText(Role.USER, "go"))
        .toList()
    }

    // Assert: the graph ran under the runner, and its node is stamped on the event.
    assertEquals(listOf("wf@1/a@1"), events.mapNotNull { it.nodeInfo?.path })
    assertTrue(events.any { it.output == "A" }, "the node's output never reached the caller")
  }

  @Test
  fun aRunnerRootedOnAGraphExposesTheWorkflowAsItsNode() {
    // Arrange + Act
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))))
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Assert: an app rooted on a graph exposes the workflow as the runner's running node.
    assertSame(workflow, runner.node)
  }
}
