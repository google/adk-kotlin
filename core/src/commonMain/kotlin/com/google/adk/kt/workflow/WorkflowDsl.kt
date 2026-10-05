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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.types.Schema

/**
 * Builds a [Workflow] from the edges [block] declares.
 *
 * ```
 * val support = workflow("support_triage") {
 *   Start.then(classify).route {
 *     on("billing") then billing
 *     otherwise(summarize)
 *   }
 *   billing.then(summarize)
 * }
 * ```
 *
 * The other parameters are passed to the [Workflow] constructor unchanged.
 *
 * @throws IllegalArgumentException if the declared graph is invalid, such as a routing map with no
 *   entry; most graph errors throw its subtype [GraphValidationException].
 * @throws IllegalStateException if an `on` was never completed with `then`.
 */
@ExperimentalWorkflowApi
fun workflow(
  name: String,
  description: String = "",
  maxConcurrency: Int? = null,
  rerunOnResume: Boolean = true,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
  block: EdgesDslBlock<EdgesBuilder>,
): Workflow =
  Workflow(
    name = name,
    edges = edges(block),
    description = description,
    maxConcurrency = maxConcurrency,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    stateSchema = stateSchema,
  )
