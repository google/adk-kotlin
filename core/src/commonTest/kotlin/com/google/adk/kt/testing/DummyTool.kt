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
package com.google.adk.kt.testing

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration

/**
 * A highly configurable dummy implementation of [BaseTool] for testing purposes.
 *
 * This fixture is designed to be a fully reusable and configurable [BaseTool]. Instead of creating
 * standalone inline tool classes per test, developers can pass an [onRun] lambda to define the
 * specific simulated logic.
 *
 * ### Example usage:
 * ```kotlin
 * // Simulating a straightforward return value
 * val dummy = DummyTool { _, _ -> "success" }
 *
 * // Simulating state modification
 * val dummyWithState = DummyTool { context, _ ->
 *   context.actions.stateDelta["key"] = "value"
 *   "done"
 * }
 *
 * // Simulating exceptions
 * val failingDummy = DummyTool { _, _ -> throw Exception("Simulated error") }
 * ```
 *
 * @param name The name of the tool. Defaults to "dummy_tool".
 * @param description The description of the tool. Defaults to "A dummy tool for testing.".
 * @param onRun The suspendable lambda that simulates the [run] method execution. It receives the
 *   [ToolContext] and arguments map, and must return the tool's result. Defaults to returning a
 *   successful "status" mapped to "done".
 */
class DummyTool(
  name: String = "dummy_tool",
  description: String = "A dummy tool for testing.",
  override val isLongRunning: Boolean = false,
  val onRun: suspend (context: ToolContext, args: Map<String, Any>) -> Any = { _, _ ->
    mapOf("status" to "done")
  },
) : BaseTool(name, description) {
  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    return onRun(context, args)
  }
}
