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

package com.google.adk.kt.tools

/**
 * The semantic labels a [ToolExecutionError] can classify itself with: HTTP error types following
 * OpenTelemetry semantics.
 *
 * A member's [name] is its wire form, the string a telemetry backend sees. The set is deliberately
 * not exhaustive - a tool that fails with a code no member names passes that code to
 * [ToolExecutionError] as a string instead.
 */
enum class ToolErrorType {
  BAD_REQUEST,
  UNAUTHORIZED,
  FORBIDDEN,
  NOT_FOUND,
  REQUEST_TIMEOUT,
  INTERNAL_SERVER_ERROR,
  BAD_GATEWAY,
  SERVICE_UNAVAILABLE,
  GATEWAY_TIMEOUT,
}

/**
 * Thrown when running a tool goes wrong, as opposed to the tool running and reporting a failure to
 * the model.
 *
 * A tool that fails in the normal course of its work returns `mapOf(FunctionTool.ERROR_KEY to
 * "<message>")`, which the model sees and can recover from; this exception ends the tool call and
 * is for the framework around it. [errorType] is the caller's own classification of the failure,
 * `null` when the failure did not classify itself.
 */
class ToolExecutionError(
  message: String,
  /**
   * The classification, as the wire string a telemetry backend sees, or `null` if the failure did
   * not classify itself.
   */
  val errorType: String? = null,
) : Exception(message) {

  /**
   * Classifies the failure with one of the labels the SDK names. Equivalent to passing
   * `errorType.name` to the primary constructor.
   */
  constructor(message: String, errorType: ToolErrorType) : this(message, errorType.name)
}
