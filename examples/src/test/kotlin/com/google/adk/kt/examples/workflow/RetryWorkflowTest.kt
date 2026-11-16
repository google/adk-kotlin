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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/** Runs [RetryWorkflow] and its Java port, [RetryWorkflowJava], with the same assertions. */
@RunWith(Parameterized::class)
class RetryWorkflowTest(private val port: Port) {

  private fun create(): Workflow =
    when (port) {
      Port.KOTLIN -> RetryWorkflow.create()
      Port.JAVA -> RetryWorkflowJava.create()
    }

  // runTest's virtual time skips the retry backoff, which can reach 30 s per run.
  @Test
  fun run_retriesTheLookupUntilItSucceedsOrFailsFiveTimes() = runTest {
    // All five attempts fail in about 1 run in 6; 100 runs miss that with odds near 1e-8.
    var failedRuns = 0
    var recoveredRuns = 0
    repeat(100) {
      // Act
      val run = runWorkflow(create(), "go")

      // Assert: each attempt fails at random, so the test checks what holds for either outcome.
      val attempts = run.messages.count { it.startsWith("Getting weather...") }
      val failed = run.failure != null
      assertThat(attempts).isIn(1..5)
      if (failed) {
        failedRuns++
        assertThat(attempts).isEqualTo(5)
        assertThat(run.failure).hasMessageThat().isEqualTo("HTTP Error 500: Internal Server Error")
        assertThat(run.outputs).isEmpty()
      } else {
        if (attempts > 1) recoveredRuns++
        assertThat(run.outputs).containsExactly("get_weather" to "sunny")
      }
      val expected = buildList {
        for (attempt in 1..attempts) {
          add("Getting weather... attempt $attempt")
          if (attempt < attempts || failed) {
            add("HttpException: HTTP Error 500: Internal Server Error")
          }
        }
        if (!failed) add("The weather is sunny")
      }
      val reported =
        run.events.mapNotNull { event ->
          when (val errorCode = event.errorCode) {
            null -> event.contentText().ifEmpty { null }
            else -> "$errorCode: ${event.errorMessage}"
          }
        }
      assertThat(reported).containsExactlyElementsIn(expected).inOrder()
    }
    assertThat(failedRuns).isGreaterThan(0)
    assertThat(recoveredRuns).isGreaterThan(0)
  }

  companion object {
    @JvmStatic @Parameters(name = "{0}") fun ports() = Port.entries
  }
}
