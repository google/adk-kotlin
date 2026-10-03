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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.BaseNode
import com.google.adk.kt.workflow.NodeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a workflow node. A Java subclass overrides [runNodeJava] to
 * set routes or state on the context and return a Reactive Streams [Publisher] of node outputs,
 * which this base adapts to the `Flow`-returning [runNode].
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
abstract class BasePublisherNode
@JvmOverloads
constructor(
  name: String,
  description: String = "",
  rerunOnResume: Boolean = false,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
) :
  BaseNode(
    name,
    description,
    rerunOnResume,
    waitForOutput,
    config,
    inputSchema,
    outputSchema,
    stateSchema,
  ) {

  /** Creates a node with [config] and every other property at its default. */
  constructor(
    name: String,
    config: NodeConfig,
  ) : this(name, description = "", rerunOnResume = false, waitForOutput = false, config = config)

  /** Produces this node's outputs. Reactive Streams forbids null, so publish nothing instead. */
  protected abstract fun runNodeJava(
    context: Context,
    nodeInput: Any?,
  ): Publisher<out @JvmWildcard Any>

  final override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> =
    runNodeJava(context, nodeInput).asFlow()
}
