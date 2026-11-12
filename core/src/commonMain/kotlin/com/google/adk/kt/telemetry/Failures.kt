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

package com.google.adk.kt.telemetry

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.NodeInterruptedException
import com.google.genai.kotlin.GenAiApiException
import kotlinx.coroutines.CancellationException

/** Whether [e] escaping a span is a failure: a cancellation or a node pausing for input is not. */
internal fun isFailure(e: Throwable): Boolean =
  e !is CancellationException && e !is NodeInterruptedException

/**
 * Records [e] on this span with its `error.type`, as Python ADK does for an error escaping a span.
 */
internal fun Span.recordFailure(e: Throwable) {
  recordException(e)
  this[TelemetryAttributes.ERROR_TYPE] = errorType(e)
}

/**
 * Returns the `error.type` for [e]: the HTTP status code of a GenAI API error, since the SDK raises
 * one class for every 4xx, else the class name.
 */
internal fun errorType(e: Throwable): String =
  (e as? GenAiApiException)?.code?.toString() ?: e::class.simpleName ?: "Exception"
