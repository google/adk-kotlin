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

package com.google.adk.kt.tools.mcp

/** Base exception for MCP tools on JVM and Android. */
sealed class McpToolException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause) {
  /** Exception thrown when an MCP tool declaration cannot be generated. */
  class McpToolDeclarationException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)

  /** Exception thrown when MCP tool discovery or initialization fails. */
  class McpToolLoadingException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)

  /** Exception thrown when an MCP tool or resource operation fails. */
  class McpToolExecutionException(message: String, cause: Throwable? = null) :
    McpToolException(message, cause)
}
