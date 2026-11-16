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
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/** Runs [NestedWorkflow] and its Java port, [NestedWorkflowJava], with the same assertions. */
@RunWith(Parameterized::class)
class NestedWorkflowTest(private val port: Port) {

  private val model = FakeModel { request ->
    val instruction = request.instruction
    when {
      instruction.startsWith("Find the name") -> "Ada Lovelace"
      instruction.startsWith("Write a short") -> "A bio of ${request.contents.last().text()}."
      instruction.startsWith("Describe one") -> "An event."
      else -> error("No reply for this instruction.")
    }
  }

  private fun create(): Workflow =
    when (port) {
      Port.KOTLIN -> NestedWorkflow.create(model)
      Port.JAVA -> NestedWorkflowJava.create(model)
    }

  @Test
  fun yearInMessage_runsBothBranchesAndCombinesTheirAnswers(): Unit = runBlocking {
    // Act
    val run = runWorkflow(create(), "Tell me about 1984")

    // Assert: the agents read the year from state; the two branches run in either order.
    assertThat(run.failure).isNull()
    assertThat(run.events.single { it.nodeName == "process_input" }.actions.stateDelta)
      .containsExactly("year", "1984")
    val findNameInstruction =
      """
      Find the name of one famous person who was born in this year: 1984.
      Return ONLY their name, nothing else.
      """
        .trimIndent()
    val findHistoricalEventInstruction =
      """
      Describe one highly significant historical event that occurred in this year: 1984.
      Keep the description to 2 sentences.
      """
        .trimIndent()
    assertThat(model.requests.map { it.instruction to it.contents.last().text() })
      .containsExactly(
        findNameInstruction to "Tell me about 1984",
        "Write a short, engaging 3-sentence biography for the specified person." to "Ada Lovelace",
        findHistoricalEventInstruction to "Tell me about 1984",
      )
    assertThat(run.outputs)
      .containsExactly(
        "join_for_aggregation" to
          mapOf(
            "find_famous_person" to "A bio of Ada Lovelace.",
            "find_historical_event" to "An event.",
          )
      )
    assertThat(run.messages.last())
      .isEqualTo(
        """
        # Year: 1984

        ## Famous Person Bio:

        A bio of Ada Lovelace.

        ## Historical Event:

        An event.
        """
          .trimIndent()
      )
    // The branches' answers come first, in either order, and the combined message only once.
    assertThat(run.messages.dropLast(1))
      .containsExactly("Ada Lovelace", "A bio of Ada Lovelace.", "An event.")
  }

  @Test
  fun noYearInMessage_asksForAYearAndFails(): Unit = runBlocking {
    // Act
    val run = runWorkflow(create(), "Tell me about last year")

    // Assert
    assertThat(run.messages).containsExactly("Please provide a valid 4-digit year (e.g., 1955).")
    assertThat(run.failure).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(run.failure).hasMessageThat().isEqualTo("Invalid year format.")
    assertThat(model.requests).isEmpty()
    assertThat(run.outputs).isEmpty()
  }

  companion object {
    @JvmStatic @Parameters(name = "{0}") fun ports() = Port.entries
  }
}

/** The instruction of the agent that sent this request. */
private val LlmRequest.instruction: String
  get() = config.systemInstruction?.text().orEmpty()
