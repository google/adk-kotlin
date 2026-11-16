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
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertWithMessage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/** Runs [LoopSelfWorkflow] and its Java port, [LoopSelfWorkflowJava], with the same assertions. */
@RunWith(Parameterized::class)
class LoopSelfWorkflowTest(private val port: Port) {

  private fun create(): Workflow =
    when (port) {
      Port.KOTLIN -> LoopSelfWorkflow.create()
      Port.JAVA -> LoopSelfWorkflowJava.create()
    }

  @Test
  fun numberInRange_guessesUntilCorrect(): Unit = runBlocking {
    for (target in listOf(0, 3, 10)) {
      // Act: a port that never guesses the target would loop forever, so the run has a deadline.
      val run = withTimeout(10.seconds) { runWorkflow(create(), "$target") }

      // Assert: the guesses are random, so the test checks what holds for any of them.
      val assertForTarget = assertWithMessage("target %s", target)
      assertForTarget.that(run.failure).isNull()
      assertForTarget
        .that(run.messages.takeLast(2))
        .containsExactly("Guessing $target...", "Correct!")
        .inOrder()
      val wrongGuesses = run.messages.dropLast(2)
      for (guess in wrongGuesses) {
        assertForTarget.that(guess).matches("""Guessing (\d|10)\.\.\.""")
      }
      assertForTarget.that(wrongGuesses).doesNotContain("Guessing $target...")
      // Each wrong guess routes back to the guessing node.
      assertForTarget
        .that(run.events.mapNotNull { it.actions.route })
        .containsExactlyElementsIn(List(wrongGuesses.size) { listOf(Route.Tag("guessed_wrong")) })
      assertForTarget
        .that(run.events.single { it.nodeName == "validate_input" }.actions.stateDelta)
        .containsExactly("target_number", target)
      assertForTarget.that(run.outputs).isEmpty()
    }
  }

  @Test
  fun numberOutOfRange_asksForANumberInRangeAndFails(): Unit = runBlocking {
    for (input in listOf("-1", "11")) {
      // Act
      val run = runWorkflow(create(), input)

      // Assert
      val assertForInput = assertWithMessage("input %s", input)
      assertForInput.that(run.messages).containsExactly("Please provide a number between 0 and 10.")
      assertForInput
        .that(run.events.mapNotNull { it.errorCode })
        .containsExactly("IllegalArgumentException")
      assertForInput.that(run.failure).isInstanceOf(IllegalArgumentException::class.java)
      assertForInput.that(run.failure).hasMessageThat().isEqualTo("Invalid input.")
      assertForInput.that(run.outputs).isEmpty()
    }
  }

  companion object {
    @JvmStatic @Parameters(name = "{0}") fun ports() = Port.entries
  }
}
