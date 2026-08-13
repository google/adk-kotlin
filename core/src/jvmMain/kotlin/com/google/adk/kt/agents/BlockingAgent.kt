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

package com.google.adk.kt.agents

import com.google.adk.kt.events.Event
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Java-friendly base for implementing a [BaseAgent]. A Java subclass overrides the plain, blocking
 * [runBlocking], which returns all events for the turn at once; this base runs it on [ioContext]
 * and adapts it to the `Flow`-returning [runAsyncImpl] contract that Java cannot express. Leave
 * [ioContext] at its default to run blocking work off the agent's dispatcher, or inject one to
 * control where it runs.
 */
abstract class BlockingAgent
@JvmOverloads
constructor(
  name: String,
  description: String = "",
  subAgents: List<BaseAgent> = emptyList(),
  @Suppress("GlobalCoroutineDispatchers") private val ioContext: CoroutineContext = Dispatchers.IO,
) : BaseAgent(name, description, subAgents) {

  /**
   * Blocking counterpart of [runAsyncImpl]; returns every event for the turn. Runs on [ioContext].
   */
  protected abstract fun runBlocking(context: InvocationContext): List<Event>

  final override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    for (event in withContext(ioContext) { runBlocking(context) }) {
      emit(event)
    }
  }
}
