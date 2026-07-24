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

import com.google.adk.kt.agents.ReadonlyContext

/**
 * Decides whether a [BaseTool] should be exposed to the LLM under the current context.
 *
 * A toolset accepts a predicate (usually wrapped in [ToolFilter.Predicate]) and applies it in
 * [Toolset.getTools] to narrow the tools it returns. As a functional (SAM) interface it can be
 * written as a lambda: `ToolFilter.Predicate { tool, context -> tool.name.startsWith("read_") }`.
 */
fun interface ToolPredicate {
  /**
   * Returns `true` if [tool] should be selected.
   *
   * @param tool the tool to check.
   * @param readonlyContext the current invocation context, or `null` when none is available (for
   *   example when a toolset lists its tools outside of an invocation).
   */
  fun test(tool: BaseTool, readonlyContext: ReadonlyContext?): Boolean
}
