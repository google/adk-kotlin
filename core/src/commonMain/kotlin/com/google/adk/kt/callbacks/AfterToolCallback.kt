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

package com.google.adk.kt.callbacks

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext

/**
 * Callback invoked after a tool runs, or after a before-tool callback answers the call in its
 * place.
 */
interface AfterToolCallback : Callback {
  /**
   * Callback executed after a tool runs, or after a before-tool callback answers the call in its
   * place.
   *
   * This callback allows for inspecting, logging, or modifying the tool result.
   *
   * @param context The context specific to the tool execution.
   * @param tool The tool for the call; for a name that matches no registered tool, a placeholder
   *   carrying that name.
   * @param args The arguments for the tool call, including any changes from before-tool callbacks.
   * @param result The tool's result as a map, or the result a before-tool or on-tool-error callback
   *   supplied instead. Values may be `null`.
   * @return The potentially updated or replaced dictionary / map to propagate downstream.
   */
  suspend fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?>

  companion object {
    // Workaround for problems when automatic SAM conversion code generated with gradle kotlin
    // plugin introduces an invalid method name in class files for functional interfaces with
    // abstract suspend methods.
    // This manual override permits the class to be used as if it were a functional interface with
    // SAM conversion (eg. AfterToolCallback { context, tool, args, result -> ... }).

    operator fun invoke(
      block:
        suspend (
          context: ToolContext, tool: BaseTool, args: Map<String, Any?>, result: Map<String, Any?>,
        ) -> Map<String, Any?>
    ): AfterToolCallback =
      object : AfterToolCallback {
        override suspend fun call(
          context: ToolContext,
          tool: BaseTool,
          args: Map<String, Any?>,
          result: Map<String, Any?>,
        ) = block(context, tool, args, result)
      }
  }
}
