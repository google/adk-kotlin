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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.RetryConfig
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.StubNode
import com.google.adk.kt.workflow.runWorkflow
import com.google.adk.kt.workflow.workflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.flow
import org.reactivestreams.Publisher

class BasePublisherNodeTest {

  /** Sets [route] on the context and publishes [outputs], as a Java subclass would. */
  private class PublishingNode(
    name: String,
    private val outputs: List<Any>,
    private val route: Route? = null,
  ) : BasePublisherNode(name) {
    override fun runNodeJava(context: Context, nodeInput: Any?): Publisher<Any> {
      if (route != null) context.routes = listOf(route)
      return AsyncJavaHelpers.publisherOf(outputs)
    }
  }

  @Test
  fun publishedValue_isTheNodeOutput() {
    // Arrange
    val graph = workflow("wf") { Start.then(PublishingNode("publisher", listOf("brief"))) }

    // Act
    val outputs =
      runWorkflow(graph).filter { it.output != null }.map { it.nodeInfo?.path to it.output }

    // Assert
    assertEquals(listOf("wf@1/publisher@1" to "brief"), outputs)
  }

  @Test
  fun routeSetOnTheContext_selectsTheEdge() {
    // Arrange
    val router = PublishingNode("router", emptyList(), Route.Tag("yes"))
    val graph =
      workflow("wf") {
        Start.then(router).route {
          on("yes") then StubNode("accepted", "A")
          on("no") then StubNode("rejected", "R")
        }
      }

    // Act
    val outputs =
      runWorkflow(graph).filter { it.output != null }.map { it.nodeInfo?.path to it.output }

    // Assert
    assertEquals(listOf("wf@1/accepted@1" to "A"), outputs)
  }

  @Test
  fun configConstructor_keepsTheConfigAndDefaultsTheRest() {
    val config = NodeConfig(retryConfig = RetryConfig(maxAttempts = 2), timeout = 5.seconds)

    val node =
      object : BasePublisherNode("configured", config) {
        override fun runNodeJava(context: Context, nodeInput: Any?): Publisher<Any> =
          AsyncJavaHelpers.publisherOf(emptyList())
      }

    assertEquals(config, node.config)
    assertEquals("", node.description)
    assertFalse(node.rerunOnResume)
  }

  @Test
  fun runNodeJavaThatThrows_isRetriedThenFailsTheRun() {
    // Arrange
    var calls = 0
    val retry = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    val node =
      object : BasePublisherNode("failing", NodeConfig(retryConfig = retry)) {
        override fun runNodeJava(context: Context, nodeInput: Any?): Publisher<Any> {
          calls++
          throw IllegalStateException("boom")
        }
      }
    val graph = workflow("wf") { Start.then(node) }

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(graph) }

    // Assert
    assertEquals("boom", error.message)
    assertEquals(2, calls)
  }

  @Test
  fun publisherThatSignalsAnError_failsTheRun() {
    // Arrange
    val node =
      object : BasePublisherNode("failing") {
        override fun runNodeJava(context: Context, nodeInput: Any?): Publisher<Any> =
          AsyncJavaHelpers.asPublisher(flow<Any> { throw IllegalStateException("boom") })
      }
    val graph = workflow("wf") { Start.then(node) }

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(graph) }

    // Assert
    assertEquals("boom", error.message)
  }

  @Test
  fun invalidName_failsAtConstruction() {
    assertFailsWith<IllegalArgumentException> { PublishingNode("a.b", emptyList()) }
  }
}
