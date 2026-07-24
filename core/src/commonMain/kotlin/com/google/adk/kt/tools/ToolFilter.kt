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
 * Selects which tools a [Toolset] exposes to the LLM.
 *
 * A filter is one of a fixed set of variants:
 * - [AllowList] keeps only the tools whose [BaseTool.name] is listed.
 * - [Predicate] keeps the tools accepted by a [ToolPredicate], which may consult the
 *   [ReadonlyContext].
 *
 * Apply a (possibly `null`) filter with [isToolSelected]: a `null` filter performs no filtering and
 * selects every tool.
 */
sealed interface ToolFilter {
  /** Selects the tools whose [BaseTool.name] is contained in [toolNames]. */
  data class AllowList(val toolNames: Set<String>) : ToolFilter

  /** Selects the tools accepted by [predicate]. */
  data class Predicate(val predicate: ToolPredicate) : ToolFilter

  companion object {
    /** Returns an [AllowList] selecting exactly the tools named in [toolNames]. */
    fun allowList(vararg toolNames: String): ToolFilter = AllowList(toolNames.toSet())
  }
}

/**
 * Returns whether [tool] is selected by this filter under [readonlyContext].
 *
 * A `null` filter applies no filtering and selects every tool. An empty [ToolFilter.AllowList]
 * selects no tools. [ToolFilter.Predicate] delegates to its [ToolPredicate], forwarding
 * [readonlyContext] unchanged.
 */
fun ToolFilter?.isToolSelected(tool: BaseTool, readonlyContext: ReadonlyContext? = null): Boolean =
  when (this) {
    null -> true
    is ToolFilter.AllowList -> tool.name in toolNames
    is ToolFilter.Predicate -> predicate.test(tool, readonlyContext)
  }
