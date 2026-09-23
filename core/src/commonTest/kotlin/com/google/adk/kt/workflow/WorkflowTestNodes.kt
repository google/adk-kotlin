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

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Test node helpers shared across the workflow test suites. Kept in one file so the open-source
 * build - which compiles the whole test source set together - sees a single definition of each.
 */

/** A node that emits a fixed value as its output. */
internal class Emitter(override val name: String, private val value: Any?) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(value) }
}

/** A bare node that emits a fixed value (null by default), for graph topology tests. */
internal class StubNode(override val name: String, private val value: Any? = null) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(value) }
}

/** Throws whatever [error] builds, so a runner's failure path can be observed. */
internal class ThrowingNode(override val name: String, private val error: () -> Throwable) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { throw error() }
}

/** Writes one state key, so where the delta lands is observable. */
internal class StateWriter(override val name: String, private val key: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.updateState(key, "v")
    emit("done")
  }
}

/** Records a state change, then throws, so the failure path's delta handling can be observed. */
internal class StateThenFailNode(
  override val name: String,
  private val key: String,
  private val value: Any,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.updateState(key, value)
    throw IllegalStateException("node failed")
  }
}

/** The `yes` tag route, for routing tests. */
internal fun yes() = Route.Tag("yes")

/** The `no` tag route, for routing tests. */
internal fun no() = Route.Tag("no")

/** Runs [workflow] as an invocation root and collects the events it emits. */
internal fun runWorkflow(workflow: Workflow): List<Event> = runBlocking {
  workflow.runAsync(testInvocationContext()).toList()
}
