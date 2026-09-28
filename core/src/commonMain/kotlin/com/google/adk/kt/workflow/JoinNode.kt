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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Schema
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Waits for every predecessor to complete, then outputs their outputs keyed by predecessor name.
 *
 * Note: An [inputSchema] applies to each predecessor's output on its own, not to the joined map.
 *
 * Every predecessor should take its edge into the join each time it completes. The join fires once
 * all predecessors have completed, whether or not they routed here: a predecessor that routed
 * elsewhere still contributes its output, or, if it finishes last, the join never fires.
 */
@ExperimentalWorkflowApi
class JoinNode
@JvmOverloads
constructor(
  name: String,
  description: String = "",
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
) :
  BaseNode(
    name = name,
    description = description,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
  ) {

  init {
    validateNodeName(name)
  }

  override val requiresAllPredecessors: Boolean
    get() = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}
