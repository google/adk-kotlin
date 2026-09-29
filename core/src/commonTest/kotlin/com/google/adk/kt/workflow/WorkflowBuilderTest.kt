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

@file:OptIn(ExperimentalWorkflowApi::class, AdkJavaInteropApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class WorkflowBuilderTest {

  @Test
  fun build_withEveryProperty_matchesTheConstructor() {
    // Arrange
    val edges = listOf(Edge(Start, StubNode("a")))
    val config = NodeConfig(timeout = 5.seconds)
    val input = Schema(type = Type.OBJECT)
    val output = Schema(type = Type.STRING)
    val state =
      Schema(type = Type.OBJECT, properties = mapOf("topic" to Schema(type = Type.STRING)))

    // Act
    val built =
      Workflow.builder()
        .name("wf")
        .edges(edges)
        .description("Plans a trip.")
        .maxConcurrency(2)
        .rerunOnResume(false)
        .waitForOutput(true)
        .config(config)
        .inputSchema(input)
        .outputSchema(output)
        .stateSchema(state)
        .build()

    // Assert
    val constructed =
      Workflow(
        name = "wf",
        edges = edges,
        description = "Plans a trip.",
        maxConcurrency = 2,
        rerunOnResume = false,
        waitForOutput = true,
        config = config,
        inputSchema = input,
        outputSchema = output,
        stateSchema = state,
      )
    assertEquals(constructed.properties(), built.properties())
  }

  @Test
  fun build_withOnlyAName_usesTheConstructorDefaults() {
    // Act
    val built = Workflow.builder().name("wf").build()

    // Assert
    assertEquals(Workflow(name = "wf").properties(), built.properties())
  }

  @Test
  fun build_withVarargEdges_keepsTheirOrder() {
    // Arrange
    val a = StubNode("a")
    val edges = listOf(Edge(Start, a), Edge(a, StubNode("b")))

    // Act
    val built = Workflow.builder().name("wf").edges(*edges.toTypedArray()).build()

    // Assert
    assertEquals(edges, built.edges)
  }

  @Test
  fun build_withoutAName_throws() {
    assertFailsWith<IllegalStateException> { Workflow.builder().build() }
  }

  /** Lists every constructor property, since [Workflow] has no structural equality. */
  private fun Workflow.properties(): List<Any?> =
    listOf(
      name,
      edges,
      description,
      maxConcurrency,
      rerunOnResume,
      waitForOutput,
      config,
      inputSchema,
      outputSchema,
      stateSchema,
    )
}
