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

package com.google.adk.kt.telemetry

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.testing.DummySpan
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.workflow.NodeInterruptedException
import com.google.genai.kotlin.ClientException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class FailuresTest {

  @Test
  fun errorType_genAiApiError_isItsHttpStatusCode() {
    val error = ClientException(code = 429, status = "RESOURCE_EXHAUSTED", message = "Slow down.")

    assertEquals("429", errorType(error))
  }

  @Test
  fun errorType_otherError_isItsClassName() {
    assertEquals("IllegalStateException", errorType(IllegalStateException("boom")))
  }

  @Test
  fun recordFailure_recordsTheErrorItsTypeAndAnErrorStatus() {
    val span = DummyTracer().spanBuilder("span").startSpan() as DummySpan
    val error = IllegalStateException("boom")

    span.recordFailure(error)

    assertEquals(listOf<Throwable>(error), span.recordedExceptions)
    assertEquals("IllegalStateException", span.attributes[TelemetryAttributes.ERROR_TYPE])
    assertEquals("IllegalStateException: boom", span.errorStatus)
  }

  @Test
  fun isFailure_error_isTrue() {
    assertTrue(isFailure(IllegalStateException("boom")))
  }

  @Test
  fun isFailure_cancellationOrNodePause_isFalse() {
    assertFalse(isFailure(CancellationException("cancelled")))
    assertFalse(isFailure(NodeInterruptedException()))
  }
}
