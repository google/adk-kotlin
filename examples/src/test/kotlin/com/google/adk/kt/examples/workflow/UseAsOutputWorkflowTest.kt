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

package com.google.adk.kt.examples.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Runs [UseAsOutputWorkflow] and its Java port, [UseAsOutputWorkflowJava], with the same
 * assertions.
 */
@RunWith(Parameterized::class)
class UseAsOutputWorkflowTest(private val port: Port) {

  private val model = FakeModel { "A fox jumped over a dog." }

  private fun create(): Workflow =
    when (port) {
      Port.KOTLIN -> UseAsOutputWorkflow.create(model)
      Port.JAVA -> UseAsOutputWorkflowJava.create(model)
    }

  @Test
  fun run_makesTheSummaryTheOrchestratorsOutput(): Unit = runBlocking {
    // Act
    val run = runWorkflow(create(), "The quick brown fox jumped over the lazy dog")

    // Assert
    assertThat(run.failure).isNull()
    val request = model.requests.single()
    assertThat(request.config.systemInstruction?.text())
      .isEqualTo("Summarize the following text in one sentence.")
    assertThat(request.contents.last().text())
      .isEqualTo("The quick brown fox jumped over the lazy dog")
    // The summarizer's message is also orchestrate's output, which orchestrate does not repeat.
    val summary = run.events.single { it.nodeName == "summarizer" }
    assertThat(summary.contentText()).isEqualTo("A fox jumped over a dog.")
    val nodeInfo = assertNotNull(summary.nodeInfo)
    assertThat(nodeInfo.messageAsOutput).isTrue()
    assertThat(nodeInfo.outputFor?.map { it.substringAfterLast('/') })
      .containsExactly("summarizer@1", "orchestrate@1")
    assertThat(run.events.filter { it.nodeName == "orchestrate" }).isEmpty()
    assertThat(run.outputs).containsExactly("finalize" to "final: A fox jumped over a dog.")
  }

  companion object {
    @JvmStatic @Parameters(name = "{0}") fun ports() = Port.entries
  }
}
